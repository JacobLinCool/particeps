package cool.jacoblin.particeps.core.export

import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BundleKind(val wireValue: String) {
    MANUAL_EXPORT("manual_export"),
    AUTOMATIC_UPLOAD("automatic_upload"),
}

data class BundleProducer(
    val platform: String,
    val clientVersion: String,
) {
    init {
        require(platform == StudyConfiguration.ANDROID_PLATFORM) { "Unsupported producer platform" }
        require(UNSIGNED_DECIMAL.matches(clientVersion) && clientVersion.toLongOrNull() != null) {
            "Invalid producer client version"
        }
    }

    private companion object { val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*") }
}

/** Outer identities authenticated by both HPKE context and document AEAD. */
data class AuthenticatedBundleHeader(
    val bundleId: UUID,
    val configurationSha256: String,
    val researcherKeyId: String,
) {
    init {
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(StudyConfiguration.ID.matches(researcherKeyId)) { "Invalid researcher key ID" }
    }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}

data class ExportSnapshot(
    val verifiedConfiguration: VerifiedConfiguration,
    val metadata: StudyMetadata,
    val producer: BundleProducer,
    val bundleKind: BundleKind,
    val exportedAtUtcMillis: Long,
    val bundleId: UUID = UUID.randomUUID(),
    val fromSequence: Long = 1,
    val toSequence: Long? = null,
    /** Soft plaintext budget; selection stops only at an event boundary. */
    val maximumPlaintextBytes: Long? = null,
) {
    init {
        val configuration = verifiedConfiguration.configuration
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(exportedAtUtcMillis >= 0) { "Invalid export time" }
        require(metadata.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
        require(metadata.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
        require(metadata.assignedParticipantId == configuration.assignedParticipantId) { "Assigned participant ID mismatch" }
        require(producer.platform == configuration.platform) { "Producer platform mismatch" }
        require(producer.clientVersion.toLong() >= configuration.minimumClientVersion) { "Producer client version is too old" }
        require(verifiedConfiguration.signerKeyId == configuration.signer.keyId) { "Signer provenance mismatch" }
        require(verifiedConfiguration.signature.size == 64) { "Invalid Ed25519 signature provenance" }
        val canonical = StudyConfigurationCodec.encode(configuration)
        require(canonical.contentEquals(verifiedConfiguration.canonicalConfigurationBytes)) {
            "Configuration provenance is not canonical"
        }
        require(canonical.sha256Hex() == verifiedConfiguration.configurationSha256) {
            "Configuration digest provenance mismatch"
        }
        maximumPlaintextBytes?.let { require(it > 0) { "Plaintext budget must be positive" } }
    }
}

data class ExportReceipt(
    val bundleId: UUID,
    val configurationSha256: String,
    val firstSequence: Long,
    val lastSequence: Long,
    val eventCount: Long,
    val sha256: String,
    val byteCount: Long,
) {
    init {
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(firstSequence > 0) { "Invalid first sequence" }
        require(lastSequence >= 0) { "Invalid last sequence" }
        require(eventCount >= 0) { "Invalid event count" }
        val expectedLast = if (eventCount == 0L) {
            firstSequence - 1
        } else {
            require(firstSequence <= Long.MAX_VALUE - eventCount + 1) { "Receipt sequence range overflows" }
            firstSequence + eventCount - 1
        }
        require(lastSequence == expectedLast) { "Receipt sequence range is inconsistent" }
        require(SHA256.matches(sha256)) { "Invalid bundle digest" }
        require(byteCount > 0) { "Invalid bundle byte count" }
    }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}

object ResearchExport {
    fun validate(configuration: StudyConfiguration) {
        HpkeCrypto.validatePublicKey(configuration.export.hpkePublicKeyBytes())
    }

    suspend fun encrypt(
        snapshot: ExportSnapshot,
        events: StudyStore,
        destination: OutputStream,
    ): ExportReceipt = withContext(Dispatchers.IO) {
        val configuration = snapshot.verifiedConfiguration.configuration
        validate(configuration)
        val durable = snapshot.metadata.nextSequenceNumber - 1
        require(durable == snapshot.metadata.eventCount) { "Export metadata boundary is inconsistent" }
        require(snapshot.fromSequence >= snapshot.metadata.retainedFromSequence) { "Export starts below retained data" }
        val requestedBoundary = snapshot.toSequence ?: durable
        require(requestedBoundary in 0..durable) { "Export boundary exceeds durable data" }
        require(snapshot.fromSequence in 1..(requestedBoundary + 1)) { "Export range start is out of bounds" }
        if (snapshot.bundleKind == BundleKind.AUTOMATIC_UPLOAD) {
            require(snapshot.fromSequence <= requestedBoundary) { "Automatic upload cannot be empty" }
        }
        val boundary = selectBoundary(snapshot, events, requestedBoundary)
        val eventCount = (boundary - snapshot.fromSequence + 1).coerceAtLeast(0)
        val context = contextInfo(
            snapshot.bundleId,
            snapshot.verifiedConfiguration.configurationSha256,
            configuration.export.researcherKeyId,
        )
        val contentKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
        val wrappedKey = HpkeCrypto.encrypt(configuration.export.hpkePublicKeyBytes(), contentKey.encoded, context)
        require(wrappedKey.size == WRAPPED_KEY_BYTES) { "Unexpected wrapped-key size" }
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
        val keyId = configuration.export.researcherKeyId.toByteArray(Charsets.UTF_8)
        val digesting = DigestingOutputStream(destination)
        digesting.write(
            ByteBuffer.allocate(FIXED_HEADER_BYTES + keyId.size + WRAPPED_KEY_BYTES)
                .put(MAGIC)
                .putUuid(snapshot.bundleId)
                .put(snapshot.verifiedConfiguration.configurationSha256.hexBytes())
                .putShort(keyId.size.toShort())
                .put(nonce)
                .put(keyId)
                .put(wrappedKey)
                .array(),
        )
        CanonicalJsonWriter(CipherOutputStream(digesting, cipher)).use { writer ->
            writeSnapshot(writer, snapshot, events, boundary, eventCount)
        }
        ExportReceipt(
            bundleId = snapshot.bundleId,
            configurationSha256 = snapshot.verifiedConfiguration.configurationSha256,
            firstSequence = snapshot.fromSequence,
            lastSequence = boundary,
            eventCount = eventCount,
            sha256 = digesting.digest().toHex(),
            byteCount = digesting.count,
        )
    }

    fun decrypt(
        encoded: ByteArray,
        privateKey: ByteArray,
        configuration: StudyConfiguration,
    ): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        decrypt(encoded.inputStream(), output, privateKey, configuration)
    }.toByteArray()

    /** [output] remains unauthenticated staging until this method returns successfully. */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        privateKey: ByteArray,
        configuration: StudyConfiguration,
    ): AuthenticatedBundleHeader {
        require(privateKey.size == HpkeCrypto.RAW_KEY_BYTES) { "X25519 private key must be 32 bytes" }
        val fixed = input.readNBytes(FIXED_HEADER_BYTES)
        require(fixed.size == FIXED_HEADER_BYTES) { "Truncated export" }
        val header = ByteBuffer.wrap(fixed)
        val magic = ByteArray(MAGIC.size).also(header::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported export format" }
        val bundleId = header.getUuid()
        val configurationDigest = ByteArray(SHA256_BYTES).also(header::get).toHex()
        val keyIdLength = header.short.toInt() and 0xffff
        val nonce = ByteArray(NONCE_BYTES).also(header::get)
        require(keyIdLength in 3..64) { "Invalid researcher key ID length" }
        val expectedDigest = StudyConfigurationCodec.encode(configuration).sha256Hex()
        require(configurationDigest == expectedDigest) { "Configuration digest mismatch" }
        val keyIdBytes = input.readNBytes(keyIdLength)
        require(keyIdBytes.size == keyIdLength) { "Truncated export" }
        val keyId = keyIdBytes.strictUtf8("researcher key ID")
        require(keyId == configuration.export.researcherKeyId) { "Researcher key ID mismatch" }
        val authenticatedHeader = AuthenticatedBundleHeader(bundleId, configurationDigest, keyId)
        val wrappedKey = input.readNBytes(WRAPPED_KEY_BYTES)
        require(wrappedKey.size == WRAPPED_KEY_BYTES) { "Truncated export" }
        val context = contextInfo(bundleId, configurationDigest, keyId)
        val contentKey = HpkeCrypto.decrypt(privateKey, wrappedKey, context)
        require(contentKey.size == KEY_BITS / 8) { "Invalid unwrapped content key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
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
        return authenticatedHeader
    }

    private suspend fun selectBoundary(
        snapshot: ExportSnapshot,
        events: StudyStore,
        requestedBoundary: Long,
    ): Long {
        val budget = snapshot.maximumPlaintextBytes ?: return requestedBoundary
        if (snapshot.fromSequence > requestedBoundary) return snapshot.fromSequence - 1
        val counter = CountingOutputStream(DiscardingOutputStream)
        var boundary = snapshot.fromSequence - 1
        var expected = snapshot.fromSequence
        var stopped = false
        CanonicalJsonWriter(counter).use { writer ->
            writer.beginArray()
            events.readEvents(snapshot.fromSequence, requestedBoundary) { event ->
                if (!stopped) {
                    require(event.sequenceNumber == expected) { "Non-contiguous export event range" }
                    writer.writeEvent(event)
                    writer.flush()
                    boundary = event.sequenceNumber
                    expected++
                    stopped = counter.count >= budget
                }
            }
            writer.endArray()
        }
        return boundary
    }

    private suspend fun writeSnapshot(
        writer: CanonicalJsonWriter,
        snapshot: ExportSnapshot,
        events: StudyStore,
        boundary: Long,
        eventCount: Long,
    ) {
        val verified = snapshot.verifiedConfiguration
        val metadata = snapshot.metadata
        writer.beginObject()
        writer.name("bundle_id").value(snapshot.bundleId.toString())
        writer.name("bundle_kind").value(snapshot.bundleKind.wireValue)
        writer.name("configuration").rawCanonicalJson(verified.canonicalConfigurationBytes)
        writer.name("configuration_sha256").value(verified.configurationSha256)
        writer.name("configuration_signature").beginObject()
        writer.name("signature").value(ProtocolBase64Url.encode(verified.signature))
        writer.name("signer_key_id").value(verified.signerKeyId)
        writer.endObject()
        writer.name("experiment").beginObject()
        writer.name("assigned_participant_id").value(metadata.assignedParticipantId)
        writer.name("configuration_id").value(metadata.configurationId)
        writer.name("durable_through_sequence").valueDecimal(metadata.nextSequenceNumber - 1)
        writer.name("event_count").valueDecimal(eventCount)
        writer.name("events").beginArray()
        var expected = snapshot.fromSequence
        if (eventCount > 0) {
            events.readEvents(snapshot.fromSequence, boundary) { event ->
                require(event.sequenceNumber == expected) { "Non-contiguous export event range" }
                writer.writeEvent(event)
                expected++
            }
        }
        require(expected == boundary + 1) { "Export event count mismatch" }
        writer.endArray()
        writer.name("experiment_id").value(metadata.experimentId)
        writer.name("first_sequence_number").valueDecimal(snapshot.fromSequence)
        writer.name("last_sequence_number").valueDecimal(boundary)
        writer.name("next_sequence_number").valueDecimal(metadata.nextSequenceNumber)
        writer.name("participant_instance_id").value(metadata.participantInstanceId)
        writer.name("retained_from_sequence").valueDecimal(metadata.retainedFromSequence)
        writer.name("state").value(metadata.state.name)
        writer.name("transitions").beginArray()
        metadata.transitions.forEach { transition ->
            writer.beginObject()
            writer.name("from").value(transition.from.name)
            writer.name("reason").value(transition.reason.name)
            writer.name("time").writeTime(transition.time)
            writer.name("to").value(transition.to.name)
            writer.endObject()
        }
        writer.endArray()
        writer.name("uploaded_through_sequence").valueDecimal(metadata.uploadedThroughSequence)
        writer.endObject()
        writer.name("exported_at_utc_millis").valueDecimal(snapshot.exportedAtUtcMillis)
        writer.name("format").value(BUNDLE_FORMAT)
        writer.name("producer").beginObject()
        writer.name("client_version").value(snapshot.producer.clientVersion)
        writer.name("platform").value(snapshot.producer.platform)
        writer.endObject()
        writer.endObject()
    }

    private fun CanonicalJsonWriter.writeEvent(event: RecordedEvent) {
        beginObject()
        name("collector_id").value(event.collectorId)
        name("fields").beginObject()
        event.fields.toSortedMap().forEach { (key, value) -> name(key).value(value) }
        endObject()
        name("observed_time").writeTime(event.observedTime)
        name("payload_schema_version").value(event.payloadSchemaVersion)
        name("payload_type").value(event.payloadType)
        name("sequence_number").valueDecimal(event.sequenceNumber)
        endObject()
    }

    private fun CanonicalJsonWriter.writeTime(time: ResearchTime) {
        beginObject()
        name("boot_session_id").value(time.bootSessionId)
        name("monotonic_time_nanos").valueDecimal(time.elapsedRealtimeNanos)
        name("wall_time_utc_millis").valueDecimal(time.wallTimeUtcMillis)
        endObject()
    }

    private fun contextInfo(bundleId: UUID, configurationSha256: String, researcherKeyId: String): ByteArray =
        ("{\"bundle_format\":\"$BUNDLE_FORMAT\",\"bundle_id\":\"$bundleId\"," +
            "\"configuration_sha256\":\"$configurationSha256\",\"researcher_key_id\":\"$researcherKeyId\"}"
            ).toByteArray(Charsets.UTF_8)

    private fun cool.jacoblin.particeps.core.definition.ExportConfiguration.hpkePublicKeyBytes(): ByteArray =
        ProtocolBase64Url.decodeExact(hpkePublicKey, HpkeCrypto.RAW_KEY_BYTES, "X25519 public key")

    private fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer = putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)

    private fun ByteBuffer.getUuid(): UUID = UUID(getLong(), getLong())

    private fun String.hexBytes(): ByteArray {
        require(length == SHA256_BYTES * 2 && all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
        return ByteArray(SHA256_BYTES) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.strictUtf8(label: String): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrElse { throw IllegalArgumentException("$label is not valid UTF-8", it) }
        .also { require(StudyConfiguration.ID.matches(it)) { "Invalid $label" } }

    private val MAGIC = "PTCEXP01".toByteArray(Charsets.US_ASCII)
    const val BUNDLE_FORMAT = "particeps-research-bundle-v1"
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val SHA256_BYTES = 32
    private const val WRAPPED_KEY_BYTES = 80
    private const val FIXED_HEADER_BYTES = 8 + 16 + SHA256_BYTES + Short.SIZE_BYTES + NONCE_BYTES
    private const val DECRYPT_CHUNK_BYTES = 64 * 1024
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}

private class DigestingOutputStream(private val destination: OutputStream) : OutputStream() {
    private val messageDigest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(value: Int) {
        destination.write(value)
        messageDigest.update(value.toByte())
        count++
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
