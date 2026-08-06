package cool.linc.particeps.core.definition

import java.util.Base64

/** Strict unpadded base64url used for all Protocol v1 raw key and signature bytes. */
object ProtocolBase64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val textPattern = Regex("[A-Za-z0-9_-]+")

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String, label: String): ByteArray {
        require(textPattern.matches(text) && '=' !in text) { "Invalid $label encoding" }
        val decoded = runCatching { decoder.decode(text) }
            .getOrElse { throw IllegalArgumentException("Invalid $label encoding", it) }
        require(encode(decoded) == text) { "Invalid $label encoding" }
        return decoded
    }

    fun decodeExact(text: String, size: Int, label: String): ByteArray =
        decode(text, label).also { require(it.size == size) { "Invalid $label length" } }
}
