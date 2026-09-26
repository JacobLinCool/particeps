package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.ParticipantDataCategorySummary
import cool.jacoblin.particeps.core.application.ParticipantRuntimeStatus
import cool.jacoblin.particeps.core.application.ParticipantStudySummary
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudyAccessStatus
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The participant projection runs for a screen that is showing it, and only when the session
 * snapshot changes. A collector here stands in for `collectAsStateWithLifecycle`, which collects
 * from start to stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyUiStateSharingTest {
    @Test
    fun aStoppedScreenProjectsNoCommitsAndResumesFromTheCurrentSnapshot() = runTest {
        val pipeline = Pipeline(this)
        var screen = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()
        repeat(COMMITS) { pipeline.commit() }
        assertEquals(1 + COMMITS, pipeline.projections)

        screen.cancel()
        advanceTimeBy(UI_STATE_STOP_TIMEOUT_MILLIS + 1)
        runCurrent()
        val atStop = pipeline.projections
        repeat(COMMITS) { pipeline.commit() }
        assertEquals("A stopped screen must not project commits", atStop, pipeline.projections)

        screen = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()
        assertEquals("Returning projects the current snapshot once", atStop + 1, pipeline.projections)
        assertEquals(pipeline.latestCommit, pipeline.activeModel().durableThroughCommit)
        screen.cancel()
    }

    @Test
    fun aRecreatedScreenWithinTheTimeoutKeepsTheProjectionRunning() = runTest {
        val pipeline = Pipeline(this)
        val first = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(UI_STATE_STOP_TIMEOUT_MILLIS / 2)
        pipeline.commit()
        val second = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()

        assertEquals(2, pipeline.projections)
        assertEquals(pipeline.latestCommit, pipeline.activeModel().durableThroughCommit)
        second.cancel()
    }

    @Test
    fun exportProgressBusyAndMessagesReuseTheParticipantModel() = runTest {
        val pipeline = Pipeline(this)
        val screen = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()
        val model = pipeline.activeModel()

        repeat(EXPORT_TICKS) { tick ->
            pipeline.export.value = ParticipantExportState.Running(
                ParticipantExportPhase.ENCRYPTING,
                completedBatches = tick.toLong(),
                totalBatches = EXPORT_TICKS.toLong(),
            )
            runCurrent()
        }
        pipeline.busy.value = true
        runCurrent()
        pipeline.messages.value = ParticipantMessage.OPERATION_FAILED
        runCurrent()

        assertEquals(1, pipeline.projections)
        val active = pipeline.state.value as StudyUiState.ActiveStudy
        assertEquals(model, active.model)
        assertEquals(
            ParticipantExportState.Running(ParticipantExportPhase.ENCRYPTING, EXPORT_TICKS - 1L, EXPORT_TICKS.toLong()),
            active.export,
        )
        assertEquals(true, active.busy)
        assertEquals(ParticipantMessage.OPERATION_FAILED, active.message)
        screen.cancel()
    }

    @Test
    fun theProjectionStillDistinguishesStartupNoStudyAndRecoveredStudies() = runTest {
        val pipeline = Pipeline(this)
        val screen = backgroundScope.launch { pipeline.state.collect {} }
        runCurrent()

        pipeline.snapshots.value = StudySessionSnapshot(startupStage = StartupStage.VERIFYING_STORAGE)
        runCurrent()
        assertEquals(StudyUiState.Initializing(StartupStage.VERIFYING_STORAGE), pipeline.state.value)

        pipeline.snapshots.value = StudySessionSnapshot(initialized = true)
        pipeline.busy.value = true
        runCurrent()
        assertEquals(StudyUiState.NoStudy(message = null, busy = true, recoveryStatus = null), pipeline.state.value)

        pipeline.snapshots.value = running(7).copy(recoveryStatus = StudyRecoveryStatus.RECOVERED_PAUSED)
        runCurrent()
        val recovered = pipeline.state.value as StudyUiState.ActiveStudy
        assertEquals(ParticipantMessage.STUDY_PAUSED_FOR_SAFETY, recovered.message)
        assertEquals(ParticipantRecoveryState.RECOVERED, recovered.recoveryStatus)

        pipeline.messages.value = ParticipantMessage.OPERATION_FAILED
        runCurrent()
        assertEquals(ParticipantMessage.OPERATION_FAILED, pipeline.state.value.message)
        assertEquals("Only snapshots with a study are projected", 2, pipeline.projections)
        screen.cancel()
    }

    private class Pipeline(private val test: TestScope) {
        var latestCommit = 1L
            private set
        var projections = 0
            private set
        val snapshots = MutableStateFlow(running(latestCommit))
        val messages = MutableStateFlow<ParticipantMessage?>(null)
        val busy = MutableStateFlow(false)
        val export = MutableStateFlow<ParticipantExportState>(ParticipantExportState.Idle)
        val state: StateFlow<StudyUiState> = participantUiState(
            test.backgroundScope,
            snapshots,
            messages,
            busy,
            export,
        ) {
            projections++
            it.toParticipantUiModel()
        }

        fun commit() {
            test.advanceTimeBy(COMMIT_SPACING_MILLIS)
            snapshots.value = running(++latestCommit)
            test.runCurrent()
        }

        fun activeModel(): ParticipantStudyUiModel = (state.value as StudyUiState.ActiveStudy).model
    }

    private companion object {
        const val COMMITS = 50
        const val EXPORT_TICKS = 20
        const val COMMIT_SPACING_MILLIS = 1_000L

        fun running(commit: Long) = StudySessionSnapshot(
            initialized = true,
            study = ParticipantStudySummary(
                experimentId = "sharing-test",
                configurationId = "sharing-test-config",
                assignedParticipantId = null,
                title = "Study",
                researcherName = "Researcher",
                researcherContact = "researcher@example.invalid",
                purpose = "Purpose",
                durationHours = 120,
                consentDocumentVersion = "consent-1",
                consentSummary = "Consent",
                signerFingerprint = "ab".repeat(32),
                signerAnchored = false,
                dataCategories = listOf(
                    ParticipantDataCategorySummary("screen_state.v1", required = true),
                    ParticipantDataCategorySummary("usage_events.v1", required = false),
                ),
                mayAdjustAppTransferSpeed = false,
                upload = null,
            ),
            runtime = ParticipantRuntimeStatus(
                state = ExperimentState.RUNNING,
                participantInstanceId = "5f0c3a1e-9d2b-4c7a-8e61-2b4d6f8a0c13",
                lifetimeDataEventCount = commit * 10,
                durableThroughCommit = commit,
                activeRunningElapsedMillis = commit * 1_000,
                calendarElapsedMillis = commit * 1_000,
                elapsedMeasuredAtUtcMillis = 1_000_000L + commit,
                stateEnteredAtUtcMillis = 1_000_000L,
            ),
            access = listOf(StudyAccessStatus(AccessKind.USAGE_ACCESS, true, AccessResolution.Satisfied, null)),
        )
    }
}
