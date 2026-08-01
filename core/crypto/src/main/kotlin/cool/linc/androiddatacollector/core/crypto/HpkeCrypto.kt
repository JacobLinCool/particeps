package cool.linc.androiddatacollector.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey

data class HpkeKeysetPair(
    val privateKeysetJson: String,
    val publicKeysetJson: String,
)

object HpkeCrypto {
    private const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    init {
        HybridConfig.register()
    }

    fun generateKeyset(): HpkeKeysetPair {
        val privateHandle = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
        return HpkeKeysetPair(
            privateKeysetJson = TinkJsonProtoKeysetFormat.serializeKeyset(
                privateHandle,
                InsecureSecretKeyAccess.get(),
            ),
            publicKeysetJson = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
                privateHandle.publicKeysetHandle,
            ),
        )
    }

    fun encrypt(
        publicKeysetJson: String,
        plaintext: ByteArray,
        contextInfo: ByteArray,
    ): ByteArray {
        val handle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(publicKeysetJson)
        validatePublicHandle(handle)
        val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return primitive.encrypt(plaintext, contextInfo)
    }

    fun validatePublicKeyset(publicKeysetJson: String) {
        val handle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(publicKeysetJson)
        validatePublicHandle(handle)
        handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    fun decrypt(
        privateKeysetJson: String,
        ciphertext: ByteArray,
        contextInfo: ByteArray,
    ): ByteArray {
        val handle = TinkJsonProtoKeysetFormat.parseKeyset(
            privateKeysetJson,
            InsecureSecretKeyAccess.get(),
        )
        validatePrivateHandle(handle)
        val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        return primitive.decrypt(ciphertext, contextInfo)
    }

    private fun validatePublicHandle(handle: KeysetHandle) {
        require(handle.size() == 1) { "HPKE public keyset must contain exactly one key" }
        val entry = handle.getAt(0)
        require(entry.isPrimary && entry.status == KeyStatus.ENABLED) { "HPKE public key must be primary and enabled" }
        val key = entry.key as? HpkePublicKey
            ?: throw IllegalArgumentException("Export key must be an HPKE public key")
        validateParameters(key.parameters)
    }

    private fun validatePrivateHandle(handle: KeysetHandle) {
        require(handle.size() == 1) { "HPKE private keyset must contain exactly one key" }
        val entry = handle.getAt(0)
        require(entry.isPrimary && entry.status == KeyStatus.ENABLED) { "HPKE private key must be primary and enabled" }
        val key = entry.key as? HpkePrivateKey
            ?: throw IllegalArgumentException("Export decryption key must be an HPKE private key")
        validateParameters(key.parameters)
    }

    private fun validateParameters(parameters: HpkeParameters) {
        require(parameters.kemId == HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256) {
            "Export HPKE KEM must be X25519/HKDF-SHA256"
        }
        require(parameters.kdfId == HpkeParameters.KdfId.HKDF_SHA256) {
            "Export HPKE KDF must be HKDF-SHA256"
        }
        require(parameters.aeadId == HpkeParameters.AeadId.AES_256_GCM) {
            "Export HPKE AEAD must be AES-256-GCM"
        }
        require(parameters.variant == HpkeParameters.Variant.TINK) { "Export HPKE key must use the TINK variant" }
    }
}
