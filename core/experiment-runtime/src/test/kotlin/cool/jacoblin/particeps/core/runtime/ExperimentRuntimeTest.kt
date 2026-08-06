package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorEventContract
import cool.jacoblin.particeps.core.collector.EventFieldContract
import cool.jacoblin.particeps.core.collector.EventFieldType
import cool.jacoblin.particeps.core.collector.EventPayloadContract
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.EmitResult
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.ChoiceOption
import cool.jacoblin.particeps.core.definition.InterventionConfiguration
import cool.jacoblin.particeps.core.definition.InterventionTrigger
import cool.jacoblin.particeps.core.definition.LocalizedText
import cool.jacoblin.particeps.core.definition.MultipleChoiceQuestion
import cool.jacoblin.particeps.core.definition.OneTimeSchedule
import cool.jacoblin.particeps.core.definition.RelativeClock
import cool.jacoblin.particeps.core.definition.ScaleQuestion
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.SingleChoiceQuestion
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRuntimeTest {
    @Test
    fun requiredMissingHardwareBlocksEnrollment() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(
            clocks,
            AccessRequirement(AccessKind.GYROSCOPE_HARDWARE, required = true),
        )
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.completeAccessSetup(emptySet()))
        assertEquals(ExperimentState.ACCESS_SETUP, runtime.snapshot.value.metadata?.state)
        assertEquals(0, plugin.collector.startCount)
    }

    @Test
    fun optionalMissingHardwareBlocksOnlyItsCollectorAndStartsWhenAccessAppears() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(
            clocks,
            AccessRequirement(AccessKind.GYROSCOPE_HARDWARE, required = false),
        )
        var available = emptySet<AccessKind>()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { available },
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(CommandResult.Success, runtime.start())
        assertEquals(0, plugin.collector.startCount)
        assertEquals(
            CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "ACCESS_UNAVAILABLE"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )

        assertEquals(CommandResult.Success, runtime.pause())
        available = setOf(AccessKind.GYROSCOPE_HARDWARE)
        assertEquals(CommandResult.Success, runtime.resume())
        assertEquals(1, plugin.collector.startCount)
        assertEquals(CollectorStatus.ACTIVE, plugin.collector.health.value.status)
    }

    @Test
    fun participantCommandsGateAndPersistCollectorEvents() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(ExperimentState.IMPORTED, runtime.snapshot.value.metadata?.state)
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(ExperimentState.CONSENT_PENDING, runtime.snapshot.value.metadata?.state)
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(ExperimentState.READY, runtime.snapshot.value.metadata?.state)

        assertEquals(CommandResult.Success, runtime.start())
        assertEquals(1, plugin.collector.startCount)
        assertEquals(CollectorStatus.ACTIVE, plugin.collector.health.value.status)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.pause())
        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.pauseCount)
        assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_STOPPED"))
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.resume())
        assertEquals(1, plugin.collector.resumeCount)
        assertTrue(plugin.emit("ACTIVITY_STARTED") is EmitResult.Accepted)
        assertEquals(listOf(1L, 2L), store.events.map { it.sequenceNumber })

        assertEquals(CommandResult.Success, runtime.finishEarly())
        assertEquals(ExperimentState.COMPLETED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.stopCount)
        assertEquals(CollectorStatus.STOPPED, plugin.collector.health.value.status)
        assertEquals(8, runtime.snapshot.value.metadata?.transitions?.size)
        assertTrue(store.saveCount >= 11)
        assertNull(runtime.snapshot.value.incidentCode)
    }

    @Test
    fun initializationRecoversRunningStateAndRestartsCollectors() = runTest {
        val store = InMemoryStudyStore(
            StudyMetadata.initial(EXPERIMENT_ID, CONFIGURATION_ID).copy(state = ExperimentState.RUNNING),
        )
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )

        assertEquals(CommandResult.Success, runtime.initialize())

        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.startCount)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
    }

    @Test
    fun failedInitialStartRetainsCollectorOwnershipUntilShutdownReleasesIt() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        assertEquals(CommandResult.Success, runtime.initialize())
        plugin.collector.failNextStartWithOwnedResources = true
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))

        assertEquals(CommandResult.Success, runtime.start())
        assertTrue(plugin.collector.requiresStop)
        assertEquals(CollectorStatus.FAILED, runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID]?.status)

        runtime.shutdown()
        assertEquals(1, plugin.collector.stopCount)
        assertFalse(plugin.collector.requiresStop)
    }

    @Test
    fun failedTerminalStopRemainsOwnedAndShutdownRetriesIt() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        plugin.collector.failNextStopWithOwnedResources = true

        assertEquals(CommandResult.Success, runtime.finishEarly())
        assertEquals(1, plugin.collector.stopCount)
        assertTrue(plugin.collector.requiresStop)

        runtime.shutdown()
        assertEquals(2, plugin.collector.stopCount)
        assertFalse(plugin.collector.requiresStop)
    }

    @Test
    fun collectorEventContractIsEnforcedBeforePersistence() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)

        assertEquals(EmitResult.ContractViolation, plugin.emit("ACTIVITY_RESUMED", schemaVersion = 2))
        assertEquals(EmitResult.ContractViolation, plugin.emit("ACTIVITY_RESUMED", collectorId = "other.v1"))
        assertEquals(
            EmitResult.ContractViolation,
            plugin.emit("ACTIVITY_RESUMED", fields = mapOf("source" to "x".repeat(2_000))),
        )
        assertTrue(store.events.isEmpty())
        assertEquals(0L, runtime.snapshot.value.metadata?.eventCount)
    }

    @Test
    fun surveySubmissionValidatesEveryQuestionTypeAndCommitsExactlyOnce() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        val occurrence = InterventionOccurrence(
            occurrenceId = "a".repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:1",
            scheduledFor = ResearchTime(1_000, 1_000, "boot-test"),
            expiresAtUtcMillis = 60_000,
            state = OccurrenceState.SCHEDULED,
        )
        runtime.ensureOccurrence(occurrence)
        val claim = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        assertTrue(claim.dispatch.action is SurveyAction)
        runtime.markNotificationPosted(occurrence.occurrenceId)
        assertEquals(OccurrenceState.OPENED, runtime.openOccurrence(occurrence.occurrenceId)?.occurrence?.state)

        val incomplete = mapOf("mood-scale" to SurveyAnswer.Integer(3))
        assertEquals(SurveySubmissionResult.INVALID, runtime.submitSurvey(occurrence.occurrenceId, incomplete))
        val answers = mapOf(
            "daily-note" to SurveyAnswer.Text("felt focused"),
            "mood-scale" to SurveyAnswer.Integer(4),
            "primary-place" to SurveyAnswer.Choices(listOf("place-home")),
            "symptoms" to SurveyAnswer.Choices(listOf("symptom-tired", "symptom-headache")),
        )
        val concurrent = listOf(
            async { runtime.submitSurvey(occurrence.occurrenceId, answers) },
            async { runtime.submitSurvey(occurrence.occurrenceId, answers) },
        ).awaitAll()
        assertEquals(1, concurrent.count { it == SurveySubmissionResult.ACCEPTED })
        assertEquals(1, concurrent.count { it == SurveySubmissionResult.ALREADY_SUBMITTED })

        val submitted = requireNotNull(runtime.surveySubmissionEvent(occurrence.occurrenceId))
        assertEquals("SURVEY_SUBMITTED", submitted.payloadType)
        assertEquals("daily-survey", submitted.fields["survey_id"])
        val encoded = requireNotNull(submitted.fields["answers_json"])
        assertTrue(encoded.contains("\"daily-note\":\"felt focused\""))
        assertTrue(encoded.contains("\"primary-place\":[\"place-home\"]"))
        assertTrue(encoded.contains("\"symptoms\":[\"symptom-tired\",\"symptom-headache\"]"))
        assertTrue(!encoded.contains("Home"))
        assertEquals(4L, store.events.count().toLong())
    }

    @Test
    fun lateSurveyOccurrenceExpiresWithoutOpeningOrSubmission() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        val occurrence = InterventionOccurrence(
            occurrenceId = "b".repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:1",
            scheduledFor = ResearchTime(1, 1, "boot-test"),
            expiresAtUtcMillis = 2_500,
            state = OccurrenceState.SCHEDULED,
        )
        runtime.ensureOccurrence(occurrence)
        assertEquals(OccurrenceClaimResult.Expired, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertNull(runtime.openOccurrence(occurrence.occurrenceId))
        assertEquals(OccurrenceState.EXPIRED, runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state)
        assertEquals(listOf("INTERVENTION_SCHEDULED", "SURVEY_EXPIRED"), store.events.map { it.payloadType })
    }

    @Test
    fun dedicatedExpiryCheckLeavesEarlyScheduledWorkUntouchedAndExpiresOnce() = runTest {
        val store = InMemoryStudyStore()
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        val occurrence = surveyOccurrence("c", expiresAtUtcMillis = 5_000)
        runtime.ensureOccurrence(occurrence)

        clocks.wallTimeUtcMillis = 4_250
        assertEquals(OccurrenceExpiryResult.NotDue(750), runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.SCHEDULED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )

        clocks.wallTimeUtcMillis = 5_000
        assertEquals(OccurrenceExpiryResult.Expired, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceExpiryResult.Terminal, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceClaimResult.Terminal, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            1,
            store.events.count { it.payloadType == "SURVEY_EXPIRED" && it.fields["occurrence_id"] == occurrence.occurrenceId },
        )
    }

    @Test
    fun deliveryClaimWaitsForItsWallInstantAndARecoveredPostingClaimIsIdempotent() = runTest {
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        val occurrence = surveyOccurrence("9", scheduledAtUtcMillis = 3_000, expiresAtUtcMillis = 5_000)
        runtime.ensureOccurrence(occurrence)

        clocks.wallTimeUtcMillis = 2_500
        assertEquals(OccurrenceClaimResult.NotDue(500), runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.SCHEDULED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )

        clocks.wallTimeUtcMillis = 3_000
        val first = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        val recovered = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        assertEquals(OccurrenceState.POSTING, first.dispatch.occurrence.state)
        assertEquals(first, recovered)
        assertTrue(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertTrue(runtime.markNotificationPosted(occurrence.occurrenceId))

        clocks.wallTimeUtcMillis = 5_000
        assertFalse(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.EXPIRED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )
    }

    @Test
    fun dedicatedExpiryCheckExpiresPostingPostedAndOpenedSurveyStates() = runTest {
        val store = InMemoryStudyStore()
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        start(runtime)
        val occurrences = listOf("d", "e", "f").mapIndexed { index, prefix ->
            surveyOccurrence(prefix, expiresAtUtcMillis = 5_000 + index * 1_000L).also {
                runtime.ensureOccurrence(it)
            }
        }
        runtime.claimOccurrenceIfDue(occurrences[0].occurrenceId)
        runtime.claimOccurrenceIfDue(occurrences[1].occurrenceId)
        runtime.markNotificationPosted(occurrences[1].occurrenceId)
        runtime.claimOccurrenceIfDue(occurrences[2].occurrenceId)
        runtime.markNotificationPosted(occurrences[2].occurrenceId)
        runtime.openOccurrence(occurrences[2].occurrenceId)

        clocks.wallTimeUtcMillis = 10_000
        occurrences.forEach { occurrence ->
            assertEquals(OccurrenceExpiryResult.Expired, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
            assertEquals(
                OccurrenceState.EXPIRED,
                runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
            )
        }
        assertEquals(3, store.events.count { it.payloadType == "SURVEY_EXPIRED" })
        assertEquals(OccurrenceExpiryResult.Missing, runtime.expireOccurrenceIfDue("0".repeat(64)))
    }

    @Test
    fun pausedFinishedAndWithdrawnStudiesRejectEveryInterventionMutation() = runTest {
        val lifecycleCases = listOf<Pair<String, suspend (ExperimentRuntime) -> CommandResult>>(
            "pause" to { it.pause() },
            "finish" to { it.finishEarly() },
            "withdraw" to { it.withdraw() },
        )
        lifecycleCases.forEach { (name, transition) ->
            val store = InMemoryStudyStore()
            val clocks = MutableClocks(1_000)
            val runtime = ExperimentRuntime(
                configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
                clocks = clocks,
                scope = backgroundScope,
                availableAccess = { emptySet() },
            )
            start(runtime)
            val posted = surveyOccurrence("1", expiresAtUtcMillis = 60_000)
            val opened = surveyOccurrence("2", expiresAtUtcMillis = 60_000)
            val scheduled = surveyOccurrence("3", expiresAtUtcMillis = 60_000)
            val posting = surveyOccurrence("4", expiresAtUtcMillis = 60_000)
            listOf(posted, opened, scheduled, posting).forEach { runtime.ensureOccurrence(it) }
            runtime.claimOccurrenceIfDue(posted.occurrenceId)
            assertTrue(runtime.markNotificationPosted(posted.occurrenceId))
            runtime.claimOccurrenceIfDue(opened.occurrenceId)
            assertTrue(runtime.markNotificationPosted(opened.occurrenceId))
            assertTrue(runtime.openOccurrence(opened.occurrenceId)?.action is SurveyAction)
            runtime.claimOccurrenceIfDue(posting.occurrenceId)
            assertEquals(CommandResult.Success, transition(runtime))
            val eventCount = store.events.size

            assertEquals(OccurrenceClaimResult.InactiveStudy, runtime.claimOccurrenceIfDue(scheduled.occurrenceId))
            assertEquals(OccurrenceExpiryResult.InactiveStudy, runtime.expireOccurrenceIfDue(posted.occurrenceId))
            assertFalse(runtime.markNotificationPosted(posting.occurrenceId))
            assertNull(runtime.openOccurrence(posted.occurrenceId))
            assertEquals(
                SurveySubmissionResult.INVALID,
                runtime.submitSurvey(opened.occurrenceId, validSurveyAnswers()),
            )
            assertEquals("$name must not append intervention events", eventCount, store.events.size)
        }
    }

    @Test
    fun illegalCommandFailsWithoutMutatingDurableState() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            availableAccess = { emptySet() },
        )
        runtime.initialize()

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.start())

        assertEquals(ExperimentState.IMPORTED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun exportSnapshotIsRepeatableInEveryDataBearingStateAndNeverMutatesState() = runTest {
        val exportable = listOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
        exportable.forEach { state ->
            val store = InMemoryStudyStore(
                StudyMetadata.initial(EXPERIMENT_ID, CONFIGURATION_ID).copy(state = state),
            )
            val clocks = FakeClocks()
            val runtime = ExperimentRuntime(
                configuration = configuration(),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
                clocks = clocks,
                scope = backgroundScope,
                availableAccess = { emptySet() },
            )
            assertEquals(CommandResult.Success, runtime.initialize())

            assertEquals(state, runtime.metadataForExport().state)
            assertEquals(state, runtime.metadataForExport().state)
            assertEquals(state, runtime.snapshot.value.metadata?.state)
        }
    }

    private class InMemoryStudyStore(
        initial: StudyMetadata? = null,
    ) : StudyStore {
        var metadata: StudyMetadata? = initial
        val events = mutableListOf<RecordedEvent>()
        var saveCount = 0
        var usedBytes = 0L
        var quotaBytes = 16_777_216L
        val evictionTargets = mutableListOf<Long>()

        override suspend fun storageUsage() = StorageUsage(usedBytes, quotaBytes)

        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata {
            evictionTargets += targetBytes
            // Mirrors the real store: only delivered events go, the floor lands on the first
            // survivor, and the newest event survives regardless, standing in for the segment
            // still being appended to. Nothing is held back for a collector's most recent event —
            // lastEvents is persisted in the metadata rather than rebuilt from surviving frames.
            val newest = events.maxOfOrNull { it.sequenceNumber } ?: Long.MAX_VALUE
            val floor = minOf(metadata.uploadedThroughSequence + 1, newest)
            if (floor <= metadata.retainedFromSequence) return metadata
            events.removeAll { it.sequenceNumber < floor }
            usedBytes = 0
            return metadata.copy(retainedFromSequence = floor).also { this.metadata = it }
        }

        override suspend fun loadMetadata(): StudyMetadata? = metadata

        override suspend fun initialize(metadata: StudyMetadata) {
            check(this.metadata == null)
            this.metadata = metadata
            saveCount += 1
        }

        override suspend fun saveMetadata(metadata: StudyMetadata) {
            this.metadata = metadata
            saveCount += 1
        }

        override suspend fun appendEvent(event: RecordedEvent) {
            val current = requireNotNull(metadata)
            require(event.sequenceNumber == current.nextSequenceNumber)
            events += event
            metadata = current.copy(
                eventCount = event.sequenceNumber,
                nextSequenceNumber = event.sequenceNumber + 1,
                lastEvents = current.lastEvents + (event.collectorId to event),
            )
            saveCount += 1
        }

        override suspend fun appendEventAtomically(event: RecordedEvent, metadata: StudyMetadata) {
            require(event.sequenceNumber == requireNotNull(this.metadata).nextSequenceNumber)
            events += event
            this.metadata = metadata
            saveCount += 1
        }

        override suspend fun readEvents(
            fromSequenceInclusive: Long,
            upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit,
        ) {
            events.asSequence()
                .takeWhile { it.sequenceNumber <= upToSequenceInclusive }
                .filter { it.sequenceNumber >= fromSequenceInclusive }
                .forEach(consume)
        }

        override suspend fun clear() {
            metadata = null
            events.clear()
        }
    }

    private class FakeClocks : ResearchClocks {
        private var tick = 1L

        override fun now(): ResearchTime = ResearchTime(
            wallTimeUtcMillis = tick * 1_000,
            elapsedRealtimeNanos = tick++ * 1_000,
            bootSessionId = "boot-test",
        )
    }

    private class MutableClocks(
        var wallTimeUtcMillis: Long,
    ) : ResearchClocks {
        private var elapsedRealtimeNanos = 0L

        override fun now(): ResearchTime = ResearchTime(
            wallTimeUtcMillis = wallTimeUtcMillis,
            elapsedRealtimeNanos = ++elapsedRealtimeNanos,
            bootSessionId = "boot-test",
        )
    }

    private class FakeCollectorPlugin(
        private val clocks: ResearchClocks,
        private val accessRequirement: AccessRequirement? = null,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = AppLifecycleConfiguration.ID,
            displayName = "Fake collector",
            privacyClass = PrivacyClass.SENSITIVE,
            eventContract = CollectorEventContract(
                payloadSchemaVersion = 1,
                maximumEncodedEventBytes = 512,
                payloads = listOf("ACTIVITY_RESUMED", "ACTIVITY_STOPPED", "ACTIVITY_STARTED")
                    .associateWith {
                        EventPayloadContract(
                            mapOf("source" to EventFieldContract(EventFieldType.STRING, required = true)),
                        )
                    },
            ),
        )
        lateinit var context: CollectorContext
        lateinit var collector: FakeCollector

        override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
            require(configuration is AppLifecycleConfiguration)
            return setOfNotNull(accessRequirement)
        }

        override fun create(
            configuration: CollectorConfiguration,
            context: CollectorContext,
        ): Collector {
            require(configuration is AppLifecycleConfiguration)
            this.context = context
            collector = FakeCollector()
            return collector
        }

        suspend fun emit(
            type: String,
            collectorId: String = descriptor.id,
            schemaVersion: Int = descriptor.payloadSchemaVersion,
            fields: Map<String, String> = mapOf("source" to "test"),
        ): EmitResult {
            val token = context.eventSink.captureToken() ?: return EmitResult.RejectedByAdmissionGate
            return context.eventSink.emit(
                token,
                EventDraft(
                    collectorId = collectorId,
                    payloadSchemaVersion = schemaVersion,
                    observedTime = clocks.now(),
                    payloadType = type,
                    fields = fields,
                ),
            )
        }
    }

    private class FakeCollector : Collector {
        private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = mutableHealth
        override var requiresStop = false
            private set
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var failNextStartWithOwnedResources = false
        var failNextStopWithOwnedResources = false

        override suspend fun start() {
            startCount += 1
            requiresStop = true
            if (failNextStartWithOwnedResources) {
                failNextStartWithOwnedResources = false
                mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_REGISTRATION_FAILED")
                error("Start left collector resources requiring cleanup")
            }
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun pause() {
            pauseCount += 1
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }

        override suspend fun resume() {
            resumeCount += 1
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun stop() {
            stopCount += 1
            if (failNextStopWithOwnedResources) {
                failNextStopWithOwnedResources = false
                mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED")
                error("Stop left collector resources requiring cleanup")
            }
            requiresStop = false
            mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
        }
    }

    private companion object {
        const val EXPERIMENT_ID = "runtime-test"
        const val CONFIGURATION_ID = "runtime-config"

        fun configuration(
            collectors: List<CollectorConfiguration> = listOf(AppLifecycleConfiguration(required = true)),
            surveys: List<SurveyDefinition> = emptyList(),
            interventions: List<InterventionConfiguration> = emptyList(),
        ) = StudyConfiguration(
            schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
            experimentId = EXPERIMENT_ID,
            configurationId = CONFIGURATION_ID,
            assignedParticipantId = null,
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = StudyConfiguration.ANDROID_PLATFORM,
            minimumClientVersion = 1,
            title = "Runtime test",
            researcherName = "Test researcher",
            researcherContact = "test@example.invalid",
            purpose = "Runtime test purpose",
            durationHours = 1,
            consentDocumentVersion = "test-1",
            consentSummary = "Test consent",
            collectors = collectors,
            surveys = surveys,
            interventions = interventions,
            maximumLocalBytes = 16_777_216,
            signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
            export = ExportConfiguration(
                researcherKeyId = "test-key",
                hpkePublicKey = RAW_PUBLIC_KEY,
            ),
            upload = null,
        )

        suspend fun start(runtime: ExperimentRuntime) {
            assertEquals(CommandResult.Success, runtime.initialize())
            assertEquals(CommandResult.Success, runtime.reviewStudy())
            assertEquals(CommandResult.Success, runtime.acceptConsent())
            assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
            assertEquals(CommandResult.Success, runtime.start())
        }

        fun survey() = SurveyDefinition(
            "daily-survey",
            LocalizedText("Daily survey", mapOf("zh-TW" to "每日問卷")),
            LocalizedText("Answer four questions."),
            listOf(
                ShortTextQuestion("daily-note", LocalizedText("How was today?"), false, 40),
                ScaleQuestion("mood-scale", LocalizedText("Mood"), true, 1, 5, LocalizedText("Low"), LocalizedText("High")),
                SingleChoiceQuestion(
                    "primary-place",
                    LocalizedText("Where were you?"),
                    true,
                    listOf(ChoiceOption("place-home", LocalizedText("Home")), ChoiceOption("place-work", LocalizedText("Work"))),
                ),
                MultipleChoiceQuestion(
                    "symptoms",
                    LocalizedText("Symptoms"),
                    true,
                    listOf(
                        ChoiceOption("symptom-tired", LocalizedText("Tired")),
                        ChoiceOption("symptom-headache", LocalizedText("Headache")),
                        ChoiceOption("symptom-none", LocalizedText("None")),
                    ),
                    1,
                    2,
                ),
            ),
        )

        fun surveyIntervention() = InterventionConfiguration(
            "survey-notice",
            SurveyAction("Daily survey", "Your survey is ready.", "daily-survey"),
            listOf(InterventionTrigger("after-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )

        fun validSurveyAnswers(): Map<String, SurveyAnswer> = mapOf(
            "daily-note" to SurveyAnswer.Text("complete"),
            "mood-scale" to SurveyAnswer.Integer(4),
            "primary-place" to SurveyAnswer.Choices(listOf("place-home")),
            "symptoms" to SurveyAnswer.Choices(listOf("symptom-none")),
        )

        fun surveyOccurrence(
            prefix: String,
            scheduledAtUtcMillis: Long = 100,
            expiresAtUtcMillis: Long,
        ) = InterventionOccurrence(
            occurrenceId = prefix.repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:$prefix",
            scheduledFor = ResearchTime(scheduledAtUtcMillis, scheduledAtUtcMillis, "boot-test"),
            expiresAtUtcMillis = expiresAtUtcMillis,
            state = OccurrenceState.SCHEDULED,
        )
    }
}

private const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
