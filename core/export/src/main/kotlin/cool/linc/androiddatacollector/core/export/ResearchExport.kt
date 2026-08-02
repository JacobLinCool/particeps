package cool.linc.androiddatacollector.core.export

import com.google.gson.stream.JsonWriter
import cool.linc.androiddatacollector.core.crypto.HpkeCrypto
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.StudyConfigurationCodec
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportSnapshot(
    val configuration: StudyConfiguration,
    val metadata: StudyMetadata,
    val exportedAtUtcMillis: Long,
    /**
     * First sequence to include. A participant export starts at whatever the device still holds;
     * a scheduled upload sends the window after the last one an endpoint confirmed, so bundles do
     * not repeat the whole history.
     */
    val fromSequence: Long = 1,
    /**
     * Last sequence to include, or null for everything durable.
     */
    val toSequence: Long? = null,
    /**
     * Soft ceiling on the bundle's plaintext, or null for no ceiling.
     *
     * A bundle stops at the first event boundary past the budget and reports where it stopped, so a
     * caller asks for everything and finds out how much fit. Scheduled delivery uses this to keep a
     * single request a sane size while still draining as much of a backlog as it can; a participant
     * export passes null, because their copy should be complete.
     */
    val maximumPlaintextBytes: Long? = null,
)

data class ExportReceipt(
    val researcherKeyId: String,
    val firstSequence: Long,
    /** Last sequence actually written, which is where a budgeted bundle stopped. */
    val sequenceBoundary: Long,
    val eventCount: Long,
    val sha256: String,
    val byteCount: Long,
)

object ResearchExport {
    fun validate(configuration: StudyConfiguration) {
        HpkeCrypto.validatePublicKeyset(configuration.export.tinkHpkePublicKeysetJson)
    }

    suspend fun encrypt(
        snapshot: ExportSnapshot,
        events: StudyStore,
        destination: OutputStream,
    ): ExportReceipt = withContext(Dispatchers.IO) {
        val durable = snapshot.metadata.nextSequenceNumber - 1
        require(durable == snapshot.metadata.eventCount) { "Export metadata boundary is inconsistent" }
        val boundary = snapshot.toSequence ?: durable
        require(boundary in 0..durable) { "Export boundary exceeds the durable event count" }
        require(snapshot.fromSequence in 1..(boundary + 1)) { "Export range start is out of bounds" }
        val context = contextInfo(snapshot.configuration)
        val contentKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
        val wrappedKey = HpkeCrypto.encrypt(
            snapshot.configuration.export.tinkHpkePublicKeysetJson,
            contentKey.encoded,
            context,
        )
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
        val keyId = snapshot.configuration.export.researcherKeyId.toByteArray(Charsets.UTF_8)
        val digesting = DigestingOutputStream(destination)
        digesting.write(
            ByteBuffer.allocate(MAGIC.size + Short.SIZE_BYTES + Int.SIZE_BYTES + NONCE_BYTES + keyId.size + wrappedKey.size)
                .put(MAGIC)
                .putShort(keyId.size.toShort())
                .putInt(wrappedKey.size)
                .put(nonce)
                .put(keyId)
                .put(wrappedKey)
                .array(),
        )
        val counting = CountingOutputStream(CipherOutputStream(digesting, cipher))
        var written = boundary
        JsonWriter(OutputStreamWriter(counting, Charsets.UTF_8)).use { writer ->
            written = writeSnapshot(writer, snapshot, events, boundary, counting)
        }
        ExportReceipt(
            researcherKeyId = snapshot.configuration.export.researcherKeyId,
            firstSequence = snapshot.fromSequence,
            sequenceBoundary = written,
            eventCount = (written - snapshot.fromSequence + 1).coerceAtLeast(0),
            sha256 = digesting.digest().toHex(),
            byteCount = digesting.count,
        )
    }

