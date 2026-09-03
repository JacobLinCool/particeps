package cool.jacoblin.particeps.core.definition

sealed interface TrafficShapingConfiguration {
    data object Disabled : TrafficShapingConfiguration

    data class Enabled(
        val targetPackages: List<String>,
        val profiles: List<TrafficShapingProfile>,
    ) : TrafficShapingConfiguration {
        init {
            require(targetPackages.size in 1..MAXIMUM_TARGET_PACKAGES) {
                "Traffic shaping requires 1–$MAXIMUM_TARGET_PACKAGES target packages"
            }
            require(targetPackages == targetPackages.sorted().distinct()) {
                "Traffic-shaping packages must be sorted and unique"
            }
            require(targetPackages.all(ANDROID_APPLICATION_ID::matches)) {
                "Invalid Android application ID"
            }
            require(PARTICEPS_APPLICATION_ID !in targetPackages) {
                "Particeps cannot be a traffic-shaping target"
            }
            require(profiles.size in 1..MAXIMUM_PROFILES) {
                "Traffic shaping requires 1–$MAXIMUM_PROFILES profiles"
            }
            require(profiles == profiles.sortedBy(TrafficShapingProfile::id)) {
                "Traffic-shaping profiles must be sorted"
            }
            require(profiles.map(TrafficShapingProfile::id).distinct().size == profiles.size) {
                "Duplicate traffic-shaping profile ID"
            }
        }

    }

    companion object {
        const val MAXIMUM_TARGET_PACKAGES = 64
        const val MAXIMUM_PROFILES = 64
        const val PARTICEPS_APPLICATION_ID = "cool.jacoblin.particeps"
        private val ANDROID_APPLICATION_ID = Regex(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+",
        )
    }
}

data class TrafficShapingProfile(
    val id: String,
    val uplinkKbps: Int?,
    val downlinkKbps: Int?,
) {
    init {
        require(StudyConfiguration.ID.matches(id)) { "Invalid traffic-shaping profile ID" }
        require(uplinkKbps == null || uplinkKbps in MINIMUM_KBPS..MAXIMUM_KBPS) {
            "Invalid uplink cap"
        }
        require(downlinkKbps == null || downlinkKbps in MINIMUM_KBPS..MAXIMUM_KBPS) {
            "Invalid downlink cap"
        }
    }

    /** Exact profile bytes shared by signed configuration, native receipts and condition epochs. */
    fun canonicalBytes(): ByteArray = buildString {
        append("{\"downlink_kbps\":")
        append(downlinkKbps?.toString() ?: "null")
        append(",\"id\":\"")
        append(id)
        append("\",\"uplink_kbps\":")
        append(uplinkKbps?.toString() ?: "null")
        append('}')
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val MINIMUM_KBPS = 1
        const val MAXIMUM_KBPS = 1_000_000
    }
}
