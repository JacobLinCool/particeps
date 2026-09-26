package cool.jacoblin.particeps.core.automation

import java.security.MessageDigest

object DeterministicIds {
    fun actionId(
        configurationSha256: String,
        automationId: String,
        interventionId: String,
        triggerKind: String,
        causalIdentity: String,
        logicalDeadlineOrEmpty: String,
    ): String {
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        return digest(
            "particeps-action-v1",
            listOf(
                configurationSha256,
                automationId,
                interventionId,
                triggerKind,
                causalIdentity,
                logicalDeadlineOrEmpty,
            ),
        )
    }

    fun timerId(configurationSha256: String, automationId: String, producerKey: String): String {
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        return digest("particeps-timer-v1", listOf(configurationSha256, automationId, producerKey))
    }

    fun digest(domain: String, components: List<String>): String {
        require(domain.isNotBlank() && '\u0000' !in domain) { "Invalid digest domain" }
        require(components.none { '\u0000' in it }) { "Digest component contains NUL" }
        val encoded = (listOf(domain) + components).joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(encoded).toLowerHex()
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

private val LOWER_HEX = "0123456789abcdef".toCharArray()

/** Two lowercase hexadecimal digits per byte. */
internal fun ByteArray.toLowerHex(): String {
    val characters = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        characters[index * 2] = LOWER_HEX[value ushr 4]
        characters[index * 2 + 1] = LOWER_HEX[value and 0x0f]
    }
    return String(characters)
}
