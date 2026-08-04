package cool.linc.androiddatacollector.core.definition

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.math.BigDecimal
import java.time.Instant

object StudyConfigurationCodec {
    private val ROOT_KEYS = setOf(
        "schema_version",
        "experiment_id",
        "configuration_id",
        "assigned_participant_id",
        "issued_at",
        "expires_at",
        "platform",
        "minimum_client_version",
        "title",
        "researcher",
        "purpose",
        "duration_hours",
        "consent",
        "collectors",
        "surveys",
        "interventions",
        "storage",
        "signer",
        "export",
        "upload",
    )

    /** Keys required when the upload block is populated; an empty object disables upload. */
    private val UPLOAD_KEYS = setOf("endpoint", "interval_minutes", "allow_metered")

    fun decode(bytes: ByteArray): StudyConfiguration {
        val decoded = decodeStructure(bytes)
        require(encode(decoded).contentEquals(bytes)) { "Configuration JSON is not canonical" }
        return decoded
    }

    fun canonicalize(bytes: ByteArray): ByteArray = encode(decodeStructure(bytes))

    private fun decodeStructure(bytes: ByteArray): StudyConfiguration {
        require(bytes.size in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration size" }
        val root = ProtocolCanonicalJson.parse(bytes, MAX_CONFIGURATION_BYTES).requireObject("root")
        root.requireExactKeys(ROOT_KEYS)
        return StudyConfiguration(
            schemaVersion = root.requireInt("schema_version"),
            experimentId = root.requireString("experiment_id"),
            configurationId = root.requireString("configuration_id"),
            assignedParticipantId = root.requireNullableString("assigned_participant_id"),
            issuedAt = Instant.parse(root.requireString("issued_at")),
            expiresAt = Instant.parse(root.requireString("expires_at")),
            platform = root.requireString("platform"),
            minimumClientVersion = root.requireDecimalLongString("minimum_client_version"),
            title = root.requireString("title"),
            researcherName = root.requireObject("researcher").also {
                it.requireExactKeys(setOf("name", "contact"))
            }.requireString("name"),
            researcherContact = root.requireObject("researcher").requireString("contact"),
            purpose = root.requireString("purpose"),
            durationHours = root.requireInt("duration_hours"),
            consentDocumentVersion = root.requireObject("consent").also {
                it.requireExactKeys(setOf("document_version", "summary"))
            }.requireString("document_version"),
            consentSummary = root.requireObject("consent").requireString("summary"),
            collectors = root.requireArray("collectors").mapElements(::decodeCollector),
            surveys = root.requireArray("surveys").mapElements(::decodeSurvey),
            interventions = root.requireArray("interventions").mapElements(::decodeIntervention),
            maximumLocalBytes = root.requireObject("storage").also {
                it.requireExactKeys(setOf("maximum_local_bytes"))
            }.requireLong("maximum_local_bytes"),
            signer = root.requireObject("signer").let {
                it.requireExactKeys(setOf("key_id", "public_key"))
                SignerIdentity(it.requireString("key_id"), it.requireString("public_key"))
            },
            export = decodeExport(root.requireObject("export")),
            upload = decodeUpload(root.requireObject("upload")),
        )
    }

    fun encode(configuration: StudyConfiguration): ByteArray {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            writer.name("schema_version").value(configuration.schemaVersion)
            writer.name("experiment_id").value(configuration.experimentId)
            writer.name("configuration_id").value(configuration.configurationId)
            writer.name("assigned_participant_id").value(configuration.assignedParticipantId)
            writer.name("issued_at").value(configuration.issuedAt.toString())
            writer.name("expires_at").value(configuration.expiresAt.toString())
            writer.name("platform").value(configuration.platform)
            writer.name("minimum_client_version").value(configuration.minimumClientVersion.toString())
            writer.name("title").value(configuration.title)
            writer.name("researcher").beginObject()
            writer.name("name").value(configuration.researcherName)
            writer.name("contact").value(configuration.researcherContact)
            writer.endObject()
            writer.name("purpose").value(configuration.purpose)
            writer.name("duration_hours").value(configuration.durationHours)
            writer.name("consent").beginObject()
            writer.name("document_version").value(configuration.consentDocumentVersion)
            writer.name("summary").value(configuration.consentSummary)
            writer.endObject()
            writer.name("collectors").beginArray()
            configuration.collectors.forEach { encodeCollector(writer, it) }
            writer.endArray()
            writer.name("surveys").beginArray()
            configuration.surveys.forEach { encodeSurvey(writer, it) }
            writer.endArray()
            writer.name("interventions").beginArray()
            configuration.interventions.forEach { encodeIntervention(writer, it) }
            writer.endArray()
            writer.name("storage").beginObject()
            writer.name("maximum_local_bytes").value(configuration.maximumLocalBytes)
            writer.endObject()
            writer.name("signer").beginObject()
            writer.name("key_id").value(configuration.signer.keyId)
            writer.name("public_key").value(configuration.signer.publicKey)
            writer.endObject()
            writer.name("export").beginObject()
            writer.name("researcher_key_id").value(configuration.export.researcherKeyId)
            writer.name("hpke_public_key").value(configuration.export.hpkePublicKey)
            writer.endObject()
            writer.name("upload").beginObject()
            configuration.upload?.let { upload ->
                writer.name("endpoint").value(upload.endpoint)
                writer.name("interval_minutes").value(upload.intervalMinutes)
                writer.name("allow_metered").value(upload.allowMetered)
            }
            writer.endObject()
            writer.endObject()
        }
        return ProtocolCanonicalJson.encode(JsonParser.parseString(output.toString()))
    }

