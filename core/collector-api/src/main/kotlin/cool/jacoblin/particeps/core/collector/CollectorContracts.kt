package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.LocationV1PriorityValue
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_EVENTS
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SourceCoverage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
    val priority: LocationV1PriorityValue,
) {
    init {
        require(intervalMillis in 1_000..3_600_000) { "Invalid location interval" }
        require(minimumIntervalMillis in 500..intervalMillis) { "Invalid location minimum interval" }
        require(maximumBatchDelayMillis in 0..86_400_000) { "Invalid location batch delay" }
        require(minimumDisplacementMillimeters in 0..10_000_000) { "Invalid location displacement" }
    }

    companion object {
        fun from(configuration: LocationV1ProfileConfiguration) = LocationAccessProfile(
            intervalMillis = configuration.intervalMillis,
            minimumIntervalMillis = configuration.minimumIntervalMillis,
            maximumBatchDelayMillis = configuration.maximumBatchDelayMillis,
            minimumDisplacementMillimeters = configuration.minimumDisplacementMillimeters.toInt(),
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
    val sourceContract: RegistrySourceContract,
    val accessKinds: Set<AccessKind>,
) {
    val schemaVersion: Int get() = sourceContract.schemaVersion
    val maximumEncodedEventBytes: Int get() = sourceContract.maximumEncodedEventBytes

    init {
        require(ID_PATTERN.matches(id)) { "Invalid collector ID" }
        require(displayName.isNotBlank()) { "Collector display name must not be blank" }
        require(sourceContract.sourceKind == RegistrySourceKind.COLLECTOR) {
            "Collector descriptor must reference a COLLECTOR source"
        }
        require(sourceContract.sourceId == id) { "Collector ID must equal its generated source contract ID" }
        require(sourceContract.emissionAuthority == RegistryEmissionAuthority.SOURCE_PLUGIN_ONLY) {
            "Collector source must be emitted only by its source plugin"
        }
    }

    fun accessRequirements(required: Boolean): Set<AccessRequirement> =
        accessKinds.mapTo(mutableSetOf()) { kind -> AccessRequirement(kind, required) }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_.-]{2,63}")
    }
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

data class SourceEventBatch(
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val resourceGeneration: Long,
    val producerOrdinal: Long,
    val events: List<EventDraft>,
    val coverage: SourceCoverage? = null,
) {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(resourceGeneration > 0) { "Resource generation must be positive" }
        require(producerOrdinal >= 0) { "Producer ordinal must be non-negative" }
        require(events.size in 1..MAX_OBSERVATION_EVENTS) {
            "Source event batch must contain 1..$MAX_OBSERVATION_EVENTS events"
        }
        require(events.all { event ->
            event.type.sourceId == sourceId && event.type.schemaVersion == schemaVersion
        }) { "Every event must match the batch source and schema" }
        val contract = requireNotNull(ProtocolEventSourceRegistry[sourceId.value]) {
            "Unknown event source: $sourceId"
        }
        require(contract.schemaVersion == schemaVersion) { "Batch schema does not match the registry" }
        if (contract.isRetrospective) {
            requireNotNull(coverage) { "Retrospective source batches require half-open coverage" }
        }
    }
}

data class CoverageAdvance(
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val resourceGeneration: Long,
    val producerOrdinal: Long,
    val coverage: SourceCoverage,
) {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(resourceGeneration > 0) { "Resource generation must be positive" }
        require(producerOrdinal >= 0) { "Producer ordinal must be non-negative" }
        val contract = requireNotNull(ProtocolEventSourceRegistry[sourceId.value]) {
            "Unknown event source: $sourceId"
        }
        require(contract.schemaVersion == schemaVersion) { "Coverage schema does not match the registry" }
        require(contract.isRetrospective) { "Zero-event coverage is only valid for retrospective sources" }
    }
}

enum class SourceQualityGapReason {
    CLOCK_DISCONTINUITY,
    ORDER_UNPROVABLE,
    PLATFORM_HISTORY_GAP,
    PROCESS_RECOVERY,
    RETROSPECTIVE_COVERAGE_GAP,
    WALL_CLOCK_CHANGED,
}

sealed interface EmitBatchResult {
    data class Accepted(
        val observationSequence: Long,
    ) : EmitBatchResult {
        init {
            require(observationSequence > 0) { "Observation sequence must be positive" }
        }
    }

    data object RejectedByAdmissionGate : EmitBatchResult

    /** The source crossed its generated ID, schema, field, ordering, or encoded-size boundary. */
    data object ContractViolation : EmitBatchResult

    data class SourceQualityGap(val reason: SourceQualityGapReason) : EmitBatchResult

    data object StorageFailure : EmitBatchResult
}

/** Exact byte count of this event in the authenticated Protocol v1 event representation. */
fun EventDraft.protocolEncodedBytes(
    sequenceNumber: Long,
    conditionEpochId: ConditionEpochId?,
): Int {
    require(sequenceNumber > 0) { "Sequence number must be positive" }
    var size = EVENT_JSON_PUNCTUATION_BYTES
    size += conditionEpochId?.value?.quotedJsonBytes() ?: JSON_NULL_BYTES
    size += type.eventType.quotedJsonBytes()
    size += fields.entries.sumOf { (key, value) ->
        key.quotedJsonBytes() + JSON_NAME_SEPARATOR_BYTES + value.quotedJsonBytes()
    }
    size += (fields.size - 1).coerceAtLeast(0) * JSON_VALUE_SEPARATOR_BYTES
    size += observedTime.bootSessionId.quotedJsonBytes()
    size += observedTime.elapsedRealtimeNanos.toString().quotedJsonBytes()
    size += observedTime.wallTimeUtcMillis.toString().quotedJsonBytes()
    size += type.schemaVersion.toString().length
    size += sequenceNumber.toString().quotedJsonBytes()
    size += type.sourceId.value.quotedJsonBytes()
    return size
}

