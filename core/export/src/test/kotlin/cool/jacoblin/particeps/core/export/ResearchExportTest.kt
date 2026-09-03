package cool.jacoblin.particeps.core.export

import com.google.gson.JsonParser
import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.DesiredProfile
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.crypto.HpkeCrypto
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
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.withComputedDigest
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchExportTest {
    @Test
    fun kotlinExporterWritesEncryptedPythonInteropFixtureWhenExplicitlyRequested() = runBlocking {
        val outputDirectory = System.getenv(KOTLIN_EXPORT_INTEROP_DIRECTORY_ENV)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: return@runBlocking
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)
        val ciphertext = destination.toByteArray()
        val manifest = buildString {
            append("{\"bundle_sha256\":\"")
            append(ciphertext.sha256Hex())
            append("\",\"byte_count\":\"")
            append(ciphertext.size)
            append("\",\"event_count\":\"")
            append(receipt.eventCount)
            append("\",\"last_source_id\":\"app_lifecycle.v1\"")
            append(",\"researcher_key_id\":\"")
            append(fixture.configuration.export.researcherKeyId)
            append("\"}")
        }

        Files.createDirectories(outputDirectory)
        Files.write(outputDirectory.resolve(INTEROP_BUNDLE_FILE), ciphertext)
        Files.writeString(
            outputDirectory.resolve(INTEROP_PRIVATE_KEY_FILE),
            ProtocolBase64Url.encode(fixture.hpke.privateKey),
        )
        Files.writeString(outputDirectory.resolve(INTEROP_MANIFEST_FILE), manifest)

        assertEquals(ciphertext.size.toLong(), receipt.byteCount)
        assertEquals(32, fixture.hpke.privateKey.size)
    }

    @Test
    fun encryptedBundleCarriesCompleteAuthenticatedEngineCommits() = runBlocking {
        val fixture = fixture(assignedParticipantId = "arm-a-017")
        val chain = commitChain(fixture.verified.configurationSha256, assignedParticipantId = "arm-a-017")
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)

        val encrypted = destination.toByteArray()
        val frame = ByteBuffer.wrap(encrypted)
        assertEquals("PTCEXP01", ByteArray(8).also(frame::get).toString(Charsets.US_ASCII))
        val plaintext = ResearchExport.decrypt(encrypted, fixture.hpke.privateKey, fixture.configuration)
        val root = JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject
        val experiment = root.getAsJsonObject("experiment")
        val commits = experiment.getAsJsonArray("commits")

        assertEquals(TOP_LEVEL_KEYS, root.keySet())
        assertEquals(EXPERIMENT_KEYS, experiment.keySet())
        assertEquals(COMMIT_KEYS, commits[0].asJsonObject.keySet())
        assertEquals("3", experiment.get("commit_count").asString)
        assertEquals("5", experiment.get("event_count").asString)
        assertEquals(chain.commits[0].commitSha256, commits[0].asJsonObject.get("commit_sha256").asString)
        assertEquals(
            "app_lifecycle.v1",
            commits[2].asJsonObject.getAsJsonArray("source_observations")[0]
                .asJsonObject.get("source_id").asString,
        )
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("arm-a-017"))
        assertEquals(3L, receipt.commitCount)
        assertEquals(5L, receipt.eventCount)
        assertEquals(encrypted.size.toLong(), receipt.byteCount)

        val header = AuthenticatedBundleHeader(
            receipt.bundleId,
            fixture.verified.configurationSha256,
            fixture.configuration.export.researcherKeyId,
        )
        val verified = ResearchBundleVerifier.verify(plaintext, header, fixture.configuration)
        assertEquals(3L, verified.experiment.durableThroughCommit)
        assertEquals(chain.commits.last().commitSha256, verified.experiment.lastCommitSha256)
        assertEquals(1L, verified.experiment.lifetimeDataEventCount)
    }

    @Test
    fun bundleCodecPreservesClosedResourceAuditTimerComponentKind() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256, includeResourceAuditTimer = true)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        val root = JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject
        val kinds = root.getAsJsonObject("experiment").getAsJsonArray("commits")
            .flatMap { it.asJsonObject.getAsJsonArray("mutations") }
            .map { it.asJsonObject.get("component_kind").asString }

        assertTrue("RESOURCE_AUDIT_TIMER" in kinds)
        ResearchBundleVerifier.verify(
            plaintext,
            AuthenticatedBundleHeader(
                receipt.bundleId,
                fixture.verified.configurationSha256,
                fixture.configuration.export.researcherKeyId,
            ),
            fixture.configuration,
        )
        Unit
    }

    @Test
    fun automaticBudgetStopsOnlyAtACompleteCommitBoundary() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            snapshot(
                fixture,
                chain.runtime,
                kind = BundleKind.AUTOMATIC_UPLOAD,
                maximumPlaintextBytes = 1,
            ),
            chain.store,
            destination,
        )

        assertEquals(1L, receipt.firstCommitSequence)
        assertEquals(1L, receipt.lastCommitSequence)
        assertEquals(1L, receipt.commitCount)
        assertEquals(2L, receipt.eventCount)
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        val header = AuthenticatedBundleHeader(
            receipt.bundleId,
            fixture.verified.configurationSha256,
            fixture.configuration.export.researcherKeyId,
        )
        val verified = ResearchBundleVerifier.verify(plaintext, header, fixture.configuration)
        assertEquals(1L, verified.experiment.commitCount)
        assertEquals(3L, verified.experiment.durableThroughCommit)
    }

    @Test
    fun retainedPartialRangeCarriesItsAuthenticatedPredecessorAnchor() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256, collectorProducerOrdinal = 7)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            snapshot(fixture, chain.runtime, fromCommit = 3),
            chain.store,
            destination,
        )
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        val header = AuthenticatedBundleHeader(
            receipt.bundleId,
            fixture.verified.configurationSha256,
            fixture.configuration.export.researcherKeyId,
        )
        val verified = ResearchBundleVerifier.verify(plaintext, header, fixture.configuration)

        assertEquals(3L, receipt.firstCommitSequence)
        assertEquals(1L, receipt.commitCount)
        assertEquals(chain.commits[1].commitSha256, verified.experiment.firstPreviousCommitSha256)
        assertEquals(chain.commits[2].commitSha256, verified.experiment.lastCommitSha256)
    }

    @Test
    fun manualEmptyBundleIsValidButAutomaticUploadIsNot() = runBlocking {
        val fixture = fixture()
        val runtime = RuntimeDocument.initial(
            "export-test",
            "export-config",
            fixture.verified.configurationSha256,
            "A".repeat(43),
        )
        val store = SnapshotStore(runtime, emptyList())
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, runtime), store, destination)

        assertEquals(1L, receipt.firstCommitSequence)
        assertEquals(0L, receipt.lastCommitSequence)
        assertEquals(0L, receipt.commitCount)
        assertEquals(0L, receipt.eventCount)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResearchExport.encrypt(
                    snapshot(fixture, runtime, kind = BundleKind.AUTOMATIC_UPLOAD),
                    store,
                    ByteArrayOutputStream(),
                )
            }
        }
        Unit
    }

    @Test
    fun verifierRejectsLegacyFlatEventsTamperedContentAndRetiredComponentKinds() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        val header = AuthenticatedBundleHeader(
            receipt.bundleId,
            fixture.verified.configurationSha256,
            fixture.configuration.export.researcherKeyId,
        )
        val text = plaintext.toString(Charsets.UTF_8)

        val tampered = text.replaceFirst(chain.commits[0].resultingCheckpointSha256, "9".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            ResearchBundleVerifier.verify(tampered.toByteArray(), header, fixture.configuration)
        }
        val legacy = text.replaceFirst("\"commit_count\":\"3\",\"commits\":", "\"event_count\":\"5\",\"events\":")
        assertThrows(IllegalArgumentException::class.java) {
            ResearchBundleVerifier.verify(legacy.toByteArray(), header, fixture.configuration)
        }
        val retiredRandomSelectionComponent = text.replaceFirst(
            "\"component_kind\":\"AUTOMATION_CHECKPOINT\"",
            "\"component_kind\":\"RANDOM_SELECTION\"",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ResearchBundleVerifier.verify(
                retiredRandomSelectionComponent.toByteArray(),
                header,
                fixture.configuration,
            )
        }
        Unit
    }

    @Test
    fun verifierRejectsCollectorDataWithoutAConditionEpoch() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256, collectorEpoch = null)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
        val header = AuthenticatedBundleHeader(
            receipt.bundleId,
            fixture.verified.configurationSha256,
            fixture.configuration.export.researcherKeyId,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ResearchBundleVerifier.verify(plaintext, header, fixture.configuration)
        }
        Unit
    }

    @Test
    fun exportRefusesAStoreCommitWhoseAuthenticatedBodyChanged() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256)
        val corrupt = chain.commits.toMutableList().apply {
            this[1] = this[1].copy(resultingCheckpointSha256 = "9".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResearchExport.encrypt(
                    snapshot(fixture, chain.runtime),
                    SnapshotStore(chain.runtime, corrupt),
                    ByteArrayOutputStream(),
                )
            }
        }
        Unit
    }

    @Test
    fun uploadReceiptCodecIsExactCommitRangeContract() {
        val receipt = ExportReceipt(
            UUID.fromString("00000000-0000-4000-8000-000000000099"),
            "11".repeat(32),
            501,
            750,
            250,
            4_000,
            "22".repeat(32),
            16_777_216,
        )
        val encoded = UploadReceiptCodec.encode(receipt)

        assertEquals(receipt, UploadReceiptCodec.decode(encoded))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"commit_count\":\"250\""))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("first_sequence_number"))
        assertThrows(IllegalArgumentException::class.java) {
            UploadReceiptCodec.decode((" " + encoded.toString(Charsets.UTF_8)).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadReceiptCodec.decode(
                encoded.toString(Charsets.UTF_8).replace("\"501\"", "\"0501\"").toByteArray(),
            )
        }
    }

    @Test
    fun wrongHpkeContextAndCiphertextTamperingFailClosed() = runBlocking {
        val fixture = fixture()
        val chain = commitChain(fixture.verified.configurationSha256)
        val destination = ByteArrayOutputStream()
        ResearchExport.encrypt(snapshot(fixture, chain.runtime), chain.store, destination)
        val encoded = destination.toByteArray()

        assertThrows(Exception::class.java) {
            ResearchExport.decrypt(encoded, HpkeCrypto.generateKeyPair().privateKey, fixture.configuration)
        }
        assertThrows(Exception::class.java) {
            ResearchExport.decrypt(
                encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
                fixture.hpke.privateKey,
                fixture.configuration,
            )
        }
        Unit
    }

    private fun snapshot(
        fixture: Fixture,
        runtime: RuntimeDocument,
        kind: BundleKind = BundleKind.MANUAL_EXPORT,
        maximumPlaintextBytes: Long? = null,
        fromCommit: Long = runtime.retainedFromCommit,
    ) = ExportSnapshot(
        verifiedConfiguration = fixture.verified,
        runtime = runtime,
        producer = BundleProducer("android", "42"),
        bundleKind = kind,
        exportedAtUtcMillis = 10_000,
        bundleId = UUID.fromString("00000000-0000-4000-8000-000000000099"),
        fromCommit = fromCommit,
        maximumPlaintextBytes = maximumPlaintextBytes,
    )

    private fun commitChain(
        configurationSha256: String,
        assignedParticipantId: String? = null,
        collectorEpoch: ConditionEpochId? = EPOCH_ID,
        collectorProducerOrdinal: Long = 0,
        includeResourceAuditTimer: Boolean = false,
    ): Chain {
        val epoch = ConditionEpoch(EPOCH_ID, configurationSha256, RESOURCE_DIGEST, TIME)
        val initial = RuntimeDocument.initial(
            "export-test",
            "export-config",
            configurationSha256,
            "A".repeat(43),
            assignedParticipantId,
            PARTICIPANT_ID,
        )
        val clockAtStart = StudyClockCheckpoint(
            calendarElapsedNanos = 0,
            activeRunningElapsedNanos = 0,
            anchor = TIME,
            deadlineUtcMillis = TIME.wallTimeUtcMillis + 3_600_000,
            deadlineUtcTrusted = true,
            zoneId = "UTC",
        )
        val started = RecordedEvent(
            1,
            EventTypeKey(EventSourceId("study_runtime.v1"), 1, "STUDY_STARTED"),
            TIME,
            null,
            mapOf(
                "command_id" to COMMAND_ID,
                "current_state" to "ACTIVATING",
                "transition_reason" to "STUDY_START",
            ),
        )
        val deadlineTimer = studyDeadlineTimer(configurationSha256, clockAtStart)
        val deadlineTarget = deadlineTimer.target as TimerTarget.SameBootMonotonic
        val deadlineScheduled = RecordedEvent(
            2,
            EventTypeKey(EventSourceId("timer.v1"), 1, "TIMER_SCHEDULED"),
            TIME,
            null,
            mapOf(
                "automation_id" to deadlineTimer.automationId,
                "causal_sequence" to deadlineTimer.causalSequence.toString(),
                "clock" to "SAME_BOOT_MONOTONIC",
                "generation" to deadlineTimer.generation.toString(),
                "logical_due_research_time" to ResearchTime(
                    requireNotNull(deadlineTimer.logicalDeadlineUtcMillis),
                    deadlineTarget.elapsedRealtimeNanos,
                    deadlineTarget.bootSessionId,
                ).canonicalEmbeddedJson(),
                "producer_key" to deadlineTimer.producerKey,
                "timer_id" to deadlineTimer.id,
            ),
        )
        val activatingCheckpoint = AutomationCheckpoint(
            evaluatedThroughSequence = 1,
            lifecycle = StudySessionState.ACTIVATING,
            studyStartUtcMillis = TIME.wallTimeUtcMillis,
            desiredResources = mapOf(
                APP_RESOURCE_KEY to DesiredProfile(ResourceGeneration(1uL), APP_PROFILE.id),
            ),
        )
        val activating = EngineCommit(
            commitSequence = 1,
            previousCommitSha256 = GENESIS_DIGEST,
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            consumedPendingInputSha256 = null,
            sourceObservations = emptyList(),
            events = listOf(started, deadlineScheduled),
            mutations = listOf(
                checkpointMutation(activatingCheckpoint),
                studyDeadlineTimerMutation(deadlineTimer),
            ).sortedBy(RuntimeMutation::key),
            committedAt = TIME,
            successorProjection = projection(
                revision = 1,
                nextObservation = 1,
                nextEvent = 3,
                checkpoints = emptyMap(),
                epoch = null,
                lifetime = 0,
                state = ExperimentState.ACTIVATING,
                clock = clockAtStart,
            ),
            resultingCheckpointSha256 = activatingCheckpoint.digest(),
            commitSha256 = GENESIS_DIGEST,
        ).withComputedDigest()
        val activation = RecordedEvent(
            3,
            EventTypeKey(EventSourceId("study_condition.v1"), 1, "CONDITION_EPOCH_ACTIVATED"),
            TIME,
            EPOCH_ID,
            mapOf(
                "activation_reason" to "INITIAL_START",
                "applied_resource_vector_sha256" to RESOURCE_DIGEST,
                "boundary_research_time" to TIME.canonicalEmbeddedJson(),
                "condition_epoch_id" to EPOCH_ID.value,
                "resource_vector_json" to RESOURCE_VECTOR.canonicalJson(),
                "signed_configuration_sha256" to configurationSha256,
            ),
        )
        val running = RecordedEvent(
            4,
            EventTypeKey(EventSourceId("study_runtime.v1"), 1, "STUDY_RUNNING"),
            TIME,
            EPOCH_ID,
            mapOf(
                "command_id" to COMMAND_ID,
                "current_state" to "RUNNING",
                "previous_state" to "ACTIVATING",
                "transition_reason" to "ACTIVATION_CONFIRMED",
            ),
        )
        val runningCheckpoint = AutomationCheckpoint(
            evaluatedThroughSequence = 2,
            lifecycle = StudySessionState.RUNNING,
            studyStartUtcMillis = TIME.wallTimeUtcMillis,
            desiredResources = mapOf(
                APP_RESOURCE_KEY to DesiredProfile(ResourceGeneration(1uL), APP_PROFILE.id),
            ),
        )
        val applied = EngineCommit(
            commitSequence = 2,
            previousCommitSha256 = activating.commitSha256,
            inputKind = EngineInputKind.RESOURCE_RESULT,
            consumedPendingInputSha256 = null,
            sourceObservations = emptyList(),
            events = listOf(activation, running),
            mutations = buildList {
                add(checkpointMutation(runningCheckpoint))
                add(appliedResourceMutation())
                if (includeResourceAuditTimer) add(resourceAuditTimerMutation())
            }.sortedBy(RuntimeMutation::key),
            committedAt = TIME,
            successorProjection = projection(
                revision = 2,
                nextObservation = 1,
                nextEvent = 5,
                checkpoints = emptyMap(),
                epoch = epoch,
                lifetime = 0,
                state = ExperimentState.RUNNING,
                clock = clockAtStart,
            ),
            resultingCheckpointSha256 = runningCheckpoint.digest(),
            commitSha256 = GENESIS_DIGEST,
        ).withComputedDigest()
        val checkpoint = SourceCheckpoint(
            EventSourceId("app_lifecycle.v1"),
            1,
            collectorProducerOrdinal + 1,
            null,
            null,
        )
        val collectorTime = TIME.copy(elapsedRealtimeNanos = 3_000)
        val collectorClock = clockAtStart.copy(
            calendarElapsedNanos = 1_000,
            activeRunningElapsedNanos = 1_000,
            anchor = collectorTime,
        )
        val collectorEvent = RecordedEvent(
            5,
            EventTypeKey(EventSourceId("app_lifecycle.v1"), 1, "ACTIVITY_CREATED"),
            collectorTime,
            collectorEpoch,
            mapOf("activity_class" to "MainActivity"),
        )
        val unsignedObservation = SourceObservation(
            1,
            EventSourceId("app_lifecycle.v1"),
            1,
            1,
            ObservationAdmissionKind.NORMAL,
            collectorProducerOrdinal,
            EPOCH_ID,
            1,
            5,
            5,
            null,
            GENESIS_DIGEST,
        )
        val observation = unsignedObservation.copy(
            encodedSha256 = SourceObservationIntegrity.calculate(unsignedObservation, listOf(collectorEvent)),
        )
        val collectedCheckpoint = runningCheckpoint.copy(
            evaluatedThroughSequence = 3,
            lastActiveElapsedNanos = 1_000,
            lastCalendarElapsedNanos = 1_000,
        )
        val collected = EngineCommit(
            commitSequence = 3,
            previousCommitSha256 = applied.commitSha256,
            inputKind = EngineInputKind.SOURCE_OBSERVATION,
            consumedPendingInputSha256 = null,
            sourceObservations = listOf(observation),
            events = listOf(collectorEvent),
            mutations = listOf(checkpointMutation(collectedCheckpoint)),
            committedAt = collectorTime,
            successorProjection = projection(
                revision = 3,
                nextObservation = 2,
                nextEvent = 6,
                checkpoints = mapOf(checkpoint.sourceId to checkpoint),
                epoch = epoch,
                lifetime = 1,
                state = ExperimentState.RUNNING,
                clock = collectorClock,
            ),
            resultingCheckpointSha256 = collectedCheckpoint.digest(),
            commitSha256 = GENESIS_DIGEST,
        ).withComputedDigest()
        val commits = listOf(activating, applied, collected)
        val runtime = commits.fold(initial, RuntimeDocument::advance)
        return Chain(runtime, commits, SnapshotStore(runtime, commits))
    }

    private fun projection(
        revision: Long,
        nextObservation: Long,
        nextEvent: Long,
        checkpoints: Map<EventSourceId, SourceCheckpoint>,
        epoch: ConditionEpoch?,
        lifetime: Long,
        state: ExperimentState,
        clock: StudyClockCheckpoint,
    ) = RuntimeProjection(
        state = state,
        revision = revision,
        nextCommitSequence = revision + 1,
        nextObservationSequence = nextObservation,
        nextEventSequence = nextEvent,
        sourceCheckpoints = checkpoints,
        clockCheckpoint = clock,
        activeConditionEpoch = epoch,
        lifetimeDataEventCount = lifetime,
        uploadedThroughCommit = 0,
        evaluatedThroughCommit = revision,
        retainedFromCommit = 1,
    )

    private fun checkpointMutation(checkpoint: AutomationCheckpoint) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "main"),
        RuntimeMutationOperation.UPSERT,
        AutomationCheckpointCodec.encode(checkpoint),
    )

    private fun studyDeadlineTimer(
        configurationSha256: String,
        clock: StudyClockCheckpoint,
    ): DurableTimer {
        val targetElapsedNanos = clock.anchor.elapsedRealtimeNanos + 3_600_000_000_000L
        return DurableTimer(
            id = listOf(
                "particeps-study-deadline-timer-v1",
                configurationSha256,
                "study-duration",
                "study-deadline",
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8).sha256Hex(),
            automationId = "study-duration",
            generation = 1uL,
            causalSequence = 1,
            producerKey = "study-deadline",
            target = TimerTarget.SameBootMonotonic(clock.anchor.bootSessionId, targetElapsedNanos),
            logicalDeadlineUtcMillis = requireNotNull(clock.deadlineUtcMillis),
            expiresAtUtcMillis = null,
        )
    }

    private fun studyDeadlineTimerMutation(timer: DurableTimer): RuntimeMutation {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                fun writeString(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeInt(1)
                writeString(timer.id)
                writeString(timer.automationId)
                writeString(timer.generation.toString())
                output.writeLong(timer.causalSequence)
                writeString(timer.producerKey)
                val target = timer.target as TimerTarget.SameBootMonotonic
                output.writeByte(2)
                writeString(target.bootSessionId)
                output.writeLong(target.elapsedRealtimeNanos)
                output.writeBoolean(true)
                output.writeLong(requireNotNull(timer.logicalDeadlineUtcMillis))
                output.writeBoolean(false)
            }
            bytes.toByteArray()
        }
        return RuntimeMutation(
            RuntimeComponentKey(RuntimeComponentKind.STUDY_DEADLINE_TIMER, "study-duration"),
            RuntimeMutationOperation.UPSERT,
            "durable-timer-v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
        )
    }

    private fun ResearchTime.canonicalEmbeddedJson(): String =
        "{\"boot_session_id\":\"$bootSessionId\",\"monotonic_time_nanos\":\"$elapsedRealtimeNanos\"," +
            "\"wall_time_utc_millis\":\"$wallTimeUtcMillis\"}"

    private fun appliedResourceMutation(): RuntimeMutation {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                fun writeString(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                fun writeNullableString(value: String?) {
                    output.writeBoolean(value != null)
                    if (value != null) writeString(value)
                }
                output.writeInt(1)
                writeString(APP_RESOURCE_KEY.kind.name)
                writeString(APP_RESOURCE_KEY.id)
                writeString("1")
                writeNullableString(APP_PROFILE.id)
                writeNullableString(APP_PROFILE.expectedSha256.value)
                writeString(AppliedResourceStatus.APPLIED.name)
                writeNullableString(null)
            }
            bytes.toByteArray()
        }
        return RuntimeMutation(
            RuntimeComponentKey(RuntimeComponentKind.RESOURCE, "collector:${APP_RESOURCE_KEY.id}"),
            RuntimeMutationOperation.UPSERT,
            "applied-resource-v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
        )
    }

    private fun resourceAuditTimerMutation(): RuntimeMutation {
        val timerId = "c".repeat(64)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                fun writeString(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeInt(1)
                writeString(timerId)
                writeString("bind-app-lifecycle")
                writeString("1")
                output.writeLong(1)
                writeString("resource-audit:collector:app_lifecycle.v1")
                output.writeByte(2)
                writeString(TIME.bootSessionId)
                output.writeLong(60_000_000_000)
                output.writeBoolean(true)
                output.writeLong(60_000)
                output.writeBoolean(false)
            }
            bytes.toByteArray()
        }
        return RuntimeMutation(
            RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, timerId),
            RuntimeMutationOperation.UPSERT,
            "durable-timer-v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
        )
    }

    private fun fixture(assignedParticipantId: String? = null): Fixture {
        val signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val hpke = HpkeCrypto.generateKeyPair()
        val configuration = configuration(rawPublicKey(signing), hpke.publicKey, assignedParticipantId)
        val bytes = StudyConfigurationCodec.encode(configuration)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signing.private)
            update(bytes)
            sign()
        }
        return Fixture(
            hpke,
            configuration,
            VerifiedConfiguration(
                configuration,
                bytes,
                configuration.signer.keyId,
                signature,
                bytes.sha256Hex(),
                false,
            ),
        )
    }

    private fun configuration(
        signerPublicKey: ByteArray,
        hpkePublicKey: ByteArray,
        assignedParticipantId: String?,
    ): StudyConfiguration {
        val collector = CollectorResourceConfiguration(
            AppLifecycleV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", AppLifecycleV1ProfileConfiguration())),
        )
        return StudyConfiguration(
            schemaVersion = 1,
            experimentId = "export-test",
            configurationId = "export-config",
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = "android",
            minimumClientVersion = 1,
            title = "Export test",
            researcherName = "Export researcher",
            researcherContact = "export@example.invalid",
            purpose = "Test authenticated event-driven runtime export.",
            durationHours = 1,
            consentDocumentVersion = "v1",
            consentSummary = "Export test consent.",
            assignedParticipantId = assignedParticipantId,
            collectors = listOf(collector),
            surveys = emptyList(),
            interventions = emptyList(),
            automations = listOf(
                ResourceBindingAutomation(
                    "bind-app-lifecycle",
                    collector.resourceKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ),
            ).sortedBy(AutomationDefinition::id),
            trafficShaping = TrafficShapingConfiguration.Disabled,
            maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
            signer = SignerIdentity("test-signer", ProtocolBase64Url.encode(signerPublicKey)),
            export = ExportConfiguration("export-key", ProtocolBase64Url.encode(hpkePublicKey)),
            upload = null,
        )
    }

    private fun rawPublicKey(pair: KeyPair): ByteArray = pair.public.encoded.copyOfRange(12, 44)

    private data class Fixture(
        val hpke: cool.jacoblin.particeps.core.crypto.HpkeKeyPair,
        val configuration: StudyConfiguration,
        val verified: VerifiedConfiguration,
    )

    private data class Chain(
        val runtime: RuntimeDocument,
        val commits: List<EngineCommit>,
        val store: SnapshotStore,
    )

    private class SnapshotStore(
        private var runtime: RuntimeDocument,
        private val commits: List<EngineCommit>,
    ) : StudyStore {
        override suspend fun loadRuntime() = runtime
        override suspend fun initialize(runtime: RuntimeDocument) { this.runtime = runtime }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) = error("Not supported")
        override suspend fun stagePendingInput(input: PendingEngineInput) = error("Not supported")
        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) =
            error("Not supported")
        override suspend fun loadPendingInput(): PendingEngineInput? = null
        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) =
            error("Not supported")
        override suspend fun readCommits(
            fromCommitInclusive: Long,
            throughCommitInclusive: Long,
            consume: (EngineCommit) -> Unit,
        ) {
            commits.filter { it.commitSequence in fromCommitInclusive..throughCommitInclusive }.forEach(consume)
        }
        override suspend fun storageUsage() = StorageUsage(commits.size.toLong(), 16_777_216)
        override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long) = runtime
        override suspend fun clear() = Unit
    }

    private companion object {
        const val KOTLIN_EXPORT_INTEROP_DIRECTORY_ENV = "PARTICEPS_KOTLIN_EXPORT_INTEROP_DIR"
        const val INTEROP_BUNDLE_FILE = "kotlin-export.partexp"
        const val INTEROP_PRIVATE_KEY_FILE = "researcher-private-key.base64url"
        const val INTEROP_MANIFEST_FILE = "expected.json"
        val EPOCH_ID = ConditionEpochId("00000000-0000-4000-8000-000000000010")
        val TIME = ResearchTime(1_000, 2_000, "boot-test")
        val COMMAND_ID = "b".repeat(64)
        val APP_RESOURCE_KEY = ResourceKey(ResourceKind.COLLECTOR, AppLifecycleV1ProfileConfiguration.SOURCE_ID)
        val APP_PROFILE = NamedCollectorProfile("continuous", AppLifecycleV1ProfileConfiguration()).asSignedProfile()
        val RESOURCE_VECTOR = AppliedResourceVector(
            listOf(
                AppliedResourceState(
                    APP_RESOURCE_KEY,
                    ResourceGeneration(1uL),
                    APP_PROFILE.id,
                    APP_PROFILE.expectedSha256,
                    AppliedResourceStatus.APPLIED,
                    null,
                ),
            ),
        )
        val RESOURCE_DIGEST = RESOURCE_VECTOR.conditionDigest.value
        const val PARTICIPANT_ID = "00000000-0000-4000-8000-000000000017"
        val TOP_LEVEL_KEYS = setOf(
            "bundle_id", "bundle_kind", "configuration", "configuration_sha256",
            "configuration_signature", "event_source_registry_sha256", "experiment",
            "exported_at_utc_millis", "format", "producer",
        )
        val EXPERIMENT_KEYS = setOf(
            "assigned_participant_id", "commit_count", "commits", "configuration_id",
            "durable_through_commit", "evaluated_through_commit", "event_count", "experiment_id",
            "first_commit_sequence", "last_commit_sequence", "lifetime_data_event_count",
            "next_commit_sequence", "participant_instance_id", "retained_from_commit", "state",
            "uploaded_through_commit",
        )
        val COMMIT_KEYS = setOf(
            "commit_sha256", "commit_sequence", "committed_at", "consumed_pending_input_sha256",
            "events", "input_kind", "mutations", "previous_commit_sha256",
            "resulting_checkpoint_sha256", "source_observations", "successor_projection",
        )
    }
}
