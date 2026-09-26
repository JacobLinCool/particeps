package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.ReductionResult
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.inKeyOrder
import cool.jacoblin.particeps.core.model.toLowerHex
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun digest(vararg components: String): String {
    val output = components.joinToString("\u0000").toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(output).toLowerHex()
}

internal fun submissionDigest(submission: SourceSubmission, epochId: ConditionEpochId): String {
    val sha256 = MessageDigest.getInstance("SHA-256")
    DigestSink(sha256).use { sink ->
        DataOutputStream(sink).use { output ->
            output.writeCanonicalString("particeps-source-observation-v1")
            output.writeCanonicalString(submission.sourceId.value)
            output.writeInt(submission.schemaVersion)
            output.writeLong(submission.resourceGeneration)
            output.writeLong(submission.producerOrdinal)
            output.writeCanonicalString(epochId.value)
            output.writeBoolean(submission.coverage != null)
            submission.coverage?.let { coverage ->
                output.writeCanonicalString(coverage.clockBasis.name)
                output.writeCanonicalString(coverage.startInclusive)
                output.writeCanonicalString(coverage.endExclusive)
            }
            output.writeInt(submission.events.size)
            submission.events.forEach { event ->
                output.writeCanonicalString(event.type.eventType)
                output.writeLong(event.observedTime.wallTimeUtcMillis)
                output.writeLong(event.observedTime.elapsedRealtimeNanos)
                output.writeCanonicalString(event.observedTime.bootSessionId)
                val fields = event.fields.inKeyOrder()
                output.writeInt(fields.size)
                fields.forEach { (key, value) ->
                    output.writeCanonicalString(key)
                    output.writeCanonicalString(value)
                }
            }
        }
    }
    return sha256.digest().toLowerHex()
}

internal fun DataOutputStream.writeCanonicalString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

/**
 * Feeds a `DataOutputStream` preimage straight into [sha256] through a small unsynchronized buffer,
 * the same bytes a `ByteArrayOutputStream` would have collected for one `digest` call.
 */
internal class DigestSink(private val sha256: MessageDigest) : OutputStream() {
    private val buffer = ByteArray(8 * 1_024)
    private var buffered = 0

    override fun write(b: Int) {
        if (buffered == buffer.size) drain()
        buffer[buffered++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > buffer.size - buffered) {
            drain()
            if (len >= buffer.size) {
                sha256.update(b, off, len)
                return
            }
        }
        b.copyInto(buffer, buffered, off, off + len)
        buffered += len
    }

    override fun flush() = drain()

    override fun close() = drain()

    private fun drain() {
        sha256.update(buffer, 0, buffered)
        buffered = 0
    }
}

internal fun upsertResource(resource: AppliedResourceState) = RuntimeMutation(
    resourceComponentKey(resource.key),
    RuntimeMutationOperation.UPSERT,
    RuntimeComponentCodec.encodeResource(resource),
)

internal fun upsertResourceCleanup(cleanup: DurableResourceCleanup) = RuntimeMutation(
    resourceCleanupComponentKey(cleanup.key),
    RuntimeMutationOperation.UPSERT,
    RuntimeComponentCodec.encodeResourceCleanup(cleanup),
)

internal fun removeResourceCleanup(key: ResourceKey) = RuntimeMutation(
    resourceCleanupComponentKey(key),
    RuntimeMutationOperation.REMOVE,
    null,
)

internal fun upsertAction(action: DurableActionInvocation) = RuntimeMutation(
    RuntimeComponentKey(RuntimeComponentKind.ACTION_INVOCATION, action.actionId),
    RuntimeMutationOperation.UPSERT,
    RuntimeComponentCodec.encodeAction(action),
)

internal fun upsertUploadAcknowledgement(acknowledgement: DurableUploadAcknowledgement) = RuntimeMutation(
    RuntimeComponentKey(RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT, "latest"),
    RuntimeMutationOperation.UPSERT,
    RuntimeComponentCodec.encodeUploadAcknowledgement(acknowledgement),
)

internal fun upsertResourceAuditTimer(timer: DurableTimer) = RuntimeMutation(
    RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, timer.id),
    RuntimeMutationOperation.UPSERT,
    RuntimeComponentCodec.encodeTimer(timer),
)

