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
    val required: Boolean,
    val profiles: List<NamedCollectorProfile>,
) {
    val resourceKey: ResourceKey = ResourceKey(ResourceKind.COLLECTOR, id)

    init {
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

    companion object { const val MAXIMUM_PROFILES = 64 }
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
