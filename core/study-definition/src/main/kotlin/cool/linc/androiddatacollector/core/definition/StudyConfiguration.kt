package cool.linc.androiddatacollector.core.definition

import java.time.Instant

data class StudyConfiguration(
    val schemaVersion: Int,
    val experimentId: String,
    val configurationId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val minimumAppVersion: Int,
    val title: String,
    val researcherName: String,
    val researcherContact: String,
    val purpose: String,
    val durationHours: Int,
    val consentDocumentVersion: String,
    val consentSummary: String,
    val collectors: List<CollectorConfiguration>,
    val prompts: List<PromptConfiguration>,
    val maximumLocalBytes: Long,
    val signer: SignerIdentity,
    val export: ExportConfiguration,
    val upload: UploadConfiguration?,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported configuration schema" }
        require(ID.matches(experimentId)) { "Invalid experiment ID" }
        require(ID.matches(configurationId)) { "Invalid configuration ID" }
        require(issuedAt < expiresAt) { "Configuration expiry must follow issue time" }
        require(minimumAppVersion > 0) { "Minimum app version must be positive" }
        require(title.length in 1..120) { "Invalid study title" }
        require(researcherName.length in 1..120) { "Invalid researcher name" }
        require(researcherContact.length in 3..240) { "Invalid researcher contact" }
        require(purpose.length in 1..2_000) { "Invalid study purpose" }
        require(durationHours in 1..8_760) { "Invalid study duration" }
        require(consentDocumentVersion.length in 1..64) { "Invalid consent document version" }
        require(consentSummary.length in 1..8_000) { "Invalid consent summary" }
        require(collectors.isNotEmpty()) { "At least one collector is required" }
        require(collectors.map { it.id }.distinct().size == collectors.size) { "Duplicate collector ID" }
        require(prompts.map { it.id }.distinct().size == prompts.size) { "Duplicate prompt ID" }
        require(maximumLocalBytes in MINIMUM_LOCAL_BYTES..MAXIMUM_LOCAL_BYTES) { "Invalid local quota" }
    }

    companion object {
        /**
         * The only accepted schema. There is no fallback reader and no migration: a configuration
         * either matches this exactly or is refused, which is what keeps the closed-world key
         * checks meaningful. Adding a root key is a version bump that invalidates every existing
         * file, so the number moves only when the format really changes in the field.
         */
        const val CURRENT_SCHEMA_VERSION = 1
        /**
         * Local budget a study may claim, 8 MiB to 8 GiB. The floor leaves room for the metadata
         * reserve; the ceiling is generous because high-rate collectors fill space quickly — an
         * accelerometer at 100 Hz produces tens of megabytes per hour. Ask for what the study
         * needs, not for the maximum: this is space on someone's personal phone.
         */
        const val MINIMUM_LOCAL_BYTES = 8L shl 20
        const val MAXIMUM_LOCAL_BYTES = 8L shl 30
        val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
    }
}

sealed interface CollectorConfiguration {
    val id: String
    val required: Boolean
}

data class AppLifecycleConfiguration(
    override val required: Boolean,
) : CollectorConfiguration {
    override val id: String = ID

    companion object { const val ID = "app_lifecycle.v1" }
}

data class AccelerometerConfiguration(
    override val required: Boolean,
    val samplingPeriodUs: Int,
    val maximumReportLatencyUs: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(samplingPeriodUs in 5_000..1_000_000) { "Invalid accelerometer sampling period" }
        require(maximumReportLatencyUs in 0..60_000_000) { "Invalid accelerometer report latency" }
    }

    companion object { const val ID = "accelerometer.v1" }
}

data class NetworkStateConfiguration(
    override val required: Boolean,
    val includeBandwidthEstimates: Boolean,
) : CollectorConfiguration {
    override val id: String = ID

    companion object { const val ID = "network_state.v1" }
}

enum class NetworkTransport { WIFI, MOBILE }

data class NetworkUsageConfiguration(
    override val required: Boolean,
    val transports: Set<NetworkTransport>,
    val pollIntervalMinutes: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(transports.isNotEmpty()) { "At least one network-usage transport is required" }
        require(pollIntervalMinutes in 1..1_440) { "Invalid network-usage poll interval" }
    }

    companion object { const val ID = "network_usage.v1" }
}

