package cool.jacoblin.particeps.core.definition

import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.SignedResourceProfile

/** A signed, researcher-named configuration for one collector resource. */
data class NamedCollectorProfile(
    val id: String,
    val configuration: CollectorProfileConfiguration,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid collector profile ID" }
    }

    fun asSignedProfile(): SignedResourceProfile = SignedResourceProfile(
        id = id,
        canonicalBytes = ProtocolCanonicalJson.encode(
            GeneratedCollectorProfileCodec.encode(configuration),
        ),
    )
}

/**
 * The signed declaration of one collector as a stateful resource.
 *
 * An inactive state is expressed by a binding automation selecting `null`; it is deliberately
 * not represented by a synthetic profile so that configuration, reducer and runtime agree on
 * one closed-world meaning for inactivity.
 */
data class CollectorResourceConfiguration(
    val id: String,
    val tier: CollectorTier,
    val profiles: List<NamedCollectorProfile>,
    /** Researcher-authored reason the study asks for this category; schema 2 only. */
    val rationale: String? = null,
) {
    /** Schema 1 spelling: `required: true` is core, `false` is optional. */
    constructor(id: String, required: Boolean, profiles: List<NamedCollectorProfile>) :
        this(id, if (required) CollectorTier.CORE else CollectorTier.OPTIONAL, profiles, null)

    val resourceKey: ResourceKey = ResourceKey(ResourceKind.COLLECTOR, id)

    /**
     * Whether a failure of this collector closes the whole study. Only core collectors do; a
     * requested or optional collector may be skipped or turned off by the participant.
     */
    val required: Boolean get() = tier == CollectorTier.CORE

    init {
        rationale?.let {
            require(it.length in 1..MAXIMUM_RATIONALE_LENGTH && it.isNotBlank()) { "Invalid collector rationale" }
        }
        require(id in GeneratedCollectorProfileContracts.contracts) { "Unknown collector source ID" }
        require(profiles.size in 1..MAXIMUM_PROFILES) { "Invalid collector profile count" }
        require(profiles == profiles.sortedBy(NamedCollectorProfile::id)) {
            "Collector profiles must be sorted"
        }
        require(profiles.map(NamedCollectorProfile::id).distinct().size == profiles.size) {
            "Duplicate collector profile ID"
        }
        require(profiles.all { it.configuration.sourceId == id }) {
            "Collector profile source does not match its resource"
        }
    }

    fun signedProfiles(): List<SignedResourceProfile> = profiles.map(NamedCollectorProfile::asSignedProfile)

    companion object {
        const val MAXIMUM_PROFILES = 64
        const val MAXIMUM_RATIONALE_LENGTH = 600
    }
}

/**
 * How much a study depends on one data category.
 *
 * [CORE] categories are a condition of taking part: missing access blocks Start, and a failure
 * pauses the whole study. [REQUESTED] categories are wanted by the study but the participant may
 * skip or turn them off; the App then warns, in fixed copy, that this may affect compensation.
 * [OPTIONAL] categories may be skipped or turned off with no warning.
 */
enum class CollectorTier(val wire: String) {
    CORE("core"),
    REQUESTED("requested"),
    OPTIONAL("optional");

    companion object {
        fun fromWire(value: String): CollectorTier =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown collector tier")
    }
}

/**
 * What the researcher lets participants see beyond the platform floor. Schema 2 signs both
 * switches explicitly; a schema 1 configuration has no policy and therefore shows neither.
 */
data class ParticipantDisclosure(
    /** Show an approximate sampling frequency for each data category. */
    val approximateFrequency: Boolean,
    /** Show when each data category collected data, and where the study was paused. */
    val collectionTimeline: Boolean,
) {
    companion object {
        val NONE = ParticipantDisclosure(approximateFrequency = false, collectionTimeline = false)
    }
}

const val TRAFFIC_SHAPING_RESOURCE_ID: String = "traffic-shaping.v1"

val TrafficShapingConfiguration.resourceKey: ResourceKey
    get() = ResourceKey(ResourceKind.ACTUATOR, TRAFFIC_SHAPING_RESOURCE_ID)

fun TrafficShapingConfiguration.Enabled.signedProfiles(): List<SignedResourceProfile> =
    profiles.map { profile ->
        SignedResourceProfile(
            id = profile.id,
            canonicalBytes = profile.canonicalBytes(),
        )
    }
