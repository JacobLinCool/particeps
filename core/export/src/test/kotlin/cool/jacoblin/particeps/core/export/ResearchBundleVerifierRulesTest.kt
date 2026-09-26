package cool.jacoblin.particeps.core.export

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.DesiredProfile
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.crypto.HpkeKeyPair
import cool.jacoblin.particeps.core.definition.AppLifecycleV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.withComputedDigest
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Protocol v1 verification rules that a non-replaying reader enforces on RC13 and later runtime
 * output: the reducer cursor counts reducer inputs, a source cursor changes only with its
 * completed barrier flush, a process-recovery or wall-clock gap restarts retrospective sources,
 * and every event of a commit carries one condition-epoch envelope.
 *
 * They also include the condition-epoch and checkpoint rules that need no replay: every commit
 * upserts its complete automation checkpoint, an epoch event's boundary is its observed time, a
 * recovery close is unproven PAUSED containment at the recovery instant and is the only close that
 * may lie in another boot, and coverage is empty only as the barrier flush at the close.
 *
 * The reference chain mirrors the shapes a real runtime writes: empty setup commits, a start, an
 * activation, a live and a retrospective observation, a pause whose barrier flush advances the
 * retrospective cursor and whose post-deactivation events keep the closed epoch, and a recovery.
 */
class ResearchBundleVerifierRulesTest {
    private val fixture = fixture()

    @Test
    fun referenceChainIsAccepted() {
        val verified = verify(referenceChain())

        assertEquals(1L, verified.experiment.firstCommitSequence)
        assertEquals(7L, verified.experiment.lastCommitSequence)
        assertEquals(1L, verified.experiment.lifetimeDataEventCount)
    }

    @Test
    fun reducerCursorCountsReducerInputsNotCommits() {
        val chain = referenceChain()
        assertEquals(listOf(0L, 0L, 1L, 2L, 3L, 4L, 5L), chain.map { it.checkpoint.evaluatedThroughSequence })

        assertRejected(CURSOR_NOT_BOUNDED, chain.replace(5) { it.copy(checkpoint = started(1, StudySessionState.PAUSED)) })
        // Commit 6 records three events after cursor 3, so cursor 7 overruns the commit but not the log.
        assertRejected(CURSOR_NOT_BOUNDED, chain.replace(5) { it.copy(checkpoint = started(7, StudySessionState.PAUSED)) })
        assertRejected("Setup commit does not carry the empty automation checkpoint", chain.replace(5) {
            it.copy(state = ExperimentState.READY, checkpoint = started(3, StudySessionState.PAUSED))
        })
        assertRejected("Setup commit does not carry the empty automation checkpoint", chain.replace(1) {
            it.copy(checkpoint = AutomationCheckpoint(desiredResources = DESIRED))
        })
        assertRejected("Started study has an unevaluated automation checkpoint", chain.replace(2) {
            it.copy(checkpoint = started(0, StudySessionState.ACTIVATING))
        })
        assertRejected("Automation checkpoint has an incomplete desired resource vector", chain.replace(4) {
            it.copy(checkpoint = started(3, StudySessionState.RUNNING).copy(desiredResources = DESIRED - USAGE_KEY))
        })
    }

    @Test
    fun partialBundleChecksCursorBoundsWithoutAnUnknownPredecessor() {
        val chain = referenceChain()

        verify(chain, fromCommit = 5)
        // Commit 3 ends before event 2, so cursor 2 runs past the durable events although the
        // partial bundle does not know the predecessor cursor.
        assertRejected("Automation checkpoint evaluated beyond durable events", chain.replace(2) {
            it.copy(checkpoint = started(2, StudySessionState.ACTIVATING))
        }, fromCommit = 3)
    }

