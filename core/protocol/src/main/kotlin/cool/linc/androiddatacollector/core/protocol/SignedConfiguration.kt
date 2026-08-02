package cool.linc.androiddatacollector.core.protocol

import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.StudyConfigurationCodec
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class SignedConfigurationEnvelope(
    val signerKeyId: String,
    val configurationBytes: ByteArray,
    val signature: ByteArray,
)

object SignedConfigurationCodec {
    fun encode(envelope: SignedConfigurationEnvelope): ByteArray {
        val keyId = envelope.signerKeyId.toByteArray(Charsets.UTF_8)
        require(keyId.size in 3..64) { "Invalid signer key ID" }
        require(envelope.configurationBytes.size in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration size" }
        require(envelope.signature.size in 32..128) { "Invalid signature size" }
        return ByteBuffer.allocate(
            MAGIC.size + Short.SIZE_BYTES + Int.SIZE_BYTES + Short.SIZE_BYTES +
                keyId.size + envelope.configurationBytes.size + envelope.signature.size,
        )
            .put(MAGIC)
            .putShort(keyId.size.toShort())
            .putInt(envelope.configurationBytes.size)
            .putShort(envelope.signature.size.toShort())
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
        val signatureLength = buffer.short.toInt() and 0xffff
        require(keyIdLength in 3..64) { "Invalid signer key ID length" }
        require(configurationLength in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration length" }
        require(signatureLength in 32..128) { "Invalid signature length" }
        require(buffer.remaining() == keyIdLength + configurationLength + signatureLength) { "Envelope length mismatch" }
        return SignedConfigurationEnvelope(
            signerKeyId = ByteArray(keyIdLength).also(buffer::get).toString(Charsets.UTF_8),
            configurationBytes = ByteArray(configurationLength).also(buffer::get),
            signature = ByteArray(signatureLength).also(buffer::get),
        )
    }

    private val MAGIC = "ADCCFG01".toByteArray(Charsets.US_ASCII)
    private const val MAX_CONFIGURATION_BYTES = 1_048_576
    private const val MINIMUM_ENVELOPE_BYTES = 8 + 2 + 4 + 2 + 3 + 2 + 32
    private const val MAXIMUM_ENVELOPE_BYTES = 8 + 2 + 4 + 2 + 64 + MAX_CONFIGURATION_BYTES + 128
}

/** A configuration that passed verification, and whether its signer was one the build pins. */
data class VerifiedConfiguration(
    val configuration: StudyConfiguration,
    /**
     * True when [trustedSigningKeys] listed this signer. False means the configuration certified
     * itself: the signature proves it is unchanged since signing, not who wrote it. The consent
     * screen has to say so.
     */
    val signerAnchored: Boolean,
)

/**
 * Verifies a signed study configuration.
 *
 * The signing public key travels inside the signed bytes, so any configuration can be checked with
 * nothing but the file. That is what lets one published app run any researcher's study.
 *
 * [trustedSigningKeys] is optional hardening rather than the basis of verification. Leave it empty
 * and the app accepts any correctly signed configuration, flagging the publisher as unverified.
 * Populate it — as an institution shipping its own build would — and only those signers are
 * accepted, with the pinned key overriding whatever the configuration declares.
 */
class ConfigurationVerifier(
    trustedSigningKeys: Map<String, String>,
    private val appVersionCode: Int,
    private val now: () -> Instant = Instant::now,
) {
    private val keys: Map<String, PublicKey> = trustedSigningKeys.mapValues { (_, encoded) ->
        decodeSigningKey(encoded)
    }

    fun verify(envelopeBytes: ByteArray): VerifiedConfiguration {
        val envelope = SignedConfigurationCodec.decode(envelopeBytes)
        // Decode before verifying so the declared key is available, then verify over the same bytes
        // that were decoded. Nothing is trusted until the signature checks out.
        val configuration = StudyConfigurationCodec.decode(envelope.configurationBytes)
        require(configuration.signer.keyId == envelope.signerKeyId) {
            "Envelope signer does not match the configuration"
        }

        val anchored = keys.containsKey(envelope.signerKeyId)
        if (keys.isNotEmpty()) {
            require(anchored) { "Untrusted configuration signer" }
        }
        // A pinned key wins over the declared one, so pinning cannot be sidestepped by shipping a
        // configuration that names a pinned key ID but carries a different public key.
        val key = keys[envelope.signerKeyId] ?: decodeSigningKey(configuration.signer.publicKey)
        if (anchored) {
            require(
                keys.getValue(envelope.signerKeyId).encoded
                    .contentEquals(decodeSigningKey(configuration.signer.publicKey).encoded),
            ) { "Configuration signer key does not match the pinned key" }
        }

        // Every rejection here is an IllegalArgumentException, and a bad signature has to be one
        // too. `Signature.verify` does not report them uniformly: the JDK's Ed25519 returns false
        // for a signature that simply does not match, but throws when the S component is out of
        // range, which flipping one byte of a real signature produces about 6% of the time. Which
        // byte a corrupt file happened to lose should not decide what the caller catches.
        val valid = try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(envelope.configurationBytes)
                verify(envelope.signature)
            }
        } catch (malformed: SignatureException) {
            false
        }
        require(valid) { "Invalid configuration signature" }
        val instant = now()
        require(!instant.isBefore(configuration.issuedAt)) { "Configuration is not active yet" }
        require(instant.isBefore(configuration.expiresAt)) { "Configuration has expired" }
        require(appVersionCode >= configuration.minimumAppVersion) { "App version is too old" }
        return VerifiedConfiguration(configuration, anchored)
    }

    private fun decodeSigningKey(encoded: String): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(encoded)),
        )
}

interface ActiveStudyStore {
    suspend fun load(): ByteArray?
    suspend fun save(envelopeBytes: ByteArray)
    suspend fun clear()
}
