package cool.jacoblin.particeps.core.protocol

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Immutable, closed-world transport pointer for one signed configuration artifact. */
data class JoinLink(
    val artifactUrl: URI,
    val artifactSha256: String,
    /** SHA-256/128 signer fingerprint as 32 uppercase hexadecimal characters, without spaces. */
    val signerFingerprint: String,
) {
    init {
        requireCanonicalArtifactUrl(artifactUrl)
        require(SHA256.matches(artifactSha256)) { "Invalid join artifact SHA-256" }
        require(FINGERPRINT.matches(signerFingerprint)) { "Invalid join signer fingerprint" }
    }

    fun encode(): String = buildString {
        append(PREFIX)
        append("artifact=")
        append(percentEncode(artifactUrl.toASCIIString()))
        append("&sha256=")
        append(artifactSha256)
        append("&signer_fingerprint=")
        append(signerFingerprint)
    }.also {
        require(it.length <= MAXIMUM_JOIN_LINK_BYTES) { "Particeps join link is too long" }
    }

    fun displayFingerprint(): String = signerFingerprint.chunked(4).joinToString(" ")

    companion object {
        private const val PREFIX = "particeps://join/v1?"
        private const val MAXIMUM_JOIN_LINK_BYTES = 4_096
        private const val MAXIMUM_ARTIFACT_URL_BYTES = 2_048
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FINGERPRINT = Regex("[0-9A-F]{32}")
        private val QUERY_KEYS = listOf("artifact", "sha256", "signer_fingerprint")
        private val ARTIFACT_URL = Regex("https://([^/:?#]+)(?::([0-9]+))?(/[A-Za-z0-9._~/-]+)")
        private val HOST_LABEL = Regex("(?:[a-z0-9]|[a-z0-9][a-z0-9-]{0,61}[a-z0-9])")
        private val CANONICAL_PORT = Regex("[1-9][0-9]{0,4}")
        private val UNRESERVED =
            ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet() + setOf('-', '.', '_', '~')

        fun parse(encoded: String): JoinLink {
            require(encoded.length <= MAXIMUM_JOIN_LINK_BYTES && encoded.startsWith(PREFIX)) {
                "Invalid Particeps join link"
            }
            val rawParts = encoded.removePrefix(PREFIX).split('&')
            require(rawParts.size == QUERY_KEYS.size) { "Invalid Particeps join query" }
            val values = rawParts.mapIndexed { index, part ->
                val separator = part.indexOf('=')
                require(separator > 0 && part.indexOf('=', separator + 1) < 0) { "Invalid Particeps join query" }
                require(part.substring(0, separator) == QUERY_KEYS[index]) { "Invalid Particeps join query" }
                percentDecode(part.substring(separator + 1))
            }
            return JoinLink(
                artifactUrl = try {
                    URI(values[0])
                } catch (failure: URISyntaxException) {
                    throw IllegalArgumentException("Invalid join artifact URL", failure)
                },
                artifactSha256 = values[1],
                signerFingerprint = values[2],
            ).also { require(it.encode() == encoded) { "Particeps join link is not canonical" } }
        }

        private fun requireCanonicalArtifactUrl(uri: URI) {
            val value = uri.toASCIIString()
            require(value.length <= MAXIMUM_ARTIFACT_URL_BYTES) { "Join artifact URL is too long" }
            val match = requireNotNull(ARTIFACT_URL.matchEntire(value)) {
                "Join artifact URL is outside the canonical HTTPS profile"
            }
            val host = match.groupValues[1]
            require(host.length <= 253 && host.any(Char::isLetter) && host.split('.').all(HOST_LABEL::matches)) {
                "Join artifact URL has a noncanonical host"
            }
            match.groupValues[2].takeIf(String::isNotEmpty)?.let { port ->
                require(CANONICAL_PORT.matches(port) && port.toInt() in 1..65_535 && port != "443") {
                    "Join artifact URL has a noncanonical port"
                }
            }
            require(
                match.groupValues[3].removePrefix("/").split('/').all { segment ->
                    segment.isNotEmpty() && segment != "." && segment != ".."
                },
            ) { "Join artifact URL has a noncanonical path" }
        }

        private fun percentEncode(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val character = unsigned.toChar()
                if (character in UNRESERVED) {
                    append(character)
                } else {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
        }

        private fun percentDecode(value: String): String {
            val output = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character == '%' -> {
                        require(index + 2 < value.length) { "Invalid Particeps join escaping" }
                        val high = value[index + 1].digitToIntOrNull(16)
                        val low = value[index + 2].digitToIntOrNull(16)
                        require(high != null && low != null) { "Invalid Particeps join escaping" }
                        output.write((high shl 4) or low)
                        index += 3
                    }
                    character.code < 0x80 -> {
                        output.write(character.code)
                        index++
                    }
                    else -> throw IllegalArgumentException("Particeps join query must be ASCII")
                }
            }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
