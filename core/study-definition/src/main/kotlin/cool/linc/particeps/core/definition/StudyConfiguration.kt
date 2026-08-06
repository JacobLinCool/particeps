package cool.linc.particeps.core.definition

import java.time.Instant

data class StudyConfiguration(
    val schemaVersion: Int,
    val experimentId: String,
    val configurationId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val platform: String,
    val minimumClientVersion: Long,
    val title: String,
    val researcherName: String,
    val researcherContact: String,
    val purpose: String,
    val durationHours: Int,
    val consentDocumentVersion: String,
    val consentSummary: String,
    /** Researcher-assigned opaque code. Null means anonymous/pseudonymous distribution. */
    val assignedParticipantId: String?,
    val collectors: List<CollectorConfiguration>,
    val surveys: List<SurveyDefinition>,
    val interventions: List<InterventionConfiguration>,
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
        require(platform == ANDROID_PLATFORM) { "Unsupported target platform" }
        require(minimumClientVersion > 0) { "Minimum client version must be positive" }
        require(title.length in 1..120) { "Invalid study title" }
        require(researcherName.length in 1..120) { "Invalid researcher name" }
        require(researcherContact.length in 3..240) { "Invalid researcher contact" }
        require(purpose.length in 1..2_000) { "Invalid study purpose" }
        require(durationHours in 1..8_760) { "Invalid study duration" }
        require(consentDocumentVersion.length in 1..64) { "Invalid consent document version" }
        require(consentSummary.length in 1..8_000) { "Invalid consent summary" }
        assignedParticipantId?.let {
            require(ASSIGNED_PARTICIPANT_ID.matches(it) && it.toByteArray().size <= 64) {
                "Invalid assigned participant ID"
            }
        }
        require(collectors.isNotEmpty()) { "At least one collector is required" }
        require(collectors.map { it.id }.distinct().size == collectors.size) { "Duplicate collector ID" }
        require(surveys.map { it.id }.distinct().size == surveys.size) { "Duplicate survey ID" }
        require(interventions.map { it.id }.distinct().size == interventions.size) { "Duplicate intervention ID" }
        require(interventions.flatMap { intervention -> intervention.triggers.map { it.id } }.let {
            it.distinct().size == it.size
        }) { "Duplicate intervention trigger ID" }
        var maximumOccurrenceCount = 0L
        interventions.forEach { intervention ->
            intervention.triggers.forEach { it.schedule.requireWithin(durationHours * 60) }
            maximumOccurrenceCount += intervention.triggers.sumOf {
                it.schedule.maximumOccurrences(durationHours * 60)
            }
            (intervention.action as? SurveyAction)?.let { action ->
                require(surveys.any { it.id == action.surveyId }) { "Unknown survey ID" }
            }
        }
        require(maximumOccurrenceCount <= MAXIMUM_OCCURRENCES) { "Too many intervention occurrences" }
        require(maximumLocalBytes in MINIMUM_LOCAL_BYTES..MAXIMUM_LOCAL_BYTES) { "Invalid local quota" }
    }

    companion object {
        /**
         * The only accepted pre-1.0 Protocol v1 schema. Protocol v1 is replaced in place while it
         * is pre-release: there is no legacy reader, fallback, or migration. An artifact either
         * matches the current closed-world contract exactly or is refused.
         */
        const val CURRENT_SCHEMA_VERSION = 1
        const val ANDROID_PLATFORM = "android"
        /**
         * Local budget a study may claim, 8 MiB to 8 GiB. The floor leaves room for the metadata
         * reserve; the ceiling is generous because high-rate collectors fill space quickly — an
         * accelerometer at 100 Hz produces tens of megabytes per hour. Ask for what the study
         * needs, not for the maximum: this is space on someone's personal phone.
         */
        const val MINIMUM_LOCAL_BYTES = 8L shl 20
        const val MAXIMUM_LOCAL_BYTES = 8L shl 30
        val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        val ASSIGNED_PARTICIPANT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        const val MAXIMUM_OCCURRENCES = 512L
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

data class BatteryStateConfiguration(
    override val required: Boolean,
) : CollectorConfiguration {
    override val id: String = ID

    companion object { const val ID = "battery_state.v1" }
}

data class TemporalContextConfiguration(
    override val required: Boolean,
) : CollectorConfiguration {
    override val id: String = ID

    companion object { const val ID = "temporal_context.v1" }
}

data class GyroscopeConfiguration(
    override val required: Boolean,
    val samplingPeriodUs: Int,
    val maximumReportLatencyUs: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(samplingPeriodUs in 5_000..1_000_000) { "Invalid gyroscope sampling period" }
        require(maximumReportLatencyUs in 0..60_000_000) { "Invalid gyroscope report latency" }
    }

    companion object { const val ID = "gyroscope.v1" }
}

