package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.InterventionConfiguration
import cool.jacoblin.particeps.core.definition.InterventionTrigger
import cool.jacoblin.particeps.core.definition.IntervalSchedule
import cool.jacoblin.particeps.core.definition.LocalizedText
import cool.jacoblin.particeps.core.definition.LocationConfiguration
import cool.jacoblin.particeps.core.definition.LocationPriority
import cool.jacoblin.particeps.core.definition.NotificationAction
import cool.jacoblin.particeps.core.definition.OneTimeSchedule
import cool.jacoblin.particeps.core.definition.RelativeClock
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import cool.jacoblin.particeps.core.definition.UploadConfiguration
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.TransitionReason
import cool.jacoblin.particeps.core.runtime.CommandResult
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.OccurrenceClaimResult
import cool.jacoblin.particeps.core.runtime.OccurrenceExpiryResult
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun participantLifecycleCoordinatesRuntimeHostWorkExportAndDeletion() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1, 2, 3))

        assertTrue(
            (fixture.active.record as ActiveStudyRecord.Active).envelopeBytes
                .contentEquals(byteArrayOf(1, 2, 3)),
        )
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
        assertEquals(1, fixture.work.cancelInterventionCount)
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
        assertNull(fixture.active.record)
        assertNull(manager.snapshot.value.configuration)
        // Deleting the data is the point at which delivery has nothing left to deliver.
        assertEquals(1, fixture.work.cancelCount)
        assertTrue((fixture.uploader as FakeUploader).cleared)
    }

    @Test
    fun durationCompletionRechecksEarlyAndExactDeadlineInsideTheSessionLock() = runTest {
        val fixture = fixture(configuration())
        fixture.prepareRunningStudy()

        fixture.forceDurationBoundary(offsetNanos = -1)
        assertEquals(
            DurationCompletionResult.NotDue(remainingMillis = 1),
            fixture.manager.completeAfterDurationIfDue(),
        )
        assertEquals(ExperimentState.RUNNING, fixture.store.metadata?.state)
        assertEquals(0, fixture.host.stopCount)
        assertEquals(0, fixture.work.cancelCollectionCount)

        fixture.forceDurationBoundary(offsetNanos = 0)
        assertEquals(
            DurationCompletionResult.Completed,
            fixture.manager.completeAfterDurationIfDue(),
        )
        assertEquals(ExperimentState.COMPLETED, fixture.store.metadata?.state)
        assertEquals(
            TransitionReason.STUDY_DURATION_ELAPSED,
            fixture.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.work.cancelCollectionCount)
    }

    @Test
    fun accessReconciliationCompletesADelayedDeadlineBeforeInspectingAccess() = runTest {
        val fixture = fixture(configuration())
        fixture.prepareRunningStudy()
        val inspectionsBeforeDeadline = fixture.access.inspectCount
        fixture.access.failure = IllegalStateException("access backend must not run after deadline")
        fixture.forceDurationBoundary(offsetNanos = 0)

        fixture.manager.reconcileAccess()

        assertEquals(ExperimentState.COMPLETED, fixture.store.metadata?.state)
        assertEquals(
            TransitionReason.STUDY_DURATION_ELAPSED,
            fixture.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(inspectionsBeforeDeadline, fixture.access.inspectCount)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.collector.stopCount)
    }

    @Test
    fun deletionTombstoneBlocksUploadAndImportUntilCleanupCanFinish() = runTest {
        val uploader = FakeUploader()
        val fixture = fixture(
            configuration(upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false)),
            uploader = uploader,
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        manager.finish()
        uploader.clearFailure = IllegalStateException("outbox unavailable")

        val failure = runCatching { manager.deleteLocalData() }.exceptionOrNull()

        assertEquals("outbox unavailable", failure?.message)
        assertTrue(fixture.active.record is ActiveStudyRecord.DeletionPending)
        assertTrue(manager.snapshot.value.deletionPending)
        assertTrue(uploader.deletionPrepared)
        assertEquals(UploadAttemptResult.NoWork, manager.uploadPending())
        assertTrue(runCatching { manager.importSignedConfiguration(byteArrayOf(2)) }.isFailure)

        uploader.clearFailure = null
        manager.deleteLocalData()
        assertNull(fixture.active.record)
        assertNull(manager.snapshot.value.configuration)
    }

    @Test
    fun initializationCompletesADeletionTombstoneWithoutReactivatingTheStudy() = runTest {
        val deletion = ActiveStudyRecord.DeletionPending("session-test", 16_777_216)
        val fixture = fixture(configuration(), activeRecord = deletion)

        fixture.manager.initialize()

        assertNull(fixture.active.record)
        assertTrue(fixture.store.cleared)
        assertTrue((fixture.uploader as FakeUploader).cleared)
        assertEquals(0, fixture.host.startCount)
        assertNull(fixture.manager.snapshot.value.configuration)
        assertTrue(fixture.manager.snapshot.value.initialized)
    }

    @Test
    fun joinImportBindsTheExactArtifactAndSignerBeforePersistingIt() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val configuration = configuration()
        val valid = JoinLink(
            URI("https://artifacts.example.invalid/opaque-token"),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            configuration.signer.fingerprint.replace(" ", ""),
        )
        val accepted = fixture(configuration)
        accepted.manager.initialize()

        accepted.manager.importSignedConfiguration(bytes, valid)

        assertTrue((accepted.active.record as ActiveStudyRecord.Active).envelopeBytes.contentEquals(bytes))

        listOf(
            valid.copy(artifactSha256 = "f".repeat(64)),
            valid.copy(signerFingerprint = "F".repeat(32)),
        ).forEach { hostile ->
            val rejected = fixture(configuration)
            rejected.manager.initialize()
            assertTrue(runCatching {
                rejected.manager.importSignedConfiguration(bytes, hostile)
            }.isFailure)
            assertNull(rejected.active.record)
            assertNull(rejected.manager.snapshot.value.configuration)
        }
    }

    @Test
    fun everyDeletionCleanupStepIsAttemptedAndAnyFailureRetainsTheTombstone() = runTest {
        listOf("host", "work", "uploader", "store", "active").forEach { failingStep ->
            val uploader = FakeUploader()
            val fixture = fixture(configuration(), uploader = uploader)
            fixture.manager.initialize()
            fixture.manager.importSignedConfiguration(byteArrayOf(1))
            fixture.manager.reviewStudy()
            fixture.manager.acceptConsent()
            fixture.manager.completeAccessSetup()
            fixture.manager.start()
            fixture.manager.finish()
            when (failingStep) {
                "host" -> fixture.host.stopFailure = IllegalStateException(failingStep)
                "work" -> fixture.work.cancelFailure = IllegalStateException(failingStep)
                "uploader" -> uploader.clearFailure = IllegalStateException(failingStep)
                "store" -> fixture.store.clearFailure = IllegalStateException(failingStep)
                "active" -> fixture.active.clearFailure = IllegalStateException(failingStep)
            }

            assertTrue(runCatching { fixture.manager.deleteLocalData() }.isFailure)
            assertTrue(fixture.active.record is ActiveStudyRecord.DeletionPending)
            assertTrue(fixture.host.stopCount >= 2)
            assertTrue(fixture.work.cancelCount >= 1)
            assertTrue(uploader.clearAttempts >= 1)
            assertTrue(fixture.store.clearAttempts >= 1)
        }
    }

    @Test
    fun everyStudyRequiresNotificationAccessWithoutInterventions() = runTest {
        val fixture = fixture(configuration(), grantedAccess = emptySet())
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()

        val notifications = fixture.manager.snapshot.value.access
            .single { it.requirement.kind == AccessKind.NOTIFICATIONS }
        assertTrue(notifications.requirement.required)
        assertFalse(notifications.granted)
        assertEquals(
            setOf(NotificationAccessFeature.COLLECTION, NotificationAccessFeature.DAILY_STATUS),
            fixture.access.lastRequest?.notificationFeatures,
        )
        assertEquals(
            CommandResult.Failed("REQUIRED_ACCESS_MISSING"),
            fixture.manager.completeAccessSetup(),
        )
        runCurrent()
        assertEquals(ExperimentState.ACCESS_SETUP, fixture.manager.snapshot.value.runtime.metadata?.state)

        fixture.access.granted += AccessKind.NOTIFICATIONS
        assertEquals(CommandResult.Success, fixture.manager.completeAccessSetup())
        runCurrent()
        assertEquals(ExperimentState.READY, fixture.manager.snapshot.value.runtime.metadata?.state)
    }

    @Test
    fun interventionStudyAddsOnlyTheAppOwnedInterventionNotificationFeature() = runTest {
        val intervention = InterventionConfiguration(
            id = "notice-one",
            action = NotificationAction("Study check-in", "Check in"),
            triggers = listOf(
                InterventionTrigger(
                    "after-minute",
                    OneTimeSchedule(1, RelativeClock.CALENDAR_TIME),
                    availabilityMinutes = 60,
                ),
            ),
        )
        val fixture = fixture(configuration(interventions = listOf(intervention)))
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))

        assertEquals(
            NotificationAccessFeature.entries.toSet(),
            fixture.access.lastRequest?.notificationFeatures,
        )
    }

    @Test
    fun incompletePlatformAccessInspectionRejectsStudyActivation() = runTest {
        val fixture = fixture(
            configuration(),
            omittedAccess = setOf(AccessKind.NOTIFICATIONS),
        )
        fixture.manager.initialize()

        val failure = runCatching {
            fixture.manager.importSignedConfiguration(byteArrayOf(1))
        }.exceptionOrNull()

        assertEquals(
            "Access inspection must return every planned kind and no others",
            failure?.message,
        )
        assertNull(fixture.active.record)
        assertNull(fixture.manager.snapshot.value.configuration)
    }

    @Test
    fun startAndResumeRecheckRequiredNotificationAccess() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        assertEquals(CommandResult.Success, manager.completeAccessSetup())
        runCurrent()

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        assertEquals(CommandResult.Failed("REQUIRED_ACCESS_MISSING"), manager.start())
        assertEquals(ExperimentState.READY, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(0, fixture.host.startCount)

        fixture.access.granted += AccessKind.NOTIFICATIONS
        val inspectionsBeforeStart = fixture.access.inspectCount
        assertEquals(CommandResult.Success, manager.start())
        assertEquals(inspectionsBeforeStart + 1, fixture.access.inspectCount)
        assertEquals(CommandResult.Success, manager.pause())
        runCurrent()

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        val inspectionsBeforeResume = fixture.access.inspectCount
        assertEquals(CommandResult.Failed("REQUIRED_ACCESS_MISSING"), manager.resume())
        assertEquals(inspectionsBeforeResume + 1, fixture.access.inspectCount)
        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.host.startCount)
    }

    @Test
    fun startFailsClosedWhenDurableRetryInspectionFails() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        runCurrent()
        fixture.work.safetyPauseInspectionFailure = IllegalStateException("work database unavailable")

        assertEquals(CommandResult.Failed("SAFETY_PAUSE_PENDING"), manager.start())

        assertEquals(ExperimentState.READY, manager.snapshot.value.runtime.metadata?.state)
        assertEquals("SAFETY_PAUSE_RETRY_INSPECTION_FAILED", manager.snapshot.value.incidentCode)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
    }

    @Test
    fun foregroundHostMustAcknowledgeBeforeRuntimeBecomesRunning() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        fixture.host.startFailure = IllegalStateException("platform rejected foreground start")

        assertEquals(CommandResult.Failed("COLLECTION_HOST_FAILED"), manager.start())
        runCurrent()

        assertEquals(ExperimentState.READY, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(0, fixture.collector.startCount)
        assertFalse(fixture.lifecycleEvents.contains("collector:start"))
    }

    @Test
    fun losingRequiredAccessWhileRunningPausesCollectionAndInterventions() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        manager.reconcileAccess()
        runCurrent()

        val metadata = manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.REQUIRED_ACCESS_MISSING, metadata?.transitions?.last()?.reason)
        assertEquals("REQUIRED_ACCESS_MISSING", manager.snapshot.value.incidentCode)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.work.cancelInterventionCount)
        assertEquals(CollectorStatus.PAUSED, manager.snapshot.value.runtime.collectorHealth
            .getValue(AppLifecycleConfiguration.ID).status)
    }

    @Test
    fun resumeWaitsForDurableRetryRetirementBeforeReopeningCollection() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        manager.reconcileAccess()
        runCurrent()
        fixture.access.granted += AccessKind.NOTIFICATIONS
        fixture.work.activeSafetyPauseReasons += SafetyPauseReason.REQUIRED_ACCESS_MISSING
        fixture.work.safetyPauseCancellationGate = CompletableDeferred()
        val cancellationsBeforeResume = fixture.work.safetyPauseCancelCount
        val hostStartsBeforeResume = fixture.host.startCount

        val resume = async { manager.resume() }
        runCurrent()

        assertFalse(resume.isCompleted)
        assertEquals(hostStartsBeforeResume, fixture.host.startCount)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
            manager.snapshot.value.safetyPauseStatus,
        )

        fixture.work.safetyPauseCancellationGate?.complete(Unit)
        runCurrent()

        assertEquals(CommandResult.Success, resume.await())
        assertTrue(fixture.work.activeSafetyPauseReasons.isEmpty())
        assertEquals(cancellationsBeforeResume + 1, fixture.work.safetyPauseCancelCount)
        assertEquals(hostStartsBeforeResume + 1, fixture.host.startCount)
        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.collector.resumeCount)
    }

    @Test
    fun retryCancellationFailureKeepsSafetyPausePendingAndReenqueuesWitness() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.work.safetyPauseCancelFailure = IllegalStateException("work cancellation failed")

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
            manager.snapshot.value.safetyPauseStatus,
        )
        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, fixture.work.scheduledSafetyPauseReason)
        assertEquals(1, fixture.work.safetyPauseScheduleCount)
    }

    @Test
    fun accessLossClosesAdmissionAndStopsTheHostWhenPausePersistenceFails() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.store.saveFailure = IllegalStateException("metadata unavailable")

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        manager.reconcileAccess()
        runCurrent()

        // Durable metadata still says RUNNING because that write failed, but every live collection
        // surface must already be closed. Recovery will retry the transition before activation.
        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertEquals("PAUSE_PERSISTENCE_FAILED", manager.snapshot.value.incidentCode)
        assertEquals("PAUSE_PERSISTENCE_FAILED", manager.snapshot.value.runtime.incidentCode)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.work.cancelInterventionCount)
        assertEquals(1, fixture.collector.pauseCount)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, fixture.pauseStore.pendingReason)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
            manager.snapshot.value.safetyPauseStatus,
        )
        assertEquals(1, fixture.work.safetyPauseScheduleCount)

        fixture.store.saveFailure = null
        fixture.access.granted += AccessKind.NOTIFICATIONS
        assertTrue(
            manager.retrySafetyPause("session-test", SafetyPauseReason.REQUIRED_ACCESS_MISSING),
        )
        runCurrent()
        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(TransitionReason.REQUIRED_ACCESS_MISSING, manager.snapshot.value.runtime.metadata
            ?.transitions?.last()?.reason)
        assertEquals("REQUIRED_ACCESS_MISSING", manager.snapshot.value.incidentCode)
        assertNull(fixture.pauseStore.pendingReason)
        assertNull(manager.snapshot.value.safetyPauseStatus)
        assertEquals(0, fixture.collector.resumeCount)
    }

    @Test
    fun durablePauseMarkerForcesPausedRecoveryEvenAfterAccessReturns() = runTest {
        val configuration = configuration()
        val pauseStore = FakeSafetyPauseStore(
            pendingReason = SafetyPauseReason.REQUIRED_ACCESS_MISSING,
        )
        val running = startedMetadata(configuration)
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = running,
            pauseStore = pauseStore,
        )

        fixture.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            TransitionReason.REQUIRED_ACCESS_MISSING,
            fixture.manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertNull(pauseStore.pendingReason)
        assertNull(fixture.manager.snapshot.value.safetyPauseStatus)
    }

    @Test
    fun safetyPauseRetryCompletesCollectorTeardownFailure() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.collector.failNextPause = true

        fixture.access.granted -= AccessKind.NOTIFICATIONS
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals("COLLECTOR_PAUSE_FAILED", manager.snapshot.value.incidentCode)
        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, fixture.pauseStore.pendingReason)
        assertEquals(1, fixture.work.safetyPauseScheduleCount)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)

        assertTrue(
            manager.retrySafetyPause("session-test", SafetyPauseReason.REQUIRED_ACCESS_MISSING),
        )
        runCurrent()
        assertEquals(2, fixture.collector.pauseCount)
        assertNull(fixture.pauseStore.pendingReason)
        assertEquals("REQUIRED_ACCESS_MISSING", manager.snapshot.value.incidentCode)
    }

    @Test
    fun participantPauseCollectorFailureCompletesTypedTeardownProtocolBeforeReturning() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.collector.failNextPause = true

        assertEquals(
            CommandResult.Failed("COLLECTION_TEARDOWN_FAILED"),
            manager.pause(),
        )
        runCurrent()

        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_TEARDOWN_FAILURE,
            manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        assertEquals("COLLECTION_TEARDOWN_FAILED", manager.snapshot.value.incidentCode)
        assertNull(manager.snapshot.value.runtime.pendingSafetyPauseReason)
        assertNull(fixture.pauseStore.pendingReason)
        assertEquals(2, fixture.collector.pauseCount)
        assertEquals(1, fixture.host.stopCount)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun typedWorkerReasonSurvivesMarkerAndMetadataWriteFailures() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val location = locationConfiguration(required = false)
        val lifecycle = AppLifecycleConfiguration(required = true)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.pauseStore.markFailure = IllegalStateException("marker unavailable")
        fixture.store.saveFailure = IllegalStateException("metadata unavailable")
        fixture.host.locationStartFailure = IllegalStateException("location type rejected")
        fixture.host.nonLocationStartFailure = IllegalStateException("fallback rejected")

        fixture.access.granted += locationAccess
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertNull(fixture.pauseStore.pendingReason)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.COLLECTION_HOST_FAILURE),
            manager.snapshot.value.safetyPauseStatus,
        )
        assertEquals(SafetyPauseReason.COLLECTION_HOST_FAILURE, fixture.work.scheduledSafetyPauseReason)
        fixture.collectors.values.forEach { collector ->
            assertTrue(runCatching { collector.emit(1) }.isFailure)
        }

        fixture.store.saveFailure = null
        assertTrue(
            manager.retrySafetyPause("session-test", SafetyPauseReason.COLLECTION_HOST_FAILURE),
        )
        runCurrent()

        val metadata = manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.COLLECTION_HOST_FAILURE, metadata?.transitions?.last()?.reason)
        assertNull(manager.snapshot.value.safetyPauseStatus)
        assertEquals("COLLECTION_HOST_FAILED", manager.snapshot.value.incidentCode)
    }

    @Test
    fun storageFailureUsesTypedWorkerWhenMarkerAndPauseMetadataWritesFailAndRecoversClosed() = runTest {
        val configuration = configuration()
        val running = fixture(configuration)
        running.manager.initialize()
        running.manager.importSignedConfiguration(byteArrayOf(1))
        running.manager.reviewStudy()
        running.manager.acceptConsent()
        running.manager.completeAccessSetup()
        running.manager.start()
        runCurrent()
        running.store.appendFailure = IllegalStateException("event storage unavailable")
        running.store.saveFailure = IllegalStateException("pause metadata unavailable")
        running.pauseStore.markFailure = IllegalStateException("marker unavailable")

        running.collector.emit(1)
        runCurrent()

        val failed = running.manager.snapshot.value
        assertEquals(ExperimentState.RUNNING, failed.runtime.metadata?.state)
        assertEquals("PAUSE_PERSISTENCE_FAILED", failed.incidentCode)
        assertNull(failed.runtime.pendingSafetyPauseReason)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.STORAGE_FAILURE),
            failed.safetyPauseStatus,
        )
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, running.work.scheduledSafetyPauseReason)
        assertTrue(running.work.safetyPauseScheduleCount >= 1)
        assertEquals(1, running.host.stopCount)
        assertEquals(1, running.collector.pauseCount)
        assertTrue(runCatching { running.collector.emit(1) }.isFailure)

        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = startedMetadata(configuration),
        )
        recovered.work.activeSafetyPauseReasons += SafetyPauseReason.STORAGE_FAILURE

        recovered.manager.initialize()
        runCurrent()

        val recoveredMetadata = recovered.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, recoveredMetadata?.state)
        assertEquals(TransitionReason.STORAGE_FAILURE, recoveredMetadata?.transitions?.last()?.reason)
        assertEquals("STORAGE_WRITE_FAILED", recovered.manager.snapshot.value.incidentCode)
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
        assertNull(recovered.manager.snapshot.value.runtime.pendingSafetyPauseReason)
        assertTrue(recovered.work.activeSafetyPauseReasons.isEmpty())
    }

    @Test
    fun storageFailureSignalRemainsUntilTypedRetryPersistenceIsConfirmed() = runTest {
        val fixture = fixture(configuration())
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.manager.completeAccessSetup()
        fixture.manager.start()
        runCurrent()
        fixture.store.appendFailure = IllegalStateException("event storage unavailable")
        fixture.store.saveFailure = IllegalStateException("pause metadata unavailable")
        fixture.pauseStore.markFailure = IllegalStateException("marker unavailable")
        fixture.work.safetyPauseScheduleFailure = IllegalStateException("work database unavailable")

        fixture.collector.emit(1)
        runCurrent()

        assertEquals(
            SafetyPauseReason.STORAGE_FAILURE,
            fixture.manager.snapshot.value.runtime.pendingSafetyPauseReason,
        )
        assertEquals("SAFETY_PAUSE_RETRY_SCHEDULING_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertTrue(fixture.work.activeSafetyPauseReasons.isEmpty())
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun occurrencePlanningStorageFailurePausesWithStorageReasonAndCannotRestartAfterProcessDeath() = runTest {
        val intervention = InterventionConfiguration(
            id = "notice-one",
            action = NotificationAction("Study check-in", "Check in"),
            triggers = listOf(
                InterventionTrigger(
                    "after-minute",
                    OneTimeSchedule(1, RelativeClock.CALENDAR_TIME),
                    availabilityMinutes = 60,
                ),
            ),
        )
        val configuration = configuration(interventions = listOf(intervention))
        val running = fixture(configuration)
        running.manager.initialize()
        running.manager.importSignedConfiguration(byteArrayOf(1))
        running.manager.reviewStudy()
        running.manager.acceptConsent()
        running.manager.completeAccessSetup()
        running.store.appendFailure = IllegalStateException("occurrence storage unavailable")

        assertEquals(CommandResult.Failed("STORAGE_WRITE_FAILED"), running.manager.start())
        runCurrent()

        val paused = running.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, paused?.state)
        assertEquals(TransitionReason.STORAGE_FAILURE, paused?.transitions?.last()?.reason)
        assertEquals(1, running.host.stopCount)
        assertEquals(1, running.collector.pauseCount)
        assertNull(running.manager.snapshot.value.runtime.pendingSafetyPauseReason)

        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = paused,
        )
        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, recovered.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            TransitionReason.STORAGE_FAILURE,
            recovered.manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
    }

    @Test
    fun safetyWorkerOnAnAlreadyPausedStudyPreservesTheParticipantAuditBoundary() = runTest {
        val configuration = configuration()
        val participantPause = ExperimentTransition(
            from = ExperimentState.RUNNING,
            to = ExperimentState.PAUSED,
            reason = TransitionReason.PARTICIPANT_PAUSED,
            time = ResearchTime(100, 100, "boot-before-crash"),
        )
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = StudyMetadata.initial(
                configuration.experimentId,
                configuration.configurationId,
            ).copy(
                state = ExperimentState.PAUSED,
                transitions = listOf(
                    ExperimentTransition(
                        from = ExperimentState.READY,
                        to = ExperimentState.RUNNING,
                        reason = TransitionReason.PARTICIPANT_STARTED,
                        time = ResearchTime(99, 99, "boot-before-crash"),
                    ),
                    participantPause,
                ),
            ),
        )
        fixture.work.activeSafetyPauseReasons += SafetyPauseReason.STORAGE_FAILURE

        fixture.manager.initialize()
        runCurrent()

        val metadata = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.PARTICIPANT_PAUSED, metadata?.transitions?.last()?.reason)
        assertEquals(participantPause.time, metadata?.transitions?.last()?.time)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertTrue(fixture.work.activeSafetyPauseReasons.isEmpty())
    }

    @Test
    fun typedWorkerOnReadyMetadataRewritesTheSafeBoundaryBeforeClearingItsWitness() = runTest {
        val configuration = configuration()
        val prepared = fixture(configuration)
        prepared.manager.initialize()
        prepared.manager.importSignedConfiguration(byteArrayOf(1))
        prepared.manager.reviewStudy()
        prepared.manager.acceptConsent()
        prepared.manager.completeAccessSetup()
        val ready = requireNotNull(prepared.store.metadata)
        assertEquals(ExperimentState.READY, ready.state)

        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = ready,
        )
        recovered.work.activeSafetyPauseReasons += SafetyPauseReason.WORK_SCHEDULING_FAILURE

        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.READY, recovered.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, recovered.store.saveCount)
        assertNull(recovered.manager.snapshot.value.runtime.pendingSafetyPauseReason)
        assertTrue(recovered.work.activeSafetyPauseReasons.isEmpty())
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
    }

    @Test
    fun runningCommandMetadataFailuresAllCreateTypedStorageSafetyWitnesses() = runTest {
        val commands = listOf<Pair<String, suspend (Fixture) -> CommandResult>>(
            "pause" to { it.manager.pause() },
            "finish" to { it.manager.finish() },
            "duration" to { it.completeDurationCommand() },
            "withdraw" to { it.manager.withdraw() },
        )
        commands.forEach { (name, command) ->
            val fixture = fixture(configuration())
            fixture.manager.initialize()
            fixture.manager.importSignedConfiguration(byteArrayOf(1))
            fixture.manager.reviewStudy()
            fixture.manager.acceptConsent()
            fixture.manager.completeAccessSetup()
            fixture.manager.start()
            runCurrent()
            fixture.store.saveFailure = IllegalStateException("$name metadata unavailable")

            assertEquals(CommandResult.Failed("STORAGE_WRITE_FAILED"), command(fixture))
            runCurrent()

            assertEquals(ExperimentState.RUNNING, fixture.manager.snapshot.value.runtime.metadata?.state)
            assertEquals(
                SafetyPauseStatus.Pending(SafetyPauseReason.STORAGE_FAILURE),
                fixture.manager.snapshot.value.safetyPauseStatus,
            )
            assertEquals(SafetyPauseReason.STORAGE_FAILURE, fixture.work.scheduledSafetyPauseReason)
            assertEquals(1, fixture.host.stopCount)
            assertEquals(1, fixture.collector.startCount)
            assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
        }
    }

    @Test
    fun resumeSideEffectFailureReturnsOnlyAfterTypedStoragePauseIsDurable() = runTest {
        val fixture = fixture(configuration())
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.manager.completeAccessSetup()
        fixture.manager.start()
        fixture.manager.pause()
        fixture.work.replacementFailure = IllegalStateException("work database unavailable")

        assertEquals(CommandResult.Failed("WORK_SCHEDULING_FAILED"), fixture.manager.resume())
        runCurrent()

        val metadata = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.WORK_SCHEDULING_FAILURE, metadata?.transitions?.last()?.reason)
        assertEquals(1, fixture.collector.resumeCount)
        assertEquals(2, fixture.collector.pauseCount)
        assertEquals(2, fixture.host.stopCount)
        assertNull(fixture.manager.snapshot.value.runtime.pendingSafetyPauseReason)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun recoveryMarkerFailureFallsBackToTypedWorkBeforeAccessCanLaterReopen() = runTest {
        val configuration = configuration()
        val runningMetadata = startedMetadata(configuration)
        val first = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = runningMetadata,
            grantedAccess = emptySet(),
        )
        first.pauseStore.markFailure = IllegalStateException("marker unavailable")
        first.store.saveFailure = IllegalStateException("pause metadata unavailable")

        first.manager.initialize()
        runCurrent()

        assertTrue(first.manager.snapshot.value.recoveryBlocked)
        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, first.work.scheduledSafetyPauseReason)
        assertEquals(0, first.host.startCount)
        assertEquals(0, first.collector.startCount)

        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = runningMetadata,
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
        )
        recovered.work.activeSafetyPauseReasons += SafetyPauseReason.REQUIRED_ACCESS_MISSING

        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, recovered.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            TransitionReason.REQUIRED_ACCESS_MISSING,
            recovered.manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
    }

    @Test
    fun activeTypedWorkerAloneForcesFailClosedProcessDeathRecovery() = runTest {
        val configuration = configuration()
        val running = startedMetadata(configuration)
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = running,
        )
        fixture.work.activeSafetyPauseReasons += SafetyPauseReason.COLLECTION_HOST_FAILURE

        fixture.manager.initialize()
        runCurrent()

        val metadata = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.COLLECTION_HOST_FAILURE, metadata?.transitions?.last()?.reason)
        assertEquals("COLLECTION_HOST_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertNull(fixture.manager.snapshot.value.safetyPauseStatus)
        assertTrue(fixture.work.activeSafetyPauseReasons.isEmpty())
    }

    @Test
    fun conflictingMarkerAndWorkerReasonsKeepRecoveryClosed() = runTest {
        val configuration = configuration()
        val running = startedMetadata(configuration)
        val pauseStore = FakeSafetyPauseStore(SafetyPauseReason.REQUIRED_ACCESS_MISSING)
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = running,
            pauseStore = pauseStore,
        )
        fixture.work.activeSafetyPauseReasons += SafetyPauseReason.COLLECTION_HOST_FAILURE

        fixture.manager.initialize()
        runCurrent()

        assertNull(fixture.manager.snapshot.value.configuration)
        assertEquals("STUDY_RECOVERY_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertEquals(
            SafetyPauseStatus.Pending(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
            fixture.manager.snapshot.value.safetyPauseStatus,
        )
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
    }

    @Test
    fun multipleActiveWorkerReasonsKeepRecoveryClosed() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = startedMetadata(configuration),
        )
        fixture.work.activeSafetyPauseReasons += SafetyPauseReason.entries

        fixture.manager.initialize()
        runCurrent()

        val blocked = fixture.manager.snapshot.value
        assertNull(blocked.configuration)
        assertTrue(blocked.recoveryBlocked)
        assertEquals("STUDY_RECOVERY_FAILED", blocked.incidentCode)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)

        val importFailure = runCatching {
            fixture.manager.importSignedConfiguration(byteArrayOf(2))
        }.exceptionOrNull()

        assertTrue(importFailure is IllegalStateException)
        assertTrue(fixture.manager.snapshot.value.recoveryBlocked)
        assertEquals("STUDY_RECOVERY_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertNull(fixture.manager.snapshot.value.configuration)
    }

    @Test
    fun unreadableTypedMarkerKeepsRecoveryClosed() = runTest {
        val configuration = configuration()
        val pauseStore = FakeSafetyPauseStore().also {
            it.readFailure = IllegalStateException("unknown marker reason")
        }
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = startedMetadata(configuration),
            pauseStore = pauseStore,
        )

        fixture.manager.initialize()
        runCurrent()

        assertNull(fixture.manager.snapshot.value.configuration)
        assertEquals("STUDY_RECOVERY_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertEquals(SafetyPauseStatus.MarkerUnreadable, fixture.manager.snapshot.value.safetyPauseStatus)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
    }

    @Test
    fun optionalCollectorAccessIsPausedAndResumedWithoutStoppingTheStudy() = runTest {
        val accessKind = AccessKind.ACCELEROMETER_HARDWARE
        val fixture = fixture(
            configuration(collectors = listOf(AppLifecycleConfiguration(required = false))),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS, accessKind),
            collectorAccessKinds = setOf(accessKind),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()

        fixture.access.granted -= accessKind
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(0, fixture.host.stopCount)
        assertEquals(1, fixture.collector.pauseCount)
        assertEquals(
            CollectorStatus.BLOCKED_ACCESS,
            manager.snapshot.value.runtime.collectorHealth.getValue(AppLifecycleConfiguration.ID).status,
        )

        fixture.access.granted += accessKind
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.collector.resumeCount)
        assertEquals(
            CollectorStatus.ACTIVE,
            manager.snapshot.value.runtime.collectorHealth.getValue(AppLifecycleConfiguration.ID).status,
        )
    }

    @Test
    fun optionalLocationPromotesForegroundServiceBeforeStartingCollector() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val location = LocationConfiguration(
            required = false,
            intervalMillis = 60_000,
            minimumIntervalMillis = 30_000,
            maximumBatchDelayMillis = 0,
            minimumDisplacementMillimeters = 0,
            priority = LocationPriority.BALANCED,
        )
        val fixture = fixture(
            configuration = configuration(collectors = listOf(location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
            collectorAccessKinds = locationAccess,
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()

        assertEquals(listOf(false), fixture.host.locationStarts)
        assertEquals(0, fixture.collector.startCount)

        fixture.access.granted += locationAccess
        manager.reconcileAccess()
        runCurrent()

        assertEquals(listOf(false, true), fixture.host.locationStarts)
        assertEquals(1, fixture.collector.startCount)
        assertTrue(
            fixture.lifecycleEvents.indexOf("host:true") <
                fixture.lifecycleEvents.indexOf("collector:start"),
        )
    }

    @Test
    fun failedLocationPromotionWithAcknowledgedFallbackKeepsNonLocationCollectionRunning() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val location = locationConfiguration(required = false)
        val lifecycle = AppLifecycleConfiguration(required = true)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.host.locationStartFailure = IllegalStateException("location type rejected")

        fixture.access.granted += locationAccess
        manager.reconcileAccess()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, manager.snapshot.value.runtime.metadata?.state)
        assertEquals(listOf(false, true, false), fixture.host.locationStarts)
        assertEquals("COLLECTION_HOST_FAILED", manager.snapshot.value.incidentCode)
        assertNull(manager.snapshot.value.safetyPauseStatus)
        fixture.collectors.getValue(lifecycle.id).emit(1)
        runCurrent()
        assertEquals(1L, manager.snapshot.value.runtime.metadata?.eventCount)
        assertTrue(runCatching { fixture.collectors.getValue(location.id).emit(1) }.isFailure)
    }

    @Test
    fun failedLocationPromotionAndFallbackDurablyPauseAndCloseEveryCollector() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val location = locationConfiguration(required = false)
        val lifecycle = AppLifecycleConfiguration(required = true)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.host.locationStartFailure = IllegalStateException("location type rejected")
        fixture.host.nonLocationStartFailure = IllegalStateException("fallback rejected")

        fixture.access.granted += locationAccess
        manager.reconcileAccess()
        runCurrent()

        val metadata = manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.COLLECTION_HOST_FAILURE, metadata?.transitions?.last()?.reason)
        assertEquals("COLLECTION_HOST_FAILED", manager.snapshot.value.incidentCode)
        assertEquals(listOf(false, true, false), fixture.host.locationStarts)
        assertEquals(1, fixture.host.stopCount)
        assertNull(manager.snapshot.value.safetyPauseStatus)
        fixture.collectors.values.forEach { collector ->
            assertTrue(runCatching { collector.emit(1) }.isFailure)
        }
        assertEquals(0L, metadata?.eventCount)
    }

    @Test
    fun failedLocationDemotionDurablyPausesAndClosesEveryCollector() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val location = locationConfiguration(required = false)
        val lifecycle = AppLifecycleConfiguration(required = true)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS) + locationAccess,
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.host.nonLocationStartFailure = IllegalStateException("demotion rejected")

        fixture.access.granted -= locationAccess
        manager.reconcileAccess()
        runCurrent()

        val metadata = manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.COLLECTION_HOST_FAILURE, metadata?.transitions?.last()?.reason)
        assertEquals("COLLECTION_HOST_FAILED", manager.snapshot.value.incidentCode)
        assertEquals(listOf(true, false), fixture.host.locationStarts)
        assertEquals(1, fixture.host.stopCount)
        fixture.collectors.values.forEach { collector ->
            assertTrue(runCatching { collector.emit(1) }.isFailure)
        }
        assertEquals(0L, metadata?.eventCount)
    }

    @Test
    fun accessInspectionFailurePausesARunningStudyBeforePropagatingTheFailure() = runTest {
        val fixture = fixture(configuration())
        val manager = fixture.manager
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        manager.reviewStudy()
        manager.acceptConsent()
        manager.completeAccessSetup()
        manager.start()
        runCurrent()
        fixture.access.failure = IllegalStateException("platform inspection failed")

        val failure = runCatching { manager.reconcileAccess() }.exceptionOrNull()
        runCurrent()

        assertEquals("platform inspection failed", failure?.message)
        assertEquals(ExperimentState.PAUSED, manager.snapshot.value.runtime.metadata?.state)
        assertEquals("ACCESS_INSPECTION_FAILED", manager.snapshot.value.incidentCode)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.collector.pauseCount)
    }

    @Test
    fun recoveryOfRunningStudyRestartsCollectionHostOnce() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = startedMetadata(configuration),
        )

        fixture.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.RUNNING, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(1, fixture.host.startCount)
        assertEquals(1, fixture.collector.startCount)
        assertTrue(
            fixture.lifecycleEvents.indexOf("host:false") <
                fixture.lifecycleEvents.indexOf("collector:start"),
        )
        assertTrue(fixture.manager.reconcileRedeliveredCollectionHost())
        assertEquals(2, fixture.host.startCount)
    }

    @Test
    fun rebootRecoveryCannotExtendTheAbsoluteDeadlineAndNeverReopensCollection() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = startedMetadata(configuration),
            // Wall time still follows the participant start. It is nevertheless untrusted because
            // this boot has no monotonic relationship with the boot that established the start.
            recoveredBootSessionId = "boot-after-recovery",
        )

        fixture.manager.initialize()
        runCurrent()

        val recovered = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, recovered?.state)
        assertEquals(TransitionReason.WORK_SCHEDULING_FAILURE, recovered?.transitions?.last()?.reason)
        assertEquals("WORK_SCHEDULING_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertEquals(0, fixture.work.scheduleCount)

        assertEquals(
            CommandResult.Failed("WORK_SCHEDULING_FAILED"),
            fixture.manager.resume(),
        )
        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.resumeCount)
    }

    @Test
    fun rebootRecoveryOfParticipantPausedStudyPreservesPauseAndRejectsResume() = runTest {
        val configuration = configuration()
        val participantPaused = startedMetadata(
            configuration = configuration,
            state = ExperimentState.PAUSED,
        )
        val participantPause = participantPaused.transitions.last()
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = participantPaused,
            recoveredBootSessionId = "boot-after-recovery",
        )

        fixture.manager.initialize()
        runCurrent()

        val recovered = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, recovered?.state)
        assertEquals(participantPaused.transitions, recovered?.transitions)
        assertEquals(TransitionReason.PARTICIPANT_PAUSED, recovered?.transitions?.last()?.reason)
        assertEquals(participantPause.time, recovered?.transitions?.last()?.time)
        assertEquals("WORK_SCHEDULING_FAILED", fixture.manager.snapshot.value.incidentCode)
        assertNull(fixture.manager.snapshot.value.runtime.pendingSafetyPauseReason)
        assertNull(fixture.manager.snapshot.value.safetyPauseStatus)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertEquals(0, fixture.collector.pauseCount)
        assertEquals(0, fixture.work.scheduleCount)

        assertEquals(
            CommandResult.Failed("WORK_SCHEDULING_FAILED"),
            fixture.manager.resume(),
        )
        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(participantPaused.transitions, fixture.manager.snapshot.value.runtime.metadata?.transitions)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.resumeCount)
        assertEquals(0, fixture.work.scheduleCount)
    }

    @Test
    fun recoveryWithMissingRequiredAccessPausesBeforeCollectionRestarts() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = startedMetadata(configuration),
            grantedAccess = emptySet(),
        )

        fixture.manager.initialize()
        runCurrent()

        val metadata = fixture.manager.snapshot.value.runtime.metadata
        assertEquals(ExperimentState.PAUSED, metadata?.state)
        assertEquals(TransitionReason.REQUIRED_ACCESS_MISSING, metadata?.transitions?.last()?.reason)
        assertEquals(0, fixture.host.startCount)
        assertEquals(0, fixture.collector.startCount)
        assertFalse(fixture.manager.reconcileRedeliveredCollectionHost())
    }

    @Test
    fun sameBootTimeOrTimezoneReconciliationRestoresPostedAndOpenedExpiryWork() = runTest {
        val notice = InterventionConfiguration(
            "notice-one",
            NotificationAction("Study check-in", "Check in"),
            listOf(InterventionTrigger("after-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )
        val survey = SurveyDefinition(
            "survey-one",
            LocalizedText("Survey"),
            LocalizedText("One question"),
            listOf(ShortTextQuestion("answer-one", LocalizedText("Answer"), false, 40)),
        )
        val surveyNotice = InterventionConfiguration(
            "survey-notice",
            SurveyAction("Study survey", "Answer now", survey.id),
            listOf(InterventionTrigger("survey-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )
        val configuration = configuration(interventions = listOf(notice, surveyNotice), surveys = listOf(survey))
        val posted = occurrence("a".repeat(64), OccurrenceState.NOTIFICATION_POSTED)
        val openedNotice = occurrence("b".repeat(64), OccurrenceState.OPENED).copy(
            openedAt = ResearchTime(200, 200, "boot-before-recovery"),
        )
        val openedSurvey = occurrence(
            "c".repeat(64),
            OccurrenceState.OPENED,
            interventionId = surveyNotice.id,
            triggerId = "survey-minute",
        ).copy(openedAt = ResearchTime(200, 200, "boot-before-recovery"))
        val posting = occurrence("d".repeat(64), OccurrenceState.POSTING)
        val expired = occurrence("e".repeat(64), OccurrenceState.EXPIRED)
        val metadata = startedMetadata(
            configuration,
            occurrences = listOf(posted, openedNotice, openedSurvey, posting, expired).associateBy { it.occurrenceId },
        )
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = metadata,
        )

        fixture.manager.initialize()
        fixture.manager.reconcileScheduledWork(recoverStalePosting = true)

        assertTrue(
            fixture.work.replacementExpiries.mapTo(mutableSetOf()) { it.occurrenceId }.containsAll(
                setOf(posted.occurrenceId, openedSurvey.occurrenceId, posting.occurrenceId),
            ),
        )
        assertEquals(
            setOf(openedNotice.occurrenceId, openedSurvey.occurrenceId, posting.occurrenceId, expired.occurrenceId),
            fixture.work.cancelledNotificationIds,
        )
        assertTrue(posted.occurrenceId !in fixture.work.cancelledNotificationIds)

        // These are the same atomic checks each restored InterventionExpiryWorker performs.
        assertEquals(OccurrenceExpiryResult.Expired, fixture.manager.expireOccurrenceIfDue(posted.occurrenceId))
        assertEquals(OccurrenceExpiryResult.Terminal, fixture.manager.expireOccurrenceIfDue(openedNotice.occurrenceId))
        assertEquals(OccurrenceExpiryResult.Expired, fixture.manager.expireOccurrenceIfDue(openedSurvey.occurrenceId))
        runCurrent()
        assertEquals(
            OccurrenceState.EXPIRED,
            fixture.manager.snapshot.value.runtime.metadata?.occurrences?.get(posted.occurrenceId)?.state,
        )
        assertEquals(
            OccurrenceState.OPENED,
            fixture.manager.snapshot.value.runtime.metadata?.occurrences?.get(openedNotice.occurrenceId)?.state,
        )
        assertEquals(
            OccurrenceState.EXPIRED,
            fixture.manager.snapshot.value.runtime.metadata?.occurrences?.get(openedSurvey.occurrenceId)?.state,
        )
    }

    @Test
    fun recoveryCancelsStaleNotificationBeforeAPlanningWriteCanFail() = runTest {
        val intervention = InterventionConfiguration(
            "notice-one",
            NotificationAction("Study check-in", "Check in"),
            listOf(InterventionTrigger("after-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )
        val configuration = configuration(interventions = listOf(intervention))
        val stalePosting = occurrence("9".repeat(64), OccurrenceState.POSTING)
        val start = ResearchTime(100, 100, "boot-before-recovery")
        val metadata = StudyMetadata.initial(configuration.experimentId, configuration.configurationId).copy(
            state = ExperimentState.RUNNING,
            transitions = listOf(
                ExperimentTransition(
                    ExperimentState.READY,
                    ExperimentState.RUNNING,
                    TransitionReason.PARTICIPANT_STARTED,
                    start,
                ),
            ),
            occurrences = mapOf(stalePosting.occurrenceId to stalePosting),
        )
        val fixture = fixture(configuration, activeEnvelope = byteArrayOf(9), initialMetadata = metadata)
        fixture.manager.initialize()
        fixture.store.appendFailure = IllegalStateException("storage unavailable")

        val failure = runCatching {
            fixture.manager.reconcileScheduledWork(recoverStalePosting = true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(setOf(stalePosting.occurrenceId), fixture.work.cancelledNotificationIds)
        assertTrue(fixture.work.replacementDeliveries.isEmpty())
    }

    @Test
    fun postedCommitSurvivesSuccessorSchedulingSafetyPauseAndRetryAfterResume() = runTest {
        val intervention = InterventionConfiguration(
            "notice-one",
            NotificationAction("Study check-in", "Check in"),
            listOf(
                InterventionTrigger(
                    "interval-trigger",
                    IntervalSchedule(0, 10, RelativeClock.CALENDAR_TIME),
                    60,
                ),
            ),
        )
        val fixture = fixture(configuration(interventions = listOf(intervention)))
        fixture.manager.initialize()
        fixture.manager.importSignedConfiguration(byteArrayOf(1))
        fixture.manager.reviewStudy()
        fixture.manager.acceptConsent()
        fixture.access.granted += AccessKind.NOTIFICATIONS
        fixture.manager.completeAccessSetup()
        fixture.manager.start()
        runCurrent()
        val first = requireNotNull(
            fixture.manager.snapshot.value.runtime.metadata?.occurrences?.values?.single(),
        )
        assertTrue(fixture.manager.claimOccurrenceIfDue(first.occurrenceId) is OccurrenceClaimResult.Due)
        assertTrue(fixture.manager.markNotificationPosted(first.occurrenceId))

        fixture.work.failEnqueue = true
        val failure = runCatching { fixture.manager.scheduleSuccessor(first.occurrenceId) }.exceptionOrNull()
        runCurrent()
        assertTrue(failure is IllegalStateException)
        assertEquals(
            OccurrenceState.NOTIFICATION_POSTED,
            fixture.manager.snapshot.value.runtime.metadata?.occurrences?.get(first.occurrenceId)?.state,
        )
        assertEquals(ExperimentState.PAUSED, fixture.manager.snapshot.value.runtime.metadata?.state)
        assertEquals(
            TransitionReason.WORK_SCHEDULING_FAILURE,
            fixture.manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        fixture.work.failEnqueue = false
        assertEquals(CommandResult.Success, fixture.manager.resume())
        val enqueuedBeforeRetry = fixture.work.enqueuedOccurrences.size
        fixture.manager.scheduleSuccessor(first.occurrenceId)
        assertEquals(enqueuedBeforeRetry + 1, fixture.work.enqueuedOccurrences.size)
        assertTrue(fixture.work.enqueuedOccurrences.last().occurrenceId != first.occurrenceId)
    }

    @Test
    fun pauseFinishAndWithdrawCancelVisibleOccurrenceNotifications() = runTest {
        val intervention = InterventionConfiguration(
            "notice-one",
            NotificationAction("Study check-in", "Check in"),
            listOf(InterventionTrigger("after-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )
        val configuration = configuration(interventions = listOf(intervention))
        suspend fun fixtureWithPostedOccurrence(): Fixture {
            val posted = occurrence("f".repeat(64), OccurrenceState.NOTIFICATION_POSTED)
            return fixture(
                configuration,
                activeEnvelope = byteArrayOf(9),
                initialMetadata = startedMetadata(
                    configuration,
                    occurrences = mapOf(posted.occurrenceId to posted),
                ),
            ).also { it.manager.initialize() }
        }

        val paused = fixtureWithPostedOccurrence()
        assertEquals(CommandResult.Success, paused.manager.pause())
        assertEquals(setOf("f".repeat(64)), paused.work.cancelledNotificationIds)
        assertEquals(1, paused.work.cancelInterventionCount)

        listOf<suspend (StudySessionManager) -> CommandResult>(
            { it.finish() },
            { it.withdraw() },
        ).forEach { terminate ->
            val fixture = fixtureWithPostedOccurrence()
            assertEquals(CommandResult.Success, terminate(fixture.manager))
            assertEquals(setOf("f".repeat(64)), fixture.work.cancelledNotificationIds)
            assertEquals(1, fixture.work.cancelCollectionCount)
        }
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
        assertEquals(
            TransitionReason.WORK_SCHEDULING_FAILURE,
            fixture.manager.snapshot.value.runtime.metadata?.transitions?.last()?.reason,
        )
        assertEquals(1, fixture.collector.pauseCount)
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.work.cancelInterventionCount)
        assertEquals(0, fixture.work.cancelCount)
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

        assertTrue(manager.uploadPending() is UploadAttemptResult.Confirmed)
        runCurrent()

        assertEquals(listOf(1L to 3L), uploader.ranges)
        assertEquals(1, uploader.acknowledged.size)
        assertEquals(3L, manager.snapshot.value.upload?.uploadedThroughSequence)
        assertEquals(0L, manager.snapshot.value.upload?.pendingCount)

        // A second call with nothing new must not re-send the same events.
        assertEquals(UploadAttemptResult.NoWork, manager.uploadPending())
        assertEquals(listOf(1L to 3L), uploader.ranges)

        // Only the events collected since the last confirmation go out next.
        fixture.collector.emit(2)
        runCurrent()
        assertTrue(manager.uploadPending() is UploadAttemptResult.Confirmed)
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

        assertEquals(UploadAttemptResult.Failed("UPLOAD_FAILED", retryable = false), manager.uploadPending())
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
        assertTrue(manager.uploadPending() is UploadAttemptResult.Confirmed)
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
        assertTrue(manager.uploadPending() is UploadAttemptResult.Confirmed)
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
        assertTrue(manager.uploadPending() is UploadAttemptResult.Confirmed)
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
        assertEquals(UploadAttemptResult.Failed("UPLOAD_FAILED", retryable = false), manager.uploadPending())
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
        assertEquals(UploadAttemptResult.NoWork, manager.uploadPending())

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

        assertEquals(UploadAttemptResult.NoWork, manager.uploadPending())

        assertTrue(uploader.ranges.isEmpty())
        assertNull(manager.snapshot.value.upload)
    }

    @Test
    fun startCollectorCancellationCompletesTypedContainmentBeforePropagating() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareReadyStudy()
        fixture.collector.startEntered = CompletableDeferred()
        fixture.collector.startGate = CompletableDeferred()

        val command = async { fixture.manager.start() }
        fixture.collector.startEntered?.await()
        command.cancel()
        runCurrent()

        val failure = runCatching { command.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_HOST_FAILURE,
            fixture.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(1, fixture.host.stopCount)
        assertEquals(1, fixture.collector.pauseCount)
        assertNull(fixture.pauseStore.pendingReason)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun resumeCollectorCancellationCompletesTypedContainmentBeforePropagating() = runTest {
        val fixture = fixture(configuration())
        fixture.prepareRunningStudy()
        assertEquals(CommandResult.Success, fixture.manager.pause())
        fixture.collector.resumeEntered = CompletableDeferred()
        fixture.collector.resumeGate = CompletableDeferred()

        val command = async { fixture.manager.resume() }
        fixture.collector.resumeEntered?.await()
        command.cancel()
        runCurrent()

        val failure = runCatching { command.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_HOST_FAILURE,
            fixture.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(1, fixture.collector.resumeCount)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun cancelledPostStartWorkAcknowledgementClosesAdmissionBeforeCleanupCanSuspend() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareReadyStudy()
        fixture.work.ensureGate = CompletableDeferred()
        fixture.work.cancelInterventionEntered = CompletableDeferred()
        fixture.work.cancelInterventionGate = CompletableDeferred()

        val command = async { fixture.manager.start() }
        runCurrent()
        assertEquals(1, fixture.collector.startCount)
        command.cancel()
        fixture.work.cancelInterventionEntered?.await()

        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertEquals(
            SafetyPauseReason.WORK_SCHEDULING_FAILURE,
            fixture.pauseStore.pendingReason,
        )

        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
            pauseStore = FakeSafetyPauseStore(fixture.pauseStore.pendingReason),
        )
        recovered.manager.initialize()
        runCurrent()
        assertEquals(ExperimentState.PAUSED, recovered.store.metadata?.state)
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)

        fixture.work.cancelInterventionGate?.complete(Unit)
        assertTrue(runCatching { command.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun recoveredCollectorCancellationLeavesDurablePauseForTheNextProcess() = runTest {
        val configuration = configuration()
        val fixture = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = startedMetadata(configuration),
        )
        fixture.collector.startEntered = CompletableDeferred()
        fixture.collector.startGate = CompletableDeferred()

        val initialization = async { fixture.manager.initialize() }
        fixture.collector.startEntered?.await()
        initialization.cancel()
        runCurrent()

        assertTrue(runCatching { initialization.await() }.exceptionOrNull() is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
        )
        recovered.manager.initialize()
        runCurrent()
        assertEquals(ExperimentState.PAUSED, recovered.store.metadata?.state)
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
    }

    @Test
    fun uncertainStartCommitIsAcknowledgedBackToReadyBeforeCancellationPropagates() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareReadyStudy()
        fixture.store.saveAfterCommitFailure = CancellationException("start acknowledgement lost")

        val failure = runCatching { fixture.manager.start() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(ExperimentState.READY, fixture.store.metadata?.state)
        assertNull(fixture.pauseStore.pendingReason)
        assertEquals(1, fixture.host.stopCount)
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
        )
        recovered.manager.initialize()
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
    }

    @Test
    fun uncertainResumeCommitIsAcknowledgedBackToPausedAfterFailure() = runTest {
        val fixture = fixture(configuration())
        fixture.prepareRunningStudy()
        assertEquals(CommandResult.Success, fixture.manager.pause())
        fixture.store.saveAfterCommitFailure = IllegalStateException("resume acknowledgement lost")

        assertEquals(CommandResult.Failed("STORAGE_WRITE_FAILED"), fixture.manager.resume())

        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertNull(fixture.pauseStore.pendingReason)
        assertEquals(0, fixture.collector.resumeCount)
    }

    @Test
    fun processDeathAfterTeardownWitnessButBeforeRuntimeNeverReopensRunning() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareRunningStudy()
        fixture.pauseStore.markEntered = CompletableDeferred()
        fixture.pauseStore.markReturnGate = CompletableDeferred()

        val pause = async { fixture.manager.pause() }
        fixture.pauseStore.markEntered?.await()
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
            pauseStore = FakeSafetyPauseStore(fixture.pauseStore.pendingReason),
        )
        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, recovered.store.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_TEARDOWN_FAILURE,
            recovered.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)

        fixture.pauseStore.markReturnGate?.complete(Unit)
        assertEquals(CommandResult.Success, pause.await())
    }

    @Test
    fun cancellationAfterPrearmAcknowledgementEntersTypedSafetyPauseBeforePropagation() = runTest {
        val commands = listOf<Pair<String, suspend (Fixture) -> CommandResult>>(
            "pause" to { it.manager.pause() },
            "finish" to { it.manager.finish() },
            "duration" to { it.completeDurationCommand() },
            "withdraw" to { it.manager.withdraw() },
        )
        commands.forEach { (name, command) ->
            val configuration = configuration()
            val fixture = fixture(configuration)
            fixture.prepareRunningStudy()
            fixture.pauseStore.markEntered = CompletableDeferred()
            fixture.pauseStore.markReturnGate = CompletableDeferred()

            val operation = async { command(fixture) }
            fixture.pauseStore.markEntered?.await()
            operation.cancel()
            fixture.pauseStore.markReturnGate?.complete(Unit)
            runCurrent()

            assertTrue(
                "$name cancellation must propagate",
                runCatching { operation.await() }.exceptionOrNull() is CancellationException,
            )
            assertEquals(name, ExperimentState.PAUSED, fixture.store.metadata?.state)
            assertEquals(
                name,
                TransitionReason.COLLECTION_TEARDOWN_FAILURE,
                fixture.store.metadata?.transitions?.last()?.reason,
            )
            assertNull(name, fixture.pauseStore.pendingReason)
            assertTrue(name, runCatching { fixture.collector.emit(1) }.isFailure)

            val recovered = fixture(
                configuration = configuration,
                activeEnvelope = byteArrayOf(1),
                initialMetadata = fixture.store.metadata,
            )
            recovered.manager.initialize()
            assertEquals(name, 0, recovered.host.startCount)
            assertEquals(name, 0, recovered.collector.startCount)
        }
    }

    @Test
    fun cancelledAccessPauseFinishesAppOwnedProtocolAndFreshRecoveryStaysClosed() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareRunningStudy()
        fixture.collector.pauseEntered = CompletableDeferred()
        fixture.collector.pauseGate = CompletableDeferred()
        fixture.access.granted -= AccessKind.NOTIFICATIONS

        val reconcile = async { fixture.manager.reconcileAccess() }
        fixture.collector.pauseEntered?.await()
        reconcile.cancel()
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
            pauseStore = FakeSafetyPauseStore(fixture.pauseStore.pendingReason),
        )
        recovered.manager.initialize()
        runCurrent()
        assertEquals(ExperimentState.PAUSED, recovered.store.metadata?.state)
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)

        fixture.collector.pauseGate?.complete(Unit)
        runCurrent()
        assertTrue(runCatching { reconcile.await() }.exceptionOrNull() is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertNull(fixture.pauseStore.pendingReason)
        assertTrue(runCatching { fixture.collector.emit(1) }.isFailure)
    }

    @Test
    fun processDeathAfterParticipantPauseCommitPreservesHistoryAndFinishesCleanup() = runTest {
        val configuration = configuration()
        val fixture = fixture(configuration)
        fixture.prepareRunningStudy()
        fixture.work.cancelInterventionEntered = CompletableDeferred()
        fixture.work.cancelInterventionGate = CompletableDeferred()

        val pause = async { fixture.manager.pause() }
        fixture.work.cancelInterventionEntered?.await()
        assertEquals(TransitionReason.PARTICIPANT_PAUSED, fixture.store.metadata?.transitions?.last()?.reason)
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
            pauseStore = FakeSafetyPauseStore(fixture.pauseStore.pendingReason),
        )
        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, recovered.store.metadata?.state)
        assertEquals(
            TransitionReason.PARTICIPANT_PAUSED,
            recovered.store.metadata?.transitions?.last()?.reason,
        )
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)

        fixture.work.cancelInterventionGate?.complete(Unit)
        assertEquals(CommandResult.Success, pause.await())
    }

    @Test
    fun terminalCommitRecoveryCancelsCollectionWorkAndRepairsOnlyUploadTail() = runTest {
        val configuration = configuration(
            upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false),
        )
        val fixture = fixture(configuration)
        fixture.prepareRunningStudy()
        fixture.work.cancelCollectionEntered = CompletableDeferred()
        fixture.work.cancelCollectionGate = CompletableDeferred()

        val finish = async { fixture.manager.finish() }
        fixture.work.cancelCollectionEntered?.await()
        assertEquals(ExperimentState.COMPLETED, fixture.store.metadata?.state)
        val recovered = fixture(
            configuration = configuration,
            activeEnvelope = byteArrayOf(1),
            initialMetadata = fixture.store.metadata,
            pauseStore = FakeSafetyPauseStore(fixture.pauseStore.pendingReason),
        )
        recovered.manager.initialize()
        runCurrent()

        assertEquals(ExperimentState.COMPLETED, recovered.store.metadata?.state)
        assertEquals(0, recovered.host.startCount)
        assertEquals(0, recovered.collector.startCount)
        // The typed teardown witness is reconciled first, then terminal recovery independently
        // enforces the same idempotent cleanup before KEEP-repairing the upload tail.
        assertEquals(2, recovered.work.cancelCollectionCount)
        assertEquals(0, recovered.work.cancelCount)
        assertEquals(ExperimentState.COMPLETED, recovered.work.ensuredMetadata.last().state)

        recovered.manager.reconcileScheduledWork(recoverStalePosting = true)
        assertEquals(3, recovered.work.cancelCollectionCount)
        assertEquals(ExperimentState.COMPLETED, recovered.work.ensuredMetadata.last().state)

        fixture.work.cancelCollectionGate?.complete(Unit)
        assertEquals(CommandResult.Success, finish.await())
    }

    @Test
    fun terminalCleanupFailureKeepsWitnessUntilStateAwareWorkerCancellationSucceeds() = runTest {
        val commands = listOf<Triple<String, ExperimentState, suspend (Fixture) -> CommandResult>>(
            Triple("finish", ExperimentState.COMPLETED) { it.manager.finish() },
            Triple("duration", ExperimentState.COMPLETED) { it.completeDurationCommand() },
            Triple("withdraw", ExperimentState.WITHDRAWN) { it.manager.withdraw() },
        )
        commands.forEach { (name, terminalState, command) ->
            val fixture = fixture(configuration())
            fixture.prepareRunningStudy()
            fixture.work.cancelCollectionFailure = IllegalStateException("$name cancellation failed")

            assertEquals(
                name,
                CommandResult.Failed("SAFETY_PAUSE_SHUTDOWN_FAILED"),
                command(fixture),
            )
            assertEquals(name, terminalState, fixture.store.metadata?.state)
            assertEquals(
                name,
                SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                fixture.pauseStore.pendingReason,
            )
            assertEquals(
                name,
                setOf(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
                fixture.work.activeSafetyPauseReasons,
            )

            fixture.work.cancelCollectionFailure = null
            assertTrue(
                name,
                fixture.manager.retrySafetyPause(
                    fixture.manager.snapshot.value.configuration?.experimentId ?: error("missing study"),
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                ),
            )
            assertEquals(name, 2, fixture.work.cancelCollectionCount)
            assertNull(name, fixture.pauseStore.pendingReason)
            assertTrue(name, fixture.work.activeSafetyPauseReasons.isEmpty())
        }
    }

    @Test
    fun optionalLocationHostPromotionCancellationPausesEveryCollector() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val lifecycle = AppLifecycleConfiguration(required = true)
        val location = locationConfiguration(required = false)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS),
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        fixture.prepareRunningStudy()
        fixture.host.locationStartEntered = CompletableDeferred()
        fixture.host.locationStartGate = CompletableDeferred()
        fixture.access.granted += locationAccess

        val reconcile = async { fixture.manager.reconcileAccess() }
        fixture.host.locationStartEntered?.await()
        reconcile.cancel()
        runCurrent()

        assertTrue(runCatching { reconcile.await() }.exceptionOrNull() is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_HOST_FAILURE,
            fixture.store.metadata?.transitions?.last()?.reason,
        )
        fixture.collectors.values.forEach { collector ->
            assertTrue(runCatching { collector.emit(1) }.isFailure)
        }
    }

    @Test
    fun optionalLocationHostDemotionCancellationPausesEveryCollector() = runTest {
        val locationAccess = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val lifecycle = AppLifecycleConfiguration(required = true)
        val location = locationConfiguration(required = false)
        val fixture = fixture(
            configuration = configuration(collectors = listOf(lifecycle, location)),
            grantedAccess = setOf(AccessKind.NOTIFICATIONS) + locationAccess,
            collectorAccessKindsById = mapOf(location.id to locationAccess),
        )
        fixture.prepareRunningStudy()
        fixture.host.nonLocationStartEntered = CompletableDeferred()
        fixture.host.nonLocationStartGate = CompletableDeferred()
        fixture.access.granted -= locationAccess

        val reconcile = async { fixture.manager.reconcileAccess() }
        fixture.host.nonLocationStartEntered?.await()
        reconcile.cancel()
        runCurrent()

        assertTrue(runCatching { reconcile.await() }.exceptionOrNull() is CancellationException)
        assertEquals(ExperimentState.PAUSED, fixture.store.metadata?.state)
        fixture.collectors.values.forEach { collector ->
            assertTrue(runCatching { collector.emit(1) }.isFailure)
        }
    }

    private suspend fun Fixture.prepareReadyStudy() {
        manager.initialize()
        manager.importSignedConfiguration(byteArrayOf(1))
        check(manager.reviewStudy() == CommandResult.Success)
        check(manager.acceptConsent() == CommandResult.Success)
        check(manager.completeAccessSetup() == CommandResult.Success)
    }

    private suspend fun Fixture.prepareRunningStudy() {
        prepareReadyStudy()
        check(manager.start() == CommandResult.Success)
    }

    private fun Fixture.forceDurationBoundary(offsetNanos: Long) {
        val configuration = requireNotNull(manager.snapshot.value.configuration)
        val startedAt = requireNotNull(store.metadata).transitions
            .single { it.reason == TransitionReason.PARTICIPANT_STARTED }
            .time
        val durationNanos = configuration.durationHours.toLong() * NANOS_PER_HOUR
        clocks.force(
            ResearchTime(
                wallTimeUtcMillis = startedAt.wallTimeUtcMillis + durationNanos / NANOS_PER_MILLISECOND,
                elapsedRealtimeNanos = startedAt.elapsedRealtimeNanos + durationNanos + offsetNanos,
                bootSessionId = startedAt.bootSessionId,
            ),
        )
    }

    private suspend fun Fixture.completeDurationCommand(): CommandResult {
        forceDurationBoundary(offsetNanos = 0)
        return when (val result = manager.completeAfterDurationIfDue()) {
            DurationCompletionResult.Completed -> CommandResult.Success
            is DurationCompletionResult.Failed -> result.commandResult
            DurationCompletionResult.Inactive -> error("Test study unexpectedly became inactive")
            is DurationCompletionResult.NotDue -> error("Test deadline unexpectedly remained early")
        }
    }

    private fun TestScope.fixture(
        configuration: StudyConfiguration,
        activeEnvelope: ByteArray? = null,
        activeRecord: ActiveStudyRecord? = activeEnvelope?.let(ActiveStudyRecord::Active),
        initialMetadata: StudyMetadata? = null,
        uploader: StudyUploader = FakeUploader(),
        grantedAccess: Set<AccessKind> = setOf(AccessKind.NOTIFICATIONS),
        omittedAccess: Set<AccessKind> = emptySet(),
        collectorAccessKinds: Set<AccessKind> = emptySet(),
        collectorAccessKindsById: Map<String, Set<AccessKind>> = emptyMap(),
        pauseStore: FakeSafetyPauseStore = FakeSafetyPauseStore(),
        recoveredBootSessionId: String? = null,
    ): Fixture {
        val active = FakeActiveStudyStore(activeRecord)
        val store = FakeStudyStore(initialMetadata)
        val lifecycleEvents = mutableListOf<String>()
        val collectors = configuration.collectors.associate { collectorConfiguration ->
            collectorConfiguration.id to FakeCollector(collectorConfiguration.id, lifecycleEvents)
        }
        val registry = CollectorRegistry(
            collectors.map { (collectorId, collector) ->
                FakePlugin(
                    collector = collector,
                    collectorId = collectorId,
                    accessKinds = collectorAccessKindsById[collectorId] ?: collectorAccessKinds,
                )
            },
        )
        val access = FakeAccessGateway(grantedAccess, omittedAccess)
        val host = FakeHost(lifecycleEvents)
        val work = FakeWorkScheduler(lifecycleEvents)
        val recoveredBoundary = initialMetadata?.transitions?.maxByOrNull {
            it.time.wallTimeUtcMillis
        }?.time
        val clockBootSessionId = recoveredBootSessionId
            ?: recoveredBoundary?.bootSessionId
            ?: UUID.randomUUID().toString()
        val recoveredWallTime = recoveredBoundary?.wallTimeUtcMillis ?: 0L
        val recoveredElapsedTime = recoveredBoundary
            ?.takeIf { it.bootSessionId == clockBootSessionId }
            ?.elapsedRealtimeNanos
            ?: 0L
        val clocks = FakeClocks(recoveredWallTime, recoveredElapsedTime, clockBootSessionId)
        val manager = StudySessionManager(
            activeStudyStore = active,
            verifier = StudyVerifier { verified(configuration) },
            storeFactory = StudyStoreFactory { _, _ -> store },
            runtimeFactory = ExperimentRuntimeFactory { verified, createdStore, safetyPauseWitness ->
                ExperimentRuntime(
                    verified,
                    createdStore,
                    registry,
                    clocks,
                    backgroundScope,
                    safetyPauseWitness,
                )
            },
            collectorRegistry = registry,
            accessGateway = access,
            collectionHost = host,
            safetyPauseStore = pauseStore,
            workScheduler = work,
            exporter = FakeExporter(),
            uploader = uploader,
            accessPolicy = StudyAccessPolicy(),
            scope = backgroundScope,
        )
        return Fixture(
            manager,
            active,
            store,
            collectors,
            host,
            work,
            uploader,
            access,
            pauseStore,
            clocks,
            lifecycleEvents,
        )
    }

    private data class Fixture(
        val manager: StudySessionManager,
        val active: FakeActiveStudyStore,
        val store: FakeStudyStore,
        val collectors: Map<String, FakeCollector>,
        val host: FakeHost,
        val work: FakeWorkScheduler,
        val uploader: StudyUploader,
        val access: FakeAccessGateway,
        val pauseStore: FakeSafetyPauseStore,
        val clocks: FakeClocks,
        val lifecycleEvents: MutableList<String>,
    ) {
        val collector: FakeCollector get() = collectors.values.single()
    }

    private class FakeSafetyPauseStore(
        var pendingReason: SafetyPauseReason? = null,
    ) : SafetyPauseStore {
        var markFailure: Exception? = null
        var clearFailure: Exception? = null
        var readFailure: Exception? = null
        var markEntered: CompletableDeferred<Unit>? = null
        var markReturnGate: CompletableDeferred<Unit>? = null

        override suspend fun pendingReason(): SafetyPauseReason? {
            readFailure?.let { throw it }
            return pendingReason
        }

        override suspend fun markPending(reason: SafetyPauseReason) {
            markFailure?.let { throw it }
            pendingReason = reason
            markEntered?.complete(Unit)
            markReturnGate?.await()
        }

        override suspend fun clear() {
            clearFailure?.let { throw it }
            pendingReason = null
        }
    }

    private class FakeActiveStudyStore(initial: ActiveStudyRecord?) : ActiveStudyStore {
        var record: ActiveStudyRecord? = initial
        var clearFailure: Exception? = null
        override suspend fun load(): ActiveStudyRecord? = record
        override suspend fun save(envelopeBytes: ByteArray) {
            record = ActiveStudyRecord.Active(envelopeBytes)
        }
        override suspend fun markDeletionPending(experimentId: String, maximumLocalBytes: Long) {
            val deletion = ActiveStudyRecord.DeletionPending(experimentId, maximumLocalBytes)
            check(record is ActiveStudyRecord.Active || record == deletion)
            record = deletion
        }
        override suspend fun clear() {
            clearFailure?.let { throw it }
            record = null
        }
    }

    private class FakeStudyStore(initial: StudyMetadata?) : StudyStore {
        var metadata = initial
            private set
        private val events = mutableListOf<RecordedEvent>()
        var cleared = false
        var usedBytes = 0L
        var quotaBytes = 16_777_216L
        var evictionCount = 0
        var clearAttempts = 0
        var clearFailure: Exception? = null
        var appendFailure: Exception? = null
        var saveFailure: Exception? = null
        var saveAfterCommitFailure: Throwable? = null
        var saveCount = 0

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
        override suspend fun saveMetadata(metadata: StudyMetadata) {
            saveFailure?.let { throw it }
            saveCount += 1
            this.metadata = metadata
            saveAfterCommitFailure?.let { failure ->
                saveAfterCommitFailure = null
                throw failure
            }
        }
        override suspend fun appendEvent(event: RecordedEvent) { events += event }
        override suspend fun appendEventAtomically(
            event: RecordedEvent,
            metadata: StudyMetadata,
            failureTime: ResearchTime,
        ) {
            appendFailure?.let { throw it }
            events += event
            this.metadata = metadata
        }
        override suspend fun resolvePendingAppendFailure(reason: TransitionReason): StudyMetadata? = null
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
            clearAttempts += 1
            clearFailure?.let { throw it }
            metadata = null
            events.clear()
            cleared = true
        }
    }

    private class FakePlugin(
        private val collector: FakeCollector,
        collectorId: String,
        accessKinds: Set<AccessKind>,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = collectorId,
            displayName = "Test collector",
            privacyClass = PrivacyClass.SENSITIVE,
            eventContract = requireNotNull(ProtocolEventContracts[collectorId]),
            accessKinds = accessKinds,
        )

        override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector =
            collector.also { it.context = context }
    }

    private class FakeCollector(
        private val collectorId: String,
        private val lifecycleEvents: MutableList<String>,
    ) : Collector {
        private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = mutableHealth
        var context: CollectorContext? = null
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var failNextPause = false
        var resourceOwned = false
        var startEntered: CompletableDeferred<Unit>? = null
        var startGate: CompletableDeferred<Unit>? = null
        var pauseEntered: CompletableDeferred<Unit>? = null
        var pauseGate: CompletableDeferred<Unit>? = null
        var resumeEntered: CompletableDeferred<Unit>? = null
        var resumeGate: CompletableDeferred<Unit>? = null

        override val requiresStop: Boolean get() = resourceOwned

        override suspend fun start() {
            lifecycleEvents += "collector:start"
            startCount += 1
            resourceOwned = true
            startEntered?.complete(Unit)
            startGate?.await()
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }
        override suspend fun pause() {
            lifecycleEvents += "collector:pause"
            pauseCount += 1
            pauseEntered?.complete(Unit)
            pauseGate?.await()
            if (failNextPause) {
                failNextPause = false
                error("collector pause failed")
            }
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }
        override suspend fun resume() {
            lifecycleEvents += "collector:resume"
            resumeCount += 1
            resumeEntered?.complete(Unit)
            resumeGate?.await()
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }
        override suspend fun stop() {
            stopCount += 1
            resourceOwned = false
            mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
        }

        /** Records [count] events through the real admission path, so sequence numbers are genuine. */
        suspend fun emit(count: Int) {
            val sink = checkNotNull(context).eventSink
            repeat(count) {
                val token = checkNotNull(sink.captureToken()) { "Admission gate refused a token" }
                sink.emit(
                    token,
                    EventDraft(
                        collectorId = collectorId,
                        payloadSchemaVersion = 1,
                        observedTime = checkNotNull(context).clocks.now(),
                        payloadType = "ACTIVITY_RESUMED",
                        fields = mapOf("activity_class" to "test.Activity"),
                    ),
                )
            }
        }
    }

    private class FakeClocks(
        private val wallTimeBase: Long = 0,
        private val elapsedRealtimeBase: Long = 0,
        private val bootSessionId: String = UUID.randomUUID().toString(),
    ) : ResearchClocks {
        private var tick = 0L
        private var forcedTime: ResearchTime? = null

        override fun now(): ResearchTime = forcedTime ?: ResearchTime(
            wallTimeBase + ++tick * 1_000,
            elapsedRealtimeBase + tick * 1_000_000_000,
            bootSessionId,
        )

        fun force(time: ResearchTime) {
            forcedTime = time
        }
    }

    private class FakeAccessGateway(
        initiallyGranted: Set<AccessKind>,
        private val omitted: Set<AccessKind>,
    ) : StudyAccessGateway {
        val granted = initiallyGranted.toMutableSet()
        var lastRequest: AccessInspectionRequest? = null
        var failure: RuntimeException? = null
        var inspectCount = 0

        override suspend fun inspect(request: AccessInspectionRequest): AccessSnapshot {
            inspectCount += 1
            failure?.let { throw it }
            lastRequest = request
            return AccessSnapshot(
                request.requirements.filterNot { it.kind in omitted }.sortedBy { it.kind.ordinal }.map { requirement ->
                AccessStatus(
                    requirement = requirement,
                    resolution = if (requirement.kind in granted) {
                        AccessResolution.Satisfied
                    } else {
                        AccessResolution.Unavailable(AccessUnavailableReason.SYSTEM_HANDLER_MISSING)
                    },
                    guidance = null,
                )
                },
            )
        }
    }

    private class FakeHost(
        private val lifecycleEvents: MutableList<String>,
    ) : StudyCollectionHost {
        var startCount = 0
        var stopCount = 0
        val locationStarts = mutableListOf<Boolean>()
        var startFailure: Exception? = null
        var locationStartFailure: Exception? = null
        var nonLocationStartFailure: Exception? = null
        var stopFailure: Exception? = null
        var locationStartEntered: CompletableDeferred<Unit>? = null
        var locationStartGate: CompletableDeferred<Unit>? = null
        var nonLocationStartEntered: CompletableDeferred<Unit>? = null
        var nonLocationStartGate: CompletableDeferred<Unit>? = null
        override suspend fun start(studyTitle: String, usesLocation: Boolean) {
            startCount += 1
            locationStarts += usesLocation
            if (usesLocation) {
                locationStartEntered?.complete(Unit)
                locationStartGate?.await()
            } else {
                nonLocationStartEntered?.complete(Unit)
                nonLocationStartGate?.await()
            }
            startFailure?.let { throw it }
            if (usesLocation) locationStartFailure?.let { throw it }
            if (!usesLocation) nonLocationStartFailure?.let { throw it }
            lifecycleEvents += "host:$usesLocation"
        }
        override fun stop() {
            stopCount += 1
            stopFailure?.let { throw it }
        }
    }

    private class FakeWorkScheduler(
        private val lifecycleEvents: MutableList<String>,
    ) : StudyWorkScheduler {
        var scheduleCount = 0
        var cancelCount = 0
        var cancelCollectionCount = 0
        var cancelInterventionCount = 0
        var safetyPauseScheduleCount = 0
        var safetyPauseCancelCount = 0
        var scheduledSafetyPauseReason: SafetyPauseReason? = null
        val activeSafetyPauseReasons = mutableSetOf<SafetyPauseReason>()
        var safetyPauseScheduleFailure: Exception? = null
        var safetyPauseCancelFailure: Exception? = null
        var safetyPauseInspectionFailure: Exception? = null
        var safetyPauseCancellationGate: CompletableDeferred<Unit>? = null
        var cancelInterventionEntered: CompletableDeferred<Unit>? = null
        var cancelInterventionGate: CompletableDeferred<Unit>? = null
        var cancelCollectionEntered: CompletableDeferred<Unit>? = null
        var cancelCollectionGate: CompletableDeferred<Unit>? = null
        var cancelCollectionFailure: Exception? = null
        var failSchedule = false
        var ensureGate: CompletableDeferred<Unit>? = null
        var failEnqueue = false
        var replacementFailure: Exception? = null
        var cancelFailure: Exception? = null
        var replacementDeliveries = emptyList<InterventionOccurrence>()
        var replacementExpiries = emptyList<InterventionOccurrence>()
        val cancelledNotificationIds = mutableSetOf<String>()
        val enqueuedOccurrences = mutableListOf<InterventionOccurrence>()
        val ensuredMetadata = mutableListOf<StudyMetadata>()
        val ensureObservedAt = mutableListOf<ResearchTime>()
        override suspend fun ensureCollectionWork(
            configuration: StudyConfiguration,
            metadata: StudyMetadata,
            observedAt: ResearchTime,
        ) {
            scheduleCount += 1
            ensuredMetadata += metadata
            ensureObservedAt += observedAt
            lifecycleEvents += "work:ensure"
            ensureGate?.await()
            if (failSchedule) error("Scheduling failed")
        }
        override suspend fun replaceInterventionWork(
            configuration: StudyConfiguration,
            deliveries: List<InterventionOccurrence>,
            expiries: List<InterventionOccurrence>,
        ) {
            replacementFailure?.let { throw it }
            replacementDeliveries = deliveries
            replacementExpiries = expiries
        }
        override suspend fun enqueueOccurrence(
            configuration: StudyConfiguration,
            occurrence: InterventionOccurrence,
        ) {
            if (failEnqueue) error("Enqueue failed")
            enqueuedOccurrences += occurrence
        }
        override suspend fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>) {
            cancelInterventionCount += 1
            cancelledNotificationIds += occurrenceIds
            cancelInterventionEntered?.complete(Unit)
            cancelInterventionGate?.await()
        }
        override fun cancelInterventionNotifications(occurrenceIds: Set<String>) {
            cancelledNotificationIds += occurrenceIds
        }
        override suspend fun scheduleSafetyPauseRetry(experimentId: String, reason: SafetyPauseReason) {
            safetyPauseScheduleCount += 1
            scheduledSafetyPauseReason = reason
            safetyPauseScheduleFailure?.let { throw it }
            activeSafetyPauseReasons += reason
        }
        override suspend fun pendingSafetyPauseReason(experimentId: String): SafetyPauseReason? {
            safetyPauseInspectionFailure?.let { throw it }
            check(activeSafetyPauseReasons.size <= 1)
            return activeSafetyPauseReasons.singleOrNull()
        }
        override suspend fun cancelSafetyPauseRetry() {
            safetyPauseCancelCount += 1
            safetyPauseCancellationGate?.await()
            safetyPauseCancelFailure?.let { throw it }
            activeSafetyPauseReasons.clear()
        }
        override suspend fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>) {
            cancelCollectionCount += 1
            cancelledNotificationIds += occurrenceIds
            cancelCollectionEntered?.complete(Unit)
            cancelCollectionGate?.await()
            cancelCollectionFailure?.let { throw it }
        }
        override suspend fun cancel(experimentId: String) {
            cancelCount += 1
            cancelFailure?.let { throw it }
        }
    }

    private class FakeUploader(
        var failure: Throwable? = null,
    ) : StudyUploader {
        val ranges = mutableListOf<Pair<Long, Long>>()
        val acknowledged = mutableListOf<UUID>()
        var cleared = false
        var deletionPrepared = false
        var clearAttempts = 0
        var clearFailure: Exception? = null

        override suspend fun reconcile(configuration: VerifiedConfiguration, metadata: StudyMetadata) = Unit

        override suspend fun upload(
            configuration: VerifiedConfiguration,
            metadata: StudyMetadata,
            events: StudyStore,
            fromSequence: Long,
            toSequence: Long,
        ): ExportReceipt {
            ranges += fromSequence to toSequence
            failure?.let { throw it }
            return ExportReceipt(
                bundleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                configurationSha256 = configuration.configurationSha256,
                firstSequence = fromSequence,
                lastSequence = toSequence,
                eventCount = toSequence - fromSequence + 1,
                sha256 = "1".repeat(64),
                byteCount = 1,
            )
        }

        override suspend fun acknowledge(bundleId: UUID) { acknowledged += bundleId }
        override suspend fun prepareDeletion() { deletionPrepared = true }
        override suspend fun clear() {
            clearAttempts += 1
            clearFailure?.let { throw it }
            cleared = true
        }
    }

    private class FakeExporter : StudyExporter {
        override suspend fun export(
            configuration: VerifiedConfiguration,
            metadata: StudyMetadata,
            events: StudyStore,
            destination: OutputStream,
        ): ExportReceipt {
            destination.write(1)
            return ExportReceipt(
                bundleId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
                configurationSha256 = configuration.configurationSha256,
                firstSequence = 1,
                lastSequence = metadata.eventCount,
                eventCount = metadata.eventCount,
                sha256 = "1".repeat(64),
                byteCount = 1,
            )
        }
    }

    private class CloseTrackingOutputStream : OutputStream() {
        var closed = false
        override fun write(value: Int) = Unit
        override fun close() { closed = true }
    }

    private fun configuration(
        collectors: List<CollectorConfiguration> = listOf(AppLifecycleConfiguration(required = true)),
        interventions: List<InterventionConfiguration> = emptyList(),
        surveys: List<SurveyDefinition> = emptyList(),
        upload: UploadConfiguration? = null,
    ) = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = "session-test",
        configurationId = "session-config",
        assignedParticipantId = null,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Session test",
        researcherName = "Test researcher",
        researcherContact = "test@example.invalid",
        purpose = "Application boundary test",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Test consent",
        collectors = collectors,
        surveys = surveys,
        interventions = interventions,
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = upload,
    )

    private fun startedMetadata(
        configuration: StudyConfiguration,
        state: ExperimentState = ExperimentState.RUNNING,
        occurrences: Map<String, InterventionOccurrence> = emptyMap(),
        start: ResearchTime = ResearchTime(100, 100, "boot-before-recovery"),
    ): StudyMetadata {
        require(state in setOf(ExperimentState.RUNNING, ExperimentState.PAUSED))
        val transitions = mutableListOf(
            ExperimentTransition(
                from = ExperimentState.READY,
                to = ExperimentState.RUNNING,
                reason = TransitionReason.PARTICIPANT_STARTED,
                time = start,
            ),
        )
        if (state == ExperimentState.PAUSED) {
            transitions += ExperimentTransition(
                from = ExperimentState.RUNNING,
                to = ExperimentState.PAUSED,
                reason = TransitionReason.PARTICIPANT_PAUSED,
                time = start.copy(
                    wallTimeUtcMillis = start.wallTimeUtcMillis + 1,
                    elapsedRealtimeNanos = start.elapsedRealtimeNanos + 1,
                ),
            )
        }
        return StudyMetadata.initial(configuration.experimentId, configuration.configurationId).copy(
            state = state,
            transitions = transitions,
            occurrences = occurrences,
        )
    }

    private fun locationConfiguration(required: Boolean) = LocationConfiguration(
        required = required,
        intervalMillis = 60_000,
        minimumIntervalMillis = 30_000,
        maximumBatchDelayMillis = 0,
        minimumDisplacementMillimeters = 0,
        priority = LocationPriority.BALANCED,
    )

    private fun verified(configuration: StudyConfiguration) = VerifiedConfiguration(
        configuration = configuration,
        canonicalConfigurationBytes = byteArrayOf(1),
        signerKeyId = configuration.signer.keyId,
        signature = ByteArray(64),
        configurationSha256 = "0".repeat(64),
        signerAnchored = false,
    )

    private fun occurrence(
        id: String,
        state: OccurrenceState,
        interventionId: String = "notice-one",
        triggerId: String = "after-minute",
    ) = InterventionOccurrence(
        occurrenceId = id,
        interventionId = interventionId,
        triggerId = triggerId,
        scheduleKey = "relative:1",
        scheduledFor = ResearchTime(100, 100, "boot-before-recovery"),
        expiresAtUtcMillis = 500,
        state = state,
    )
}

private const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
private const val NANOS_PER_HOUR = 60L * 60L * 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
