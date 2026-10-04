package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorFlushFailureReason
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorObservationMode
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.StudyResetMarker
import cool.jacoblin.particeps.core.model.StudyResetStore
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ResourceAuditReceipt
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailureListener
import cool.jacoblin.particeps.core.resource.ResumeReceipt
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import cool.jacoblin.particeps.core.runtime.ActionOutboxNotifier
import cool.jacoblin.particeps.core.runtime.RuntimeEntropyKind
import cool.jacoblin.particeps.core.runtime.RuntimeEntropySource
import cool.jacoblin.particeps.core.runtime.TimerWakeupAdapter
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Deterministic platform doubles for RealRuntimeBundleInteropTest. Everything behind them is
 * production code: StudySessionManager, ExperimentRuntime, the automation reducer, the registry
 * event contracts, and ResearchExport. The doubles stand only where Android would: clocks, the
 * durable store, collectors, the VPN actuator, WorkManager, and notifications.
 */

/**
 * Deterministic clocks. Each reading advances both clocks by 1 ms, as work takes time on a phone:
 * a collector started while a condition epoch is prepared therefore covers source time from a
 * little before the epoch activates. Otherwise time moves only when the scenario moves it. A reboot
 * starts a new boot session, and a wall-clock change moves only the wall clock.
 */