data class AmbientLightConfiguration(
    override val required: Boolean,
    val samplingPeriodUs: Int,
    val changeThresholdMillilux: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(samplingPeriodUs in 200_000..10_000_000) { "Invalid ambient-light sampling period" }
        require(changeThresholdMillilux in 0..100_000_000) { "Invalid ambient-light change threshold" }
    }

    companion object { const val ID = "ambient_light.v1" }
}

data class ProximityConfiguration(
    override val required: Boolean,
    val minimumEventIntervalMs: Int,
    val changeThresholdMillimeters: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(minimumEventIntervalMs in 100..60_000) { "Invalid proximity event interval" }
        require(changeThresholdMillimeters in 0..10_000) { "Invalid proximity change threshold" }
    }

    companion object { const val ID = "proximity.v1" }
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
    val minimumDisplacementMillimeters: Int,
    val priority: LocationPriority,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(intervalMillis in 1_000..3_600_000) { "Invalid location interval" }
        require(minimumIntervalMillis in 500..intervalMillis) { "Invalid location minimum interval" }
        require(maximumBatchDelayMillis in 0..86_400_000) { "Invalid location batch delay" }
        require(minimumDisplacementMillimeters in 0..10_000_000) { "Invalid location displacement" }
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

data class InterventionConfiguration(
    val id: String,
    val action: InterventionAction,
    val triggers: List<InterventionTrigger>,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid intervention ID" }
        require(triggers.isNotEmpty()) { "An intervention needs at least one trigger" }
        require(triggers.map { it.id }.distinct().size == triggers.size) { "Duplicate trigger ID" }
    }
}

sealed interface InterventionAction {
    val notificationTitle: String
    val notificationMessage: String
}

data class NotificationAction(
    override val notificationTitle: String,
    override val notificationMessage: String,
) : InterventionAction {
    init { validateNotificationText(notificationTitle, notificationMessage) }
}

data class SurveyAction(
    override val notificationTitle: String,
    override val notificationMessage: String,
    val surveyId: String,
) : InterventionAction {
    init {
        validateNotificationText(notificationTitle, notificationMessage)
        require(StudyConfiguration.ID.matches(surveyId)) { "Invalid survey ID" }
    }
}

private fun validateNotificationText(title: String, message: String) {
    require(title.length in 1..120) { "Invalid notification title" }
    require(message.length in 1..500) { "Invalid notification message" }
}

data class InterventionTrigger(
    val id: String,
    val schedule: InterventionSchedule,
    val availabilityMinutes: Int,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid trigger ID" }
        require(availabilityMinutes in 1..525_600) { "Invalid availability window" }
    }
}

sealed interface InterventionSchedule {
    fun requireWithin(studyMinutes: Int)
    fun maximumOccurrences(studyMinutes: Int): Long
}

/**
 * Conservative count of local dates reachable while Android's zone can move between its legal
 * fixed-offset extremes (UTC-18 through UTC+18). The extra partial dates matter to the global
 * durable-occurrence bound even for a study shorter than one day.
 */
internal fun maximumReachableLocalDates(studyMinutes: Int): Long =
    (studyMinutes + MAXIMUM_ZONE_OFFSET_SPAN_MINUTES + 1_439L) / 1_440L + 1

private const val MAXIMUM_ZONE_OFFSET_SPAN_MINUTES = 36 * 60

enum class RelativeClock { CALENDAR_TIME, ACTIVE_RUNNING_TIME }

data class OneTimeSchedule(
    val offsetMinutes: Int,
    val clock: RelativeClock,
) : InterventionSchedule {
    init { require(offsetMinutes >= 0) { "Invalid one-time offset" } }
    override fun requireWithin(studyMinutes: Int) {
        require(offsetMinutes < studyMinutes) { "One-time trigger is outside the study" }
    }
    override fun maximumOccurrences(studyMinutes: Int) = 1L
}