    fun decrypt(
        encoded: ByteArray,
        privateKeysetJson: String,
        configuration: StudyConfiguration,
    ): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        decrypt(encoded.inputStream(), output, privateKeysetJson, configuration)
    }.toByteArray()

    /**
     * Streams a bundle's plaintext into [output].
     *
     * Streaming because a bundle is now bounded by the study's own storage quota rather than by a
     * fixed ceiling, so a long study can produce a file far larger than a researcher's machine
     * would want to hold in memory.
     *
     * The AES-GCM tag is verified only once the last byte has been read, so [output] holds
     * unauthenticated bytes until this returns. It throws on a bad tag, and a caller that cannot
     * tolerate partial output should stage the result and only publish it after this returns —
     * `researcher-tools decrypt` writes to a temporary file for exactly that reason.
     */
    fun decrypt(
        input: java.io.InputStream,
        output: OutputStream,
        privateKeysetJson: String,
        configuration: StudyConfiguration,
    ) {
        val header = ByteArray(MAGIC.size + Short.SIZE_BYTES + Int.SIZE_BYTES + NONCE_BYTES)
        require(input.readNBytes(header, 0, header.size) == header.size) { "Truncated export" }
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported export format" }
        val keyIdLength = buffer.short.toInt() and 0xffff
        val wrappedLength = buffer.int
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        require(keyIdLength in 3..64) { "Invalid export key ID length" }
        require(wrappedLength in 32..16_384) { "Invalid wrapped key length" }

        val keyId = input.readNBytes(keyIdLength).also {
            require(it.size == keyIdLength) { "Truncated export" }
        }.toString(Charsets.UTF_8)
        require(keyId == configuration.export.researcherKeyId) { "Researcher key ID mismatch" }
        val wrappedKey = input.readNBytes(wrappedLength).also {
            require(it.size == wrappedLength) { "Truncated export" }
        }

        val context = contextInfo(configuration)
        val contentKey = HpkeCrypto.decrypt(privateKeysetJson, wrappedKey, context)
        require(contentKey.size == KEY_BITS / 8) { "Invalid unwrapped content key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
        // CipherInputStream swallows an AEAD failure as a plain end-of-stream, which would turn a
        // tampered bundle into a silently short file. Driving the cipher directly keeps the tag
        // failure an exception.
        val chunk = ByteArray(DECRYPT_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            cipher.update(chunk, 0, read)?.let(output::write)
        }
        require(total > TAG_BYTES) { "Truncated export" }
        cipher.doFinal()?.let(output::write)
    }

    /** Returns the last sequence written, which is [boundary] unless the budget stopped it sooner. */
    private suspend fun writeSnapshot(
        writer: JsonWriter,
        snapshot: ExportSnapshot,
        events: StudyStore,
        boundary: Long,
        counting: CountingOutputStream,
    ): Long {
        val metadata = snapshot.metadata
        writer.beginObject()
        writer.name("format").value(BUNDLE_FORMAT)
        writer.name("exported_at_utc_millis").value(snapshot.exportedAtUtcMillis)
        writer.name("configuration").jsonValue(
            StudyConfigurationCodec.encode(snapshot.configuration).toString(Charsets.UTF_8),
        )
        writer.name("experiment").beginObject()
        writer.name("experiment_id").value(metadata.experimentId)
        writer.name("configuration_id").value(metadata.configurationId)
        writer.name("participant_instance_id").value(metadata.participantInstanceId)
        metadata.assignedParticipantId?.let { writer.name("assigned_participant_id").value(it) }
        writer.name("state").value(metadata.state.name)
        writer.name("next_sequence_number").value(metadata.nextSequenceNumber)
        writer.name("transitions").beginArray()
        metadata.transitions.forEach { transition ->
            writer.beginObject()
            writer.name("from").value(transition.from.name)
            writer.name("to").value(transition.to.name)
            writer.name("reason").value(transition.reason.name)
            writer.name("time").writeTime(transition.time)
            writer.endObject()
        }
        writer.endArray()
        writer.name("events").beginArray()
        var written = snapshot.fromSequence - 1
        var stopped = false
        val budget = snapshot.maximumPlaintextBytes
        events.readEvents(snapshot.fromSequence, boundary) { event ->
            if (!stopped) {
                writer.writeEvent(event)
                written = event.sequenceNumber
                // Always take at least one event, so a bundle can never make zero progress, and
                // check on a stride so the budget costs one flush per batch rather than per event.
                if (budget != null && written % BUDGET_CHECK_STRIDE == 0L) {
                    writer.flush()
                    if (counting.count >= budget) stopped = true
                }
            }
        }
        writer.endArray()
        // Written after the events, because a budget decides where the bundle stops while it
        // streams. Declaring the window up front would have let a bundle claim a range it does not
        // contain, which is worse than not declaring one at all.
        writer.name("first_sequence_number").value(snapshot.fromSequence)
        writer.name("last_sequence_number").value(written)
        writer.endObject()
        writer.endObject()
        return written
    }

    private fun JsonWriter.writeEvent(event: RecordedEvent) {
        beginObject()
        name("sequence_number").value(event.sequenceNumber)
        name("collector_id").value(event.collectorId)
        name("payload_schema_version").value(event.payloadSchemaVersion)
        name("observed_time").writeTime(event.observedTime)
        name("payload_type").value(event.payloadType)
        name("fields").beginObject()
        event.fields.toSortedMap().forEach { (key, value) -> name(key).value(value) }
        endObject()
        endObject()
    }

    private fun JsonWriter.writeTime(time: ResearchTime) {
        beginObject()
        name("wall_time_utc_millis").value(time.wallTimeUtcMillis)
        name("elapsed_realtime_nanos").value(time.elapsedRealtimeNanos)
        name("boot_session_id").value(time.bootSessionId)
        endObject()
    }

    private fun contextInfo(configuration: StudyConfiguration): ByteArray =
        "$BUNDLE_FORMAT:${configuration.experimentId}:${configuration.configurationId}:${configuration.export.researcherKeyId}"
            .toByteArray(Charsets.UTF_8)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val MAGIC = "ADCEXP01".toByteArray(Charsets.US_ASCII)

    /**
     * Part of the HPKE and AES-GCM associated data as well as the bundle's own `format` field, so a
     * reader built for a different version cannot silently accept this one: the tag fails first.
     */
    const val BUNDLE_FORMAT = "research-bundle-v1"
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MINIMUM_EXPORT_BYTES = 8 + 2 + 4 + NONCE_BYTES + 3 + 32 + TAG_BYTES + 1

    /** How often the budget is checked, in events. Bounds overshoot to one stride of data. */
    private const val BUDGET_CHECK_STRIDE = 256L
    private const val DECRYPT_CHUNK_BYTES = 64 * 1024
}

/** Tracks plaintext written so the budget can stop a bundle at an event boundary. */
private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length
    }
}

private class DigestingOutputStream(
    private val destination: OutputStream,
) : OutputStream() {
    private val messageDigest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(value: Int) {
        destination.write(value)
        messageDigest.update(value.toByte())
        count += 1
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        destination.write(bytes, offset, length)
        messageDigest.update(bytes, offset, length)
        count += length
    }

    override fun flush() = destination.flush()
    override fun close() = destination.flush()
    fun digest(): ByteArray = messageDigest.digest()
}
