package cool.jacoblin.particeps.core.protocol

import cool.jacoblin.particeps.core.crypto.Ed25519Crypto
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

data class SignedConfigurationEnvelope(
    val signerKeyId: String,
    val configurationBytes: ByteArray,
    val signature: ByteArray,
)

/** The sole Protocol v1 signed-configuration framing. Older v1 bytes are intentionally rejected. */
object SignedConfigurationCodec {
    fun encode(envelope: SignedConfigurationEnvelope): ByteArray {
        require(StudyConfiguration.ID.matches(envelope.signerKeyId)) { "Invalid signer key ID" }
        val keyId = envelope.signerKeyId.toByteArray(Charsets.UTF_8)
        require(keyId.size in MINIMUM_KEY_ID_BYTES..MAXIMUM_KEY_ID_BYTES) { "Invalid signer key ID" }
        require(envelope.configurationBytes.size in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration size" }
        require(envelope.signature.size == SIGNATURE_BYTES) { "Ed25519 signature must be 64 bytes" }
        return ByteBuffer.allocate(
            MAGIC.size + Short.SIZE_BYTES + Int.SIZE_BYTES + keyId.size +
                envelope.configurationBytes.size + SIGNATURE_BYTES,
        )
            .put(MAGIC)
            .putShort(keyId.size.toShort())
            .putInt(envelope.configurationBytes.size)
            .put(keyId)
            .put(envelope.configurationBytes)
            .put(envelope.signature)
            .array()
    }

    fun decode(bytes: ByteArray): SignedConfigurationEnvelope {
        require(bytes.size in MINIMUM_ENVELOPE_BYTES..MAXIMUM_ENVELOPE_BYTES) { "Invalid envelope size" }
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported signed-configuration format" }
        val keyIdLength = buffer.short.toInt() and 0xffff
        val configurationLength = buffer.int
        require(keyIdLength in MINIMUM_KEY_ID_BYTES..MAXIMUM_KEY_ID_BYTES) { "Invalid signer key ID length" }
        require(configurationLength in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration length" }
        require(buffer.remaining() == keyIdLength + configurationLength + SIGNATURE_BYTES) {
            "Envelope length mismatch"
        }
        val keyIdBytes = ByteArray(keyIdLength).also(buffer::get)
        val keyId = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(keyIdBytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("Signer key ID is not valid UTF-8", it) }
        require(StudyConfiguration.ID.matches(keyId)) { "Invalid signer key ID" }
        return SignedConfigurationEnvelope(
            signerKeyId = keyId,
            configurationBytes = ByteArray(configurationLength).also(buffer::get),
            signature = ByteArray(SIGNATURE_BYTES).also(buffer::get),
        )
    }

    private val MAGIC = "PTCCFG01".toByteArray(Charsets.US_ASCII)
    private const val SIGNATURE_BYTES = 64
    private const val MINIMUM_KEY_ID_BYTES = 3
    private const val MAXIMUM_KEY_ID_BYTES = 64
    private const val MAX_CONFIGURATION_BYTES = 1_048_576
    private const val MINIMUM_ENVELOPE_BYTES = 8 + 2 + 4 + MINIMUM_KEY_ID_BYTES + 2 + SIGNATURE_BYTES
    const val MAXIMUM_ENVELOPE_BYTES =
        8 + 2 + 4 + MAXIMUM_KEY_ID_BYTES + MAX_CONFIGURATION_BYTES + SIGNATURE_BYTES
}

/** Immutable provenance needed by every authenticated research bundle. */
data class VerifiedConfiguration(
    val configuration: StudyConfiguration,
    val canonicalConfigurationBytes: ByteArray,
    val signerKeyId: String,
    val signature: ByteArray,
    /** Lowercase hexadecimal SHA-256 of [canonicalConfigurationBytes]. */
    val configurationSha256: String,
    val signerAnchored: Boolean,
)

class ConfigurationVerifier(
    trustedSigningKeys: Map<String, String>,
    private val clientVersion: Long,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(clientVersion > 0) { "Client version must be positive" }
    }

    private val keys: Map<String, ByteArray> = trustedSigningKeys.mapValues { (_, encoded) ->
        decodeSigningKey(encoded)
    }

    fun verify(envelopeBytes: ByteArray): VerifiedConfiguration {
        val envelope = SignedConfigurationCodec.decode(envelopeBytes)
        val configuration = StudyConfigurationCodec.decode(envelope.configurationBytes)
        require(configuration.signer.keyId == envelope.signerKeyId) {
            "Envelope signer does not match the configuration"
        }

        val anchored = keys.containsKey(envelope.signerKeyId)
        if (keys.isNotEmpty()) require(anchored) { "Untrusted configuration signer" }
        val declaredKey = decodeSigningKey(configuration.signer.publicKey)
        val verificationKey = keys[envelope.signerKeyId] ?: declaredKey
        if (anchored) {
            require(verificationKey.contentEquals(declaredKey)) {
                "Configuration signer key does not match the pinned key"
            }
        }

        val valid = Ed25519Crypto.verify(
            publicKey = verificationKey,
            message = envelope.configurationBytes,
            signature = envelope.signature,
        )
        require(valid) { "Invalid configuration signature" }
        val instant = now()
        require(!instant.isBefore(configuration.issuedAt)) { "Configuration is not active yet" }
        require(instant.isBefore(configuration.expiresAt)) { "Configuration has expired" }
        require(configuration.platform == StudyConfiguration.ANDROID_PLATFORM) { "Configuration targets another platform" }
        require(clientVersion >= configuration.minimumClientVersion) { "Client version is too old" }
        return VerifiedConfiguration(
            configuration = configuration,
            canonicalConfigurationBytes = envelope.configurationBytes.copyOf(),
            signerKeyId = envelope.signerKeyId,
            signature = envelope.signature.copyOf(),
            configurationSha256 = MessageDigest.getInstance("SHA-256")
                .digest(envelope.configurationBytes)
                .toHex(),
            signerAnchored = anchored,
        )
    }

    private fun decodeSigningKey(encoded: String): ByteArray = ProtocolBase64Url.decodeExact(
        encoded,
        Ed25519Crypto.PUBLIC_KEY_BYTES,
        "Ed25519 public key",
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

sealed interface ActiveStudyRecord {
    data class Active(val envelopeBytes: ByteArray) : ActiveStudyRecord

    /**
     * Durable point of no return for participant-requested deletion.
     *
     * The signed envelope is deliberately absent: once this record is persisted, startup has
     * enough information only to finish erasing local state, never to reactivate or upload it.
     */
    data class DeletionPending(
        val experimentId: String,
        val maximumLocalBytes: Long,
    ) : ActiveStudyRecord {
        init {
            require(StudyConfiguration.ID.matches(experimentId)) { "Invalid deletion experiment ID" }
            require(maximumLocalBytes in StudyConfiguration.MINIMUM_LOCAL_BYTES..StudyConfiguration.MAXIMUM_LOCAL_BYTES) {
                "Invalid deletion storage quota"
            }
        }
    }
}

interface ActiveStudyStore {
    suspend fun load(): ActiveStudyRecord?
    suspend fun save(envelopeBytes: ByteArray)
    suspend fun markDeletionPending(experimentId: String, maximumLocalBytes: Long)
    suspend fun clear()
}