data class IntervalSchedule(
    val startOffsetMinutes: Int,
    val intervalMinutes: Int,
    val clock: RelativeClock,
) : InterventionSchedule {
    init {
        require(startOffsetMinutes >= 0) { "Invalid interval start" }
        require(intervalMinutes in 1..525_600) { "Invalid trigger interval" }
    }
    override fun requireWithin(studyMinutes: Int) {
        require(startOffsetMinutes < studyMinutes) { "Interval trigger is outside the study" }
    }
    override fun maximumOccurrences(studyMinutes: Int): Long =
        (studyMinutes - startOffsetMinutes + intervalMinutes - 1L) / intervalMinutes
}

data class DailyLocalSchedule(
    /** Strict 24-hour local time, `HH:mm`. */
    val localTime: String,
) : InterventionSchedule {
    init { require(LOCAL_TIME.matches(localTime)) { "Invalid daily local time" } }
    override fun requireWithin(studyMinutes: Int) = Unit
    override fun maximumOccurrences(studyMinutes: Int): Long = maximumReachableLocalDates(studyMinutes)

    companion object { private val LOCAL_TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]") }
}

data class RandomLocalWindow(
    /** Inclusive local wall-clock minute, `HH:mm`. */
    val startLocalTime: String,
    /** Exclusive local wall-clock minute on the same local date, `HH:mm`. */
    val endLocalTime: String,
) {
    val startMinute: Int = parseLocalMinute(startLocalTime)
    val endMinute: Int = parseLocalMinute(endLocalTime)

    init { require(startMinute < endMinute) { "Random window must end after it starts" } }
}

data class RandomWindowSchedule(
    val localWindows: List<RandomLocalWindow>,
    val occurrencesPerWindow: Int,
    val maximumOccurrencesPerDay: Int,
    val maximumOccurrencesTotal: Int,
    val minimumSeparationMinutes: Int,
) : InterventionSchedule {
    init {
        require(localWindows.size in 1..8) { "Invalid random-window count" }
        require(localWindows.zipWithNext().all { (first, second) -> first.endMinute <= second.startMinute }) {
            "Random windows must be sorted and non-overlapping"
        }
        require(occurrencesPerWindow in 1..8) { "Invalid occurrences per random window" }
        require(maximumOccurrencesPerDay in 1..64) { "Invalid daily random occurrence limit" }
        require(maximumOccurrencesPerDay <= localWindows.size * occurrencesPerWindow) {
            "Daily random occurrence limit exceeds window capacity"
        }
        require(maximumOccurrencesTotal in 1..512) { "Invalid total random occurrence limit" }
        require(minimumSeparationMinutes in 1..1_440) { "Invalid random occurrence separation" }
        require(localWindows.all { window ->
            window.endMinute - window.startMinute >=
                1 + (occurrencesPerWindow - 1) * minimumSeparationMinutes
        }) { "A random window cannot fit its configured occurrences" }
        require(localWindows.indices.all { index ->
            val current = localWindows[index]
            val next = localWindows[(index + 1) % localWindows.size]
            val nextStart = next.startMinute + if (index == localWindows.lastIndex) 1_440 else 0
            nextStart - (current.endMinute - 1) >= minimumSeparationMinutes
        }) { "Random windows are too close for the configured separation" }
    }

    override fun requireWithin(studyMinutes: Int) = Unit

    // Wall-clock edits can expose arbitrarily many local dates inside a short monotonic study.
    // The signed lifetime cap is therefore the only safe contribution to the global 512 bound.
    override fun maximumOccurrences(studyMinutes: Int): Long = maximumOccurrencesTotal.toLong()
}

private fun parseLocalMinute(value: String): Int {
    require(LOCAL_MINUTE.matches(value)) { "Invalid local time" }
    return value.substring(0, 2).toInt() * 60 + value.substring(3).toInt()
}

private val LOCAL_MINUTE = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

data class SurveyDefinition(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val questions: List<SurveyQuestion>,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid survey ID" }
        require(questions.size in 1..100) { "Invalid survey question count" }
        require(questions.map { it.id }.distinct().size == questions.size) { "Duplicate survey question ID" }
    }
}

