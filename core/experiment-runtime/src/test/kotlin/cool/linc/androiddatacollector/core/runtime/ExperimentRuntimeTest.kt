package cool.linc.androiddatacollector.core.runtime

import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StorageUsage
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorHealth
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.CollectorRegistry
import cool.linc.androiddatacollector.core.collector.CollectorStatus
import cool.linc.androiddatacollector.core.collector.EmitResult
import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.ExportConfiguration
import cool.linc.androiddatacollector.core.definition.ChoiceOption
import cool.linc.androiddatacollector.core.definition.InterventionConfiguration
import cool.linc.androiddatacollector.core.definition.InterventionTrigger
import cool.linc.androiddatacollector.core.definition.LocalizedText
import cool.linc.androiddatacollector.core.definition.MultipleChoiceQuestion
import cool.linc.androiddatacollector.core.definition.OneTimeSchedule
import cool.linc.androiddatacollector.core.definition.RelativeClock
import cool.linc.androiddatacollector.core.definition.ScaleQuestion
import cool.linc.androiddatacollector.core.definition.ShortTextQuestion
import cool.linc.androiddatacollector.core.definition.SingleChoiceQuestion
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.ResearchClocks
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.SurveyAction
import cool.linc.androiddatacollector.core.definition.SurveyDefinition
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRuntimeTest {
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
        assertTrue(runtime.claimOccurrence(occurrence.occurrenceId)?.action is SurveyAction)
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
        assertNull(runtime.claimOccurrence(occurrence.occurrenceId))
        assertNull(runtime.openOccurrence(occurrence.occurrenceId))
        assertEquals(OccurrenceState.EXPIRED, runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state)
        assertEquals(listOf("INTERVENTION_SCHEDULED", "SURVEY_EXPIRED"), store.events.map { it.payloadType })
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

    private class FakeCollectorPlugin(
        private val clocks: ResearchClocks,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = AppLifecycleConfiguration.ID,
            payloadSchemaVersion = 1,
            displayName = "Fake collector",
            privacyClass = PrivacyClass.SENSITIVE,
            maximumEncodedEventBytes = 1_024,
        )
        lateinit var context: CollectorContext
        lateinit var collector: FakeCollector

        override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
            require(configuration is AppLifecycleConfiguration)
            return emptySet()
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

        suspend fun emit(type: String): EmitResult {
            val token = context.eventSink.captureToken() ?: return EmitResult.RejectedByAdmissionGate
            return context.eventSink.emit(
                token,
                EventDraft(
                    collectorId = descriptor.id,
                    payloadSchemaVersion = descriptor.payloadSchemaVersion,
                    observedTime = clocks.now(),
                    payloadType = type,
                    fields = mapOf("source" to "test"),
                ),
            )
        }
    }

    private class FakeCollector : Collector {
        private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = mutableHealth
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0

        override suspend fun start() {
            startCount += 1
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
            minimumAppVersion = 1,
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
            signer = SignerIdentity("test-signer", TEST_SIGNER_PUBLIC_KEY),
            export = ExportConfiguration(
                researcherKeyId = "test-key",
                tinkHpkePublicKeysetJson = "{\"placeholder\":\"not-used-in-runtime-tests\"}",
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
    }
}

private const val TEST_SIGNER_PUBLIC_KEY =
    "MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0="