    private fun decodeCollector(element: JsonElement): CollectorConfiguration {
        val root = element.requireObject("collector")
        root.requireExactKeys(setOf("id", "required", "config"))
        val required = root.requireBoolean("required")
        val config = root.requireObject("config")
        return when (root.requireString("id")) {
            AppLifecycleConfiguration.ID -> {
                config.requireExactKeys(emptySet())
                AppLifecycleConfiguration(required)
            }
            AccelerometerConfiguration.ID -> {
                config.requireExactKeys(setOf("sampling_period_us", "maximum_report_latency_us"))
                AccelerometerConfiguration(
                    required,
                    config.requireInt("sampling_period_us"),
                    config.requireInt("maximum_report_latency_us"),
                )
            }
            BatteryStateConfiguration.ID -> {
                config.requireExactKeys(emptySet())
                BatteryStateConfiguration(required)
            }
            TemporalContextConfiguration.ID -> {
                config.requireExactKeys(emptySet())
                TemporalContextConfiguration(required)
            }
            GyroscopeConfiguration.ID -> {
                config.requireExactKeys(setOf("sampling_period_us", "maximum_report_latency_us"))
                GyroscopeConfiguration(
                    required,
                    config.requireInt("sampling_period_us"),
                    config.requireInt("maximum_report_latency_us"),
                )
            }
            AmbientLightConfiguration.ID -> {
                config.requireExactKeys(setOf("sampling_period_us", "change_threshold_millilux"))
                AmbientLightConfiguration(
                    required,
                    config.requireInt("sampling_period_us"),
                    config.requireInt("change_threshold_millilux"),
                )
            }
            ProximityConfiguration.ID -> {
                config.requireExactKeys(setOf("minimum_event_interval_ms", "change_threshold_millimeters"))
                ProximityConfiguration(
                    required,
                    config.requireInt("minimum_event_interval_ms"),
                    config.requireInt("change_threshold_millimeters"),
                )
            }
            NetworkStateConfiguration.ID -> {
                config.requireExactKeys(setOf("include_bandwidth_estimates"))
                NetworkStateConfiguration(required, config.requireBoolean("include_bandwidth_estimates"))
            }
            NetworkUsageConfiguration.ID -> {
                config.requireExactKeys(setOf("transports", "poll_interval_minutes"))
                val transports = config.requireArray("transports").mapElements { item ->
                    NetworkTransport.valueOf(item.requireStringValue("network transport").uppercase())
                }.toSet()
                NetworkUsageConfiguration(required, transports, config.requireInt("poll_interval_minutes"))
            }
            UsageEventsConfiguration.ID -> {
                config.requireExactKeys(setOf("poll_interval_minutes"))
                UsageEventsConfiguration(required, config.requireInt("poll_interval_minutes"))
            }
            LocationConfiguration.ID -> {
                config.requireExactKeys(
                    setOf(
                        "interval_millis",
                        "minimum_interval_millis",
                        "maximum_batch_delay_millis",
                        "minimum_displacement_millimeters",
                        "priority",
                    ),
                )
                LocationConfiguration(
                    required,
                    config.requireLong("interval_millis"),
                    config.requireLong("minimum_interval_millis"),
                    config.requireLong("maximum_batch_delay_millis"),
                    config.requireInt("minimum_displacement_millimeters"),
                    LocationPriority.valueOf(config.requireString("priority")),
                )
            }
            KeyboardTouchConfiguration.ID -> {
                config.requireExactKeys(setOf("trajectory_sampling_hz"))
                KeyboardTouchConfiguration(required, config.requireInt("trajectory_sampling_hz"))
            }
            else -> throw IllegalArgumentException("Unknown collector ID")
        }
    }

