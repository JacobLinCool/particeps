package cool.linc.particeps.core.application

import cool.linc.particeps.core.collector.AccessKind
import cool.linc.particeps.core.collector.AccessRequirement
import cool.linc.particeps.core.collector.AccessStatus
import cool.linc.particeps.core.collector.Collector
import cool.linc.particeps.core.collector.CollectorContext
import cool.linc.particeps.core.collector.CollectorDescriptor
import cool.linc.particeps.core.collector.CollectorHealth
import cool.linc.particeps.core.collector.CollectorPlugin
import cool.linc.particeps.core.collector.CollectorRegistry
import cool.linc.particeps.core.collector.CollectorStatus
import cool.linc.particeps.core.collector.PrivacyClass
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.collector.ResearchClocks
import cool.linc.particeps.core.collector.StudyAccessGateway
import cool.linc.particeps.core.definition.AppLifecycleConfiguration
import cool.linc.particeps.core.definition.CollectorConfiguration
import cool.linc.particeps.core.definition.ExportConfiguration
import cool.linc.particeps.core.definition.InterventionConfiguration
import cool.linc.particeps.core.definition.InterventionTrigger
import cool.linc.particeps.core.definition.IntervalSchedule
import cool.linc.particeps.core.definition.LocalizedText
import cool.linc.particeps.core.definition.NotificationAction
import cool.linc.particeps.core.definition.OneTimeSchedule
import cool.linc.particeps.core.definition.RelativeClock
import cool.linc.particeps.core.definition.SignerIdentity
import cool.linc.particeps.core.definition.ShortTextQuestion
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.SurveyAction
import cool.linc.particeps.core.definition.SurveyDefinition
import cool.linc.particeps.core.definition.UploadConfiguration
import cool.linc.particeps.core.export.ExportReceipt
import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.model.ExperimentState
import cool.linc.particeps.core.model.ExperimentTransition
import cool.linc.particeps.core.model.InterventionOccurrence
import cool.linc.particeps.core.model.OccurrenceState
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import cool.linc.particeps.core.model.StorageUsage
import cool.linc.particeps.core.model.StudyMetadata
import cool.linc.particeps.core.model.StudyStore
import cool.linc.particeps.core.model.TransitionReason
import cool.linc.particeps.core.runtime.CommandResult
import cool.linc.particeps.core.runtime.ExperimentRuntime
import cool.linc.particeps.core.runtime.OccurrenceClaimResult
import cool.linc.particeps.core.runtime.OccurrenceExpiryResult
import cool.linc.particeps.core.protocol.ActiveStudyStore
import cool.linc.particeps.core.protocol.ActiveStudyRecord
import cool.linc.particeps.core.protocol.JoinLink
import cool.linc.particeps.core.protocol.VerifiedConfiguration
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
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
    fun bootOrTimezoneReconciliationRestoresPostedAndOpenedExpiryWork() = runTest {
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
        val metadata = StudyMetadata.initial(configuration.experimentId, configuration.configurationId).copy(
            state = ExperimentState.RUNNING,
            occurrences = listOf(posted, openedNotice, openedSurvey, posting, expired).associateBy { it.occurrenceId },
        )
        val fixture = fixture(
            configuration,
            activeEnvelope = byteArrayOf(9),
            initialMetadata = metadata,
        )

        fixture.manager.initialize()
        fixture.manager.rescheduleInterventions(recoverStalePosting = true)

        assertTrue(fixture.work.replacementDeliveries.isEmpty())
        assertEquals(
            setOf(posted.occurrenceId, openedSurvey.occurrenceId, posting.occurrenceId),
            fixture.work.replacementExpiries.mapTo(mutableSetOf()) { it.occurrenceId },
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
            fixture.manager.rescheduleInterventions(recoverStalePosting = true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(setOf(stalePosting.occurrenceId), fixture.work.cancelledNotificationIds)
        assertTrue(fixture.work.replacementDeliveries.isEmpty())
    }

    @Test
    fun postedCommitSurvivesSuccessorSchedulingFailureAndRetry() = runTest {
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

        fixture.work.failEnqueue = false
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
                initialMetadata = StudyMetadata.initial(configuration.experimentId, configuration.configurationId).copy(
                    state = ExperimentState.RUNNING,
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

    private fun TestScope.fixture(
        configuration: StudyConfiguration,
        activeEnvelope: ByteArray? = null,
        activeRecord: ActiveStudyRecord? = activeEnvelope?.let(ActiveStudyRecord::Active),
        initialMetadata: StudyMetadata? = null,
        uploader: StudyUploader = FakeUploader(),
    ): Fixture {
        val active = FakeActiveStudyStore(activeRecord)
        val store = FakeStudyStore(initialMetadata)
        val collector = FakeCollector()
        val registry = CollectorRegistry(listOf(FakePlugin(collector)))
        val access = FakeAccessGateway()
        val host = FakeHost()
        val work = FakeWorkScheduler()
        val manager = StudySessionManager(
            activeStudyStore = active,
            verifier = StudyVerifier { verified(configuration) },
            storeFactory = StudyStoreFactory { _, _ -> store },
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
        return Fixture(manager, active, store, collector, host, work, uploader, access)
    }

    private data class Fixture(
        val manager: StudySessionManager,
        val active: FakeActiveStudyStore,
        val store: FakeStudyStore,
        val collector: FakeCollector,
        val host: FakeHost,
        val work: FakeWorkScheduler,
        val uploader: StudyUploader,
        val access: FakeAccessGateway,
    )

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
        private var metadata = initial
        private val events = mutableListOf<RecordedEvent>()
        var cleared = false
        var usedBytes = 0L
        var quotaBytes = 16_777_216L
        var evictionCount = 0
        var clearAttempts = 0
        var clearFailure: Exception? = null
        var appendFailure: Exception? = null

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
            appendFailure?.let { throw it }
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
            clearAttempts += 1
            clearFailure?.let { throw it }
            metadata = null
            events.clear()
            cleared = true
        }
    }

    private class FakePlugin(
        private val collector: FakeCollector,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = AppLifecycleConfiguration.ID,
            displayName = "Test collector",
            privacyClass = PrivacyClass.SENSITIVE,
            eventContract = requireNotNull(ProtocolEventContracts[AppLifecycleConfiguration.ID]),
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
                        fields = mapOf("activity_class" to "test.Activity"),
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
        var stopFailure: Exception? = null
        override fun start(studyTitle: String, usesLocation: Boolean) { startCount += 1 }
        override fun stop() {
            stopCount += 1
            stopFailure?.let { throw it }
        }
    }

    private class FakeWorkScheduler : StudyWorkScheduler {
        var scheduleCount = 0
        var cancelCount = 0
        var cancelCollectionCount = 0
        var cancelInterventionCount = 0
        var failSchedule = false
        var failEnqueue = false
        var cancelFailure: Exception? = null
        var replacementDeliveries = emptyList<InterventionOccurrence>()
        var replacementExpiries = emptyList<InterventionOccurrence>()
        val cancelledNotificationIds = mutableSetOf<String>()
        val enqueuedOccurrences = mutableListOf<InterventionOccurrence>()
        override fun schedule(configuration: StudyConfiguration) {
            scheduleCount += 1
            if (failSchedule) error("Scheduling failed")
        }
        override fun replaceInterventionWork(
            configuration: StudyConfiguration,
            deliveries: List<InterventionOccurrence>,
            expiries: List<InterventionOccurrence>,
        ) {
            replacementDeliveries = deliveries
            replacementExpiries = expiries
        }
        override fun enqueueOccurrence(
            configuration: StudyConfiguration,
            occurrence: InterventionOccurrence,
        ) {
            if (failEnqueue) error("Enqueue failed")
            enqueuedOccurrences += occurrence
        }
        override fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>) {
            cancelInterventionCount += 1
            cancelledNotificationIds += occurrenceIds
        }
        override fun cancelInterventionNotifications(occurrenceIds: Set<String>) {
            cancelledNotificationIds += occurrenceIds
        }
        override fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>) {
            cancelCollectionCount += 1
            cancelledNotificationIds += occurrenceIds
        }
        override fun cancel(experimentId: String) {
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
        collectors = listOf(AppLifecycleConfiguration(required = true)),
        surveys = surveys,
        interventions = interventions,
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = upload,
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
