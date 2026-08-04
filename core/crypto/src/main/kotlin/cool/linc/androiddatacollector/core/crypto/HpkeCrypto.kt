package cool.linc.androiddatacollector.core.crypto

import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes

data class HpkeKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/** RFC 9180 base-mode X25519/HKDF-SHA256/AES-256-GCM with raw, prefix-free keys. */
@AccessesPartialKey
object HpkeCrypto {
    private val PARAMETERS = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    init {
        HybridConfig.register()
    }

    fun generateKeyPair(): HpkeKeyPair {
        val privateKey = X25519.generatePrivateKey()
        return HpkeKeyPair(privateKey, X25519.publicFromPrivate(privateKey))
    }

    fun validatePublicKey(publicKey: ByteArray) {
        publicHandle(publicKey)
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(ByteArray(0), VALIDATION_CONTEXT)
    }

    fun encrypt(
        publicKey: ByteArray,
        plaintext: ByteArray,
        contextInfo: ByteArray,
    ): ByteArray = publicHandle(publicKey)
        .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        .encrypt(plaintext, contextInfo)
        .also { require(it.size == plaintext.size + ENCAPSULATED_KEY_BYTES + TAG_BYTES) { "Unexpected HPKE output size" } }

    fun decrypt(
        privateKey: ByteArray,
        ciphertext: ByteArray,
        contextInfo: ByteArray,
    ): ByteArray {
        require(ciphertext.size >= ENCAPSULATED_KEY_BYTES + TAG_BYTES) { "Truncated HPKE ciphertext" }
        return privateHandle(privateKey)
            .getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            .decrypt(ciphertext, contextInfo)
    }

    private fun publicHandle(raw: ByteArray): KeysetHandle {
        require(raw.size == RAW_KEY_BYTES) { "X25519 public key must be 32 bytes" }
        val key = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(raw), null)
        return handle(key)
    }

    private fun privateHandle(raw: ByteArray): KeysetHandle {
        require(raw.size == RAW_KEY_BYTES) { "X25519 private key must be 32 bytes" }
        val publicKey = HpkePublicKey.create(
            PARAMETERS,
            Bytes.copyFrom(X25519.publicFromPrivate(raw)),
            null,
        )
        val privateKey = HpkePrivateKey.create(
            publicKey,
            SecretBytes.copyFrom(raw, InsecureSecretKeyAccess.get()),
        )
        return handle(privateKey)
    }

    private fun handle(key: com.google.crypto.tink.Key): KeysetHandle = KeysetHandle.newBuilder()
        .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
        .build()

    const val RAW_KEY_BYTES = 32
    const val ENCAPSULATED_KEY_BYTES = 32
    const val TAG_BYTES = 16
    private val VALIDATION_CONTEXT = "adc-hpke-public-key-validation".toByteArray(Charsets.US_ASCII)
}