internal class InteropClocks(
    var wallMillis: Long,
    var elapsedNanos: Long,
    var bootSessionId: String,
) : ResearchClocks {
    override fun now(): ResearchTime = ResearchTime(wallMillis, elapsedNanos, bootSessionId).also { advanceMillis(1) }

    override fun trustedUtcMillis(): Long = wallMillis

    fun advanceMillis(millis: Long) {
        require(millis >= 0) { "Scenario clocks never move backwards" }
        wallMillis += millis
        elapsedNanos += millis * NANOS_PER_MILLI
    }

    fun advanceToWall(target: Long) = advanceMillis(target - wallMillis)

    /** The participant or the network changes the phone's clock; elapsed time is unaffected. */
    fun changeWallClock(deltaMillis: Long) {
        wallMillis += deltaMillis
    }

    fun reboot(downtimeMillis: Long, elapsedAfterBootNanos: Long, nextBootSessionId: String) {
        require(nextBootSessionId != bootSessionId) { "A reboot starts a new boot session" }
        wallMillis += downtimeMillis
        elapsedNanos = elapsedAfterBootNanos
        bootSessionId = nextBootSessionId
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Deterministic entropy whose counter, like the device's key store, survives process restarts. */
internal class InteropEntropy(private val deviceOrdinal: Int) : RuntimeEntropySource {
    private var counter = 0L

    override fun next(kind: RuntimeEntropyKind): String {
        val ordinal = counter++
        return when (kind) {
            RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID,
            RuntimeEntropyKind.CONDITION_EPOCH_UUID,
            -> "5c0e%02x%02x-0000-4000-8000-%012x".format(deviceOrdinal, kind.ordinal, ordinal)
            RuntimeEntropyKind.ACTIVITY_TOKEN_KEY -> "ESIzRFVmd4iZqrvM3e7_ABEiM0RVZneImaq7zN3u_wA"
        }
    }
}

/**
 * In-memory durable store. It survives a simulated process death or reboot, as the encrypted
 * store does, and checks every append as the encrypted store does: the successor must equal
 * runtime.advance(commit).
 *
 * It keeps the commit and document objects themselves: nothing round-trips through the storage
 * codec. `core/storage`'s `EngineDataJsonCodec` is an Android library that this JVM module cannot
 * load, and its instrumentation tests (`EngineDataJsonCodecTest`, `EncryptedExperimentStoreTest`)
 * cover persistence. A codec change that altered what a restarted process reads would therefore
 * not show in these exports; the frozen RC13 fixtures crossed their restarts through a copy of the
 * codec.
 */
internal class InteropStudyStore : StudyStore {
    private var runtime: RuntimeDocument? = null
    private var pending: PendingEngineInput? = null
    private var stopCommitsAfterPendingWrite = false
    private var commitsInterrupted = false
    val commits = mutableListOf<EngineCommit>()
    val document: RuntimeDocument? get() = runtime

    /** Crash boundary: accept the next durable pending batch, then refuse every commit write. */
    fun interruptCommitsAfterNextPendingWrite() {
        check(pending == null && !commitsInterrupted)
        stopCommitsAfterPendingWrite = true
    }

    fun reopenAfterProcessDeath() {
        check(commitsInterrupted && pending != null)
        commitsInterrupted = false
    }

    override suspend fun loadRuntime(observeRetained: (EngineCommit) -> Unit): RuntimeDocument? =
        runtime?.also { current ->
            commits.filter { it.commitSequence >= current.retainedFromCommit }.forEach(observeRetained)
        }

    override suspend fun initialize(runtime: RuntimeDocument) {
        check(this.runtime == null) { "Store is already initialized" }
        this.runtime = runtime
    }

    override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
        append(commit, successor)
    }

    override suspend fun stagePendingInput(input: PendingEngineInput) {
        check(pending == null) { "A pending input is already staged" }
        pending = input
        if (stopCommitsAfterPendingWrite) {
            stopCommitsAfterPendingWrite = false
            commitsInterrupted = true
        }
    }

    override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
        check(pending?.encodedSha256 == expectedSha256) { "Pending input digest mismatch" }
        pending = input
    }

    override suspend fun loadPendingInput(): PendingEngineInput? = pending

    override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
        val staged = checkNotNull(pending) { "No pending input to consume" }
        check(commit.consumedPendingInputSha256 == staged.encodedSha256) { "Pending input digest mismatch" }
        append(commit, successor)
        pending = null
    }

    private fun append(commit: EngineCommit, successor: RuntimeDocument) {
        if (commitsInterrupted) throw IOException("Simulated process death after durable pending write")
        val current = checkNotNull(runtime) { "Append before initialize" }
        check(current.advance(commit) == successor) { "Successor does not equal runtime.advance(commit)" }
        commits += commit
        runtime = successor
    }

    override suspend fun <T> withReadSnapshot(block: suspend (StudyReadSnapshot) -> T): T {
        val capturedRuntime = checkNotNull(runtime) { "Snapshot before initialize" }
        val capturedCommits = commits.toList()
        return block(
            object : StudyReadSnapshot {
                override val runtime = capturedRuntime

                override suspend fun readCommits(
                    fromCommitInclusive: Long,
                    throughCommitInclusive: Long,
                    consume: (EngineCommit) -> Boolean,
                ) {
                    val range = fromCommitInclusive..throughCommitInclusive
                    for (commit in capturedCommits) {
                        if (commit.commitSequence in range && !consume(commit)) break
                    }
                }
            },
        )
    }

    override suspend fun storageUsage(): StorageUsage = StorageUsage(1_000_000L, 8L * 1024 * 1024 * 1024)

    override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument = runtime

    override suspend fun clear() {
        runtime = null
        pending = null
        commits.clear()
    }
}

internal class InteropActiveStudyStore : ActiveStudyStore {
    private var record: ActiveStudyRecord? = null

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

internal class InteropResetStore : StudyResetStore {
    private var marker: StudyResetMarker? = null

    override suspend fun load(): StudyResetMarker? = marker

    override suspend fun mark(retainedEnvelopeBytes: ByteArray?) {
        marker = StudyResetMarker(retainedEnvelopeBytes?.copyOf())
    }

    override suspend fun clear() {
        marker = null
    }
}

internal object InteropGrantedAccess : StudyAccessGateway {
    override suspend fun inspect(request: AccessInspectionRequest): AccessSnapshot = AccessSnapshot(
        request.requirements.map { requirement -> AccessStatus(requirement, AccessResolution.Satisfied, null) },
    )
}

/** WorkManager stand-in: the scenario reads due timers from the runtime and fires them itself. */
internal object InteropTimerWakeups : TimerWakeupAdapter {
    override suspend fun schedule(timer: DurableTimer) = Unit

