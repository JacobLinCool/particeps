package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ResumeReceipt
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailureListener
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A process-local indirection that lets collector plugins be assembled before their one runtime
 * exists. An unbound sink is closed: it cannot mint an admission token or accept source data.
 */
class BindableEventSink : EventSink {
    private val delegate = AtomicReference<EventSink?>()

    fun bind(sink: EventSink) {
        check(delegate.compareAndSet(null, sink) || delegate.get() === sink) {
            "Event sink is already bound to another runtime"
        }
    }

    fun unbind(sink: EventSink) {
        delegate.compareAndSet(sink, null)
    }

    override fun captureToken(): AdmissionToken? = delegate.get()?.captureToken()

    override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? =
        delegate.get()?.captureBarrierFlushToken(boundary)

    override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult =
        delegate.get()?.emitBatch(token, batch) ?: EmitBatchResult.RejectedByAdmissionGate

    override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult =
        delegate.get()?.advanceCoverage(token, advance) ?: EmitBatchResult.RejectedByAdmissionGate
}

/**
 * Owns the runtime-generated study key while exposing only domain-separated opaque tokens.
 * Collectors never receive, persist, or export the raw key.
 */
class BindableStudyScopedTokenEncoder : StudyScopedTokenEncoder {
    private val key = AtomicReference<ByteArray?>()

    @Synchronized
    fun bindBase64Url(encodedKey: String) {
        val decoded = runCatching { Base64.getUrlDecoder().decode(encodedKey) }
            .getOrElse { throw IllegalArgumentException("Invalid activity-token key", it) }
        require(decoded.size == KEY_BYTES && CANONICAL_KEY.matches(encodedKey)) {
            "Invalid activity-token key"
        }
        val existing = key.get()
        if (existing != null) {
            check(existing.contentEquals(decoded)) { "Token encoder is already bound to another study" }
            decoded.fill(0)
            return
        }
        check(key.compareAndSet(null, decoded)) { "Token encoder binding raced" }
    }

    @Synchronized
    fun clear() {
        key.getAndSet(null)?.fill(0)
    }

    @Synchronized
    override fun encode(domain: String, value: String): String {
        require(DOMAIN.matches(domain)) { "Invalid token domain" }
        require(value.isNotEmpty()) { "Token value must not be empty" }
        val activeKey = checkNotNull(key.get()) { "Token encoder is not bound" }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(activeKey, HMAC_SHA256))
        mac.update(domain.toByteArray(Charsets.UTF_8))
        mac.update(DOMAIN_SEPARATOR)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        const val KEY_BYTES = 32
        const val HMAC_SHA256 = "HmacSHA256"
        const val DOMAIN_SEPARATOR: Byte = 0
        val CANONICAL_KEY = Regex("[A-Za-z0-9_-]{43}")
        val DOMAIN = Regex("[a-z][a-z0-9.-]{2,127}")
    }
}

/**
 * Adapts one signed collector resource to the generic durable resource protocol.
 *
 * A profile/generation pair is immutable. Global barriers may suspend every collector even when
 * this particular resource is unchanged; [apply] therefore preserves the existing instance for
 * an identical pair and [resume] reopens it without manufacturing a new source generation.
 */
