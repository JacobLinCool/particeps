package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.LocationConfiguration
import cool.jacoblin.particeps.core.definition.LocationPriority
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.RecordedEvent
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class PrivacyClass {
    SENSITIVE,
    RESTRICTED,
}

enum class AccessKind {
    FINE_LOCATION,
    LOCATION_SERVICES,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    USAGE_ACCESS,
    RESEARCH_KEYBOARD_ENABLED,
    RESEARCH_KEYBOARD_SELECTED,
    ACCELEROMETER_HARDWARE,
    GYROSCOPE_HARDWARE,
    AMBIENT_LIGHT_HARDWARE,
    PROXIMITY_HARDWARE,
}

data class AccessRequirement(
    val kind: AccessKind,
    val required: Boolean,
)

/** App-owned notification purposes derived from verified study features; channel IDs stay platform-owned. */
enum class NotificationAccessFeature {
    COLLECTION,
    DAILY_STATUS,
    RECOVERY,
    INTERVENTIONS,
}

/**
 * The exact location request configured by the study and checked before collection starts.
 *
 * This deliberately preserves the signed integer units. The Android implementation performs the
 * same millimetre-to-metre conversion as the collector when it builds the Play services request.
 */
data class LocationAccessProfile(
    val intervalMillis: Long,
    val minimumIntervalMillis: Long,
    val maximumBatchDelayMillis: Long,
    val minimumDisplacementMillimeters: Int,
    val priority: LocationPriority,
) {
    init {
        require(intervalMillis in 1_000..3_600_000) { "Invalid location interval" }
        require(minimumIntervalMillis in 500..intervalMillis) { "Invalid location minimum interval" }
        require(maximumBatchDelayMillis in 0..86_400_000) { "Invalid location batch delay" }
        require(minimumDisplacementMillimeters in 0..10_000_000) { "Invalid location displacement" }
    }

    companion object {
        fun from(configuration: LocationConfiguration) = LocationAccessProfile(
            intervalMillis = configuration.intervalMillis,
            minimumIntervalMillis = configuration.minimumIntervalMillis,
            maximumBatchDelayMillis = configuration.maximumBatchDelayMillis,
            minimumDisplacementMillimeters = configuration.minimumDisplacementMillimeters,
            priority = configuration.priority,
        )
    }
}

/**
 * All context needed to inspect access without guessing collector configuration or channel IDs.
 */
data class AccessInspectionRequest(
    val requirements: Set<AccessRequirement>,
    val locationProfile: LocationAccessProfile? = null,
    val notificationFeatures: Set<NotificationAccessFeature> = emptySet(),
) {
    init {
        require(requirements.distinctBy(AccessRequirement::kind).size == requirements.size) {
            "Access inspection contains duplicate kinds"
        }
        require((AccessKind.LOCATION_SERVICES in requirements.map(AccessRequirement::kind)) == (locationProfile != null)) {
            "Location services access requires exactly one location profile"
        }
        val requestsNotifications = requirements.any { it.kind == AccessKind.NOTIFICATIONS }
        require(requestsNotifications || notificationFeatures.isEmpty()) {
            "Notification features require notifications access"
        }
        require(!requestsNotifications || notificationFeatures.containsAll(BASE_NOTIFICATION_FEATURES)) {
            "Notifications access requires collection, daily-status, and recovery channels"
        }
    }

    private companion object {
        val BASE_NOTIFICATION_FEATURES = setOf(
            NotificationAccessFeature.COLLECTION,
            NotificationAccessFeature.DAILY_STATUS,
            NotificationAccessFeature.RECOVERY,
        )
    }
}

sealed interface SetupAction {
    enum class RuntimePermission : SetupAction {
        FOREGROUND_LOCATION,
        NOTIFICATIONS,
    }

    enum class SystemSettings : SetupAction {
        APPLICATION_DETAILS,
        APPLICATION_NOTIFICATIONS,
        LOCATION_SERVICES,
        USAGE_ACCESS,
        INPUT_METHODS,
    }

    data object ShowInputMethodPicker : SetupAction
}

enum class SetupGuidance {
    FOREGROUND_LOCATION_SETTINGS,
    LOCATION_SERVICES,
    BACKGROUND_LOCATION,
    NOTIFICATIONS_SETTINGS,
    USAGE_ACCESS,
    RESEARCH_KEYBOARD_ENABLE,
    RESEARCH_KEYBOARD_SELECT,
}