    override suspend fun retire(timerId: String, generation: ULong) = Unit
}

internal object InteropActionNotifier : ActionOutboxNotifier {
    override suspend fun onActionReady(actionId: String) = Unit

    override suspend fun onActionsInactive(actionIds: List<String>) = Unit
}

internal class InteropCollectorPlugin(id: String, accessKinds: Set<AccessKind>) : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id,
        id,
        requireNotNull(ProtocolEventSourceRegistry[id]) { "Unknown source $id" },
        accessKinds,
    )

    /** The collector of the current resource generation; the runtime creates one per generation. */
    var current: InteropCollector? = null
        private set

    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector =
        InteropCollector(descriptor.id, context).also { current = it }
}

/**
 * A scripted collector. A live source records exactly the batches the scenario offers. The
 * retrospective usage-events source follows UsageEventsCollector's protocol: a query window that
 * starts at collector start, coverage for every poll, and a barrier flush through the runtime's
 * exact boundary that returns the next query start as its cursor.
 */
internal class InteropCollector(
    private val sourceId: String,
    val context: CollectorContext,
) : Collector {
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    override val health: StateFlow<CollectorHealth> = mutableHealth.asStateFlow()
    private val retrospective = requireNotNull(ProtocolEventSourceRegistry[sourceId]).isRetrospective
    override val observationMode: CollectorObservationMode =
        if (retrospective) CollectorObservationMode.RETROSPECTIVE else CollectorObservationMode.LIVE
    override val requiresStop: Boolean = true
    private var producerOrdinal = 0L
    private var queryStartUtcMillis = 0L
    private val sourceHistory = mutableListOf<Pair<Long, EventDraft>>()

    override suspend fun start() {
        if (retrospective) queryStartUtcMillis = context.clocks.now().wallTimeUtcMillis
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
    }

    override suspend fun pause() {
        mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
    }

    override suspend fun resume() {
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
    }

    override suspend fun stop() {
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    /** Offers one live batch; a partially recorded batch is offered again as its remainder. */
    suspend fun emitLive(events: List<EventDraft>) {
        check(!retrospective) { "Live emission on a retrospective source" }
        var remaining = events
        while (remaining.isNotEmpty()) {
            val token = checkNotNull(context.eventSink.captureToken()) { "$sourceId admission is closed" }
            val result = context.eventSink.emitBatch(
                token,
                SourceEventBatch(EventSourceId(sourceId), 1, context.resourceGeneration, producerOrdinal, remaining),
            )
            check(result is EmitBatchResult.Accepted) { "$sourceId batch was not accepted: $result" }
            producerOrdinal++
            remaining = remaining.drop(result.recordedEvents)
        }
    }

    /** Adds a source-history record that a later poll or barrier flush queries. */
    fun addSourceHistory(timestampUtcMillis: Long, draft: EventDraft) {
        sourceHistory += timestampUtcMillis to draft
    }

    suspend fun poll() {
        check(collectThrough(context.clocks.now(), barrierFlush = false)) { "$sourceId poll was not accepted" }
    }

    override suspend fun flushThrough(boundary: ResearchTime, cursor: String?): CollectorFlushResult {
        if (!retrospective) return CollectorFlushResult.Complete(boundary, null)
        if (cursor != null && cursor.toLongOrNull()?.let { it <= queryStartUtcMillis } != true) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        if (boundary.wallTimeUtcMillis < queryStartUtcMillis) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        val completed = if (boundary.wallTimeUtcMillis == queryStartUtcMillis) {
            advanceEmptyCoverage(boundary)
        } else {
            collectThrough(boundary, barrierFlush = true)
        }
        return if (completed) {
            CollectorFlushResult.Complete(boundary, queryStartUtcMillis.toString())
        } else {
            CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_FAILURE)
        }
    }

    private suspend fun collectThrough(observed: ResearchTime, barrierFlush: Boolean): Boolean {
        check(retrospective) { "Poll on a live source" }
        val token = if (barrierFlush) {
            context.eventSink.captureBarrierFlushToken(observed)
        } else {
            context.eventSink.captureToken()
        } ?: return false
        val end = observed.wallTimeUtcMillis
        if (end <= queryStartUtcMillis) return false
        val drafts = sourceHistory
            .filter { (timestamp, _) -> timestamp >= queryStartUtcMillis && timestamp < end }
            .map { (_, draft) -> draft.copy(observedTime = observed) }
        val coverage = SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, queryStartUtcMillis.toString(), end.toString())
        val result = if (drafts.isEmpty()) {
            context.eventSink.advanceCoverage(
                token,
                CoverageAdvance(EventSourceId(sourceId), 1, context.resourceGeneration, producerOrdinal, coverage),
            )
        } else {
            context.eventSink.emitBatch(
                token,
                SourceEventBatch(
                    EventSourceId(sourceId),
                    1,
                    context.resourceGeneration,
                    producerOrdinal,
                    drafts,
                    coverage,
                ),
            )
        }
        if (result !is EmitBatchResult.Accepted) return false
        producerOrdinal++
        queryStartUtcMillis = end
        return true
    }

    private suspend fun advanceEmptyCoverage(boundary: ResearchTime): Boolean {
        val token = context.eventSink.captureBarrierFlushToken(boundary) ?: return false
        val coordinate = queryStartUtcMillis.toString()
        val result = context.eventSink.advanceCoverage(
            token,
            CoverageAdvance(
                EventSourceId(sourceId),
                1,
                context.resourceGeneration,
                producerOrdinal,
                SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, coordinate, coordinate),
            ),
        )
        if (result !is EmitBatchResult.Accepted) return false
        producerOrdinal++
        return true
    }
}

