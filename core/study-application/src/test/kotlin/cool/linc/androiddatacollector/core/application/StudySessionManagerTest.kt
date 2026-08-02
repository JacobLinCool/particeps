package cool.linc.androiddatacollector.core.application

import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.collector.AccessStatus
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorHealth
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.CollectorRegistry
import cool.linc.androiddatacollector.core.collector.CollectorStatus
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.ResearchClocks
import cool.linc.androiddatacollector.core.collector.StudyAccessGateway
import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.ExportConfiguration
import cool.linc.androiddatacollector.core.definition.InterventionConfiguration
import cool.linc.androiddatacollector.core.definition.InterventionTrigger
import cool.linc.androiddatacollector.core.definition.NotificationAction
import cool.linc.androiddatacollector.core.definition.OneTimeSchedule
import cool.linc.androiddatacollector.core.definition.RelativeClock
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.UploadConfiguration
import cool.linc.androiddatacollector.core.export.ExportReceipt
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StorageUsage
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.runtime.CommandResult
import cool.linc.androiddatacollector.core.runtime.ExperimentRuntime
import cool.linc.androiddatacollector.core.protocol.ActiveStudyStore
import cool.linc.androiddatacollector.core.protocol.VerifiedConfiguration
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionManagerTest {
    @Test
    fun participantLifecycleCoordinatesRuntimeHostWorkExportAndDeletion() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1, 2, 3))

        assertTrue(fixture.active.saved!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(CommandResult.Success, manager.reviewStudy())
        assertEquals(CommandResult.Success, manager.acceptConsent())
        assertEquals(CommandResult.Success, manager.completeAccessSetup())
        assertEquals(CommandResult.Success, manager.start())
        assertEquals(1, fixture.host.startCount)
        assertEquals(1, fixture.work.scheduleCount)

        assertEquals(CommandResult.Success, manager.pause())
        assertEquals(1, fixture.host.stopCount)
        assertEquals(CommandResult.Success, manager.resume())
        assertEquals(2, fixture.host.startCount)
        assertEquals(CommandResult.Success, manager.finish())
        runCurrent()

        assertEquals(ExperimentState.COMPLETED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(2, fixture.host.stopCount)
        // Finishing retires reminders and the deadline but leaves delivery scheduled, so a study
        // that ends with an undelivered backlog still gets it to the researcher.
        assertEquals(1, fixture.work.cancelCollectionCount)
        assertEquals(0, fixture.work.cancelCount)
        assertEquals(1, fixture.collector.startCount)
        assertEquals(1, fixture.collector.pauseCount)
        assertEquals(1, fixture.collector.resumeCount)
        assertEquals(1, fixture.collector.stopCount)

        val destination = CloseTrackingOutputStream()
        val receipt = manager.exportTo(destination)
        assertEquals(0L, receipt.eventCount)
        assertTrue(destination.closed)

        manager.deleteLocalData()
        assertTrue(fixture.store.cleared)
        assertNull(fixture.active.saved)
        assertNull(manager.snapshot.value.configuration)
        // Deleting the data is the point at which delivery has nothing left to deliver.
        assertEquals(1, fixture.work.cancelCount)
    }

    @Test
    fun interventionNotificationIsPartOfCanonicalRequiredAccess() = runTest {
        val configuration = configuration(
            interventions = listOf(
                InterventionConfiguration(
                    "notice-one",
                    NotificationAction("Study check-in", "Check in"),
                    listOf(
                        InterventionTrigger(
                            "after-minute",
                            OneTimeSchedule(1, RelativeClock.CALENDAR_TIME),
                            60,
                        ),
                    ),
                ),
            ),
        )
        val fixture = fixture(configuration)
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()

        assertEquals(
            CommandResult.Failed("REQUIRED_ACCESS_MISSING"),
            fixture.manager.completeAccessSetup(),
        )
        runCurrent()
        assertEquals(ExperimentState.ACCESS_SETUP, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertTrue(
            fixture.manager.snapshot.value.access.single { it.requirement.kind == AccessKind.NOTIFICATIONS }
                .requirement.required,
        )
    }

    @Test
    fun recoveryOfRunningStudyRestartsCollectionHostOnce() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = StudyMetadata.initial(configuration.experimentId, configuration.configurationId)
                .copy(state = ExperimentState.RUNNING),
        )

        fixture.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.host.startCount)
        assertEquals(1, fixture.collector.startCount)
    }

    @Test
    fun schedulingFailureCompensatesToPausedAndStopsForegroundHost() = runTest {
        val fixture = fixture(configuration())
        fixture.work.failSchedule = true
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.manager.completeAccessSetup()

        assertEquals(CommandResult.Failed("WORK_SCHEDULING_FAILED"), fixture.manager.start())
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.collector.pauseCount)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.work.cancelCount)
    }

    @Test
    fun uploadSendsUndeliveredRangesAndAdvancesTheWatermarkOnlyOnSuccess() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val uploader = FakeUploader()
        val fixture = fixture(configuration(upload = upload), uploader = uploader)
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(3)
        runCurrent()

        assertEquals(CommandResult.Success, manager.uploadPending())
        runCurrent()

        assertEquals(listOf(1L to 3L), uploader.ranges)
        assertEquals(3L, manager.snapshot.value.upload?.uploadedThroughSequence)
        assertEquals(0L, manager.snapshot.value.upload?.pendingCount)

        // A second call with nothing new must not re-send the same events.
        assertEquals(CommandResult.Success, manager.uploadPending())
        assertEquals(listOf(1L to 3L), uploader.ranges)

        // Only the events collected since the last confirmation go out next.
        fixture.collector.emit(2)
        runCurrent()
        assertEquals(CommandResult.Success, manager.uploadPending())
        runCurrent()
        assertEquals(listOf(1L to 3L, 4L to 5L), uploader.ranges)
        assertEquals(5L, manager.snapshot.value.upload?.uploadedThroughSequence)
    }

    @Test
    fun failedUploadKeepsTheWatermarkAndDoesNotMaskACollectionIncident() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val uploader = FakeUploader(failure = IllegalStateException("endpoint down"))
        val fixture = fixture(configuration(upload = upload), uploader = uploader)
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(2)
        runCurrent()

        assertEquals(CommandResult.Failed("UPLOAD_FAILED"), manager.uploadPending())
        runCurrent()

        assertEquals(0L, manager.snapshot.value.upload?.uploadedThroughSequence)
        assertEquals("UPLOAD_FAILED", manager.snapshot.value.upload?.lastFailureCode)
        // A transient delivery problem is not a collection incident; the participant-facing code
        // must stay clear so a real storage or access failure is not buried by it.
        assertNull(manager.snapshot.value.incidentCode)
    }

    @Test
    fun finishedStudyStillDeliversItsBacklogAndThenReportsDrained() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val uploader = FakeUploader()
        val fixture = fixture(configuration(upload = upload), uploader = uploader)
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(2)
        runCurrent()

        // The study ends before anything was delivered.
        assertEquals(CommandResult.Success, manager.finish())
        runCurrent()
        assertTrue(uploader.ranges.isEmpty())
        assertTrue(!manager.uploadDrained())

        // Delivery is still scheduled, so the backlog goes out after the study is over.
        assertEquals(CommandResult.Success, manager.uploadPending())
        runCurrent()

        assertEquals(listOf(1L to 2L), uploader.ranges)
        assertTrue(manager.uploadDrained())
    }

    @Test
    fun localDataSurvivesDeliveryWhileThereIsRoomForIt() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val fixture = fixture(configuration(upload = upload), uploader = FakeUploader())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(4)
        runCurrent()

        // Comfortably inside the quota: full local retention is the norm, not an optimisation.
        fixture.store.usedBytes = 100
        fixture.store.quotaBytes = 1_000
        assertEquals(CommandResult.Success, manager.uploadPending())
        runCurrent()

        assertEquals(4L, manager.snapshot.value.upload?.uploadedThroughSequence)
        assertEquals(0, fixture.store.evictionCount)
        assertEquals(1L, manager.snapshot.value.runtime.metadata?.retainedFromSequence)
    }

    @Test
    fun deliveredEventsAreReclaimedOnlyOnceStorageIsUnderPressure() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val fixture = fixture(configuration(upload = upload), uploader = FakeUploader())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(4)
        runCurrent()

        // Past the 80% mark, so delivered events become reclaimable.
        fixture.store.usedBytes = 900
        fixture.store.quotaBytes = 1_000
        assertEquals(CommandResult.Success, manager.uploadPending())
        runCurrent()

        assertEquals(1, fixture.store.evictionCount)
        // Events 1-3 go; event 4 stays because the newest event is never reclaimed, so a reload
        // always finds one.
        assertEquals(4L, manager.snapshot.value.runtime.metadata?.retainedFromSequence)
        // The lifetime counter does not rewind, so sequence numbers are never reissued.
        assertEquals(4L, manager.snapshot.value.runtime.metadata?.eventCount)
        assertEquals(5L, manager.snapshot.value.runtime.metadata?.nextSequenceNumber)
    }

    @Test
    fun undeliveredEventsAreKeptEvenUnderStoragePressure() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        // The endpoint refuses, so nothing is confirmed and nothing may be reclaimed — the study
        // fills up and fails closed rather than dropping research data to make room.
        val fixture = fixture(
            configuration(upload = upload),
            uploader = FakeUploader(failure = IllegalStateException("endpoint down")),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(4)
        runCurrent()

        fixture.store.usedBytes = 990
        fixture.store.quotaBytes = 1_000
        assertEquals(CommandResult.Failed("UPLOAD_FAILED"), manager.uploadPending())
        runCurrent()

        assertEquals(0, fixture.store.evictionCount)
        assertEquals(1L, manager.snapshot.value.runtime.metadata?.retainedFromSequence)
    }

    @Test
    fun uploadBeforeCollectionStartsIsANoOpRatherThanAFailure() = runTest {
        val upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)
        val uploader = FakeUploader()
        val fixture = fixture(configuration(upload = upload), uploader = uploader)
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))

        // A periodic worker can fire before the participant has started the study.
        assertEquals(CommandResult.Success, manager.uploadPending())

        assertTrue(uploader.ranges.isEmpty())
        assertNull(manager.snapshot.value.upload)
    }

    @Test
    fun studyWithoutAnUploadBlockNeverContactsAnEndpoint() = runTest {
        val uploader = FakeUploader()
        val fixture = fixture(configuration(), uploader = uploader)
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        fixture.collector.emit(2)
        runCurrent()

        assertEquals(CommandResult.Success, manager.uploadPending())

        assertTrue(uploader.ranges.isEmpty())
        assertNull(manager.snapshot.value.upload)
    }

    private fun TestScope.fixture(
        configuration: StudyConfiguration,
        activeEnvelope: ByteArray? = null,
        initialMetadata: StudyMetadata? = null,
        uploader: StudyUploader = FakeUploader(),
    ): Fixture {
        val active = FakeActiveStudyStore(activeEnvelope)
        val store = FakeStudyStore(initialMetadata)
        val collector = FakeCollector()
        val registry = CollectorRegistry(listOf(FakePlugin(collector)))
        val access = FakeAccessGateway()
        val host = FakeHost()
        val work = FakeWorkScheduler()
        val manager = StudySessionManager(
            activeStudyStore = active,
            verifier = StudyVerifier { VerifiedConfiguration(configuration, signerAnchored = false) },
            storeFactory = StudyStoreFactory { store },
            runtimeFactory = ExperimentRuntimeFactory { verified, createdStore, availableAccess ->
                ExperimentRuntime(
                    verified,
                    createdStore,
                    registry,
                    FakeClocks(),
                    backgroundScope,
                    availableAccess,
                )
            },
            collectorRegistry = registry,
            accessGateway = access,
            collectionHost = host,
            workScheduler = work,
            exporter = FakeExporter(),
            uploader = uploader,
            accessPolicy = StudyAccessPolicy(),
            scope = backgroundScope,
        )
        return Fixture(manager, active, store, collector, host, work, uploader)
    }

    private data class Fixture(
        val manager: StudySessionManager,
        val active: FakeActiveStudyStore,
        val store: FakeStudyStore,
        val collector: FakeCollector,
        val host: FakeHost,
        val work: FakeWorkScheduler,
        val uploader: StudyUploader,
    )

    private class FakeActiveStudyStore(initial: ByteArray?) : ActiveStudyStore {
        var saved = initial
        override suspend fun load(): ByteArray? = saved
        override suspend fun save(envelopeBytes: ByteArray) { saved = envelopeBytes }
        override suspend fun clear() { saved = null }
    }

    private class FakeStudyStore(initial: StudyMetadata?) : StudyStore {
        private var metadata = initial
        private val events = mutableListOf<RecordedEvent>()
        var cleared = false
        var usedBytes = 0L
        var quotaBytes = 16_777_216L
        var evictionCount = 0

        override suspend fun storageUsage() = StorageUsage(usedBytes, quotaBytes)

        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata {
            evictionCount += 1
            // Mirrors the real store: only what an endpoint confirmed is reclaimable, and the
            // newest event survives regardless, standing in for the segment still being appended
            // to. Nothing is held back for a collector's most recent event — lastEvents lives in
            // the metadata rather than being rebuilt from surviving frames.
            val newest = events.maxOfOrNull { it.sequenceNumber } ?: Long.MAX_VALUE
            val floor = minOf(metadata.uploadedThroughSequence + 1, newest)
            if (floor <= metadata.retainedFromSequence) return metadata
            events.removeAll { it.sequenceNumber < floor }
            usedBytes = 0
            return metadata.copy(retainedFromSequence = floor).also { this.metadata = it }
        }

        override suspend fun loadMetadata(): StudyMetadata? = metadata
        override suspend fun initialize(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun saveMetadata(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun appendEvent(event: RecordedEvent) { events += event }
        override suspend fun appendEventAtomically(event: RecordedEvent, metadata: StudyMetadata) {
            events += event
            this.metadata = metadata
        }
        override suspend fun readEvents(
            fromSequenceInclusive: Long,
            upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit,
        ) {
            events.takeWhile { it.sequenceNumber <= upToSequenceInclusive }
                .filter { it.sequenceNumber >= fromSequenceInclusive }
                .forEach(consume)
        }
        override suspend fun clear() {
            metadata = null
            events.clear()
            cleared = true
        }
    }

    private class FakePlugin(
        private val collector: FakeCollector,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            AppLifecycleConfiguration.ID,
            1,
            "Test collector",
            PrivacyClass.SENSITIVE,
            1_024,
        )

        override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> = emptySet()
        override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector =
            collector.also { it.context = context }
    }

    private class FakeCollector : Collector {
        private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = mutableHealth
        var context: CollectorContext? = null
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0

        override suspend fun start() { startCount += 1; mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE) }
        override suspend fun pause() { pauseCount += 1; mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED) }
        override suspend fun resume() { resumeCount += 1; mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE) }
        override suspend fun stop() { stopCount += 1; mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED) }

        /** Records [count] events through the real admission path, so sequence numbers are genuine. */
        suspend fun emit(count: Int) {
            val sink = checkNotNull(context).eventSink
            repeat(count) {
                val token = checkNotNull(sink.captureToken()) { "Admission gate refused a token" }
                sink.emit(
                    token,
                    EventDraft(
                        collectorId = AppLifecycleConfiguration.ID,
                        payloadSchemaVersion = 1,
                        observedTime = checkNotNull(context).clocks.now(),
                        payloadType = "ACTIVITY_RESUMED",
                        fields = emptyMap(),
                    ),
                )
            }
        }
    }

    private class FakeClocks : ResearchClocks {
        private var tick = 0L
        override fun now(): ResearchTime = ResearchTime(++tick * 1_000, tick * 1_000, "boot-test")
    }

    private class FakeAccessGateway : StudyAccessGateway {
        val granted = mutableSetOf<AccessKind>()
        override fun inspect(requirements: Set<AccessRequirement>): List<AccessStatus> = requirements
            .sortedBy { it.kind.name }
            .map { AccessStatus(it, it.kind in granted) }
        override fun grantedKinds(requirements: Set<AccessRequirement>): Set<AccessKind> =
            requirements.map(AccessRequirement::kind).filterTo(mutableSetOf()) { it in granted }
    }

    private class FakeHost : StudyCollectionHost {
        var startCount = 0
        var stopCount = 0
        override fun start(studyTitle: String, usesLocation: Boolean) { startCount += 1 }
        override fun stop() { stopCount += 1 }
    }

    private class FakeWorkScheduler : StudyWorkScheduler {
        var scheduleCount = 0
        var cancelCount = 0
        var cancelCollectionCount = 0
        var failSchedule = false
        override fun schedule(configuration: StudyConfiguration) {
            scheduleCount += 1
            if (failSchedule) error("Scheduling failed")
        }
        override fun replaceInterventionWork(
            configuration: StudyConfiguration,
            occurrences: List<InterventionOccurrence>,
        ) = Unit
        override fun enqueueOccurrence(
            configuration: StudyConfiguration,
            occurrence: InterventionOccurrence,
        ) = Unit
        override fun cancelCollectionWork(experimentId: String) { cancelCollectionCount += 1 }
        override fun cancel(experimentId: String) { cancelCount += 1 }
    }

    private class FakeUploader(
        private val failure: Throwable? = null,
    ) : StudyUploader {
        val ranges = mutableListOf<Pair<Long, Long>>()

        override suspend fun upload(
            configuration: StudyConfiguration,
            metadata: StudyMetadata,
            events: StudyStore,
            fromSequence: Long,
            toSequence: Long,
        ): ExportReceipt {
            ranges += fromSequence to toSequence
            failure?.let { throw it }
            return ExportReceipt(
                configuration.export.researcherKeyId,
                fromSequence,
                toSequence,
                toSequence - fromSequence + 1,
                "hash",
                1,
            )
        }
    }

    private class FakeExporter : StudyExporter {
        override suspend fun export(
            configuration: StudyConfiguration,
            metadata: StudyMetadata,
            events: StudyStore,
            destination: OutputStream,
        ): ExportReceipt {
            destination.write(1)
            return ExportReceipt(configuration.export.researcherKeyId, 1, metadata.eventCount, metadata.eventCount, "hash", 1)
        }
    }

    private class CloseTrackingOutputStream : OutputStream() {
        var closed = false
        override fun write(value: Int) = Unit
        override fun close() { closed = true }
    }

    private fun configuration(
        interventions: List<InterventionConfiguration> = emptyList(),
        upload: UploadConfiguration? = null,
    ) = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = "session-test",
        configurationId = "session-config",
        assignedParticipantId = null,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        minimumAppVersion = 1,
        title = "Session test",
        researcherName = "Test researcher",
        researcherContact = "test@example.invalid",
        purpose = "Application boundary test",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Test consent",
        collectors = listOf(AppLifecycleConfiguration(required = true)),
        surveys = emptyList(),
        interventions = interventions,
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", TEST_SIGNER_PUBLIC_KEY),
        export = ExportConfiguration("export-key", "x".repeat(32)),
        upload = upload,
    )
}

private const val TEST_SIGNER_PUBLIC_KEY =
    "MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0="