    @Test
    fun sourceCursorChangesOnlyWithItsCompletedBarrierFlush() {
        val chain = referenceChain()
        assertEquals(null, chain[4].sourceCheckpoints.getValue(USAGE).cursor)
        assertEquals("300", chain[5].sourceCheckpoints.getValue(USAGE).cursor)

        assertRejected(CHECKPOINT_DIVERGES, chain.replace(4) {
            it.copy(sourceCheckpoints = it.sourceCheckpoints + (USAGE to usageCheckpoint(1, 100, 200, cursor = "200")))
        })
        assertRejected(CHECKPOINT_DIVERGES, chain.replace(5) {
            it.copy(
                observations = emptyList(),
                sourceCheckpoints = chain[4].sourceCheckpoints + (USAGE to usageCheckpoint(1, 100, 200, cursor = "300")),
            )
        })
        assertRejected(FLUSH_INVALID, chain.replace(5) {
            it.copy(
                state = ExperimentState.RUNNING,
                events = it.events.filterNot { event -> event.type == CONDITION_DEACTIVATED },
                epoch = activeEpoch,
            )
        })
        assertRejected(FLUSH_INVALID, chain.replace(5) {
            it.copy(
                observations = listOf(ObservationSpec(APP, ObservationAdmissionKind.BARRIER_FLUSH, 1, 1, coverage = coverage(1, 2))),
                sourceCheckpoints = chain[4].sourceCheckpoints + (APP to SourceCheckpoint(APP, 1, 2, coverage(1, 2), null)),
            )
        })
        assertRejected(FLUSH_INVALID, chain.replace(5) {
            it.copy(
                observations = it.observations +
                    ObservationSpec(USAGE, ObservationAdmissionKind.BARRIER_FLUSH, 1, 2, coverage = coverage(300, 300)),
                sourceCheckpoints = chain[4].sourceCheckpoints + (USAGE to usageCheckpoint(3, 300, 300, cursor = "300")),
            )
        })
        assertRejected(FLUSH_INVALID, chain.replace(5) {
            it.copy(
                observations = it.observations + ObservationSpec(APP, ObservationAdmissionKind.NORMAL, 1, 1, eventIndex = 0),
                events = listOf(activityEvent()) + it.events,
                sourceCheckpoints = it.sourceCheckpoints + (APP to SourceCheckpoint(APP, 1, 2, null, null)),
            )
        })
    }

    @Test
    fun processRecoveryRemovesRetrospectiveCheckpointsOnly() {
        val chain = referenceChain()
        assertEquals(setOf(APP), chain[6].sourceCheckpoints.keys)

        verify(chain, fromCommit = 7)
        assertRejected(UNPROVEN_CHECKPOINT, chain.replace(6) { it.copy(sourceCheckpoints = chain[5].sourceCheckpoints) })
        assertRejected("Quality-gap commit retained a retrospective source checkpoint", chain.replace(6) {
            it.copy(sourceCheckpoints = chain[5].sourceCheckpoints)
        }, fromCommit = 7)
        assertRejected(CHECKPOINT_DIVERGES, chain.replace(6) { it.copy(sourceCheckpoints = emptyMap()) })
        assertRejected(CHECKPOINT_DIVERGES, chain.replace(6) {
            it.copy(
                inputKind = EngineInputKind.TIMER_WAKE,
                events = emptyList(),
                checkpoint = started(4, StudySessionState.PAUSED),
            )
        })
        assertRejected(RECOVERY_GAP, chain.replace(6) {
            it.copy(events = emptyList(), checkpoint = started(4, StudySessionState.PAUSED))
        })
        assertRejected(RECOVERY_GAP, chain.replace(6) { it.copy(events = listOf(gapEvent(null, "WALL_CLOCK_CHANGED"))) })
        assertRejected(RECOVERY_GAP, chain.replace(6) { it.copy(events = it.events + gapEvent(null, "WALL_CLOCK_CHANGED")) })
        assertRejected("Process-recovery quality gap requires RECOVERY input", chain.replace(6) {
            it.copy(inputKind = EngineInputKind.TIMER_WAKE)
        })
    }

    @Test
    fun recoveryThatClosesAnEpochMayConsumeAStagedRetrospectiveObservation() {
        val chain = closingRecoveryChain()

        verify(chain)
        // The partial bundle learns the closing epoch from the staged observation, not its vector.
        verify(chain, fromCommit = 6)
        assertRejected("Condition epoch deactivation does not match the active epoch", chain.replace(5) {
            it.copy(
                events = it.events.map { event ->
                    if (event.type != CONDITION_DEACTIVATED) event
                    else event.copy(fields = event.fields + ("applied_resource_vector_sha256" to "e".repeat(64)))
                },
            )
        })
        assertRejected("Clock-gap commit cannot backfill a retrospective source", chain.replace(5) {
            it.copy(
                inputKind = EngineInputKind.TIMER_WAKE,
                events = listOf(gapEvent(EPOCH_ID, "WALL_CLOCK_CHANGED")) + it.events.drop(1),
            )
        })
    }

