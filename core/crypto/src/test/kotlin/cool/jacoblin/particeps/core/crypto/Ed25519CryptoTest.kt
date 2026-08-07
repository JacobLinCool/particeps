package cool.jacoblin.particeps.core.crypto

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519CryptoTest {
    @Test
    fun verifiesRawPublicKeysWithoutAProviderSpecificKeyFactory() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = pair.public.encoded.copyOfRange(
            pair.public.encoded.size - Ed25519Crypto.PUBLIC_KEY_BYTES,
            pair.public.encoded.size,
        )
        val message = "protocol-v1".toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(message)
            sign()
        }

        assertTrue(Ed25519Crypto.verify(publicKey, message, signature))
        assertFalse(Ed25519Crypto.verify(publicKey, message + 0, signature))
    }

    @Test
    fun rejectsInvalidWireLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            Ed25519Crypto.verify(ByteArray(31), ByteArray(0), ByteArray(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ed25519Crypto.verify(ByteArray(32), ByteArray(0), ByteArray(63))
        }
    }
}
