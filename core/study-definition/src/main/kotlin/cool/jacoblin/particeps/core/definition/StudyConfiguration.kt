package cool.jacoblin.particeps.core.definition

import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
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
    val collectors: List<CollectorResourceConfiguration>,
    val surveys: List<SurveyDefinition>,
    val interventions: List<InterventionConfiguration>,
    val automations: List<AutomationDefinition>,
    val trafficShaping: TrafficShapingConfiguration,
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
        require(collectors.size <= MAXIMUM_COLLECTORS) { "Too many collector resources" }
        require(collectors.size + (if (trafficShaping is TrafficShapingConfiguration.Enabled) 1 else 0) <= MAXIMUM_RESOURCES) {
            "Too many stateful resources"
        }
        require(collectors == collectors.sortedBy(CollectorResourceConfiguration::id)) {
            "Collector resources must be sorted"
        }
        require(collectors.map { it.id }.distinct().size == collectors.size) { "Duplicate collector ID" }
        require(surveys.size <= MAXIMUM_SURVEYS) { "Too many surveys" }
        require(surveys.map { it.id }.distinct().size == surveys.size) { "Duplicate survey ID" }
        require(interventions == interventions.sortedBy(InterventionConfiguration::id)) {
            "Interventions must be sorted"
        }
        require(interventions.map { it.id }.distinct().size == interventions.size) { "Duplicate intervention ID" }
        require(automations.size <= MAXIMUM_AUTOMATIONS) { "Too many automations" }
        require(automations == automations.sortedBy(AutomationDefinition::id)) { "Automations must be sorted" }
        require(automations.map(AutomationDefinition::id).distinct().size == automations.size) {
            "Duplicate automation ID"
        }
        require(automations.all { ID.matches(it.id) }) { "Invalid automation ID" }
        val occurrenceAutomations = automations.filterIsInstance<OccurrenceAutomation>()
        require(occurrenceAutomations.sumOf { it.maximumActivations.toLong() } <= MAXIMUM_OCCURRENCES) {
            "Too many lifetime one-shot activations"
        }
        require(occurrenceAutomations.all { automation ->
            interventions.any { it.id == automation.interventionId }
        }) { "Occurrence automation references an unknown intervention" }
        require(interventions.all { intervention ->
            occurrenceAutomations.any { it.interventionId == intervention.id }
        }) { "Unused intervention definition" }
        interventions.forEach { intervention ->
            (intervention.action as? SurveyAction)?.let { action ->
                require(surveys.any { it.id == action.surveyId }) { "Unknown survey ID" }
            }
        }
        require(automations.isNotEmpty() ||
            (collectors.isEmpty() && interventions.isEmpty() && trafficShaping == TrafficShapingConfiguration.Disabled)
        ) { "Configured resources and interventions require automations" }
        val declaredProfiles = buildMap<ResourceKey, Set<String>> {
            collectors.forEach { collector ->
                put(collector.resourceKey, collector.profiles.mapTo(mutableSetOf(), NamedCollectorProfile::id))
            }
            (trafficShaping as? TrafficShapingConfiguration.Enabled)?.let { shaping ->
                put(
                    ResourceKey(ResourceKind.ACTUATOR, TRAFFIC_SHAPING_RESOURCE_ID),
                    shaping.profiles.mapTo(mutableSetOf(), TrafficShapingProfile::id),
                )
            }
        }
        val bindings = automations.filterIsInstance<ResourceBindingAutomation>()
        require(bindings.map(ResourceBindingAutomation::resource).toSet() == declaredProfiles.keys) {
            "Every declared resource requires exactly one binding automation"
        }
        require(bindings.map(ResourceBindingAutomation::resource).distinct().size == bindings.size) {
            "A stateful resource can have only one binding automation"
        }
        bindings.forEach { binding ->
            require(binding.cases.size in 1..MAXIMUM_BINDING_CASES) { "Invalid resource condition case count" }
            val profiles = requireNotNull(declaredProfiles[binding.resource]) { "Unknown automation resource" }
            require(binding.defaultProfileId == null || binding.defaultProfileId in profiles) {
                "Unknown default resource profile"
            }
            require(binding.cases.all { it.profileId == null || it.profileId in profiles }) {
                "Unknown resource condition profile"
            }
        }
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
        const val MAXIMUM_COLLECTORS = 64
        const val MAXIMUM_RESOURCES = 64
        const val MAXIMUM_SURVEYS = 128
        const val MAXIMUM_AUTOMATIONS = 128
        const val MAXIMUM_BINDING_CASES = 16
    }
}

data class InterventionConfiguration(
    val id: String,
    val required: Boolean,
    val action: InterventionAction,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid intervention ID" }
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
