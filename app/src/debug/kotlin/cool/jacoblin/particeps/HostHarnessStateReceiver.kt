package cool.jacoblin.particeps

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudyRuntimeAssembly
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.RuntimeSnapshot
import java.math.BigInteger
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

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
                pending.resultData = if (action == ACTION &&
                    intent.getBooleanExtra(EXTRA_APPLIED_PROFILE, false)
                ) {
                    // One deadline covers initialization and both coordinator locks.
                    withTimeout(QUERY_TIMEOUT_MILLIS) {
                        application.session.snapshot.first { it.initialized }
                        HostHarnessAppliedProfileReader.read(application.session).toJson()
                    }
                } else {
                    withTimeout(QUERY_TIMEOUT_MILLIS) {
                        application.session.snapshot.first { it.initialized }
                    }
                    when (action) {
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
                }
                pending.resultCode = Activity.RESULT_OK
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
        const val EXTRA_APPLIED_PROFILE = "include_applied_profile"
        val ACTIONS = setOf(ACTION, PROVISION_ACTION, RESET_ACTION)
        const val RESET_COMPLETE = "RESET"
        const val NO_STUDY_STATE = "NONE"
        const val QUERY_UNAVAILABLE = "UNAVAILABLE"
        const val QUERY_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * Test-only access to the coordinator's existing committed projection. Keep this in debug rather
 * than adding a product API that exposes treatment identities to the participant application.
 * Exact reflective names are intentional: an internal refactor fails the harness visibly, never
 * falls back to a desired profile, an independently reopened store, or an unsynchronized read.
 */
internal object HostHarnessAppliedProfileReader {
    suspend fun read(session: StudySessionManager): HostHarnessAppliedProfile {
        val sessionLock = field(session, "sessionMutex") as Mutex
        return sessionLock.withLock {
            val assembly = field(session, "assembly") as StudyRuntimeAssembly?
                ?: return@withLock HostHarnessAppliedProfile.noStudy()
            val runtime = assembly.runtime
            val runtimeLock = field(runtime, "mutex") as Mutex
            runtimeLock.withLock {
                // This lock order is also used by normal session lifecycle commands.
                val memory = checkNotNull(field(runtime, "memory"))
                val document = checkNotNull(field(memory, "document") as RuntimeDocument?)
                val resources = field(memory, "appliedResources") as Map<*, *>
                val applied = resources.entries.map { (key, value) ->
                    val resource = value as AppliedResourceState
                    check(key == resource.key) { "Applied resource key mismatch" }
                    resource
                }.sortedBy(AppliedResourceState::key)
                val gate = checkNotNull(field(runtime, "gate"))
                // capture() also checks the signed deadline, unlike isOpen(). It has no side effect.
                val admissionOpen = gate.javaClass.getDeclaredMethod("capture").apply {
                    isAccessible = true
                }.invoke(gate) != null
                HostHarnessAppliedProfile.fromCommitted(
                    document, runtime.snapshot.value, AppliedResourceVector(applied), admissionOpen,
                )
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)

    /** Pin the exact production seams in a JVM test; none of these names is discovered heuristically. */
    fun requireReflectionContract() {
        check(StudySessionManager::class.java.getDeclaredField("sessionMutex").type == Mutex::class.java)
        check(StudySessionManager::class.java.getDeclaredField("assembly").type == StudyRuntimeAssembly::class.java)
        check(ExperimentRuntime::class.java.getDeclaredField("mutex").type == Mutex::class.java)
        val memoryType = ExperimentRuntime::class.java.getDeclaredField("memory").type
        check(memoryType.getDeclaredField("document").type == RuntimeDocument::class.java)
        check(Map::class.java.isAssignableFrom(memoryType.getDeclaredField("appliedResources").type))
        val gateType = ExperimentRuntime::class.java.getDeclaredField("gate").type
        check(gateType.getDeclaredMethod("capture").parameterCount == 0)
    }
}

internal data class HostHarnessAppliedProfile(
    val status: String,
    val state: String,
    val admissionOpen: Boolean,
    val revision: Long,
    val activeRunningElapsedMillis: Long,
    val conditionEpochId: String?,
    val appliedResourceVectorSha256: String?,
    val profileId: String?,
    val appliedProfileSha256: String?,
    val resourceGeneration: BigInteger?,
) {
    fun toJson(): String = JSONObject().apply {
        put("schema_version", 1)
        put("status", status)
        put("state", state)
        put("admission_open", admissionOpen)
        put("revision", revision)
        put("active_running_elapsed_millis", activeRunningElapsedMillis)
        put("condition_epoch_id", conditionEpochId ?: JSONObject.NULL)
        put("applied_resource_vector_sha256", appliedResourceVectorSha256 ?: JSONObject.NULL)
        put("profile_id", profileId ?: JSONObject.NULL)
        put("applied_profile_sha256", appliedProfileSha256 ?: JSONObject.NULL)
        put("resource_generation", resourceGeneration ?: JSONObject.NULL)
    }.toString()

    companion object {
        private val TRAFFIC_KEY = ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID)

        fun noStudy() = HostHarnessAppliedProfile("NO_STUDY", "NONE", false, 0, 0, null, null, null, null, null)

        fun fromCommitted(
            document: RuntimeDocument,
            snapshot: RuntimeSnapshot,
            applied: AppliedResourceVector,
            admissionOpen: Boolean,
        ): HostHarnessAppliedProfile {
            val epoch = document.activeConditionEpoch
            val elapsed = document.clockCheckpoint?.activeRunningElapsedNanos ?: 0
            check(snapshot.initialized && snapshot.revision == document.revision && snapshot.state == document.state) {
                "Runtime snapshot and committed document differ"
            }
            check(snapshot.conditionEpochId == epoch?.id &&
                snapshot.appliedResourceVectorSha256 == epoch?.appliedResourceVectorSha256 &&
                snapshot.activeRunningElapsedNanos == elapsed) { "Runtime epoch or clock projection differs" }
            if (epoch != null) {
                check(epoch.configurationSha256 == document.configurationSha256 &&
                    epoch.appliedResourceVectorSha256 == applied.conditionDigest.value) {
                    "Committed epoch does not authenticate the applied vector"
                }
            }
            val traffic = applied.resources.singleOrNull { it.key == TRAFFIC_KEY }
            val verified = document.state == ExperimentState.RUNNING && epoch != null &&
                snapshot.admissionOpen && admissionOpen && traffic?.status == AppliedResourceStatus.APPLIED
            if (verified) {
                check(document.components.keys.none { it.kind == RuntimeComponentKind.RESOURCE_CLEANUP }) {
                    "Verified running state cannot retain attempted resource cleanup"
                }
            }
            val generation = traffic?.desiredGeneration?.value?.takeIf { verified }?.let {
                // JSONObject emits Number as an integer token; preserve the complete UInt64 domain.
                BigInteger(it.toString())
            }
            return HostHarnessAppliedProfile(
                status = if (verified) "VERIFIED" else "PENDING",
                state = document.state.name,
                admissionOpen = snapshot.admissionOpen && admissionOpen,
                revision = document.revision,
                // Committed study time, never extrapolated from the host wall clock.
                activeRunningElapsedMillis = elapsed / 1_000_000,
                conditionEpochId = epoch?.id?.value,
                appliedResourceVectorSha256 = epoch?.appliedResourceVectorSha256,
                profileId = traffic?.profileId.takeIf { verified },
                appliedProfileSha256 = traffic?.appliedProfileSha256?.value.takeIf { verified },
                resourceGeneration = generation,
            )
        }
    }
}

private class HostHarnessProvisionException(
    val stage: String,
    val resultCode: String,
) : IllegalStateException("Host-harness provisioning failed")