class CollectorResourceActuator(
    private val declaration: CollectorResourceConfiguration,
    private val plugin: CollectorPlugin,
    private val scope: CoroutineScope,
    private val eventSink: EventSink,
    private val clocks: ResearchClocks,
    private val tokenEncoder: StudyScopedTokenEncoder,
) : StatefulResourceActuator {
    override val key = declaration.resourceKey
    override val supportsHotProfileSwap: Boolean = false

    private val profiles = declaration.profiles.associateBy(NamedCollectorProfile::id)
    private val mutex = Mutex()
    @Volatile private var listener: ResourceTerminalFailureListener? = null
    @Volatile private var active: ActiveCollector? = null
    private var preparedOperation: PreparedOperation? = null
    private var cleanupPending: DesiredResourceState? = null
    private var lastReleaseReceipt: ReleaseReceipt? = null
    @Volatile private var terminalGeneration: ResourceGeneration? = null

    init {
        require(plugin.descriptor.id == declaration.id) { "Collector plugin does not match its resource" }
    }

    override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) {
        this.listener = listener
    }

    override suspend fun prepare(
        desired: DesiredResourceState,
        requestId: String,
    ): PrepareReceipt = mutex.withLock {
        val profile = requireDesiredProfile(desired)
        check(cleanupPending == null) { "Failed collector activation must be released before prepare" }
        active?.let { current ->
            require(desired.generation >= current.generation) { "Stale collector prepare generation" }
            if (desired.generation == current.generation) {
                require(current.profile.asSignedProfile() == profile) {
                    "A collector generation cannot identify two profiles"
                }
            }
        }
        val operation = PreparedOperation(desired, requestId)
        val previous = preparedOperation
        check(previous == null || previous == operation) { "Another collector operation is already prepared" }
        preparedOperation = operation
        lastReleaseReceipt = null
        PrepareReceipt(
            key = key,
            generation = desired.generation,
            profileId = profile.id,
            expectedProfileSha256 = profile.expectedSha256,
            appliedProfileSha256 = null,
            requestId = requestId,
        )
    }

    override suspend fun suspendAt(
        desired: DesiredResourceState,
        boundary: ResearchTime,
    ): SuspendReceipt = mutex.withLock {
        val current = requireCurrentMatches(desired)
        if (current != null && !current.suspended) {
            current.suppressTerminal = true
            try {
                current.collector.pause()
                current.suspended = true
            } finally {
                current.suppressTerminal = false
            }
        }
        SuspendReceipt(
            key = key,
            generation = desired.generation,
            profileId = desired.profile?.id,
            expectedProfileSha256 = desired.profile?.expectedSha256,
            appliedProfileSha256 = desired.profile?.expectedSha256,
            boundary = boundary,
        )
    }

    override suspend fun flushThrough(
        desired: DesiredResourceState,
        boundary: ResearchTime,
        cursor: String?,
    ): FlushReceipt = mutex.withLock {
        val current = requireCurrentMatches(desired)
        if (current == null) {
            return@withLock FlushReceipt(
                key = key,
                generation = desired.generation,
                profileId = null,
                expectedProfileSha256 = null,
                appliedProfileSha256 = null,
                boundary = boundary,
                cursor = cursor,
                complete = true,
            )
        }
        when (val result = current.collector.flushThrough(boundary, cursor)) {
            is CollectorFlushResult.Complete -> FlushReceipt(
                key = key,
                generation = current.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = desired.profile?.expectedSha256,
                boundary = result.boundary,
                cursor = result.cursor,
                complete = true,
            )
            is CollectorFlushResult.Failed -> FlushReceipt(
                key = key,
                generation = current.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = desired.profile?.expectedSha256,
                boundary = boundary,
                cursor = cursor,
                complete = false,
            )
        }
    }

    override suspend fun apply(desired: DesiredResourceState): ApplyReceipt = mutex.withLock {
        require(desired.key == key) { "Desired collector resource mismatch" }
        val profile = requireNotNull(desired.profile) { "Collector apply requires an active profile" }
        val named = requireNotNull(profiles[profile.id]) { "Unknown collector profile" }
        require(named.asSignedProfile() == profile) { "Signed collector profile mismatch" }
        val prepared = checkNotNull(preparedOperation) { "Collector apply was not prepared" }
        require(prepared.desired == desired) { "Collector apply does not match its prepared state" }

        val current = active
        if (current != null && current.generation == desired.generation) {
            require(current.profile.id == named.id) { "A generation cannot change collector profile" }
            preparedOperation = null
            return@withLock applyReceipt(desired)
        }
        if (current != null) {
            require(desired.generation > current.generation) { "Stale collector generation" }
            stopCurrent(current)
        }

        val generation = desired.generation.value
        require(generation <= Long.MAX_VALUE.toULong()) { "Collector generation exceeds Protocol v1" }
        val collector = plugin.create(
            named.configuration,
            CollectorContext(
                scope = scope,
                eventSink = eventSink,
                clocks = clocks,
                sourceContract = plugin.descriptor.sourceContract,
                resourceGeneration = generation.toLong(),
                tokenEncoder = tokenEncoder,
            ),
        )
        val created = ActiveCollector(desired.generation, named, collector)
        active = created
        terminalGeneration = null
        try {
            collector.start()
            created.applied = true
            cleanupPending = null
            created.healthJob = observeHealth(created)
        } catch (failure: Throwable) {
            created.suppressTerminal = true
            val cleanupFailure = runCatching { if (collector.requiresStop) collector.stop() }.exceptionOrNull()
            created.suppressTerminal = false
            created.healthJob?.cancel()
            if (cleanupFailure == null) {
                active = null
                cleanupPending = desired
            } else {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            preparedOperation = null
        }
        applyReceipt(desired)
    }

    override suspend fun verify(desired: DesiredResourceState): VerifyReceipt = mutex.withLock {
        require(desired.key == key) { "Desired collector resource mismatch" }
        val current = active
        val expected = desired.profile
        val matches = current != null &&
            expected != null &&
            current.generation == desired.generation &&
            current.profile.id == expected.id &&
            current.profile.asSignedProfile() == expected &&
            current.collector.health.value.status in VERIFIED_COLLECTOR_STATES
        VerifyReceipt(
            key = key,
            generation = desired.generation,
            profileId = expected?.id,
            expectedProfileSha256 = expected?.expectedSha256,
            appliedProfileSha256 = expected?.expectedSha256.takeIf { matches },
            healthy = matches,
            failureReason = if (matches) null else "COLLECTOR_VERIFICATION_FAILED",
        )
    }

    override suspend fun resume(desired: DesiredResourceState): ResumeReceipt = mutex.withLock {
        val current = requireNotNull(requireCurrentMatches(desired)) {
            "Cannot resume an inactive collector"
        }
        try {
            current.suppressTerminal = true
            if (current.suspended) {
                current.collector.resume()
                current.suspended = false
            }
            current.suppressTerminal = false
            ResumeReceipt(
                key = key,
                generation = current.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = desired.profile?.expectedSha256,
                resumed = true,
                failureReason = null,
            )
        } catch (_: Throwable) {
            current.suppressTerminal = false
            notifyTerminal(current, "COLLECTOR_RESUME_FAILED")
            ResumeReceipt(
                key = key,
                generation = current.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = desired.profile?.expectedSha256,
                resumed = false,
                failureReason = "COLLECTOR_RESUME_FAILED",
            )
        }
    }

    override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth = mutex.withLock {
        val current = requireNotNull(requireCurrentMatches(desired)) {
            "Cannot open admission for an inactive collector"
        }
        try {
            current.suppressTerminal = true
            require(!current.suspended) { "Cannot open admission for a suspended collector" }
            current.collector.onAdmissionOpened()
            current.suppressTerminal = false
            currentHealth(current)
        } catch (failure: Throwable) {
            current.suppressTerminal = false
            notifyTerminal(current, "COLLECTOR_ADMISSION_OPEN_FAILED")
            throw failure
        }
    }

    override suspend fun release(desired: DesiredResourceState): ReleaseReceipt = mutex.withLock {
        require(desired.key == key) { "Desired collector resource mismatch" }
        lastReleaseReceipt?.takeIf { receipt ->
            active == null && cleanupPending == null && preparedOperation == null && receipt.matches(desired)
        }?.let { return@withLock it }
        val current = active
        val evidence = when {
            current != null && current.matches(desired) -> {
                val next = if (current.applied) ReleaseEvidence.APPLIED else ReleaseEvidence.NOT_APPLIED
                stopCurrent(current)
                next
            }
            cleanupPending == desired || preparedOperation?.desired == desired -> {
                current?.let { stopCurrent(it) }
                ReleaseEvidence.NOT_APPLIED
            }
            current != null -> {
                val profile = requireDesiredProfile(desired)
                require(current.generation == desired.generation) { "Stale collector release generation" }
                require(current.profile.asSignedProfile() == profile) { "Collector release profile mismatch" }
                val next = if (current.applied) ReleaseEvidence.APPLIED else ReleaseEvidence.NOT_APPLIED
                stopCurrent(current)
                next
            }
            cleanupPending != null -> throw IllegalArgumentException("Collector cleanup release state mismatch")
            preparedOperation != null -> throw IllegalArgumentException("Collector prepared release state mismatch")
            else -> {
                require(desired.profile == null) { "Collector is inactive for the release state" }
                ReleaseEvidence.INACTIVE
            }
        }
        preparedOperation = null
        cleanupPending = null
        terminalGeneration = null
        ReleaseReceipt(
            key = key,
            generation = desired.generation,
            profileId = desired.profile?.id,
            expectedProfileSha256 = desired.profile?.expectedSha256,
            appliedProfileSha256 = desired.profile?.expectedSha256.takeIf {
                evidence == ReleaseEvidence.APPLIED
            },
            evidence = evidence,
            released = true,
        ).also { lastReleaseReceipt = it }
    }

    override fun health(): ResourceHealth {
        val current = active ?: return ResourceHealth(
            key = key,
            status = ResourceHealthStatus.INACTIVE,
            generation = null,
            profileId = null,
            expectedProfileSha256 = null,
            appliedProfileSha256 = null,
            failureReason = null,
        )
        return currentHealth(current)
    }

    private fun applyReceipt(desired: DesiredResourceState) = ApplyReceipt(
        key = key,
        generation = desired.generation,
        profileId = desired.profile?.id,
        expectedProfileSha256 = desired.profile?.expectedSha256,
        appliedProfileSha256 = desired.profile?.expectedSha256,
    )

    private fun requireDesiredProfile(desired: DesiredResourceState): SignedResourceProfile {
        require(desired.key == key) { "Desired collector resource mismatch" }
        val profile = requireNotNull(desired.profile) { "Collector operation requires an active profile" }
        val named = requireNotNull(profiles[profile.id]) { "Unknown collector profile" }
        require(named.asSignedProfile() == profile) { "Signed collector profile mismatch" }
        return profile
    }

    private fun requireCurrentMatches(desired: DesiredResourceState): ActiveCollector? {
        require(desired.key == key) { "Desired collector resource mismatch" }
        val current = active
        if (current == null) {
            require(desired.profile == null) { "Collector is inactive for the requested state" }
            return null
        }
        val profile = requireDesiredProfile(desired)
        require(current.applied) { "Collector profile was never applied" }
        require(current.generation == desired.generation) { "Stale collector generation" }
        require(current.profile.asSignedProfile() == profile) { "Collector profile or digest mismatch" }
        return current
    }

    private fun ReleaseReceipt.matches(desired: DesiredResourceState): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256

    private fun ActiveCollector.matches(desired: DesiredResourceState): Boolean =
        desired.key == key &&
            generation == desired.generation &&
            desired.profile != null &&
            profile.asSignedProfile() == desired.profile

    private suspend fun stopCurrent(current: ActiveCollector) {
        current.suppressTerminal = true
        try {
            current.collector.stop()
            current.healthJob?.cancel()
            current.healthJob = null
            if (active === current) active = null
        } finally {
            current.suppressTerminal = false
        }
    }

    private fun observeHealth(current: ActiveCollector): Job = scope.launch {
        current.collector.health.collect { health ->
            when (health.status) {
                CollectorStatus.BLOCKED_ACCESS -> notifyTerminal(current, "COLLECTOR_ACCESS_LOST")
                CollectorStatus.FAILED -> notifyTerminal(current, "COLLECTOR_FAILED")
                CollectorStatus.STOPPED,
                CollectorStatus.ACTIVE,
                CollectorStatus.PAUSED,
                -> Unit
            }
        }
    }

    @Synchronized
    private fun notifyTerminal(current: ActiveCollector, reason: String) {
        if (current.suppressTerminal || active !== current || terminalGeneration == current.generation) return
        terminalGeneration = current.generation
        listener?.onTerminalFailure(ResourceTerminalFailure(key, current.generation, reason))
    }

    private fun currentHealth(current: ActiveCollector): ResourceHealth = if (!current.applied) {
        failedHealth(current, "COLLECTOR_START_FAILED")
    } else {
        when (current.collector.health.value.status) {
            CollectorStatus.ACTIVE -> ResourceHealth(
                key = key,
                status = ResourceHealthStatus.APPLIED,
                generation = current.generation,
                profileId = current.profile.id,
                expectedProfileSha256 = current.profile.asSignedProfile().expectedSha256,
                appliedProfileSha256 = current.profile.asSignedProfile().expectedSha256,
                failureReason = null,
            )
            CollectorStatus.PAUSED -> ResourceHealth(
                key = key,
                status = ResourceHealthStatus.SUSPENDED,
                generation = current.generation,
                profileId = current.profile.id,
                expectedProfileSha256 = current.profile.asSignedProfile().expectedSha256,
                appliedProfileSha256 = current.profile.asSignedProfile().expectedSha256,
                failureReason = null,
            )
            CollectorStatus.STOPPED -> failedHealth(current, "COLLECTOR_STOPPED_UNEXPECTEDLY")
            CollectorStatus.BLOCKED_ACCESS -> failedHealth(current, "COLLECTOR_ACCESS_LOST")
            CollectorStatus.FAILED -> failedHealth(current, "COLLECTOR_FAILED")
        }
    }

    private fun failedHealth(current: ActiveCollector, reason: String) = ResourceHealth(
        key = key,
        status = ResourceHealthStatus.FAILED,
        generation = current.generation,
        profileId = current.profile.id,
        expectedProfileSha256 = current.profile.asSignedProfile().expectedSha256,
        appliedProfileSha256 = null,
        failureReason = reason,
    )

    private data class PreparedOperation(
        val desired: DesiredResourceState,
        val requestId: String,
    )

    private data class ActiveCollector(
        val generation: ResourceGeneration,
        val profile: NamedCollectorProfile,
        val collector: Collector,
        @Volatile var applied: Boolean = false,
        @Volatile var suspended: Boolean = false,
        @Volatile var suppressTerminal: Boolean = false,
        var healthJob: Job? = null,
    )

    private companion object {
        val VERIFIED_COLLECTOR_STATES = setOf(CollectorStatus.ACTIVE, CollectorStatus.PAUSED)
    }
}
