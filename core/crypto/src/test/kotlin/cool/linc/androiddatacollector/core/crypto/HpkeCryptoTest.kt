package cool.linc.androiddatacollector.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HpkeCryptoTest {
    @Test
    fun generatedHpkeKeysetEncryptsOnlyForMatchingPrivateKeyAndContext() {
        val keys = HpkeCrypto.generateKeyset()
        val plaintext = "research content key".toByteArray()
        val context = "experiment:config:key".toByteArray()

        HpkeCrypto.validatePublicKeyset(keys.publicKeysetJson)
        val ciphertext = HpkeCrypto.encrypt(keys.publicKeysetJson, plaintext, context)

        assertArrayEquals(plaintext, HpkeCrypto.decrypt(keys.privateKeysetJson, ciphertext, context))
        assertThrows(GeneralSecurityException::class.java) {
            HpkeCrypto.decrypt(keys.privateKeysetJson, ciphertext, "wrong-context".toByteArray())
        }
    }

    @Test
    fun publicKeyValidationRejectsAValidHpkeKeyWithTheWrongAeadSuite() {
        HybridConfig.register()
        val privateHandle = KeysetHandle.generateNew(
            HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .setVariant(HpkeParameters.Variant.TINK)
                .build(),
        )
        val publicJson = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
            privateHandle.publicKeysetHandle,
        )

        assertThrows(IllegalArgumentException::class.java) {
            HpkeCrypto.validatePublicKeyset(publicJson)
        }
    }
}