internal fun removeResourceAuditTimer(timerId: String) = RuntimeMutation(
    RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, timerId),
    RuntimeMutationOperation.REMOVE,
    null,
)

internal fun resourceComponentKey(key: ResourceKey) = RuntimeComponentKey(
    RuntimeComponentKind.RESOURCE,
    "${key.kind.name.lowercase()}:${key.id}",
)

internal fun resourceCleanupComponentKey(key: ResourceKey) = RuntimeComponentKey(
    RuntimeComponentKind.RESOURCE_CLEANUP,
    "${key.kind.name.lowercase()}:${key.id}",
)

internal fun cleanupResourceKey(id: String): ResourceKey {
    val separator = id.indexOf(':')
    require(separator > 0 && separator < id.lastIndex) { "Invalid cleanup resource component ID" }
    return ResourceKey(
        enumValueOf<ResourceKind>(id.substring(0, separator).uppercase()),
        id.substring(separator + 1),
    )
}

/**
 * The reducer [intents] one commit records as timer events and hands to WorkManager: those that
 * are part of the net change from [before] to [after], in their original order, each once. A
 * retirement is kept when [before] holds that timer at that generation and [after] no longer
 * holds it unchanged; a schedule is kept when [after] holds exactly that timer and [before] did
 * not. For each input the runtime submits alone, the reducer retires only the timer [before] holds
 * and schedules only the timer [after] holds, at most once each, so every intent is kept; the
 * runtime drops a stale timer wake before reducing it. A multi-input reduction also names each
 * generation it armed and then retired or replaced, such as a window that slides once per merged
 * event, and those intents are dropped: the base generation retires once and only the resulting
 * generation is scheduled.
 */
internal fun committedTimerIntents(
    before: Map<String, DurableTimer>,
    after: Map<String, DurableTimer>,
    intents: List<TimerIntent>,
): List<TimerIntent> = intents.filter { intent ->
    when (intent) {
        is TimerIntent.Retire -> before[intent.timerId].let { prior ->
            prior != null && prior.generation == intent.generation && after[intent.timerId] != prior
        }
        is TimerIntent.Schedule -> after[intent.timer.id] == intent.timer && before[intent.timer.id] != intent.timer
    }
}.distinct()

internal fun timerMutations(
    before: Map<String, DurableTimer>,
    after: Map<String, DurableTimer>,
): List<RuntimeMutation> = buildList {
    (before.keys - after.keys).sorted().forEach { timerId ->
        add(RuntimeMutation(RuntimeComponentKey(RuntimeComponentKind.TIMER, timerId), RuntimeMutationOperation.REMOVE, null))
    }
    after.toSortedMap().forEach { (timerId, timer) ->
        if (before[timerId] != timer) {
            add(
                RuntimeMutation(
                    RuntimeComponentKey(RuntimeComponentKind.TIMER, timerId),
                    RuntimeMutationOperation.UPSERT,
                    RuntimeComponentCodec.encodeTimer(timer),
                ),
            )
        }
    }
}

internal fun checkpointMutations(
    current: RuntimeDocument,
    checkpoint: AutomationCheckpoint,
): List<RuntimeMutation> {
    val encoded = RuntimeComponentCodec.encodeCheckpoint(checkpoint)
    val parts = encoded.chunked(MAX_COMPONENT_CHARS)
    val desiredKeys = parts.indices.map { index ->
        RuntimeComponentKey(
            RuntimeComponentKind.AUTOMATION_CHECKPOINT,
            if (index == 0) "main" else "main/${index.toString().padStart(4, '0')}",
        )
    }
    val existingKeys = current.components.keys.filter {
        it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main")
    }
    return buildList {
        parts.forEachIndexed { index, part ->
            add(RuntimeMutation(desiredKeys[index], RuntimeMutationOperation.UPSERT, part))
        }
        (existingKeys - desiredKeys.toSet()).sorted().forEach { stale ->
            add(RuntimeMutation(stale, RuntimeMutationOperation.REMOVE, null))
        }
    }
}

internal fun emptyReduction(checkpoint: AutomationCheckpoint) = ReductionResult(
    checkpoint,
    emptyList(),
    emptyList(),
    emptyList(),
    emptyMap(),
    emptyList(),
)
