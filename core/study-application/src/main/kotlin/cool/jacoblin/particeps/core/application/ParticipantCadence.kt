package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.AccelerometerV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.AmbientLightV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.GyroscopeV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.KeyboardTouchV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NetworkThroughputV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NetworkUsageV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration

/**
 * How often a data category records, as a participant may be told when the signed disclosure policy
 * allows it. It is derived only from the category's signed profiles: a category with several
 * profiles reports the range they span, never which one is in force.
 */
sealed interface ParticipantCadence {
    /** The category records when its state changes, not on a fixed period. */
    data object WhenChanged : ParticipantCadence

    /** The category records about every [shortestMillis] to [longestMillis]; equal for one period. */
    data class Every(val shortestMillis: Long, val longestMillis: Long) : ParticipantCadence {
        init {
            require(shortestMillis in 1..longestMillis) { "Invalid cadence range" }
        }
    }
}

/** The approximate cadence of [collector] across all of its signed profiles. */
fun participantCadence(collector: CollectorResourceConfiguration): ParticipantCadence {
    val periods = collector.profiles.mapNotNull { profile ->
        when (val configuration = profile.configuration) {
            is AccelerometerV1ProfileConfiguration -> configuration.samplingPeriodUs / MICROS_PER_MILLI
            is GyroscopeV1ProfileConfiguration -> configuration.samplingPeriodUs / MICROS_PER_MILLI
            is AmbientLightV1ProfileConfiguration -> configuration.samplingPeriodUs / MICROS_PER_MILLI
            is KeyboardTouchV1ProfileConfiguration -> MILLIS_PER_SECOND / configuration.trajectorySamplingHz
            is LocationV1ProfileConfiguration -> configuration.intervalMillis
            is NetworkThroughputV1ProfileConfiguration -> configuration.pollIntervalSeconds * MILLIS_PER_SECOND
            is NetworkUsageV1ProfileConfiguration -> configuration.pollIntervalSeconds * MILLIS_PER_SECOND
            is UsageEventsV1ProfileConfiguration -> configuration.pollIntervalSeconds * MILLIS_PER_SECOND
            else -> null
        }?.coerceAtLeast(1L)
    }
    if (periods.isEmpty()) return ParticipantCadence.WhenChanged
    return ParticipantCadence.Every(periods.min(), periods.max())
}

private const val MICROS_PER_MILLI = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