    private fun encodeCollector(writer: JsonWriter, collector: CollectorConfiguration) {
        writer.beginObject()
        writer.name("id").value(collector.id)
        writer.name("required").value(collector.required)
        writer.name("config").beginObject()
        when (collector) {
            is AppLifecycleConfiguration -> Unit
            is AccelerometerConfiguration -> {
                writer.name("sampling_period_us").value(collector.samplingPeriodUs)
                writer.name("maximum_report_latency_us").value(collector.maximumReportLatencyUs)
            }
            is BatteryStateConfiguration, is TemporalContextConfiguration -> Unit
            is GyroscopeConfiguration -> {
                writer.name("sampling_period_us").value(collector.samplingPeriodUs)
                writer.name("maximum_report_latency_us").value(collector.maximumReportLatencyUs)
            }
            is AmbientLightConfiguration -> {
                writer.name("sampling_period_us").value(collector.samplingPeriodUs)
                writer.name("change_threshold_millilux").value(collector.changeThresholdMillilux)
            }
            is ProximityConfiguration -> {
                writer.name("minimum_event_interval_ms").value(collector.minimumEventIntervalMs)
                writer.name("change_threshold_millimeters").value(collector.changeThresholdMillimeters)
            }
            is NetworkStateConfiguration ->
                writer.name("include_bandwidth_estimates").value(collector.includeBandwidthEstimates)
            is NetworkUsageConfiguration -> {
                writer.name("transports").beginArray()
                collector.transports.sortedBy { it.name }.forEach { writer.value(it.name.lowercase()) }
                writer.endArray()
                writer.name("poll_interval_minutes").value(collector.pollIntervalMinutes)
            }
            is UsageEventsConfiguration -> writer.name("poll_interval_minutes").value(collector.pollIntervalMinutes)
            is LocationConfiguration -> {
                writer.name("interval_millis").value(collector.intervalMillis)
                writer.name("minimum_interval_millis").value(collector.minimumIntervalMillis)
                writer.name("maximum_batch_delay_millis").value(collector.maximumBatchDelayMillis)
                writer.name("minimum_displacement_millimeters").value(collector.minimumDisplacementMillimeters)
                writer.name("priority").value(collector.priority.name)
            }
            is KeyboardTouchConfiguration ->
                writer.name("trajectory_sampling_hz").value(collector.trajectorySamplingHz)
        }
        writer.endObject()
        writer.endObject()
    }

    private fun decodeIntervention(element: JsonElement): InterventionConfiguration {
        val root = element.requireObject("intervention")
        root.requireExactKeys(setOf("id", "action", "triggers"))
        return InterventionConfiguration(
            id = root.requireString("id"),
            action = decodeAction(root.requireObject("action")),
            triggers = root.requireArray("triggers").mapElements(::decodeTrigger),
        )
    }

    private fun decodeAction(root: JsonObject): InterventionAction = when (root.requireString("type")) {
        "notification" -> {
            root.requireExactKeys(setOf("type", "notification_title", "notification_message"))
            NotificationAction(root.requireString("notification_title"), root.requireString("notification_message"))
        }
        "survey" -> {
            root.requireExactKeys(setOf("type", "notification_title", "notification_message", "survey_id"))
            SurveyAction(
                root.requireString("notification_title"),
                root.requireString("notification_message"),
                root.requireString("survey_id"),
            )
        }
        else -> throw IllegalArgumentException("Unknown intervention action")
    }