data class LocalizedText(
    val default: String,
    val translations: Map<String, String> = emptyMap(),
) {
    init {
        require(default.length in 1..2_000) { "Invalid default localized text" }
        require(translations.size <= 32) { "Too many localized values" }
        require(translations.keys.map(String::lowercase).distinct().size == translations.size) {
            "Duplicate localized language tag"
        }
        translations.forEach { (language, value) ->
            require(BCP47.matches(language)) { "Invalid language tag" }
            require(value.length in 1..2_000) { "Invalid localized text" }
        }
    }
    fun resolve(languageTag: String): String {
        translations.entries.firstOrNull { it.key.equals(languageTag, ignoreCase = true) }?.let { return it.value }
        val requested = languageTag.lowercase().split('-')
        return translations.entries
            .filter { it.key.substringBefore('-').equals(requested.first(), ignoreCase = true) }
            .sortedByDescending { it.key.count { character -> character == '-' } }
            .firstOrNull { (tag) -> tag.lowercase().split('-').drop(1).all(requested::contains) }
            ?.value
            ?: default
    }
    companion object { private val BCP47 = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*") }
}

sealed interface SurveyQuestion {
    val id: String
    val prompt: LocalizedText
    val required: Boolean
}

data class ShortTextQuestion(
    override val id: String,
    override val prompt: LocalizedText,
    override val required: Boolean,
    val maximumLength: Int,
) : SurveyQuestion {
    init {
        validateQuestionId(id)
        require(maximumLength in 1..4_000) { "Invalid short-text limit" }
    }
}

data class ScaleQuestion(
    override val id: String,
    override val prompt: LocalizedText,
    override val required: Boolean,
    val minimum: Int,
    val maximum: Int,
    val minimumLabel: LocalizedText,
    val maximumLabel: LocalizedText,
) : SurveyQuestion {
    init {
        validateQuestionId(id)
        require(minimum in -1_000..1_000 && maximum in -1_000..1_000 && minimum < maximum) {
            "Invalid scale bounds"
        }
    }
}

data class SingleChoiceQuestion(
    override val id: String,
    override val prompt: LocalizedText,
    override val required: Boolean,
    val options: List<ChoiceOption>,
) : SurveyQuestion {
    init {
        validateQuestionId(id)
        validateOptions(options)
    }
}

data class MultipleChoiceQuestion(
    override val id: String,
    override val prompt: LocalizedText,
    override val required: Boolean,
    val options: List<ChoiceOption>,
    val minimumSelections: Int,
    val maximumSelections: Int,
) : SurveyQuestion {
    init {
        validateQuestionId(id)
        validateOptions(options)
        require(minimumSelections in 0..options.size) { "Invalid minimum selections" }
        require(maximumSelections in maxOf(1, minimumSelections)..options.size) { "Invalid maximum selections" }
        if (required) require(minimumSelections > 0) { "Required multiple choice needs a selection" }
    }
}

data class ChoiceOption(val id: String, val label: LocalizedText) {
    init { require(StudyConfiguration.ID.matches(id)) { "Invalid choice option ID" } }
}

private fun validateQuestionId(id: String) =
    require(StudyConfiguration.ID.matches(id)) { "Invalid survey question ID" }

private fun validateOptions(options: List<ChoiceOption>) {
    require(options.size in 2..50) { "Invalid choice option count" }
    require(options.map { it.id }.distinct().size == options.size) { "Duplicate choice option ID" }
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
    /** Unpadded base64url raw 32-byte Ed25519 public key. */
    val publicKey: String,
) {
    init {
        require(StudyConfiguration.ID.matches(keyId)) { "Invalid signer key ID" }
        ProtocolBase64Url.decodeExact(publicKey, RAW_PUBLIC_KEY_BYTES, "signer public key")
    }

    /**
     * SHA-256 over the encoded public key, as 8 uppercase groups of 4 hex characters. Short enough
     * for a research team to print on a recruitment sheet and a participant to check by eye.
     */
    val fingerprint: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(ProtocolBase64Url.decodeExact(publicKey, RAW_PUBLIC_KEY_BYTES, "signer public key"))
            .take(16)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }

    companion object { const val RAW_PUBLIC_KEY_BYTES = 32 }
}

data class ExportConfiguration(
    val researcherKeyId: String,
    /** Unpadded base64url raw 32-byte X25519 public key. */
    val hpkePublicKey: String,
) {
    init {
        require(StudyConfiguration.ID.matches(researcherKeyId)) { "Invalid researcher key ID" }
        ProtocolBase64Url.decodeExact(hpkePublicKey, RAW_PUBLIC_KEY_BYTES, "researcher public key")
    }


    companion object { const val RAW_PUBLIC_KEY_BYTES = 32 }
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
