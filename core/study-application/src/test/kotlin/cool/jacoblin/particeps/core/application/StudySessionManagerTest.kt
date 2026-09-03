package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.definition.UploadConfiguration
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyResetMarker
import cool.jacoblin.particeps.core.model.StudyResetStore
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionManagerTest {
    @Test
    fun exactEnrollmentLifecycleExportsACompleteCommitBoundaryAndDeletes() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(ENVELOPE)

        assertEquals(ExperimentState.CONFIG_VERIFIED, fixture.manager.snapshot.value.runtime.state)
        assertEquals(StudyCommandResult.Success, fixture.manager.reviewStudy())
        assertEquals(StudyCommandResult.Success, fixture.manager.acceptConsent())
        assertEquals(StudyCommandResult.Success, fixture.manager.completeAccessSetup())
        assertEquals(StudyCommandResult.Success, fixture.manager.start())
        assertEquals(StudyCommandResult.Success, fixture.manager.pause())
        assertEquals(StudyCommandResult.Success, fixture.manager.resume())
        assertEquals(StudyCommandResult.Success, fixture.manager.complete())
        runCurrent()

        val beforeExport = fixture.store.runtime ?: error("missing runtime")
        val destination = ByteArrayOutputStream()
        val receipt = fixture.manager.exportTo(destination)

        assertEquals(beforeExport.revision, receipt.lastCommitSequence)
        assertEquals(beforeExport.revision, receipt.commitCount)
        assertTrue(receipt.eventCount > 0)
        assertTrue(destination.size() > 0)
        assertEquals(receipt.eventCount, fixture.manager.snapshot.value.lastExport?.eventCount)
        assertEquals(ExperimentState.COMPLETED, fixture.manager.snapshot.value.runtime.state)
        assertNull(fixture.manager.snapshot.value.runtime.participantInstanceId?.takeIf(String::isBlank))
        assertTrue(fixture.manager.snapshot.value.runtime.startedAtUtcMillis != null)

        fixture.manager.deleteLocalData()

        assertTrue(fixture.store.cleared)
        assertNull(fixture.active.record)
        assertNull(fixture.manager.snapshot.value.study)
        assertTrue(fixture.manager.snapshot.value.initialized)
    }

    @Test
    fun sameBootProcessRecoveryFromRunningIsDurablyPausedAndNeverAutoResumes() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(ENVELOPE)
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.manager.completeAccessSetup()
        fixture.manager.start()
        assertEquals(ExperimentState.RUNNING, fixture.store.runtime?.state)
        fixture.manager.shutdownProcess()

        val recovered = fixture.newManager()
        recovered.initialize()
        runCurrent()

        assertEquals(StudyRecoveryStatus.RECOVERED_PAUSED, recovered.snapshot.value.recoveryStatus)
        assertEquals(ExperimentState.PAUSED, recovered.snapshot.value.runtime.state)
        assertTrue(recovered.snapshot.value.runtime.deadlineUtcTrusted)
        recovered.deleteLocalData()
    }

    @Test
    fun recoveryRetryReverifiesWithoutDeletingAndResetRemainsExplicit() = runTest {
        val fixture = fixture(activeRecord = ActiveStudyRecord.Active(ENVELOPE))
        fixture.acceptedFailure = IllegalStateException("key temporarily unavailable")

        fixture.manager.initialize()

        assertEquals(StudyRecoveryStatus.ACTION_REQUIRED, fixture.manager.snapshot.value.recoveryStatus)
        assertTrue(fixture.active.record is ActiveStudyRecord.Active)
        assertFalse(fixture.store.cleared)

        fixture.acceptedFailure = null
        fixture.manager.retryRecovery()

        assertEquals(StudyRecoveryStatus.NONE, fixture.manager.snapshot.value.recoveryStatus)
        assertEquals(ExperimentState.CONFIG_VERIFIED, fixture.manager.snapshot.value.runtime.state)
        assertTrue(fixture.active.record is ActiveStudyRecord.Active)
        fixture.manager.deleteLocalData()
    }

    @Test
    fun automaticUploadUsesCompleteCommitBoundariesAndDurablyAcknowledgesBeforeCleanup() = runTest {
        val fixture = fixture(studyConfiguration = configuration(withUpload = true))
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(ENVELOPE)
        val beforeUpload = requireNotNull(fixture.store.runtime)
        val bundleId = UUID.fromString("123e4567-e89b-42d3-a456-426614174099")

        val encrypted = ByteArrayOutputStream()
        val receipt = requireNotNull(fixture.manager.prepareAutomaticUpload(encrypted, bundleId))

        assertEquals(bundleId, receipt.bundleId)
        assertEquals(1L, receipt.firstCommitSequence)
        assertEquals(beforeUpload.revision, receipt.lastCommitSequence)
        assertTrue(encrypted.size() > 0)
        assertEquals(1, fixture.uploadCoordinator.reconciliations.size)
        assertEquals("export-key", fixture.uploadCoordinator.reconciliations.single().plan.researcherKeyId)
        assertEquals(1, fixture.uploadScheduler.scheduled.size)

        assertEquals(StudyCommandResult.Success, fixture.manager.acknowledgeAutomaticUpload(receipt))
        assertEquals(receipt.lastCommitSequence, fixture.store.runtime?.uploadedThroughCommit)
        assertEquals(listOf(bundleId), fixture.uploadCoordinator.acknowledged)
        assertEquals(2, fixture.uploadScheduler.scheduled.size)
        fixture.manager.shutdownProcess()
    }

    @Test
    fun platformAccessLossSafetyPausesWithoutExposingAPlatformReason() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(ENVELOPE)
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.manager.completeAccessSetup()
        fixture.manager.start()

        assertEquals(StudyCommandResult.FailedClosed, fixture.manager.safetyPauseForPlatformAccessLoss())
        runCurrent()
        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.state)
        fixture.manager.shutdownProcess()
    }

    private fun TestScope.fixture(
        activeRecord: ActiveStudyRecord? = null,
        studyConfiguration: StudyConfiguration = configuration(),
    ): Fixture {
        val verified = verified(studyConfiguration)
        val active = FakeActiveStudyStore(activeRecord)
        val store = FakeStudyStore()
        val runtimeFactory = CapturingRuntimeFactory(
            EventDrivenRuntimeAssemblyFactory(
                collectorRegistry = CollectorRegistry(emptyList()),
                clocks = IncrementingClocks(),
                scope = this,
                zoneId = { "UTC" },
            ),
        )
        val fixture = Fixture(
            scope = this,
            configuration = verified,
            active = active,
            store = store,
            runtimeFactory = runtimeFactory,
            uploadCoordinator = FakeUploadCoordinator(),
            uploadScheduler = FakeUploadScheduler(),
        )
        fixture.manager = fixture.newManager()
        return fixture
    }

    private class Fixture(
        val scope: TestScope,
        val configuration: VerifiedConfiguration,
        val active: FakeActiveStudyStore,
        val store: FakeStudyStore,
        val runtimeFactory: CapturingRuntimeFactory,
        val uploadCoordinator: FakeUploadCoordinator,
        val uploadScheduler: FakeUploadScheduler,
    ) {
        var acceptedFailure: Throwable? = null
        lateinit var manager: StudySessionManager

        fun newManager() = StudySessionManager(
            activeStudyStore = active,
            verifier = StudyVerifier { configuration },
            acceptedStudyVerifier = AcceptedStudyVerifier {
                acceptedFailure?.let { throw it }
                configuration
            },
            storeFactory = StudyStoreFactory { _, _ -> store },
            runtimeFactory = runtimeFactory,
            collectorRegistry = CollectorRegistry(emptyList()),
            accessGateway = GrantedAccessGateway,
            resetStore = FakeResetStore(),
            storageResetter = cool.jacoblin.particeps.core.model.StudyStorageResetter { store.clear() },
            recoveryReporter = NoOpRecoveryReporter,
            accessPolicy = StudyAccessPolicy(),
            uploadCoordinator = uploadCoordinator,
            uploadScheduler = uploadScheduler,
            bundleProducer = BundleProducer(StudyConfiguration.ANDROID_PLATFORM, "1"),
            exportedAtUtcMillis = { 1_900_000_000_000L },
            scope = scope,
        )
    }

    private class CapturingRuntimeFactory(
        private val delegate: StudyRuntimeAssemblyFactory,
    ) : StudyRuntimeAssemblyFactory {
        var last: StudyRuntimeAssembly? = null
        override fun create(configuration: VerifiedConfiguration, store: StudyStore): StudyRuntimeAssembly =
            delegate.create(configuration, store).also { last = it }
    }

    private class IncrementingClocks : ResearchClocks {
        private var tick = 0L
        override fun now(): ResearchTime {
            tick++
            return ResearchTime(
                wallTimeUtcMillis = 1_800_000_000_000L + tick,
                elapsedRealtimeNanos = tick * 1_000_000L,
                bootSessionId = "boot-one",
            )
        }

        override fun trustedUtcMillis(): Long = 1_800_000_000_000L + tick
    }

    private object GrantedAccessGateway : StudyAccessGateway {
        override suspend fun inspect(
            request: cool.jacoblin.particeps.core.collector.AccessInspectionRequest,
        ): AccessSnapshot = AccessSnapshot(
            request.requirements.map { requirement ->
                AccessStatus(requirement, AccessResolution.Satisfied, null)
            },
        )
    }

    private object NoOpRecoveryReporter : RecoveryReporter {
        override fun actionRequired(failure: Throwable?) = Unit
        override fun clear() = Unit
    }

    private class FakeUploadCoordinator : StudyUploadCoordinator {
        val reconciliations = mutableListOf<UploadReconciliation>()
        val acknowledged = mutableListOf<UUID>()

        override suspend fun reconcile(context: UploadReconciliation) {
            reconciliations += context
        }

        override suspend fun acknowledge(bundleId: UUID) {
            acknowledged += bundleId
        }

        override suspend fun prepareDeletion(experimentId: String) = Unit
        override suspend fun clear(experimentId: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private class FakeUploadScheduler : StudyUploadScheduler {
        val scheduled = mutableListOf<StudyUploadPlan>()

        override suspend fun ensureScheduled(plan: StudyUploadPlan) {
            scheduled += plan
        }

        override suspend fun cancel(experimentId: String) = Unit
        override suspend fun cancelAll() = Unit
    }

    private class FakeResetStore : StudyResetStore {
        private var marker: StudyResetMarker? = null
        override suspend fun load(): StudyResetMarker? = marker
        override suspend fun mark(retainedEnvelopeBytes: ByteArray?) {
            marker = StudyResetMarker(retainedEnvelopeBytes?.copyOf())
        }
        override suspend fun clear() {
            marker = null
        }
    }

    private class FakeActiveStudyStore(initial: ActiveStudyRecord?) : ActiveStudyStore {
        var record: ActiveStudyRecord? = initial
        override suspend fun load(): ActiveStudyRecord? = record
        override suspend fun save(envelopeBytes: ByteArray) {
            record = ActiveStudyRecord.Active(envelopeBytes.copyOf())
        }
        override suspend fun markDeletionPending(experimentId: String, maximumLocalBytes: Long) {
            record = ActiveStudyRecord.DeletionPending(experimentId, maximumLocalBytes)
        }
        override suspend fun clear() {
            record = null
        }
    }

    private class FakeStudyStore : StudyStore {
        var runtime: RuntimeDocument? = null
        var pending: PendingEngineInput? = null
        val commits = mutableListOf<EngineCommit>()
        var cleared = false

        override suspend fun loadRuntime(): RuntimeDocument? = runtime
        override suspend fun initialize(runtime: RuntimeDocument) {
            check(this.runtime == null)
            this.runtime = runtime
        }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
            commits += commit
            runtime = successor
        }
        override suspend fun stagePendingInput(input: PendingEngineInput) {
            check(pending == null)
            pending = input
        }
        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
            check(pending?.encodedSha256 == expectedSha256)
            pending = input
        }
        override suspend fun loadPendingInput(): PendingEngineInput? = pending
        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
            check(pending != null)
            commits += commit
            runtime = successor
            pending = null
        }
        override suspend fun readCommits(
            fromCommitInclusive: Long,
            throughCommitInclusive: Long,
            consume: (EngineCommit) -> Unit,
        ) {
            commits.filter { it.commitSequence in fromCommitInclusive..throughCommitInclusive }.forEach(consume)
        }
        override suspend fun storageUsage(): StorageUsage = StorageUsage(0, StudyConfiguration.MINIMUM_LOCAL_BYTES)
        override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument = runtime
        override suspend fun clear() {
            runtime = null
            pending = null
            commits.clear()
            cleared = true
        }
    }

    private fun configuration(withUpload: Boolean = false) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "session-study",
        configurationId = "session-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Session study",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Verify the durable application lifecycle.",
        durationHours = 24,
        consentDocumentVersion = "v1",
        consentSummary = "Consent.",
        assignedParticipantId = "participant-1",
        collectors = emptyList(),
        surveys = emptyList(),
        interventions = emptyList(),
        automations = emptyList(),
        trafficShaping = TrafficShapingConfiguration.Disabled,
        maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
        signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
        export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
        upload = if (withUpload) UploadConfiguration("https://upload.example.invalid/v1", 60, false) else null,
    )

    private fun verified(configuration: StudyConfiguration): VerifiedConfiguration {
        val canonical = StudyConfigurationCodec.encode(configuration)
        return VerifiedConfiguration(
            configuration = configuration,
            canonicalConfigurationBytes = canonical,
            signerKeyId = configuration.signer.keyId,
            signature = ByteArray(64) { 3 },
            configurationSha256 = MessageDigest.getInstance("SHA-256")
                .digest(canonical)
                .joinToString(separator = "") { "%02x".format(it) },
            signerAnchored = true,
        )
    }

    private companion object {
        val ENVELOPE = byteArrayOf(1, 2, 3)
    }
}