enum class AccessUnavailableReason {
    HARDWARE_ABSENT,
    LOCATION_SETTINGS_CHANGE_UNAVAILABLE,
    LOCATION_SETTINGS_CHECK_FAILED,
    SYSTEM_HANDLER_MISSING,
}

sealed interface AccessResolution {
    data object Satisfied : AccessResolution

    data class ActionRequired(
        val action: SetupAction,
    ) : AccessResolution

    data class BlockedByPrerequisites(
        val missing: Set<AccessKind>,
    ) : AccessResolution {
        init {
            require(missing.isNotEmpty()) { "Blocked access must name a missing prerequisite" }
        }
    }

    data class Unavailable(
        val reason: AccessUnavailableReason,
    ) : AccessResolution
}

data class AccessStatus(
    val requirement: AccessRequirement,
    val resolution: AccessResolution,
    val guidance: SetupGuidance?,
) {
    val granted: Boolean get() = resolution == AccessResolution.Satisfied
}

data class AccessSnapshot(
    val statuses: List<AccessStatus>,
) {
    val satisfiedKinds: Set<AccessKind> = statuses
        .filter(AccessStatus::granted)
        .mapTo(mutableSetOf()) { it.requirement.kind }
    val requiredReady: Boolean = statuses.none { it.requirement.required && !it.granted }

    init {
        require(statuses.distinctBy { it.requirement.kind }.size == statuses.size) {
            "Access snapshot contains duplicate kinds"
        }
    }
}

interface StudyAccessGateway {
    suspend fun inspect(request: AccessInspectionRequest): AccessSnapshot
}

data class CollectorAccessRequirement(
    val collectorId: String,
    val requirement: AccessRequirement,
)

data class CollectorDescriptor(
    val id: String,
    val displayName: String,
    val privacyClass: PrivacyClass,
    val eventContract: CollectorEventContract,
    val accessKinds: Set<AccessKind>,
) {
    val payloadSchemaVersion get() = eventContract.payloadSchemaVersion
    val maximumEncodedEventBytes get() = eventContract.maximumEncodedEventBytes

    init {
        require(ID_PATTERN.matches(id)) { "Invalid collector ID" }
        require(displayName.isNotBlank()) { "Collector display name must not be blank" }
    }

    fun accessRequirements(required: Boolean): Set<AccessRequirement> =
        accessKinds.mapTo(mutableSetOf()) { kind -> AccessRequirement(kind, required) }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_.-]{2,63}")
    }
}

enum class EventFieldType {
    BOOLEAN,
    DECIMAL_STRING,
    ENUM,
    FLOAT32,
    FLOAT64,
    INT32,
    JSON_STRING,
    STRING,
}

