package cool.jacoblin.particeps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import cool.jacoblin.particeps.core.model.ExperimentState
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Narrow host-control seam compiled into the instrumentation APK only.
 *
 * The host invokes one method at a time around real adb process, reboot, package, connectivity and
 * VPN operations. No production callback is replaced and the assertions read only the same
 * participant-safe lifecycle projection exposed by [StudySessionManager].
 */
@RunWith(AndroidJUnit4::class)
class HostHarnessStudyControlTest {
    @Test
    fun provisionRunningStudy() = runBlocking {
        requireHostHarnessInvocation()
        val session = initializedSession()
        session.clearStudyDataForTest()
        val encoded = InstrumentationRegistry.getInstrumentation().context.assets
            .open("host_harness_study_envelope.txt")
            .bufferedReader()
            .use { it.readText() }
        session.importSignedConfiguration(Base64.getDecoder().decode(encoded.trim()))

        assertEquals(StudyCommandResult.Success, session.reviewStudy())
        assertEquals(StudyCommandResult.Success, session.acceptConsent())
        assertEquals(StudyCommandResult.Success, session.completeAccessSetup())
        assertEquals(StudyCommandResult.Success, session.start())
        awaitState(session, ExperimentState.RUNNING)
        assertNotNull(session.snapshot.value.study)
    }

    @Test
    fun assertSafetyPaused() = runBlocking {
        requireHostHarnessInvocation()
        val session = initializedSession()
        awaitState(session, ExperimentState.PAUSED)
        assertAdmissionQuiescent(session)
    }

    @Test
    fun assertDurablySafetyPaused() = runBlocking {
        requireHostHarnessInvocation()
        val session = initializedSession()
        awaitState(session, ExperimentState.PAUSED)
        assertEquals(
            "A live safety pause must already be durable before process recovery",
            StudyRecoveryStatus.NONE,
            session.snapshot.value.recoveryStatus,
        )
        assertAdmissionQuiescent(session)
    }

    private suspend fun assertAdmissionQuiescent(
        session: cool.jacoblin.particeps.core.application.StudySessionManager,
    ) {
        val admitted = session.snapshot.value.runtime.lifetimeDataEventCount
        delay(QUIESCENCE_MILLIS)
        assertEquals(
            "Safety pause must close event admission before host verification continues",
            admitted,
            session.snapshot.value.runtime.lifetimeDataEventCount,
        )
    }

    @Test
    fun assertStillRunning() = runBlocking {
        requireHostHarnessInvocation()
        val session = initializedSession()
        awaitState(session, ExperimentState.RUNNING)
        assertEquals(StudyRecoveryStatus.NONE, session.snapshot.value.recoveryStatus)
    }

    @Test
    fun resetStudy() = runBlocking {
        requireHostHarnessInvocation()
        initializedSession().clearStudyDataForTest()
    }

    private fun requireHostHarnessInvocation() {
        assumeTrue(
            "Host control tests run only under the blocking adb harness",
            InstrumentationRegistry.getArguments().getString(HOST_HARNESS_ARGUMENT) == "true",
        )
    }

    private suspend fun initializedSession() =
        ApplicationProvider.getApplicationContext<CollectorApplication>().session.also { session ->
            withTimeout(TIMEOUT_MILLIS) { session.snapshot.first { it.initialized } }
        }

    private suspend fun awaitState(
        session: cool.jacoblin.particeps.core.application.StudySessionManager,
        expected: ExperimentState,
    ) {
        withTimeout(TIMEOUT_MILLIS) {
            session.snapshot.first { it.runtime.state == expected }
        }
        assertEquals(expected, session.snapshot.value.runtime.state)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 105_000L
        const val QUIESCENCE_MILLIS = 1_000L
        const val HOST_HARNESS_ARGUMENT = "particepsHostHarness"
    }
}
