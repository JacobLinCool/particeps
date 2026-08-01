package cool.linc.androiddatacollector.core.definition

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.time.Instant

object StudyConfigurationCodec {
    private val ROOT_KEYS = setOf(
        "schema_version",
        "experiment_id",
        "configuration_id",
        "issued_at",
        "expires_at",
        "minimum_app_version",
        "title",
        "researcher",
        "purpose",
        "duration_hours",
        "consent",
        "collectors",
        "prompts",
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
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).requireObject("root")
        root.requireExactKeys(ROOT_KEYS)
        return StudyConfiguration(
            schemaVersion = root.requireInt("schema_version"),
            experimentId = root.requireString("experiment_id"),
            configurationId = root.requireString("configuration_id"),
            issuedAt = Instant.parse(root.requireString("issued_at")),
            expiresAt = Instant.parse(root.requireString("expires_at")),
            minimumAppVersion = root.requireInt("minimum_app_version"),
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
            prompts = root.requireArray("prompts").mapElements(::decodePrompt),
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
            writer.name("issued_at").value(configuration.issuedAt.toString())
            writer.name("expires_at").value(configuration.expiresAt.toString())
            writer.name("minimum_app_version").value(configuration.minimumAppVersion)
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
            writer.name("prompts").beginArray()
            configuration.prompts.forEach { prompt ->
                writer.beginObject()
                writer.name("id").value(prompt.id)
                writer.name("delay_minutes").value(prompt.delayMinutes)
                writer.name("message").value(prompt.message)
                writer.endObject()
            }
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
            writer.name("tink_hpke_public_keyset").jsonValue(configuration.export.tinkHpkePublicKeysetJson)
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
        return output.toString().toByteArray(Charsets.UTF_8)
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
                        "minimum_displacement_meters",
                        "priority",
                    ),
                )
                LocationConfiguration(
                    required,
                    config.requireLong("interval_millis"),
                    config.requireLong("minimum_interval_millis"),
                    config.requireLong("maximum_batch_delay_millis"),
                    config.requireFloat("minimum_displacement_meters"),
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
                writer.name("minimum_displacement_meters").value(collector.minimumDisplacementMeters)
                writer.name("priority").value(collector.priority.name)
            }
            is KeyboardTouchConfiguration ->
                writer.name("trajectory_sampling_hz").value(collector.trajectorySamplingHz)
        }
        writer.endObject()
        writer.endObject()
    }

    private fun decodePrompt(element: JsonElement): PromptConfiguration {
        val root = element.requireObject("prompt")
        root.requireExactKeys(setOf("id", "delay_minutes", "message"))
        return PromptConfiguration(
            root.requireString("id"),
            root.requireInt("delay_minutes"),
            root.requireString("message"),
        )
    }

    private fun decodeExport(root: JsonObject): ExportConfiguration {
        root.requireExactKeys(setOf("researcher_key_id", "tink_hpke_public_keyset"))
        val keyset = root.get("tink_hpke_public_keyset")
        require(keyset != null && keyset.isJsonObject) { "Public keyset must be an object" }
        return ExportConfiguration(root.requireString("researcher_key_id"), keyset.toString())
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

    private fun JsonObject.requireFloat(name: String): Float {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be numeric" }
        val parsed = value.asString.toFloatOrNull()
        require(parsed != null && parsed.isFinite()) { "$name must be finite" }
        return parsed
    }

    private fun JsonObject.requireIntegerLiteral(name: String): String {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        val raw = value.asString
        require(INTEGER.matches(raw)) { "$name must be an integer literal" }
        return raw
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

    private const val MAX_CONFIGURATION_BYTES = 1_048_576
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
}
