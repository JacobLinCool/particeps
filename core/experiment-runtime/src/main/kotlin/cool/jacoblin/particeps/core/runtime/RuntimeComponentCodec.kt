package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Deterministic, exact component encoding. It is intentionally not a permissive JSON reader. */
internal object RuntimeComponentCodec {
    private const val ACTION_VERSION = 2
    private const val UPLOAD_ACKNOWLEDGEMENT_VERSION = 1
    private const val RESOURCE_VERSION = 1
    private const val RESOURCE_CLEANUP_VERSION = 1
    private const val TIMER_VERSION = 1

    fun encodeCheckpoint(checkpoint: AutomationCheckpoint): String = AutomationCheckpointCodec.encode(checkpoint)

    fun decodeCheckpoint(encoded: String): AutomationCheckpoint = AutomationCheckpointCodec.decode(encoded)

    fun encodeAction(action: DurableActionInvocation): String = encode(ACTION_PREFIX) {
        writeInt(ACTION_VERSION)
        writeString(action.actionId)
        writeString(action.automationId)
        writeString(action.interventionId)
        writeLong(action.causalSequence)
        writeNullableLong(action.logicalDeadlineUtcMillis)
        writeLong(action.expiresAtUtcMillis)
        writeString(action.conditionSha256)
        writeULong(action.generation)
        writeResearchTime(action.requestedAt)
        writeNullableResearchTime(action.openedAt)
        writeString(action.state.name)
        writeNullableString(action.failureReason)
    }

    fun decodeAction(encoded: String): DurableActionInvocation = decode(encoded, ACTION_PREFIX) {
        require(readInt() == ACTION_VERSION) { "Unsupported action component" }
        DurableActionInvocation(
            actionId = readString(),
            automationId = readString(),
            interventionId = readString(),
            causalSequence = readLong(),
            logicalDeadlineUtcMillis = readNullableLong(),
            expiresAtUtcMillis = readLong(),
            conditionSha256 = readString(),
            generation = readULong(),
            requestedAt = readResearchTime(),
            openedAt = readNullableResearchTime(),
            state = enumValueOf(readString()),
            failureReason = readNullableString(),
        )
    }

    fun encodeUploadAcknowledgement(acknowledgement: DurableUploadAcknowledgement): String =
        encode(UPLOAD_ACKNOWLEDGEMENT_PREFIX) {
            writeInt(UPLOAD_ACKNOWLEDGEMENT_VERSION)
            writeString(acknowledgement.bundleId)
            writeLong(acknowledgement.firstCommit)
            writeLong(acknowledgement.throughCommit)
            writeString(acknowledgement.bundleSha256)
            writeResearchTime(acknowledgement.acknowledgedAt)
        }

    fun decodeUploadAcknowledgement(encoded: String): DurableUploadAcknowledgement =
        decode(encoded, UPLOAD_ACKNOWLEDGEMENT_PREFIX) {
            require(readInt() == UPLOAD_ACKNOWLEDGEMENT_VERSION) {
                "Unsupported upload acknowledgement component"
            }
            DurableUploadAcknowledgement(
                bundleId = readString(),
                firstCommit = readLong(),
                throughCommit = readLong(),
                bundleSha256 = readString(),
                acknowledgedAt = readResearchTime(),
            )
        }

    fun encodeResource(resource: AppliedResourceState): String = encode(RESOURCE_PREFIX) {
        writeInt(RESOURCE_VERSION)
        writeResourceKey(resource.key)
        writeULong(resource.desiredGeneration.value)
        writeNullableString(resource.profileId)
        writeNullableString(resource.appliedProfileSha256?.value)
        writeString(resource.status.name)
        writeNullableString(resource.failureReason)
    }

    fun decodeResource(encoded: String): AppliedResourceState = decode(encoded, RESOURCE_PREFIX) {
        require(readInt() == RESOURCE_VERSION) { "Unsupported resource component" }
        AppliedResourceState(
            key = readResourceKey(),
            desiredGeneration = ResourceGeneration(readULong()),
            profileId = readNullableString(),
            appliedProfileSha256 = readNullableString()?.let(::Sha256Digest),
            status = enumValueOf<AppliedResourceStatus>(readString()),
            failureReason = readNullableString(),
        )
    }

    fun encodeResourceCleanup(cleanup: DurableResourceCleanup): String = encode(RESOURCE_CLEANUP_PREFIX) {
        writeInt(RESOURCE_CLEANUP_VERSION)
        writeResourceKey(cleanup.key)
        writeULong(cleanup.generation.value)
        writeString(cleanup.profileId)
        writeString(cleanup.expectedProfileSha256.value)
    }

