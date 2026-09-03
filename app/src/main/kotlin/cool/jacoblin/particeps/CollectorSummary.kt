package cool.jacoblin.particeps

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A profile-independent description of one participant-visible data category.
 *
 * Sampling rates, polling cadence, automation state, and active resource profiles are deliberately
 * absent. Those values can reveal treatment assignment and are not needed to explain the category
 * of data that a signed study may collect.
 */
data class CollectorSummary(
    val glyph: Glyph,
    val name: String,
    val detail: String,
    val optional: Boolean,
)

@Composable
fun ParticipantDataCategory.summarize(): CollectorSummary {
    val (glyph, name, detail) = when (kind) {
        ParticipantDataKind.APP_LIFECYCLE -> Triple(
            Glyph.APP,
            stringResource(R.string.collector_app_lifecycle_name),
            stringResource(R.string.collector_app_lifecycle_detail),
        )

        ParticipantDataKind.ACCELEROMETER -> Triple(
            Glyph.MOTION,
            stringResource(R.string.collector_accelerometer_name),
            stringResource(R.string.participant_data_accelerometer_detail),
        )

        ParticipantDataKind.BATTERY_STATE -> Triple(
            Glyph.DATA_VOLUME,
            stringResource(R.string.collector_battery_state_name),
            stringResource(R.string.collector_battery_state_detail),
        )

        ParticipantDataKind.TEMPORAL_CONTEXT -> Triple(
            Glyph.CLOCK,
            stringResource(R.string.collector_temporal_context_name),
            stringResource(R.string.collector_temporal_context_detail),
        )

        ParticipantDataKind.GYROSCOPE -> Triple(
            Glyph.MOTION,
            stringResource(R.string.collector_gyroscope_name),
            stringResource(R.string.participant_data_gyroscope_detail),
        )

        ParticipantDataKind.AMBIENT_LIGHT -> Triple(
            Glyph.APP,
            stringResource(R.string.collector_ambient_light_name),
            stringResource(R.string.participant_data_ambient_light_detail),
        )

        ParticipantDataKind.PROXIMITY -> Triple(
            Glyph.CONNECTION,
            stringResource(R.string.collector_proximity_name),
            stringResource(R.string.participant_data_proximity_detail),
        )

        ParticipantDataKind.NETWORK_STATE -> Triple(
            Glyph.CONNECTION,
            stringResource(R.string.collector_network_state_name),
            stringResource(R.string.collector_network_state_detail),
        )

        ParticipantDataKind.NETWORK_USAGE -> Triple(
            Glyph.DATA_VOLUME,
            stringResource(R.string.collector_network_usage_name),
            stringResource(R.string.participant_data_network_usage_detail),
        )

        ParticipantDataKind.USAGE_EVENTS -> Triple(
            Glyph.SCREEN,
            stringResource(R.string.collector_usage_events_name),
            stringResource(R.string.participant_data_usage_events_detail),
        )

        ParticipantDataKind.LOCATION -> Triple(
            Glyph.LOCATION,
            stringResource(R.string.collector_location_name),
            stringResource(R.string.participant_data_location_detail),
        )

        ParticipantDataKind.KEYBOARD_TOUCH -> Triple(
            Glyph.KEYBOARD,
            stringResource(R.string.collector_keyboard_touch_name),
            stringResource(R.string.collector_keyboard_touch_detail),
        )
    }
    return CollectorSummary(glyph, name, detail, optional)
}

@Composable
fun minutesLabel(minutes: Int): String = when {
    minutes % (60 * 24) == 0 -> (minutes / (60 * 24)).let {
        pluralStringResource(R.plurals.unit_days, it, it)
    }

    minutes % 60 == 0 -> stringResource(R.string.unit_hours, minutes / 60)
    else -> stringResource(R.string.unit_minutes, minutes)
}

internal sealed interface ExactDuration {
    val value: Long

    data class Microseconds(override val value: Long) : ExactDuration
    data class Milliseconds(override val value: Long) : ExactDuration
    data class Seconds(override val value: Long) : ExactDuration
    data class Minutes(override val value: Long) : ExactDuration
}

/** Chooses the coarsest integral unit without discarding signed microseconds. */
internal fun exactDuration(microseconds: Long): ExactDuration = when {
    microseconds % 60_000_000L == 0L -> ExactDuration.Minutes(microseconds / 60_000_000L)
    microseconds % 1_000_000L == 0L -> ExactDuration.Seconds(microseconds / 1_000_000L)
    microseconds % 1_000L == 0L -> ExactDuration.Milliseconds(microseconds / 1_000L)
    else -> ExactDuration.Microseconds(microseconds)
}

@Composable
fun durationLabel(hours: Int): String = when {
    hours < 24 -> pluralStringResource(R.plurals.study_duration_hours, hours, hours)
    hours % 24 == 0 -> (hours / 24).let {
        pluralStringResource(R.plurals.study_duration_days, it, it)
    }

    else -> stringResource(R.string.study_duration_days_hours, hours / 24, hours % 24)
}

@Composable
fun uploadCadenceLabel(upload: ParticipantUploadDisclosure): String {
    val minutes = upload.intervalMinutes
    val every = when {
        minutes % (60 * 24) == 0 -> (minutes / (60 * 24)).let {
            pluralStringResource(R.plurals.upload_every_days, it, it)
        }

        minutes % 60 == 0 -> (minutes / 60).let {
            pluralStringResource(R.plurals.upload_every_hours, it, it)
        }

        else -> pluralStringResource(R.plurals.upload_every_minutes, minutes, minutes)
    }
    val network = if (upload.allowMetered) {
        stringResource(R.string.upload_any_network)
    } else {
        stringResource(R.string.upload_wifi_only)
    }
    return stringResource(R.string.consent_upload_cadence, every, network)
}