    @Test
    fun wallClockGapRestartsRetrospectiveSourcesAndForbidsBackfill() {
        val chain = referenceChain().take(5) + CommitSpec(
            inputKind = EngineInputKind.TIMER_WAKE,
            state = ExperimentState.RUNNING,
            checkpoint = started(4, StudySessionState.RUNNING),
            events = listOf(gapEvent(EPOCH_ID, "WALL_CLOCK_CHANGED")),
            epoch = activeEpoch,
            sourceCheckpoints = mapOf(APP to APP_CHECKPOINT),
        )

        verify(chain)
        assertRejected(UNPROVEN_CHECKPOINT, chain.replace(5) { it.copy(sourceCheckpoints = chain[4].sourceCheckpoints) })
        assertRejected("Clock-gap commit cannot backfill a retrospective source", chain.replace(5) {
            it.copy(
                observations = listOf(ObservationSpec(USAGE, ObservationAdmissionKind.NORMAL, 1, 1, coverage = coverage(200, 250))),
            )
        })
    }

    @Test
    fun everyEventOfACommitCarriesOneConditionEpochEnvelope() {
        val chain = referenceChain()
        // The closing commit records lifecycle events after the deactivation under the closed epoch.
        assertEquals(CONDITION_DEACTIVATED, chain[5].events[1].type)
        assertEquals(EPOCH_ID, chain[5].events.last().epoch)

        assertRejected("Commit events do not share one condition epoch envelope", chain.replace(5) {
            it.copy(events = it.events.dropLast(1) + it.events.last().copy(epoch = null))
        })
        assertRejected("Condition epoch transition is not recorded under its own epoch", chain.replace(3) {
            it.copy(events = it.events.map { event -> event.copy(epoch = null) })
        })
        assertRejected("Commit events do not carry the predecessor's condition epoch", chain.replace(6) {
            it.copy(events = listOf(gapEvent(EPOCH_ID, "PROCESS_RECOVERY")))
        })
        assertRejected("Commit records more than one condition epoch transition", chain.replace(5) {
            it.copy(events = it.events + activatedEvent())
        })
    }

    @Test
    fun everyCommitUpsertsItsCompleteAutomationCheckpoint() {
        val chain = referenceChain()
        // Commit 5 stores its checkpoint in two parts, so commit 6 must rewrite or remove both.
        val split = chain.replace(4) { it.copy(checkpointParts = 2) }

        verify(
            split.replace(5) { it.copy(checkpointParts = 2) }
                .replace(6) { it.copy(removedParts = listOf("main/0001")) },
        )
        verify(split.replace(5) { it.copy(removedParts = listOf("main/0001")) })
        assertRejected("Commit does not upsert its complete automation checkpoint", split)
        assertRejected("Runtime mutation removes an unknown automation checkpoint part", chain.replace(5) {
            it.copy(removedParts = listOf("main/0001"))
        })
    }

    @Test
    fun conditionEpochBoundaryIsItsEventTime() {
        val chain = referenceChain()

        assertRejected("Condition epoch boundary differs from its event time", chain.replace(3) {
            it.copy(events = it.events.map { event -> if (event.type == CONDITION_ACTIVATED) event.copy(time = LATER) else event })
        })
        assertRejected("Condition epoch boundary differs from its event time", chain.replace(5) {
            it.copy(
                events = it.events.map { event ->
                    if (event.type != CONDITION_DEACTIVATED) event
                    else event.copy(fields = event.fields + ("boundary_research_time" to boundaryJson(LATER)))
                },
            )
        })
    }

    @Test
    fun onlyARecoveryCloseAtTheRecoveryInstantMayLieInAnotherBoot() {
        assertRejected("Condition epoch cannot span a reboot", referenceChain().replace(5) {
            it.copy(
                events = it.events.map { event ->
                    if (event.type == CONDITION_DEACTIVATED) deactivatedEvent("PARTICIPANT_PAUSED", REBOOTED) else event
                },
            )
        })

        val rebooted = closingRecoveryChain().replace(5) {
            it.copy(
                events = it.events.map { event ->
                    if (event.type == CONDITION_DEACTIVATED) deactivatedEvent("PROCESS_RECOVERY_UNPROVEN", REBOOTED)
                    else event.copy(time = REBOOTED)
                },
            )
        }
        verify(rebooted)
        assertRejected("Recovery close is not at the recovery instant", rebooted.replace(5) {
            it.copy(events = it.events.map { event -> if (event.type == "SOURCE_QUALITY_GAP") event.copy(time = TIME) else event })
        })
        assertRejected("Recovery close is not unproven PAUSED containment", closingRecoveryChain().replace(5) {
            it.copy(
                events = it.events.map { event ->
                    if (event.type == CONDITION_DEACTIVATED) deactivatedEvent("SAFETY_PAUSED") else event
                },
            )
        })
    }