data class UsageEventsConfiguration(
    override val required: Boolean,
    val pollIntervalMinutes: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(pollIntervalMinutes in 1..1_440) { "Invalid usage-events poll interval" }
    }

    companion object { const val ID = "usage_events.v1" }
}

enum class LocationPriority { BALANCED, HIGH_ACCURACY }

data class LocationConfiguration(
    override val required: Boolean,
    val intervalMillis: Long,
    val minimumIntervalMillis: Long,
    val maximumBatchDelayMillis: Long,
    val minimumDisplacementMeters: Float,
    val priority: LocationPriority,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(intervalMillis in 1_000..3_600_000) { "Invalid location interval" }
        require(minimumIntervalMillis in 500..intervalMillis) { "Invalid location minimum interval" }
        require(maximumBatchDelayMillis in 0..86_400_000) { "Invalid location batch delay" }
        require(minimumDisplacementMeters in 0f..10_000f) { "Invalid location displacement" }
    }

    companion object { const val ID = "location.v1" }
}

data class KeyboardTouchConfiguration(
    override val required: Boolean,
    val trajectorySamplingHz: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(trajectorySamplingHz in 1..120) { "Invalid keyboard trajectory sampling rate" }
    }

    companion object { const val ID = "keyboard_touch.v1" }
}

data class PromptConfiguration(
    val id: String,
    val delayMinutes: Int,
    val message: String,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid prompt ID" }
        require(delayMinutes in 1..525_600) { "Invalid prompt delay" }
        require(message.length in 1..500) { "Invalid prompt message" }
    }
}

/**
 * Who signed this configuration.
 *
 * The public key lives inside the signed bytes, so a configuration certifies itself: verifying it
 * needs nothing but the file. That keeps one published app able to run any researcher's study, at
 * the cost that a signature proves only "unchanged since signing", not who wrote it. A build may
 * still pin a set of accepted signers — see `ConfigurationVerifier` — and the consent screen shows
 * [fingerprint] so a participant can compare it against what the research team published.
 */
data class SignerIdentity(
    val keyId: String,
    /** Base64 X.509 SubjectPublicKeyInfo for an Ed25519 key. */
    val publicKey: String,
) {
    init {
        require(StudyConfiguration.ID.matches(keyId)) { "Invalid signer key ID" }
        require(publicKey.length in 32..1_024) { "Invalid signer public key" }
    }

    /**
     * SHA-256 over the encoded public key, as 8 uppercase groups of 4 hex characters. Short enough
     * for a research team to print on a recruitment sheet and a participant to check by eye.
     */
    val fingerprint: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.util.Base64.getDecoder().decode(publicKey))
            .take(16)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }
}

data class ExportConfiguration(
    val researcherKeyId: String,
    val tinkHpkePublicKeysetJson: String,
) {
    init {
        require(StudyConfiguration.ID.matches(researcherKeyId)) { "Invalid researcher key ID" }
        require(tinkHpkePublicKeysetJson.length in 32..16_384) { "Invalid researcher public keyset" }
    }
}

/**
 * Scheduled delivery of collected events to a researcher endpoint. Absent when the study
 * relies solely on participant-initiated export.
 *
 * The payload is the same HPKE-encrypted bundle the participant would export by hand, so the
 * endpoint stores ciphertext it cannot read. `allowMetered` defaults to false in the schema
 * because uploading over a participant's mobile data is a cost they did not agree to unless
 * the study says so and the consent text discloses it.
 */
data class UploadConfiguration(
    val endpoint: String,
    val intervalMinutes: Int,
    val allowMetered: Boolean,
) {
    init {
        require(endpoint.length in 8..2_048) { "Invalid upload endpoint" }
        require(endpoint.startsWith("https://")) { "Upload endpoint must use https" }
        require(runCatching { java.net.URI(endpoint) }.getOrNull()?.host?.isNotEmpty() == true) {
            "Invalid upload endpoint"
        }
        require(intervalMinutes in MINIMUM_INTERVAL_MINUTES..MAXIMUM_INTERVAL_MINUTES) {
            "Invalid upload interval"
        }
    }

    companion object {
        /**
         * Delivery is scheduled as a self-renewing one-time job rather than WorkManager periodic
         * work, whose floor is 15 minutes. That floor would have made a configured cadence below it
         * a false statement on the consent screen.
         */
        const val MINIMUM_INTERVAL_MINUTES = 1
        const val MAXIMUM_INTERVAL_MINUTES = 10_080
    }
}