    fun decodeResourceCleanup(encoded: String): DurableResourceCleanup = decode(encoded, RESOURCE_CLEANUP_PREFIX) {
        require(readInt() == RESOURCE_CLEANUP_VERSION) { "Unsupported resource cleanup component" }
        DurableResourceCleanup(
            key = readResourceKey(),
            generation = ResourceGeneration(readULong()),
            profileId = readString(),
            expectedProfileSha256 = Sha256Digest(readString()),
        )
    }

    fun encodeTimer(timer: DurableTimer): String = encode(TIMER_PREFIX) {
        writeInt(TIMER_VERSION)
        writeTimer(timer)
    }

    fun decodeTimer(encoded: String): DurableTimer = decode(encoded, TIMER_PREFIX) {
        require(readInt() == TIMER_VERSION) { "Unsupported timer component" }
        readTimer()
    }

    private fun encode(prefix: String, block: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output -> output.block() }
            buffer.toByteArray()
        }
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun <T> decode(encoded: String, prefix: String, block: DataInputStream.() -> T): T {
        require(encoded.startsWith(prefix)) { "Unexpected runtime component encoding" }
        val payload = Base64.getUrlDecoder().decode(encoded.removePrefix(prefix))
        require(payload.size <= MAX_COMPONENT_BYTES) { "Runtime component is too large" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val value = input.block()
            require(input.read() == -1) { "Trailing runtime component bytes" }
            value
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Runtime component string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid runtime component string size" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeResearchTime(value: ResearchTime) {
        writeLong(value.wallTimeUtcMillis)
        writeLong(value.elapsedRealtimeNanos)
        writeString(value.bootSessionId)
    }

    private fun DataInputStream.readResearchTime() = ResearchTime(readLong(), readLong(), readString())

    private fun DataOutputStream.writeNullableResearchTime(value: ResearchTime?) {
        writeBoolean(value != null)
        if (value != null) writeResearchTime(value)
    }

    private fun DataInputStream.readNullableResearchTime(): ResearchTime? =
        if (readBoolean()) readResearchTime() else null

    private fun DataOutputStream.writeULong(value: ULong) = writeString(value.toString())
    private fun DataInputStream.readULong(): ULong = readString().toULong()

    private fun DataOutputStream.writeResourceKey(key: ResourceKey) {
        writeString(key.kind.name)
        writeString(key.id)
    }

    private fun DataInputStream.readResourceKey(): ResourceKey = ResourceKey(enumValueOf(readString()), readString())

    private fun DataOutputStream.writeTimer(timer: DurableTimer) {
        writeString(timer.id)
        writeString(timer.automationId)
        writeULong(timer.generation)
        writeLong(timer.causalSequence)
        writeString(timer.producerKey)
        when (val target = timer.target) {
            is TimerTarget.CalendarUtc -> {
                writeByte(0)
                writeLong(target.utcMillis)
            }
            is TimerTarget.ActiveElapsed -> {
                writeByte(1)
                writeLong(target.elapsedNanos)
            }
            is TimerTarget.SameBootMonotonic -> {
                writeByte(2)
                writeString(target.bootSessionId)
                writeLong(target.elapsedRealtimeNanos)
            }
        }
        writeNullableLong(timer.logicalDeadlineUtcMillis)
        writeNullableLong(timer.expiresAtUtcMillis)
    }

    private fun DataInputStream.readTimer(): DurableTimer {
        val id = readString()
        val automationId = readString()
        val generation = readULong()
        val causalSequence = readLong()
        val producerKey = readString()
        val target = when (readUnsignedByte()) {
            0 -> TimerTarget.CalendarUtc(readLong())
            1 -> TimerTarget.ActiveElapsed(readLong())
            2 -> TimerTarget.SameBootMonotonic(readString(), readLong())
            else -> throw IllegalArgumentException("Unknown timer target component")
        }
        return DurableTimer(
            id,
            automationId,
            generation,
            causalSequence,
            producerKey,
            target,
            readNullableLong(),
            readNullableLong(),
        )
    }

    private const val ACTION_PREFIX = "action-invocation-v1:"
    private const val UPLOAD_ACKNOWLEDGEMENT_PREFIX = "upload-acknowledgement-v1:"
    private const val RESOURCE_PREFIX = "applied-resource-v1:"
    private const val RESOURCE_CLEANUP_PREFIX = "resource-cleanup-v1:"
    private const val TIMER_PREFIX = "durable-timer-v1:"
    private const val MAX_STRING_BYTES = 512 * 1_024
    private const val MAX_COMPONENT_BYTES = 512 * 1_024
}