fun RegistrySourceContract.accepts(
    event: EventDraft,
    sequenceNumber: Long,
    conditionEpochId: ConditionEpochId?,
): Boolean {
    if (event.type.sourceId.value != sourceId || event.type.schemaVersion != schemaVersion) return false
    val eventContract = events[event.type.eventType] ?: return false
    if (!eventContract.accepts(event.fields)) return false
    val encodedBytes = runCatching { event.protocolEncodedBytes(sequenceNumber, conditionEpochId) }.getOrNull()
        ?: return false
    return encodedBytes <= eventContract.maximumEncodedEventBytes
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
private const val JSON_NULL_BYTES = 4
private val JSON_NAMED_ESCAPES = setOf('\b', '\t', '\n', '\u000C', '\r')

/**
 * UTF-8 bytes in the fixed JCS member names, braces, separators, and `fields` object. Dynamic
 * values are counted separately above. Keeping the literal here makes protocol changes visible.
 */
private val EVENT_JSON_PUNCTUATION_BYTES = (
    "{\"condition_epoch_id\":" +
        ",\"event_type\":" +
        ",\"fields\":{" + "}" +
        ",\"observed_time\":{\"boot_session_id\":" +
        ",\"monotonic_time_nanos\":" +
        ",\"wall_time_utc_millis\":" + "}" +
        ",\"schema_version\":" +
        ",\"sequence_number\":" +
        ",\"source_id\":" + "}"
    ).toByteArray(Charsets.UTF_8).size

interface EventSink {
    /** Captures ordinary collection admission. Returns null as soon as a barrier begins. */
    fun captureToken(): AdmissionToken?

    /**
     * Captures the unique drain-only capability for an exact retrospective boundary flush.
     * Collector implementations may call this only from [Collector.flushThrough] with the
     * runtime-provided boundary; ordinary polling and callback paths must use [captureToken].
     */
    fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken?

    suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult

    suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult
}

/** Runtime-owned HMAC capability; collectors receive no raw study key. */
fun interface StudyScopedTokenEncoder {
    fun encode(domain: String, value: String): String
}

data class CollectorContext(
    val scope: CoroutineScope,
    val eventSink: EventSink,
    val clocks: ResearchClocks,
    val sourceContract: RegistrySourceContract,
    val resourceGeneration: Long,
    val tokenEncoder: StudyScopedTokenEncoder,
) {
    init {
        require(sourceContract.sourceKind == RegistrySourceKind.COLLECTOR) {
            "Collector context requires a COLLECTOR source contract"
        }
        require(resourceGeneration > 0) { "Collector resource generation must be positive" }
    }
}

enum class CollectorObservationMode {
    LIVE,
    RETROSPECTIVE,
}

enum class CollectorFlushFailureReason {
    RETROSPECTIVE_FLUSH_NOT_IMPLEMENTED,
    SOURCE_FAILURE,
    SOURCE_QUALITY_GAP,
}

sealed interface CollectorFlushResult {
    data class Complete(
        val boundary: ResearchTime,
        val cursor: String?,
    ) : CollectorFlushResult

    data class Failed(val reason: CollectorFlushFailureReason) : CollectorFlushResult
}

interface ResearchClocks {
    fun now(): cool.jacoblin.particeps.core.model.ResearchTime

    /** UTC accepted for crossing a boot boundary; null keeps collection fail-closed. */
    fun trustedUtcMillis(): Long? = null
}

interface CollectorPlugin {
    val descriptor: CollectorDescriptor

    fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector
}

interface Collector {
    val health: StateFlow<CollectorHealth>

    val observationMode: CollectorObservationMode get() = CollectorObservationMode.LIVE

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

    suspend fun flushThrough(boundary: ResearchTime, cursor: String?): CollectorFlushResult =
        if (observationMode == CollectorObservationMode.LIVE) {
            CollectorFlushResult.Complete(boundary, null)
        } else {
            CollectorFlushResult.Failed(CollectorFlushFailureReason.RETROSPECTIVE_FLUSH_NOT_IMPLEMENTED)
        }

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

    fun pluginFor(configuration: CollectorProfileConfiguration): CollectorPlugin =
        plugins.singleOrNull { it.descriptor.id == configuration.sourceId }
            ?: throw IllegalArgumentException("Collector is not compiled into this app: ${configuration.sourceId}")

    fun accessRequirements(
        configurations: List<Pair<CollectorProfileConfiguration, Boolean>>,
    ): List<CollectorAccessRequirement> =
        configurations.flatMap { (configuration, required) ->
            val descriptor = pluginFor(configuration).descriptor
            descriptor.accessRequirements(required).map { requirement ->
                CollectorAccessRequirement(descriptor.id, requirement)
            }
        }
}
