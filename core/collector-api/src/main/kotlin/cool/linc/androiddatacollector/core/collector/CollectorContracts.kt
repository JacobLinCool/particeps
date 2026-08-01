package cool.linc.androiddatacollector.core.collector

import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class PrivacyClass {
    SENSITIVE,
    RESTRICTED,
}

enum class AccessKind {
    FINE_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    USAGE_ACCESS,
    RESEARCH_KEYBOARD_ENABLED,
    RESEARCH_KEYBOARD_SELECTED,
    ACCELEROMETER_HARDWARE,
}

data class AccessRequirement(
    val kind: AccessKind,
    val required: Boolean,
)

data class AccessStatus(
    val requirement: AccessRequirement,
    val granted: Boolean,
)
interface StudyAccessGateway {
    fun inspect(requirements: Set<AccessRequirement>): List<AccessStatus>
    fun grantedKinds(requirements: Set<AccessRequirement>): Set<AccessKind>
}

data class CollectorDescriptor(
    val id: String,
    val payloadSchemaVersion: Int,
    val displayName: String,
    val privacyClass: PrivacyClass,
    val maximumEncodedEventBytes: Int,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid collector ID" }
        require(payloadSchemaVersion > 0) { "Payload schema version must be positive" }
        require(displayName.isNotBlank()) { "Collector display name must not be blank" }
        require(maximumEncodedEventBytes in 128..65_536) { "Invalid maximum event size" }
    }

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

sealed interface EmitResult {
    data class Accepted(val sequenceNumber: Long) : EmitResult

    data object RejectedByAdmissionGate : EmitResult

    data object StorageFailure : EmitResult
}

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
    fun now(): cool.linc.androiddatacollector.core.model.ResearchTime
}

interface CollectorPlugin {
    val descriptor: CollectorDescriptor

    fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement>

    fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector
}

interface Collector {
    val health: StateFlow<CollectorHealth>

    suspend fun start()

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

    fun accessRequirements(configurations: List<CollectorConfiguration>): Set<AccessRequirement> =
        configurations.flatMap { configuration ->
            pluginFor(configuration).accessRequirements(configuration)
        }.toSet()
}