/**
 * VPN traffic-shaping double. Its audit events have exactly the fields TrafficShapingActuator.audit
 * writes, with cumulative counters and one VPN generation per resource generation. A new process
 * gets a new instance whose health is inactive, as after the VPN service dies with the process.
 */
internal class InteropTrafficActuator(
    override val key: ResourceKey,
    private val targetPackageListSha256: String,
    private val capsByProfile: Map<String, Pair<Long?, Long?>>,
) : StatefulResourceActuator, PeriodicResourceAuditSource {
    override val supportsHotProfileSwap = true
    override val sourceId = EventSourceId("traffic_shaping.v1")
    override val schemaVersion = 1
    override val intervalSeconds = 60L
    private var health = inactive()
    private var counterBase = 0L

    override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) = Unit

    override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
        health = desiredHealth(desired, ResourceHealthStatus.PREPARED, applied = false)
        return PrepareReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            null,
            requestId,
        )
    }

    override suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt {
        health = desiredHealth(desired, ResourceHealthStatus.SUSPENDED, applied = true)
        return SuspendReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
            boundary,
        )
    }

    override suspend fun flushThrough(
        desired: DesiredResourceState,
        boundary: ResearchTime,
        cursor: String?,
    ): FlushReceipt = FlushReceipt(
        key,
        desired.generation,
        desired.profile?.id,
        desired.profile?.expectedSha256,
        desired.profile?.expectedSha256,
        boundary,
        cursor,
        complete = true,
    )

    override suspend fun apply(desired: DesiredResourceState): ApplyReceipt {
        health = desiredHealth(desired, ResourceHealthStatus.APPLIED, applied = true)
        return ApplyReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
        )
    }

    override suspend fun verify(desired: DesiredResourceState): VerifyReceipt = VerifyReceipt(
        key,
        desired.generation,
        desired.profile?.id,
        desired.profile?.expectedSha256,
        desired.profile?.expectedSha256,
        healthy = true,
        failureReason = null,
    )

    override suspend fun resume(desired: DesiredResourceState): ResumeReceipt {
        health = desiredHealth(desired, ResourceHealthStatus.APPLIED, applied = true)
        return ResumeReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
            resumed = true,
            failureReason = null,
        )
    }

    override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth = health

    override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
        health = inactive()
        return ReleaseReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
            ReleaseEvidence.APPLIED,
            released = true,
        )
    }

    override fun health(): ResourceHealth = health

    override suspend fun audit(request: ResourceAuditRequest): ResourceAuditReceipt {
        val evidence = request.evidence
        val vpnGenerationId = "7a0f0000-0000-4000-8000-%012x".format(evidence.generation.value.toLong())
        counterBase += 1_000
        val counters = mapOf(
            "downlink_bytes" to (counterBase * 20).toString(),
            "downlink_packets" to (counterBase / 50).toString(),
            "downlink_throttled_nanoseconds" to (counterBase * 7).toString(),
            "uplink_bytes" to (counterBase * 10).toString(),
            "uplink_packets" to (counterBase / 100).toString(),
            "uplink_throttled_nanoseconds" to (counterBase * 3).toString(),
        )
        val common = mapOf(
            "condition_epoch_id" to request.conditionEpochId.value,
            "profile_id" to evidence.profileId,
            "resource_generation" to evidence.generation.toString(),
            "vpn_generation_id" to vpnGenerationId,
        )
        val drafts = when (request) {
            is ResourceAuditRequest.EpochActivated -> {
                val (uplink, downlink) = capsByProfile[evidence.profileId] ?: (null to null)
                listOf(
                    EventDraft(
                        EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_PROFILE_APPLIED"),
                        request.observedAt,
                        buildMap {
                            put("activation_research_time", request.activatedAt.json())
                            put("applied_profile_sha256", evidence.appliedProfileSha256.value)
                            put("condition_epoch_id", request.conditionEpochId.value)
                            downlink?.let { put("downlink_kbps", it.toString()) }
                            put("profile_id", evidence.profileId)
                            put("resource_generation", evidence.generation.toString())
                            put("signed_configuration_sha256", request.signedConfigurationSha256.value)
                            put("target_package_list_sha256", targetPackageListSha256)
                            uplink?.let { put("uplink_kbps", it.toString()) }
                            put("verification_completed_research_time", request.observedAt.json())
                            put("vpn_generation_id", vpnGenerationId)
                        },
                    ),
                )
            }
            is ResourceAuditRequest.Periodic -> listOf(
                snapshot(request, common + counters, "PERIODIC", request.logicalDeadline),
            )
            is ResourceAuditRequest.EpochBoundary -> listOf(
                snapshot(request, common + counters, "EPOCH_BOUNDARY", request.boundary),
                EventDraft(
                    EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_PROFILE_REMOVED"),
                    request.observedAt,
                    common + counters + mapOf(
                        "boundary_research_time" to request.boundary.json(),
                        "removal_reason" to request.reason.name,
                    ),
                ),
            )
        }
        return ResourceAuditReceipt(evidence, drafts)
    }

    private fun snapshot(
        request: ResourceAuditRequest,
        fields: Map<String, String>,
        reason: String,
        logicalDeadline: ResearchTime,
    ) = EventDraft(
        EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_SNAPSHOT"),
        request.observedAt,
        fields + mapOf(
            "logical_deadline_research_time" to logicalDeadline.json(),
            "observation_research_time" to request.observedAt.json(),
            "snapshot_reason" to reason,
        ),
    )

    private fun inactive() = ResourceHealth(key, ResourceHealthStatus.INACTIVE, null, null, null, null, null)

    private fun desiredHealth(
        desired: DesiredResourceState,
        status: ResourceHealthStatus,
        applied: Boolean,
    ) = ResourceHealth(
        key = desired.key,
        status = status,
        generation = desired.generation,
        profileId = desired.profile?.id,
        expectedProfileSha256 = desired.profile?.expectedSha256,
        appliedProfileSha256 = desired.profile?.expectedSha256.takeIf { applied },
        failureReason = null,
    )
}

private fun ResearchTime.json() =
    "{\"boot_session_id\":\"$bootSessionId\",\"monotonic_time_nanos\":\"$elapsedRealtimeNanos\"," +
        "\"wall_time_utc_millis\":\"$wallTimeUtcMillis\"}"

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