data class EventFieldContract(
    val type: EventFieldType,
    val required: Boolean,
    val enumValues: Set<String> = emptySet(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maximumLength: Int? = null,
) {
    init {
        require((type == EventFieldType.ENUM) == enumValues.isNotEmpty()) { "Invalid event enum contract" }
        require(minimum == null || minimum.isFinite()) { "Invalid event field minimum" }
        require(maximum == null || maximum.isFinite()) { "Invalid event field maximum" }
        require(minimum == null || maximum == null || minimum <= maximum) { "Invalid event field range" }
        require(maximumLength == null || maximumLength > 0) { "Invalid event field length" }
    }

    internal fun accepts(value: String): Boolean {
        if (maximumLength != null && value.length > maximumLength) return false
        return when (type) {
            EventFieldType.BOOLEAN -> value == "true" || value == "false"
            EventFieldType.DECIMAL_STRING -> UNSIGNED_DECIMAL.matches(value) && value.toLongOrNull() != null
            EventFieldType.ENUM -> value in enumValues
            EventFieldType.FLOAT32 -> FLOAT_DECIMAL.matches(value) &&
                value.toFloatOrNull()?.let { it.isFinite() && inRange(it.toDouble()) } == true
            EventFieldType.FLOAT64 -> FLOAT_DECIMAL.matches(value) &&
                value.toDoubleOrNull()?.let { it.isFinite() && inRange(it) } == true
            EventFieldType.INT32 -> SIGNED_INTEGER.matches(value) &&
                value.toIntOrNull()?.let { inRange(it.toDouble()) } == true
            EventFieldType.JSON_STRING -> isStrictJson(value)
            EventFieldType.STRING -> true
        }
    }

    private fun inRange(value: Double): Boolean =
        (minimum == null || value >= minimum) && (maximum == null || value <= maximum)

    private companion object {
        val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
        val SIGNED_INTEGER = Regex("0|-?[1-9][0-9]*")
        val FLOAT_DECIMAL = Regex("[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")

        fun isStrictJson(value: String): Boolean = try {
            val reader = JsonReader(StringReader(value)).apply { strictness = Strictness.STRICT }
            JsonParser.parseReader(reader)
            reader.peek() == JsonToken.END_DOCUMENT
        } catch (_: JsonParseException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }
}

data class EventPayloadContract(
    val fields: Map<String, EventFieldContract>,
) {
    init {
        require(fields.keys.all(FIELD_NAME::matches)) { "Invalid event field name" }
    }

    internal fun accepts(values: Map<String, String>): Boolean =
        values.keys.all(fields::containsKey) &&
            fields.all { (name, contract) ->
                val value = values[name]
                if (value == null) !contract.required else contract.accepts(value)
            }

    private companion object { val FIELD_NAME = Regex("[a-z][a-z0-9_]{1,63}") }
}

data class CollectorEventContract(
    val payloadSchemaVersion: Int,
    val maximumEncodedEventBytes: Int,
    val payloads: Map<String, EventPayloadContract>,
) {
    init {
        require(payloadSchemaVersion > 0) { "Payload schema version must be positive" }
        require(maximumEncodedEventBytes in 128..65_536) { "Invalid maximum event size" }
        require(payloads.isNotEmpty()) { "Collector must declare at least one payload" }
        require(payloads.keys.all(PAYLOAD_TYPE::matches)) { "Invalid payload type" }
    }

    fun accepts(event: EventDraft, sequenceNumber: Long): Boolean {
        if (event.payloadSchemaVersion != payloadSchemaVersion) return false
        val payload = payloads[event.payloadType] ?: return false
        if (!payload.accepts(event.fields)) return false
        val encodedBytes = try {
            event.protocolEncodedBytes(sequenceNumber)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return encodedBytes <= maximumEncodedEventBytes
    }

    private companion object { val PAYLOAD_TYPE = Regex("[A-Z][A-Z0-9_]{1,63}") }
}

enum class CollectorStatus {
    STOPPED,
    ACTIVE,
    PAUSED,
    BLOCKED_ACCESS,
    FAILED,
}

data class CollectorHealth(
    val status: CollectorStatus,
    val reasonCode: String? = null,
) {
    init {
        require((status in REASONED_STATES) == (reasonCode != null)) {
            "Only blocked or failed collector health has a reason code"
        }
        require(reasonCode == null || REASON_CODE.matches(reasonCode)) { "Invalid collector reason code" }
    }

    private companion object {
        val REASONED_STATES = setOf(CollectorStatus.BLOCKED_ACCESS, CollectorStatus.FAILED)
        val REASON_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}

/** Opaque runtime-issued admission capability; collector features cannot construct a valid token. */
interface AdmissionToken

sealed interface EmitResult {
    data class Accepted(val sequenceNumber: Long) : EmitResult

    data object RejectedByAdmissionGate : EmitResult

    /** The collector crossed its declared ID, schema, or maximum encoded-size boundary. */
    data object ContractViolation : EmitResult

    data object StorageFailure : EmitResult
}

/** Exact byte count of this event in the authenticated Protocol v1 event representation. */
fun EventDraft.protocolEncodedBytes(sequenceNumber: Long): Int {
    require(sequenceNumber > 0) { "Sequence number must be positive" }
    var size = EVENT_JSON_PUNCTUATION_BYTES
    size += collectorId.quotedJsonBytes()
    size += fields.entries.sumOf { (key, value) ->
        key.quotedJsonBytes() + JSON_NAME_SEPARATOR_BYTES + value.quotedJsonBytes()
    }
    size += (fields.size - 1).coerceAtLeast(0) * JSON_VALUE_SEPARATOR_BYTES
    size += observedTime.bootSessionId.quotedJsonBytes()
    size += observedTime.elapsedRealtimeNanos.toString().quotedJsonBytes()
    size += observedTime.wallTimeUtcMillis.toString().quotedJsonBytes()
    size += payloadSchemaVersion.toString().length
    size += payloadType.quotedJsonBytes()
    size += sequenceNumber.toString().quotedJsonBytes()
    return size
}

private fun String.quotedJsonBytes(): Int {
    var bytes = JSON_QUOTE_BYTES * 2
    var index = 0
    while (index < length) {
        val character = this[index]
        bytes += when {
            character == '"' || character == '\\' -> 2
            character in JSON_NAMED_ESCAPES -> 2
            character.code < 0x20 -> 6
            character.code < 0x80 -> 1
            character.code < 0x800 -> 2
            character.isHighSurrogate() -> {
                require(index + 1 < length && this[index + 1].isLowSurrogate()) {
                    "Event text contains an unpaired surrogate"
                }
                index++
                4
            }
            character.isLowSurrogate() -> throw IllegalArgumentException(
                "Event text contains an unpaired surrogate",
            )
            else -> 3
        }
        index++
    }
    return bytes
}

private const val JSON_QUOTE_BYTES = 1
private const val JSON_NAME_SEPARATOR_BYTES = 1
private const val JSON_VALUE_SEPARATOR_BYTES = 1
private val JSON_NAMED_ESCAPES = setOf('\b', '\t', '\n', '\u000C', '\r')

/**
 * UTF-8 bytes in the fixed JCS member names, braces, separators, and `fields` object. Dynamic
 * values are counted separately above. Keeping the literal here makes protocol changes visible.
 */
private val EVENT_JSON_PUNCTUATION_BYTES = (
    "{\"collector_id\":" +
        ",\"fields\":{" + "}" +
        ",\"observed_time\":{\"boot_session_id\":" +
        ",\"monotonic_time_nanos\":" +
        ",\"wall_time_utc_millis\":" + "}" +
        ",\"payload_schema_version\":" +
        ",\"payload_type\":" +
        ",\"sequence_number\":" + "}"
    ).toByteArray(Charsets.UTF_8).size

interface EventSink {
    fun captureToken(): AdmissionToken?

    suspend fun emit(token: AdmissionToken, event: EventDraft): EmitResult

    suspend fun latestEvent(collectorId: String): RecordedEvent?
}

data class CollectorContext(
    val scope: CoroutineScope,
    val eventSink: EventSink,
    val clocks: ResearchClocks,
)

interface ResearchClocks {
    fun now(): cool.jacoblin.particeps.core.model.ResearchTime

    /** UTC accepted for crossing a boot boundary; null keeps collection fail-closed. */
    fun trustedUtcMillis(): Long? = null
}

interface CollectorPlugin {
    val descriptor: CollectorDescriptor

    fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector
}

interface Collector {
    val health: StateFlow<CollectorHealth>

    /** True while the owner must keep this instance and call [stop] to release process resources. */
    val requiresStop: Boolean get() = false

    suspend fun start()

    /**
     * Called after source startup succeeds and the runtime opens this collector's admission gate.
     * Collectors that publish an initial snapshot must do so here: callbacks delivered while the
     * source is still registering are intentionally outside the admitted collection interval.
     */
    suspend fun onAdmissionOpened() = Unit

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()
}

class CollectorRegistry(
    plugins: List<CollectorPlugin>,
) {
    val plugins: List<CollectorPlugin> = plugins.toList()

    init {
        val duplicateIds = this.plugins
            .groupingBy { it.descriptor.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) { "Duplicate collector IDs: $duplicateIds" }
    }

    fun pluginFor(configuration: CollectorConfiguration): CollectorPlugin =
        plugins.singleOrNull { it.descriptor.id == configuration.id }
            ?: throw IllegalArgumentException("Collector is not compiled into this app: ${configuration.id}")

    fun accessRequirements(configurations: List<CollectorConfiguration>): List<CollectorAccessRequirement> =
        configurations.flatMap { configuration ->
            val descriptor = pluginFor(configuration).descriptor
            descriptor.accessRequirements(configuration.required).map { requirement ->
                CollectorAccessRequirement(descriptor.id, requirement)
            }
        }
}
