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
        return MessageDigest.getInstance("SHA-256").digest(encoded).toHex()
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
