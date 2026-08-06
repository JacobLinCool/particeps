package cool.linc.particeps.core.crypto

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HpkeCryptoTest {
    @Test
    fun rawNoPrefixHpkeRoundTripsWithTheExactProtocolSuite() {
        val keys = HpkeCrypto.generateKeyPair()
        val plaintext = ByteArray(32) { it.toByte() }
        val context = "particeps protocol context".toByteArray()

        HpkeCrypto.validatePublicKey(keys.publicKey)
        val ciphertext = HpkeCrypto.encrypt(keys.publicKey, plaintext, context)

        assertEquals(32, keys.privateKey.size)
        assertEquals(32, keys.publicKey.size)
        assertEquals(80, ciphertext.size)
        assertArrayEquals(plaintext, HpkeCrypto.decrypt(keys.privateKey, ciphertext, context))
    }

    @Test
    fun hpkeFailsClosedForWrongKeyContextAndCiphertext() {
        val keys = HpkeCrypto.generateKeyPair()
        val other = HpkeCrypto.generateKeyPair()
        val context = "correct".toByteArray()
        val ciphertext = HpkeCrypto.encrypt(keys.publicKey, ByteArray(32) { 7 }, context)

        assertThrows(GeneralSecurityException::class.java) {
            HpkeCrypto.decrypt(keys.privateKey, ciphertext, "wrong".toByteArray())
        }
        assertThrows(GeneralSecurityException::class.java) {
            HpkeCrypto.decrypt(other.privateKey, ciphertext, context)
        }
        assertThrows(GeneralSecurityException::class.java) {
            HpkeCrypto.decrypt(keys.privateKey, ciphertext.copyOf().also { it[it.lastIndex]++ }, context)
        }
    }

    @Test
    fun rejectsLegacyKeysetsAndInvalidRawLengths() {
        val legacyJson = "{\"primaryKeyId\":123,\"key\":[]}".toByteArray()
        assertFalse(legacyJson.size == HpkeCrypto.RAW_KEY_BYTES)
        assertThrows(IllegalArgumentException::class.java) { HpkeCrypto.validatePublicKey(legacyJson) }
        assertThrows(IllegalArgumentException::class.java) {
            HpkeCrypto.encrypt(ByteArray(31), ByteArray(32), ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HpkeCrypto.decrypt(ByteArray(33), ByteArray(80), ByteArray(0))
        }
        assertThrows(GeneralSecurityException::class.java) {
            HpkeCrypto.validatePublicKey(ByteArray(32))
        }
    }
}
