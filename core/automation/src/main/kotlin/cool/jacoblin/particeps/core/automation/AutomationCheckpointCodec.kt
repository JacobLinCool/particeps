package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.Base64

/** Exact public wire codec for the reducer checkpoint persisted in EngineCommit mutations. */
object AutomationCheckpointCodec {
    private const val VERSION = 1
    private const val PREFIX = "automation-checkpoint-v1:"

    fun encode(checkpoint: AutomationCheckpoint): String = encodePayload {
        writeInt(VERSION)
        writeLong(checkpoint.evaluatedThroughSequence)
        writeString(checkpoint.lifecycle.name)
        writeNullableLong(checkpoint.studyStartUtcMillis)
        writeLong(checkpoint.lastActiveElapsedNanos)
        writeLong(checkpoint.lastCalendarElapsedNanos)
        writeMap(checkpoint.latchValues.toSortedMap(), { writeString(it) }) { writeBoolean(it) }
        writeMap(checkpoint.presenceKeys.toSortedMap(), { writeString(it) }) { values ->
            writeList(values.sorted()) { writeString(it) }
        }
        writeMap(checkpoint.heldSinceNanos.toSortedMap(), { writeString(it) }) { writeLong(it) }
        writeMap(checkpoint.priorConditionValues.toSortedMap(), { writeString(it) }) { writeBoolean(it) }
        writeMap(checkpoint.windows.toSortedMap(), { writeString(it) }) { entries ->
            writeList(entries) { entry ->
                writeLong(entry.sequenceNumber)
                writeLong(entry.timeNanos)
                writeString(entry.bootSessionId)
                writeString(entry.numericValue.toString())
            }
        }
        writeMap(checkpoint.sequences.toSortedMap(), { writeString(it) }) { partials ->
            writeList(partials) { partial ->
                writeInt(partial.nextStep)
                writeLong(partial.firstSequenceNumber)
                writeLong(partial.lastSequenceNumber)
                writeLong(partial.firstTimeNanos)
                writeString(partial.bootSessionId)
            }
        }
        writeMap(checkpoint.activationCounts.toSortedMap(), { writeString(it) }) { writeInt(it) }
        writeMap(checkpoint.cooldownMarks.toSortedMap(), { writeString(it) }) { mark ->
            writeLong(mark.activeElapsedNanos)
            writeLong(mark.calendarElapsedNanos)
        }
        writeMap(checkpoint.desiredResources.toSortedMap(), { writeResourceKey(it) }) { desired ->
            writeULong(desired.generation.value)
            writeNullableString(desired.profileId)
        }
        writeMap(checkpoint.timers.toSortedMap(), { writeString(it) }, { writeTimer(it) })
        writeMap(checkpoint.timerGenerations.toSortedMap(), { writeString(it) }, { writeULong(it) })
        writeMap(checkpoint.materializedTimers.toSortedMap(), { writeString(it) }) { summaries ->
            writeList(summaries) { summary ->
                writeString(summary.producerKey)
                writeLong(summary.selectedUtcMillis)
                writeBoolean(summary.terminal)
            }
        }
    }

    fun decode(encoded: String): AutomationCheckpoint {
        val checkpoint = decodePayload(encoded) {
            require(readInt() == VERSION) { "Unsupported automation checkpoint component" }
            AutomationCheckpoint(
                evaluatedThroughSequence = readLong(),
                lifecycle = enumValueOf(readString()),
                studyStartUtcMillis = readNullableLong(),
                lastActiveElapsedNanos = readLong(),
                lastCalendarElapsedNanos = readLong(),
                latchValues = readMap({ readString() }, { readBoolean() }),
                presenceKeys = readMap({ readString() }) { readList { readString() }.toSortedSet() },
                heldSinceNanos = readMap({ readString() }, { readLong() }),
                priorConditionValues = readMap({ readString() }, { readBoolean() }),
                windows = readMap({ readString() }) {
                    readList { WindowEntry(readLong(), readLong(), readString(), BigInteger(readString())) }
                },
                sequences = readMap({ readString() }) {
                    readList { SequencePartial(readInt(), readLong(), readLong(), readLong(), readString()) }
                },
                activationCounts = readMap({ readString() }, { readInt() }),
                cooldownMarks = readMap({ readString() }) { CooldownMark(readLong(), readLong()) },
                desiredResources = readMap({ readResourceKey() }) {
                    DesiredProfile(ResourceGeneration(readULong()), readNullableString())
                },
                timers = readMap({ readString() }, { readTimer() }),
                timerGenerations = readMap({ readString() }, { readULong() }),
                materializedTimers = readMap({ readString() }) {
                    readList { MaterializedTimerSummary(readString(), readLong(), readBoolean()) }
                },
            )
        }
        require(encode(checkpoint) == encoded) { "Automation checkpoint component is not canonical" }
        return checkpoint
    }

    private fun encodePayload(block: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output -> output.block() }
            buffer.toByteArray()
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun <T> decodePayload(encoded: String, block: DataInputStream.() -> T): T {
        require(encoded.startsWith(PREFIX)) { "Unexpected automation checkpoint encoding" }
        val payload = runCatching { Base64.getUrlDecoder().decode(encoded.removePrefix(PREFIX)) }
            .getOrElse { throw IllegalArgumentException("Invalid automation checkpoint base64url", it) }
        require(payload.size <= MAX_COMPONENT_BYTES) { "Automation checkpoint is too large" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val value = input.block()
            require(input.read() == -1) { "Trailing automation checkpoint bytes" }
            value
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Automation checkpoint string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid automation checkpoint string size" }
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

    private fun <K, V> DataOutputStream.writeMap(
        values: Map<K, V>,
        writeKey: DataOutputStream.(K) -> Unit,
        writeValue: DataOutputStream.(V) -> Unit,
    ) {
        require(values.size <= MAX_COLLECTION_SIZE) { "Automation checkpoint map is too large" }
        writeInt(values.size)
        values.forEach { (key, value) ->
            writeKey(key)
            writeValue(value)
        }
    }

    private fun <K, V> DataInputStream.readMap(
        readKey: DataInputStream.() -> K,
        readValue: DataInputStream.() -> V,
    ): Map<K, V> {
        val size = readInt()
        require(size in 0..MAX_COLLECTION_SIZE) { "Invalid automation checkpoint map size" }
        return buildMap(size) {
            repeat(size) {
                val key = readKey()
                require(key !in this) { "Duplicate automation checkpoint map key" }
                put(key, readValue())
            }
        }
    }

    private fun <T> DataOutputStream.writeList(
        values: List<T>,
        writeValue: DataOutputStream.(T) -> Unit,
    ) {
        require(values.size <= MAX_COLLECTION_SIZE) { "Automation checkpoint list is too large" }
        writeInt(values.size)
        values.forEach { value -> writeValue(value) }
    }

    private fun <T> DataInputStream.readList(readValue: DataInputStream.() -> T): List<T> {
        val size = readInt()
        require(size in 0..MAX_COLLECTION_SIZE) { "Invalid automation checkpoint list size" }
        return List(size) { readValue() }
    }

    private const val MAX_STRING_BYTES = 512 * 1_024
    private const val MAX_COMPONENT_BYTES = 512 * 1_024
    private const val MAX_COLLECTION_SIZE = 4_096
}
