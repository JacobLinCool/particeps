package cool.linc.androiddatacollector.core.crypto

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/** Provider-independent Ed25519 verification for Protocol v1 raw public keys. */
object Ed25519Crypto {
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(publicKey.size == PUBLIC_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "Ed25519 signature must be 64 bytes" }
        return try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
}