    @Test
    fun coverageIsEmptyOnlyAsTheBarrierFlushAtTheClose() {
        // The pause closes at wall 1000, where the preceding poll ended: the flush is [1000, 1000).
        val atBoundary = referenceChain()
            .replace(4) {
                it.copy(
                    observations = listOf(
                        it.observations[0],
                        ObservationSpec(USAGE, ObservationAdmissionKind.NORMAL, 1, 0, coverage = coverage(100, 1000)),
                    ),
                    sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(1, 100, 1000)),
                )
            }
            .replace(5) {
                it.copy(
                    observations = listOf(
                        ObservationSpec(USAGE, ObservationAdmissionKind.BARRIER_FLUSH, 1, 1, coverage = coverage(1000, 1000)),
                    ),
                    sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(2, 1000, 1000, cursor = "1000")),
                )
            }

        verify(atBoundary)
        assertRejected("Retrospective coverage is empty outside a boundary flush", referenceChain().replace(4) {
            it.copy(
                observations = listOf(
                    it.observations[0],
                    ObservationSpec(USAGE, ObservationAdmissionKind.NORMAL, 1, 0, coverage = coverage(100, 100)),
                ),
                sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(1, 100, 100)),
            )
        })
        assertRejected("Retrospective coverage is empty outside a boundary flush", referenceChain().replace(5) {
            it.copy(
                observations = listOf(
                    ObservationSpec(USAGE, ObservationAdmissionKind.BARRIER_FLUSH, 1, 1, coverage = coverage(200, 200)),
                ),
                sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(2, 200, 200, cursor = "200")),
            )
        })
        assertRejected("Retrospective coverage runs backwards", referenceChain().replace(5) {
            it.copy(
                observations = listOf(
                    ObservationSpec(USAGE, ObservationAdmissionKind.BARRIER_FLUSH, 1, 1, coverage = coverage(200, 150)),
                ),
                sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(2, 200, 150, cursor = "150")),
            )
        })
    }

    /** The reference chain with its pause replaced by a recovery that closes the epoch. */
    private fun closingRecoveryChain(): List<CommitSpec> = referenceChain().take(5) + CommitSpec(
        inputKind = EngineInputKind.RECOVERY,
        state = ExperimentState.PAUSED,
        checkpoint = started(4, StudySessionState.PAUSED),
        events = listOf(
            gapEvent(EPOCH_ID, "PROCESS_RECOVERY"),
            deactivatedEvent("PROCESS_RECOVERY_UNPROVEN"),
            runtimeEvent("STUDY_SAFETY_PAUSED", EPOCH_ID, "PAUSED", "PAUSING", "REQUIRED_RESOURCE_FAILURE"),
        ),
        observations = listOf(ObservationSpec(USAGE, ObservationAdmissionKind.NORMAL, 1, 1, coverage = coverage(200, 250))),
        sourceCheckpoints = mapOf(APP to APP_CHECKPOINT),
        consumedPending = "d".repeat(64),
    )

    private fun referenceChain(): List<CommitSpec> = listOf(
        CommitSpec(EngineInputKind.LIFECYCLE_COMMAND, ExperimentState.CONFIG_VERIFIED, AutomationCheckpoint()),
        CommitSpec(EngineInputKind.LIFECYCLE_COMMAND, ExperimentState.READY, AutomationCheckpoint()),
        CommitSpec(
            EngineInputKind.LIFECYCLE_COMMAND,
            ExperimentState.ACTIVATING,
            started(1, StudySessionState.ACTIVATING),
            events = listOf(runtimeEvent("STUDY_STARTED", null, "ACTIVATING", null, "STUDY_START")),
        ),
        CommitSpec(
            EngineInputKind.RESOURCE_RESULT,
            ExperimentState.RUNNING,
            started(2, StudySessionState.RUNNING),
            events = listOf(
                activatedEvent(),
                runtimeEvent("STUDY_RUNNING", EPOCH_ID, "RUNNING", "ACTIVATING", "ACTIVATION_CONFIRMED"),
            ),
            epoch = activeEpoch,
        ),
        CommitSpec(
            EngineInputKind.SOURCE_OBSERVATION,
            ExperimentState.RUNNING,
            started(3, StudySessionState.RUNNING),
            events = listOf(activityEvent()),
            observations = listOf(
                ObservationSpec(APP, ObservationAdmissionKind.NORMAL, 1, 0, eventIndex = 0),
                ObservationSpec(USAGE, ObservationAdmissionKind.NORMAL, 1, 0, coverage = coverage(100, 200)),
            ),
            epoch = activeEpoch,
            sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(1, 100, 200)),
        ),
        CommitSpec(
            EngineInputKind.LIFECYCLE_COMMAND,
            ExperimentState.PAUSED,
            started(4, StudySessionState.PAUSED),
            events = listOf(
                runtimeEvent("STUDY_PAUSE_REQUESTED", EPOCH_ID, "PAUSING", "RUNNING", "PARTICIPANT_PAUSE"),
                deactivatedEvent("PARTICIPANT_PAUSED"),
                runtimeEvent("STUDY_PAUSED", EPOCH_ID, "PAUSED", "PAUSING", "PARTICIPANT_PAUSE"),
            ),
            observations = listOf(
                ObservationSpec(USAGE, ObservationAdmissionKind.BARRIER_FLUSH, 1, 1, coverage = coverage(200, 300)),
            ),
            sourceCheckpoints = mapOf(APP to APP_CHECKPOINT, USAGE to usageCheckpoint(2, 200, 300, cursor = "300")),
        ),
        CommitSpec(
            EngineInputKind.RECOVERY,
            ExperimentState.PAUSED,
            started(5, StudySessionState.PAUSED),
            events = listOf(gapEvent(null, "PROCESS_RECOVERY")),
            sourceCheckpoints = mapOf(APP to APP_CHECKPOINT),
        ),
    )

    private fun verify(specs: List<CommitSpec>, fromCommit: Long = 1): VerifiedResearchBundle = runBlocking {
        val commits = seal(specs)
        val initial = RuntimeDocument.initial(
            EXPERIMENT_ID,
            CONFIGURATION_ID,
            fixture.verified.configurationSha256,
            "A".repeat(43),
            participantInstanceId = PARTICIPANT_ID,
        )
        val runtime = commits.fold(initial, RuntimeDocument::advance)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            ExportSnapshot(
                verifiedConfiguration = fixture.verified,
                runtime = runtime,
                producer = BundleProducer("android", "42"),
                bundleKind = BundleKind.MANUAL_EXPORT,
                exportedAtUtcMillis = 10_000,
                bundleId = UUID.fromString("00000000-0000-4000-8000-000000000099"),
                fromCommit = fromCommit,
                maximumPlaintextBytes = null,
            ),
            SnapshotStore(runtime, commits),
            destination,
        )
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        ResearchBundleVerifier.verify(
            plaintext,
            AuthenticatedBundleHeader(
                receipt.bundleId,
                fixture.verified.configurationSha256,
                fixture.configuration.export.researcherKeyId,
            ),
            fixture.configuration,
        )
    }

    private fun assertRejected(message: String, specs: List<CommitSpec>, fromCommit: Long = 1) {
        val failure = assertThrows(IllegalArgumentException::class.java) { verify(specs, fromCommit) }
        assertEquals(message, failure.message)
    }

    /** Assigns sequences, observation digests, projections and commit digests to [specs]. */
    private fun seal(specs: List<CommitSpec>): List<EngineCommit> {
        var previousSha256 = GENESIS_DIGEST
        var nextEvent = 1L
        var nextObservation = 1L
        var lifetime = 0L
        return specs.mapIndexed { index, spec ->
            val sequence = index + 1L
            val events = spec.events.mapIndexed { offset, event ->
                RecordedEvent(
                    nextEvent + offset,
                    EventTypeKey(EventSourceId(event.source), 1, event.type),
                    event.time,
                    event.epoch,
                    event.fields,
                )
            }
            val observations = spec.observations.mapIndexed { offset, observation ->
                val observed = listOfNotNull(observation.eventIndex?.let(events::get))
                val unsigned = SourceObservation(
                    nextObservation + offset,
                    observation.source,
                    1,
                    observation.generation,
                    observation.admission,
                    observation.ordinal,
                    observation.epoch,
                    observed.size,
                    observed.firstOrNull()?.sequenceNumber,
                    observed.lastOrNull()?.sequenceNumber,
                    observation.coverage,
                    GENESIS_DIGEST,
                )
                unsigned.copy(encodedSha256 = SourceObservationIntegrity.calculate(unsigned, observed))
            }
            nextEvent += events.size
            nextObservation += observations.size
            lifetime += events.count { it.type.sourceId == APP }
            EngineCommit(
                commitSequence = sequence,
                previousCommitSha256 = previousSha256,
                inputKind = spec.inputKind,
                consumedPendingInputSha256 = spec.consumedPending,
                sourceObservations = observations,
                events = events,
                mutations = checkpointMutations(spec),
                committedAt = TIME,
                successorProjection = RuntimeProjection(
                    state = spec.state,
                    revision = sequence,
                    nextCommitSequence = sequence + 1,
                    nextObservationSequence = nextObservation,
                    nextEventSequence = nextEvent,
                    sourceCheckpoints = spec.sourceCheckpoints,
                    clockCheckpoint = if (spec.checkpoint.studyStartUtcMillis == null) null else CLOCK,
                    activeConditionEpoch = spec.epoch,
                    lifetimeDataEventCount = lifetime,
                    uploadedThroughCommit = 0,
                    evaluatedThroughCommit = sequence,
                    retainedFromCommit = 1,
                ),
                resultingCheckpointSha256 = spec.checkpoint.digest(),
                commitSha256 = GENESIS_DIGEST,
            ).withComputedDigest().also { previousSha256 = it.commitSha256 }
        }
    }

    /** Upserts [spec]'s checkpoint in [CommitSpec.checkpointParts] parts and removes [CommitSpec.removedParts]. */
    private fun checkpointMutations(spec: CommitSpec): List<RuntimeMutation> {
        val encoded = AutomationCheckpointCodec.encode(spec.checkpoint)
        val size = (encoded.length + spec.checkpointParts - 1) / spec.checkpointParts
        val upserts = encoded.chunked(size).mapIndexed { index, part ->
            val id = if (index == 0) "main" else "main/${index.toString().padStart(4, '0')}"
            RuntimeMutation(RuntimeComponentKey(RuntimeComponentKind.AUTOMATION_CHECKPOINT, id), RuntimeMutationOperation.UPSERT, part)
        }
        val removals = spec.removedParts.map { id ->
            RuntimeMutation(RuntimeComponentKey(RuntimeComponentKind.AUTOMATION_CHECKPOINT, id), RuntimeMutationOperation.REMOVE, null)
        }
        return (upserts + removals).sortedBy(RuntimeMutation::key)
    }

    private fun List<CommitSpec>.replace(index: Int, change: (CommitSpec) -> CommitSpec): List<CommitSpec> =
        toMutableList().apply { this[index] = change(this[index]) }

    private data class CommitSpec(
        val inputKind: EngineInputKind,
        val state: ExperimentState,
        val checkpoint: AutomationCheckpoint,
        val events: List<EventSpec> = emptyList(),
        val observations: List<ObservationSpec> = emptyList(),
        val epoch: ConditionEpoch? = null,
        val sourceCheckpoints: Map<EventSourceId, SourceCheckpoint> = emptyMap(),
        val consumedPending: String? = null,
        val checkpointParts: Int = 1,
        val removedParts: List<String> = emptyList(),
    )

    private data class EventSpec(
        val source: String,
        val type: String,
        val epoch: ConditionEpochId?,
        val fields: Map<String, String>,
        val time: ResearchTime = TIME,
    )

    private data class ObservationSpec(
        val source: EventSourceId,
        val admission: ObservationAdmissionKind,
        val generation: Long,
        val ordinal: Long,
        val coverage: SourceCoverage? = null,
        val eventIndex: Int? = null,
        val epoch: ConditionEpochId = EPOCH_ID,
    )

    private fun started(cursor: Long, lifecycle: StudySessionState) = AutomationCheckpoint(
        evaluatedThroughSequence = cursor,
        lifecycle = lifecycle,
        studyStartUtcMillis = TIME.wallTimeUtcMillis,
        desiredResources = DESIRED,
    )

    private fun runtimeEvent(
        type: String,
        epoch: ConditionEpochId?,
        current: String,
        previous: String?,
        reason: String,
    ) = EventSpec(
        "study_runtime.v1",
        type,
        epoch,
        buildMap {
            put("command_id", COMMAND_ID)
            put("current_state", current)
            previous?.let { put("previous_state", it) }
            put("transition_reason", reason)
        },
    )

    private fun activatedEvent() = EventSpec(
        "study_condition.v1",
        CONDITION_ACTIVATED,
        EPOCH_ID,
        mapOf(
            "activation_reason" to "INITIAL_START",
            "applied_resource_vector_sha256" to RESOURCE_DIGEST,
            "boundary_research_time" to BOUNDARY_JSON,
            "condition_epoch_id" to EPOCH_ID.value,
            "resource_vector_json" to RESOURCE_VECTOR.canonicalJson(),
            "signed_configuration_sha256" to fixture.verified.configurationSha256,
        ),
    )

    private fun deactivatedEvent(reason: String, time: ResearchTime = TIME) = EventSpec(
        "study_condition.v1",
        CONDITION_DEACTIVATED,
        EPOCH_ID,
        time = time,
        fields = mapOf(
            "applied_resource_vector_sha256" to RESOURCE_DIGEST,
            "boundary_research_time" to boundaryJson(time),
            "condition_epoch_id" to EPOCH_ID.value,
            "deactivation_reason" to reason,
            "resource_vector_json" to RESOURCE_VECTOR.canonicalJson(),
            "signed_configuration_sha256" to fixture.verified.configurationSha256,
        ),
    )

    private fun gapEvent(epoch: ConditionEpochId?, reason: String) = EventSpec(
        "study_runtime.v1",
        "SOURCE_QUALITY_GAP",
        epoch,
        mapOf("reason" to reason, "source_id" to "study_runtime.v1"),
    )

    private fun activityEvent() = EventSpec(
        APP.value,
        "ACTIVITY_CREATED",
        EPOCH_ID,
        mapOf("activity_class" to "MainActivity"),
    )

    private fun fixture(): Fixture {
        val signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val hpke = HpkeCrypto.generateKeyPair()
        val app = CollectorResourceConfiguration(
            APP.value,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", AppLifecycleV1ProfileConfiguration())),
        )
        val usage = CollectorResourceConfiguration(
            USAGE.value,
            required = false,
            profiles = listOf(NamedCollectorProfile("poll", UsageEventsV1ProfileConfiguration(60))),
        )
        val configuration = StudyConfiguration(
            schemaVersion = 1,
            experimentId = EXPERIMENT_ID,
            configurationId = CONFIGURATION_ID,
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = "android",
            minimumClientVersion = 1,
            title = "Verifier rules",
            researcherName = "Verifier researcher",
            researcherContact = "verifier@example.invalid",
            purpose = "Test Protocol v1 verification rules on runtime-shaped commits.",
            durationHours = 1,
            consentDocumentVersion = "v1",
            consentSummary = "Verifier rules consent.",
            assignedParticipantId = null,
            collectors = listOf(app, usage),
            surveys = emptyList(),
            interventions = emptyList(),
            automations = listOf(
                ResourceBindingAutomation(
                    "bind-app-lifecycle",
                    app.resourceKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ),
                ResourceBindingAutomation(
                    "bind-usage-events",
                    usage.resourceKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "poll")),
                    "poll",
                ),
            ).sortedBy(AutomationDefinition::id),
            trafficShaping = TrafficShapingConfiguration.Disabled,
            maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
            signer = SignerIdentity("test-signer", ProtocolBase64Url.encode(signing.public.encoded.copyOfRange(12, 44))),
            export = ExportConfiguration("export-key", ProtocolBase64Url.encode(hpke.publicKey)),
            upload = null,
        )
        val bytes = StudyConfigurationCodec.encode(configuration)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signing.private)
            update(bytes)
            sign()
        }
        return Fixture(
            hpke,
            configuration,
            VerifiedConfiguration(configuration, bytes, configuration.signer.keyId, signature, bytes.sha256Hex(), false),
        )
    }

    private data class Fixture(
        val hpke: HpkeKeyPair,
        val configuration: StudyConfiguration,
        val verified: VerifiedConfiguration,
    )

    private class SnapshotStore(
        override val runtime: RuntimeDocument,
        private val commits: List<EngineCommit>,
    ) : StudyReadSnapshot {
        override suspend fun readCommits(
            fromCommitInclusive: Long,
            throughCommitInclusive: Long,
            consume: (EngineCommit) -> Boolean,
        ) {
            for (commit in commits) {
                if (commit.commitSequence in fromCommitInclusive..throughCommitInclusive && !consume(commit)) break
            }
        }
    }

    private companion object {
        const val EXPERIMENT_ID = "verifier-rules"
        const val CONFIGURATION_ID = "verifier-rules-config"
        const val PARTICIPANT_ID = "00000000-0000-4000-8000-000000000017"
        const val CONDITION_ACTIVATED = "CONDITION_EPOCH_ACTIVATED"
        const val CONDITION_DEACTIVATED = "CONDITION_EPOCH_DEACTIVATED"
        const val CURSOR_NOT_BOUNDED = "Automation reducer cursor is not causally bounded by the commit"
        const val CHECKPOINT_DIVERGES = "Successor source checkpoint diverges from observation provenance"
        const val UNPROVEN_CHECKPOINT = "Successor projection introduced an unproven source checkpoint"
        const val FLUSH_INVALID = "Barrier flush is not one retrospective coverage observation"
        const val RECOVERY_GAP = "Recovery commit does not record exactly one process-recovery quality gap"
        val COMMAND_ID = "b".repeat(64)
        val TIME = ResearchTime(1_000, 2_000, "boot-test")
        val LATER = ResearchTime(1_001, 1_002_000, "boot-test")
        val REBOOTED = ResearchTime(5_000, 7_000, "boot-next")
        val BOUNDARY_JSON = boundaryJson(TIME)

        fun boundaryJson(time: ResearchTime) =
            "{\"boot_session_id\":\"${time.bootSessionId}\",\"monotonic_time_nanos\":\"${time.elapsedRealtimeNanos}\"," +
                "\"wall_time_utc_millis\":\"${time.wallTimeUtcMillis}\"}"
        val CLOCK = StudyClockCheckpoint(
            calendarElapsedNanos = 0,
            activeRunningElapsedNanos = 0,
            anchor = TIME,
            deadlineUtcMillis = TIME.wallTimeUtcMillis + 3_600_000,
            deadlineUtcTrusted = true,
            zoneId = "UTC",
        )
        val APP = EventSourceId(AppLifecycleV1ProfileConfiguration.SOURCE_ID)
        val USAGE = EventSourceId(UsageEventsV1ProfileConfiguration.SOURCE_ID)
        val APP_KEY = ResourceKey(ResourceKind.COLLECTOR, APP.value)
        val USAGE_KEY = ResourceKey(ResourceKind.COLLECTOR, USAGE.value)
        val DESIRED = mapOf(
            APP_KEY to DesiredProfile(ResourceGeneration(1uL), "continuous"),
            USAGE_KEY to DesiredProfile(ResourceGeneration(1uL), "poll"),
        )
        val EPOCH_ID = ConditionEpochId("00000000-0000-4000-8000-000000000021")
        val APP_CHECKPOINT = SourceCheckpoint(APP, 1, 1, null, null)
        val RESOURCE_VECTOR = AppliedResourceVector(
            listOf(
                NamedCollectorProfile("continuous", AppLifecycleV1ProfileConfiguration()).asSignedProfile().let { profile ->
                    AppliedResourceState(APP_KEY, ResourceGeneration(1uL), profile.id, profile.expectedSha256, AppliedResourceStatus.APPLIED, null)
                },
                NamedCollectorProfile("poll", UsageEventsV1ProfileConfiguration(60)).asSignedProfile().let { profile ->
                    AppliedResourceState(USAGE_KEY, ResourceGeneration(1uL), profile.id, profile.expectedSha256, AppliedResourceStatus.APPLIED, null)
                },
            ),
        )
        val RESOURCE_DIGEST = RESOURCE_VECTOR.conditionDigest.value

        fun coverage(start: Long, end: Long) = SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, start.toString(), end.toString())

        fun usageCheckpoint(nextOrdinal: Long, start: Long, end: Long, cursor: String? = null) =
            SourceCheckpoint(USAGE, 1, nextOrdinal, coverage(start, end), cursor)
    }

    /** The reference epoch is bound to this fixture's signed configuration digest. */
    private val activeEpoch: ConditionEpoch
        get() = ConditionEpoch(EPOCH_ID, fixture.verified.configurationSha256, RESOURCE_DIGEST, TIME)
}