    private fun decodeTrigger(element: JsonElement): InterventionTrigger {
        val root = element.requireObject("trigger")
        root.requireExactKeys(setOf("id", "schedule", "availability_minutes"))
        val schedule = root.requireObject("schedule")
        return InterventionTrigger(
            root.requireString("id"),
            when (schedule.requireString("type")) {
                "one_time" -> {
                    schedule.requireExactKeys(setOf("type", "offset_minutes", "clock"))
                    OneTimeSchedule(schedule.requireInt("offset_minutes"), enumValueOf(schedule.requireString("clock")))
                }
                "interval" -> {
                    schedule.requireExactKeys(setOf("type", "start_offset_minutes", "interval_minutes", "clock"))
                    IntervalSchedule(
                        schedule.requireInt("start_offset_minutes"),
                        schedule.requireInt("interval_minutes"),
                        enumValueOf(schedule.requireString("clock")),
                    )
                }
                "daily_local" -> {
                    schedule.requireExactKeys(setOf("type", "local_time"))
                    DailyLocalSchedule(schedule.requireString("local_time"))
                }
                "random_window" -> {
                    schedule.requireExactKeys(
                        setOf(
                            "type",
                            "local_windows",
                            "occurrences_per_window",
                            "maximum_occurrences_per_day",
                            "maximum_occurrences_total",
                            "minimum_separation_minutes",
                        ),
                    )
                    RandomWindowSchedule(
                        localWindows = schedule.requireArray("local_windows").mapElements { item ->
                            val window = item.requireObject("random local window")
                            window.requireExactKeys(setOf("start_local_time", "end_local_time"))
                            RandomLocalWindow(
                                window.requireString("start_local_time"),
                                window.requireString("end_local_time"),
                            )
                        },
                        occurrencesPerWindow = schedule.requireInt("occurrences_per_window"),
                        maximumOccurrencesPerDay = schedule.requireInt("maximum_occurrences_per_day"),
                        maximumOccurrencesTotal = schedule.requireInt("maximum_occurrences_total"),
                        minimumSeparationMinutes = schedule.requireInt("minimum_separation_minutes"),
                    )
                }
                else -> throw IllegalArgumentException("Unknown intervention schedule")
            },
            root.requireInt("availability_minutes"),
        )
    }

    private fun decodeSurvey(element: JsonElement): SurveyDefinition {
        val root = element.requireObject("survey")
        root.requireExactKeys(setOf("id", "title", "description", "questions"))
        return SurveyDefinition(
            root.requireString("id"),
            decodeLocalizedText(root.requireObject("title")),
            decodeLocalizedText(root.requireObject("description")),
            root.requireArray("questions").mapElements(::decodeQuestion),
        )
    }

    private fun decodeLocalizedText(root: JsonObject): LocalizedText {
        root.requireExactKeys(setOf("default", "translations"))
        val translations = root.requireObject("translations")
        return LocalizedText(
            root.requireString("default"),
            translations.keySet().associateWith { language -> translations.requireString(language) },
        )
    }

    private fun decodeQuestion(element: JsonElement): SurveyQuestion {
        val root = element.requireObject("question")
        val id = root.requireString("id")
        val prompt = decodeLocalizedText(root.requireObject("prompt"))
        val required = root.requireBoolean("required")
        return when (root.requireString("type")) {
            "short_text" -> {
                root.requireExactKeys(setOf("type", "id", "prompt", "required", "maximum_length"))
                ShortTextQuestion(id, prompt, required, root.requireInt("maximum_length"))
            }
            "scale" -> {
                root.requireExactKeys(
                    setOf("type", "id", "prompt", "required", "minimum", "maximum", "minimum_label", "maximum_label"),
                )
                ScaleQuestion(
                    id,
                    prompt,
                    required,
                    root.requireInt("minimum"),
                    root.requireInt("maximum"),
                    decodeLocalizedText(root.requireObject("minimum_label")),
                    decodeLocalizedText(root.requireObject("maximum_label")),
                )
            }
            "single_choice" -> {
                root.requireExactKeys(setOf("type", "id", "prompt", "required", "options"))
                SingleChoiceQuestion(id, prompt, required, root.requireArray("options").mapElements(::decodeChoice))
            }
            "multiple_choice" -> {
                root.requireExactKeys(
                    setOf("type", "id", "prompt", "required", "options", "minimum_selections", "maximum_selections"),
                )
                MultipleChoiceQuestion(
                    id,
                    prompt,
                    required,
                    root.requireArray("options").mapElements(::decodeChoice),
                    root.requireInt("minimum_selections"),
                    root.requireInt("maximum_selections"),
                )
            }
            else -> throw IllegalArgumentException("Unknown survey question type")
        }
    }

