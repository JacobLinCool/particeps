package cool.jacoblin.particeps

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingCounterSnapshot
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
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.RuntimeResourceHost
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
                    (intent.getBooleanExtra(EXTRA_APPLIED_PROFILE, false) ||
                        intent.getBooleanExtra(EXTRA_NATIVE_COUNTERS, false))
                ) {
                    // One deadline covers initialization and both coordinator locks.
                    withTimeout(QUERY_TIMEOUT_MILLIS) {
                        application.session.snapshot.first { it.initialized }
                        HostHarnessAppliedProfileReader.read(
                            application.session,
                            includeNativeCounters = intent.getBooleanExtra(EXTRA_NATIVE_COUNTERS, false),
                        ).toJson()
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
        const val EXTRA_NATIVE_COUNTERS = "include_native_counters"
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
    suspend fun read(session: StudySessionManager, includeNativeCounters: Boolean = false): HostHarnessAppliedProfile {
        val sessionLock = field(session, "sessionMutex") as Mutex
        return sessionLock.withLock {
            val assembly = field(session, "assembly") as StudyRuntimeAssembly?
            if (assembly == null) {
                check(!includeNativeCounters) { "Native counters require a verified running study" }
                return@withLock HostHarnessAppliedProfile.noStudy()
            }
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
                val profile = HostHarnessAppliedProfile.fromCommitted(
                    document, runtime.snapshot.value, AppliedResourceVector(applied), admissionOpen(gate),
                )
                if (!includeNativeCounters) profile else {
                    check(profile.status == "VERIFIED") { "Native counters require a verified running epoch" }
                    val hosts = field(runtime, "hosts") as Map<*, *>
                    val key = ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID)
                    val host = hosts[key] as RuntimeResourceHost
                    check(host.key == key) { "Traffic host key differs from its runtime key" }
                    val actuator = host.actuator as TrafficShapingActuator
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    val healthBefore = actuator.health()
                    val vpnBefore = verifiedVpnGeneration(actuator)
                    val counters = actuator.snapshot()
                    val vpnAfter = verifiedVpnGeneration(actuator)
                    val healthAfter = actuator.health()
                    val completedAt = SystemClock.elapsedRealtimeNanos()
                    profile.copy(nativeCounters = HostHarnessNativeCounters.fromVerified(
                        profile, counters, healthBefore, healthAfter, vpnBefore, vpnAfter,
                        admissionOpen(gate), startedAt, completedAt,
                    ))
                }
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)

    private fun admissionOpen(gate: Any): Boolean = gate.javaClass.getDeclaredMethod("capture").apply {
        isAccessible = true
    }.invoke(gate) != null

    private fun verifiedVpnGeneration(actuator: TrafficShapingActuator): String? =
        synchronized(checkNotNull(field(actuator, "stateLock"))) {
            field(actuator, "verifiedVpnGenerationId") as String?
        }

    /** Pin the exact production seams in a JVM test; none of these names is discovered heuristically. */
    fun requireReflectionContract() {
        check(StudySessionManager::class.java.getDeclaredField("sessionMutex").type == Mutex::class.java)
        check(StudySessionManager::class.java.getDeclaredField("assembly").type == StudyRuntimeAssembly::class.java)
        check(ExperimentRuntime::class.java.getDeclaredField("mutex").type == Mutex::class.java)
        check(Map::class.java.isAssignableFrom(ExperimentRuntime::class.java.getDeclaredField("hosts").type))
        check(TrafficShapingActuator::class.java.getDeclaredField("verifiedVpnGenerationId").type == String::class.java)
        check(TrafficShapingActuator::class.java.getDeclaredField("stateLock").type == Any::class.java)
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
    val nativeCounters: HostHarnessNativeCounters? = null,
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
        nativeCounters?.let { put("native_counters", it.toJsonObject()) }
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

/**
 * The counters are live cumulative native observations, not durable usage records. The two
 * monotonic timestamps bracket the read because native packet counters advance independently.
 * Both health reads and the verified VPN identity must still match the committed epoch; a
 * service's retained terminal snapshot is never promoted to a successful live sample.
 */
internal data class HostHarnessNativeCounters(
    val sampleStartedElapsedRealtimeNanos: Long,
    val sampleCompletedElapsedRealtimeNanos: Long,
    val counters: TrafficShapingCounterSnapshot,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("sample_started_elapsed_realtime_nanos", sampleStartedElapsedRealtimeNanos)
        put("sample_completed_elapsed_realtime_nanos", sampleCompletedElapsedRealtimeNanos)
        put("native_generation", counters.nativeGeneration)
        put("vpn_generation_id", counters.vpnGenerationId)
        put("profile_sha256", counters.profileSha256.value)
        put("uplink_bytes", counters.uplinkBytes)
        put("uplink_packets", counters.uplinkPackets)
        put("downlink_bytes", counters.downlinkBytes)
        put("downlink_packets", counters.downlinkPackets)
        put("uplink_throttled_nanos", counters.uplinkThrottledNanos)
        put("downlink_throttled_nanos", counters.downlinkThrottledNanos)
    }

    companion object {
        fun fromVerified(
            profile: HostHarnessAppliedProfile,
            counters: TrafficShapingCounterSnapshot?,
            healthBefore: ResourceHealth,
            healthAfter: ResourceHealth,
            vpnBefore: String?,
            vpnAfter: String?,
            admissionOpenAfterSample: Boolean,
            startedAt: Long,
            completedAt: Long,
        ): HostHarnessNativeCounters {
            check(profile.status == "VERIFIED" && profile.state == "RUNNING" && profile.admissionOpen &&
                admissionOpenAfterSample) { "Native counter sample lost verified admission" }
            check(startedAt >= 0 && completedAt >= startedAt) { "Native sample clock is invalid" }
            val observed = checkNotNull(counters) { "Native counters are unavailable" }
            check(observed.profileSha256.value == profile.appliedProfileSha256) {
                "Native counter profile differs from the committed profile"
            }
            check(vpnBefore != null && vpnBefore == vpnAfter && observed.vpnGenerationId == vpnBefore) {
                "Native counters do not belong to the verified VPN generation"
            }
            for (health in listOf(healthBefore, healthAfter)) {
                check(health.key == ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID) &&
                    health.status == ResourceHealthStatus.APPLIED && health.failureReason == null &&
                    health.profileId == profile.profileId &&
                    health.appliedProfileSha256?.value == profile.appliedProfileSha256 &&
                    health.expectedProfileSha256?.value == profile.appliedProfileSha256 &&
                    health.generation?.value?.toString()?.let(::BigInteger) == profile.resourceGeneration) {
                    "Native sample resource health no longer matches the committed profile"
                }
            }
            return HostHarnessNativeCounters(startedAt, completedAt, observed)
        }
    }
}

private class HostHarnessProvisionException(
    val stage: String,
    val resultCode: String,
) : IllegalStateException("Host-harness provisioning failed")
