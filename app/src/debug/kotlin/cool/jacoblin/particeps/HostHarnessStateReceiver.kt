package cool.jacoblin.particeps

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.model.ExperimentState
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shell-only process-continuity and lifecycle seam for the blocking adb host harness.
 *
 * This component exists only in the debug source set and requires the signature-level DUMP
 * permission held by adb shell. Lifecycle commands run in the normal app process so finishing an
 * instrumentation process cannot manufacture the process death that Protocol v1 must fail closed.
 */
class HostHarnessStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action !in ACTIONS || !context.applicationInfo.isDebuggable()) {
            resultCode = Activity.RESULT_CANCELED
            return
        }
        val application = context.applicationContext as? CollectorApplication
        if (application == null) {
            resultCode = Activity.RESULT_CANCELED
            return
        }
        val pending = goAsync()
        application.applicationScope.launch {
            try {
                withTimeout(QUERY_TIMEOUT_MILLIS) {
                    application.session.snapshot.first { it.initialized }
                }
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = when (action) {
                    PROVISION_ACTION -> application.provision(requireNotNull(intent))
                    RESET_ACTION -> {
                        application.resetForHostHarness()
                        RESET_COMPLETE
                    }
                    else -> {
                        val snapshot = application.session.snapshot.value
                        val state = snapshot.runtime.state?.name ?: NO_STUDY_STATE
                        "$state:${snapshot.runtime.lifetimeDataEventCount}"
                    }
                }
            } catch (failure: HostHarnessProvisionException) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "FAILED:${failure.stage}:${failure.resultCode}"
            } catch (failure: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "$QUERY_UNAVAILABLE:${failure::class.java.simpleName}"
            } finally {
                pending.finish()
            }
        }
    }

    private fun ApplicationInfo.isDebuggable(): Boolean =
        flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private suspend fun CollectorApplication.provision(intent: Intent): String {
        val encoded = requireNotNull(intent.getStringExtra(EXTRA_SIGNED_ENVELOPE)) {
            "Missing signed host-harness study envelope"
        }
        resetForHostHarness()
        session.importSignedConfiguration(Base64.getDecoder().decode(encoded))
        requireSuccess("REVIEW", session.reviewStudy())
        requireSuccess("CONSENT", session.acceptConsent())
        requireSuccess("ACCESS", session.completeAccessSetup())
        val start = session.start()
        if (start != StudyCommandResult.Success) {
            throw HostHarnessProvisionException("START", start::class.java.simpleName)
        }
        withTimeout(QUERY_TIMEOUT_MILLIS) {
            session.snapshot.first { it.runtime.state == ExperimentState.RUNNING }
        }
        return ExperimentState.RUNNING.name
    }

    private fun requireSuccess(stage: String, result: StudyCommandResult) {
        if (result != StudyCommandResult.Success) {
            throw HostHarnessProvisionException(stage, result::class.java.simpleName)
        }
    }

    private suspend fun CollectorApplication.resetForHostHarness() {
        when {
            session.snapshot.value.study != null -> session.deleteLocalData()
            session.snapshot.value.recoveryStatus ==
                cool.jacoblin.particeps.core.application.StudyRecoveryStatus.ACTION_REQUIRED ->
                session.resetAfterRecoveryFailure()
        }
        check(session.snapshot.value.study == null && !session.snapshot.value.deletionPending) {
            "Host-harness reset did not reach an empty durable session"
        }
    }

    private companion object {
        const val ACTION = "cool.jacoblin.particeps.HOST_HARNESS_QUERY"
        const val PROVISION_ACTION = "cool.jacoblin.particeps.HOST_HARNESS_PROVISION"
        const val RESET_ACTION = "cool.jacoblin.particeps.HOST_HARNESS_RESET"
        const val EXTRA_SIGNED_ENVELOPE = "signed_envelope_base64"
        val ACTIONS = setOf(ACTION, PROVISION_ACTION, RESET_ACTION)
        const val RESET_COMPLETE = "RESET"
        const val NO_STUDY_STATE = "NONE"
        const val QUERY_UNAVAILABLE = "UNAVAILABLE"
        const val QUERY_TIMEOUT_MILLIS = 30_000L
    }
}

private class HostHarnessProvisionException(
    val stage: String,
    val resultCode: String,
) : IllegalStateException("Host-harness provisioning failed")