    private fun decodeChoice(element: JsonElement): ChoiceOption {
        val root = element.requireObject("choice")
        root.requireExactKeys(setOf("id", "label"))
        return ChoiceOption(root.requireString("id"), decodeLocalizedText(root.requireObject("label")))
    }

    private fun encodeIntervention(writer: JsonWriter, intervention: InterventionConfiguration) {
        writer.beginObject()
        writer.name("id").value(intervention.id)
        writer.name("action").beginObject()
        when (val action = intervention.action) {
            is NotificationAction -> writer.name("type").value("notification")
            is SurveyAction -> writer.name("type").value("survey")
        }
        writer.name("notification_title").value(intervention.action.notificationTitle)
        writer.name("notification_message").value(intervention.action.notificationMessage)
        (intervention.action as? SurveyAction)?.let { writer.name("survey_id").value(it.surveyId) }
        writer.endObject()
        writer.name("triggers").beginArray()
        intervention.triggers.forEach { trigger ->
            writer.beginObject()
            writer.name("id").value(trigger.id)
            writer.name("schedule").beginObject()
            when (val schedule = trigger.schedule) {
                is OneTimeSchedule -> {
                    writer.name("type").value("one_time")
                    writer.name("offset_minutes").value(schedule.offsetMinutes)
                    writer.name("clock").value(schedule.clock.name)
                }
                is IntervalSchedule -> {
                    writer.name("type").value("interval")
                    writer.name("start_offset_minutes").value(schedule.startOffsetMinutes)
                    writer.name("interval_minutes").value(schedule.intervalMinutes)
                    writer.name("clock").value(schedule.clock.name)
                }
                is DailyLocalSchedule -> {
                    writer.name("type").value("daily_local")
                    writer.name("local_time").value(schedule.localTime)
                }
                is RandomWindowSchedule -> {
                    writer.name("type").value("random_window")
                    writer.name("local_windows").beginArray()
                    schedule.localWindows.forEach { window ->
                        writer.beginObject()
                        writer.name("start_local_time").value(window.startLocalTime)
                        writer.name("end_local_time").value(window.endLocalTime)
                        writer.endObject()
                    }
                    writer.endArray()
                    writer.name("occurrences_per_window").value(schedule.occurrencesPerWindow)
                    writer.name("maximum_occurrences_per_day").value(schedule.maximumOccurrencesPerDay)
                    writer.name("maximum_occurrences_total").value(schedule.maximumOccurrencesTotal)
                    writer.name("minimum_separation_minutes").value(schedule.minimumSeparationMinutes)
                }
            }
            writer.endObject()
            writer.name("availability_minutes").value(trigger.availabilityMinutes)
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    private fun encodeSurvey(writer: JsonWriter, survey: SurveyDefinition) {
        writer.beginObject()
        writer.name("id").value(survey.id)
        writer.name("title").also { encodeLocalizedText(writer, survey.title) }
        writer.name("description").also { encodeLocalizedText(writer, survey.description) }
        writer.name("questions").beginArray()
        survey.questions.forEach { encodeQuestion(writer, it) }
        writer.endArray()
        writer.endObject()
    }

    private fun encodeLocalizedText(writer: JsonWriter, text: LocalizedText) {
        writer.beginObject()
        writer.name("default").value(text.default)
        writer.name("translations").beginObject()
        text.translations.toSortedMap().forEach { (language, value) -> writer.name(language).value(value) }
        writer.endObject()
        writer.endObject()
    }

    private fun encodeQuestion(writer: JsonWriter, question: SurveyQuestion) {
        writer.beginObject()
        writer.name("type").value(
            when (question) {
                is ShortTextQuestion -> "short_text"
                is ScaleQuestion -> "scale"
                is SingleChoiceQuestion -> "single_choice"
                is MultipleChoiceQuestion -> "multiple_choice"
            },
        )
        writer.name("id").value(question.id)
        writer.name("prompt").also { encodeLocalizedText(writer, question.prompt) }
        writer.name("required").value(question.required)
        when (question) {
            is ShortTextQuestion -> writer.name("maximum_length").value(question.maximumLength)
            is ScaleQuestion -> {
                writer.name("minimum").value(question.minimum)
                writer.name("maximum").value(question.maximum)
                writer.name("minimum_label").also { encodeLocalizedText(writer, question.minimumLabel) }
                writer.name("maximum_label").also { encodeLocalizedText(writer, question.maximumLabel) }
            }
            is SingleChoiceQuestion -> writer.name("options").also { encodeChoices(writer, question.options) }
            is MultipleChoiceQuestion -> {
                writer.name("options").also { encodeChoices(writer, question.options) }
                writer.name("minimum_selections").value(question.minimumSelections)
                writer.name("maximum_selections").value(question.maximumSelections)
            }
        }
        writer.endObject()
    }

    private fun encodeChoices(writer: JsonWriter, choices: List<ChoiceOption>) {
        writer.beginArray()
        choices.forEach { choice ->
            writer.beginObject()
            writer.name("id").value(choice.id)
            writer.name("label").also { encodeLocalizedText(writer, choice.label) }
            writer.endObject()
        }
        writer.endArray()
    }

    private fun decodeExport(root: JsonObject): ExportConfiguration {
        root.requireExactKeys(setOf("researcher_key_id", "hpke_public_key"))
        return ExportConfiguration(root.requireString("researcher_key_id"), root.requireString("hpke_public_key"))
    }

    /**
     * An empty object means the study does not upload. Every other shape must carry the full
     * key set, so a study cannot half-declare upload and inherit a default endpoint or cadence.
     */
    private fun decodeUpload(root: JsonObject): UploadConfiguration? {
        if (root.keySet().isEmpty()) return null
        root.requireExactKeys(UPLOAD_KEYS)
        return UploadConfiguration(
            root.requireString("endpoint"),
            root.requireInt("interval_minutes"),
            root.requireBoolean("allow_metered"),
        )
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        require(keySet() == expected) { "Unexpected JSON keys: expected=$expected actual=${keySet()}" }
    }

    private fun JsonObject.requireString(name: String): String =
        requireNotNull(get(name)).requireStringValue(name)

    private fun JsonObject.requireNullableString(name: String): String? = requireNotNull(get(name)).let {
        if (it.isJsonNull) null else it.requireStringValue(name)
    }

    private fun JsonElement.requireStringValue(name: String): String {
        require(isJsonPrimitive && asJsonPrimitive.isString) { "$name must be a string" }
        return asString
    }

    private fun JsonObject.requireBoolean(name: String): Boolean {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return value.asBoolean
    }

    private fun JsonObject.requireInt(name: String): Int {
        val raw = requireIntegerLiteral(name)
        return raw.toIntOrNull() ?: throw IllegalArgumentException("$name is outside Int range")
    }

    private fun JsonObject.requireLong(name: String): Long {
        val raw = requireIntegerLiteral(name)
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name is outside Long range")
    }

    private fun JsonObject.requireIntegerLiteral(name: String): String {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        val raw = value.asString
        require(raw.length <= MAXIMUM_NUMBER_CHARACTERS) { "$name is outside the supported numeric range" }
        raw.substringAfterAny('e', 'E')?.let { exponent ->
            require(exponent.toLongOrNull()?.let { kotlin.math.abs(it) <= MAXIMUM_DECIMAL_EXPONENT } == true) {
                "$name is outside the supported numeric range"
            }
        }
        val integer = runCatching { BigDecimal(raw).toBigIntegerExact() }.getOrNull()
        require(integer != null) { "$name must be an integer" }
        return integer.toString()
    }

    private fun JsonObject.requireDecimalLongString(name: String): Long {
        val raw = requireString(name)
        require(UNSIGNED_DECIMAL.matches(raw)) { "$name must be a canonical unsigned decimal string" }
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name is outside Long range")
    }

    private fun JsonObject.requireObject(name: String): JsonObject =
        requireNotNull(get(name)).requireObject(name)

    private fun JsonObject.requireArray(name: String): JsonArray {
        val value = requireNotNull(get(name))
        require(value.isJsonArray) { "$name must be an array" }
        return value.asJsonArray
    }

    private fun JsonElement.requireObject(name: String): JsonObject {
        require(isJsonObject) { "$name must be an object" }
        return asJsonObject
    }

    private fun <T> JsonArray.mapElements(transform: (JsonElement) -> T): List<T> =
        map(transform)

    private fun String.substringAfterAny(first: Char, second: Char): String? {
        val index = indexOfAny(charArrayOf(first, second))
        return if (index < 0) null else substring(index + 1)
    }

    private const val MAX_CONFIGURATION_BYTES = 1_048_576
    private const val MAXIMUM_NUMBER_CHARACTERS = 64
    private const val MAXIMUM_DECIMAL_EXPONENT = 64L
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
}
