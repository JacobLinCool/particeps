package cool.jacoblin.particeps.core.export

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StreamingAesGcmTest {
    private val random = SecureRandom()

    @Test
    fun streamingOutputEqualsPlatformGcmForEveryLengthAndWriteSplit() {
        val lengths = (0..70).toList() + listOf(255, 256, 257, 4_095, 4_096, 65_537, 1_048_577)
        lengths.forEach { length ->
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val nonce = ByteArray(12).also(random::nextBytes)
            val aad = ByteArray(random.nextInt(80)).also(random::nextBytes)
            val plaintext = ByteArray(length).also(random::nextBytes)
            val expected = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }
            val actual = ByteArrayOutputStream().also { sink ->
                StreamingAesGcmOutputStream(sink, key, nonce, aad).use { stream ->
                    var offset = 0
                    while (offset < plaintext.size) {
                        val take = minOf(plaintext.size - offset, 1 + random.nextInt(37))
                        if (take == 1) stream.write(plaintext[offset].toInt()) else stream.write(plaintext, offset, take)
                        offset += take
                    }
                }
            }.toByteArray()
            assertArrayEquals("length $length", expected, actual)
            val decrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(actual)
            }
            assertArrayEquals(plaintext, decrypted)
        }
    }
}
