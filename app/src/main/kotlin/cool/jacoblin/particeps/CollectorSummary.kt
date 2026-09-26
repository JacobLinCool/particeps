package cool.jacoblin.particeps

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A profile-independent description of one participant-visible data category.
 *
 * Sampling rates, polling cadence, automation state, and active resource profiles are deliberately
 * absent. Those values can reveal treatment assignment and are not needed to explain the category
 * of data that a signed study may collect. [records] and [notRecorded] name the fields the event
 * source registry defines for the category; a field that a profile can switch off is described as
 * one the category may include, so the copy stays the same for every profile.
 */
data class CollectorSummary(
    val glyph: Glyph,
    val name: String,
    val records: String,
    val notRecorded: String,
    val optional: Boolean,
)

private data class CategoryCopy(val glyph: Glyph, val name: Int, val records: Int, val notRecorded: Int)

private fun ParticipantDataKind.categoryCopy(): CategoryCopy = when (this) {
    ParticipantDataKind.APP_LIFECYCLE -> CategoryCopy(
        Glyph.APP,
        R.string.collector_app_lifecycle_name,
        R.string.collector_app_lifecycle_records,
        R.string.collector_app_lifecycle_not_recorded,
    )
    ParticipantDataKind.ACCELEROMETER -> CategoryCopy(
        Glyph.MOTION,
        R.string.collector_accelerometer_name,
        R.string.collector_accelerometer_records,
        R.string.collector_accelerometer_not_recorded,
    )
    ParticipantDataKind.BATTERY_STATE -> CategoryCopy(
        Glyph.DATA_VOLUME,
        R.string.collector_battery_state_name,
        R.string.collector_battery_state_records,
        R.string.collector_battery_state_not_recorded,
    )
    ParticipantDataKind.TEMPORAL_CONTEXT -> CategoryCopy(
        Glyph.CLOCK,
        R.string.collector_temporal_context_name,
        R.string.collector_temporal_context_records,
        R.string.collector_temporal_context_not_recorded,
    )
    ParticipantDataKind.GYROSCOPE -> CategoryCopy(
        Glyph.MOTION,
        R.string.collector_gyroscope_name,
        R.string.collector_gyroscope_records,
        R.string.collector_gyroscope_not_recorded,
    )
    ParticipantDataKind.AMBIENT_LIGHT -> CategoryCopy(
        Glyph.APP,
        R.string.collector_ambient_light_name,
        R.string.collector_ambient_light_records,
        R.string.collector_ambient_light_not_recorded,
    )
    ParticipantDataKind.PROXIMITY -> CategoryCopy(
        Glyph.CONNECTION,
        R.string.collector_proximity_name,
        R.string.collector_proximity_records,
        R.string.collector_proximity_not_recorded,
    )
    ParticipantDataKind.SCREEN_STATE -> CategoryCopy(
        Glyph.SCREEN,
        R.string.collector_screen_state_name,
        R.string.collector_screen_state_records,
        R.string.collector_screen_state_not_recorded,
    )
    ParticipantDataKind.NETWORK_THROUGHPUT -> CategoryCopy(
        Glyph.DATA_VOLUME,
        R.string.collector_network_throughput_name,
        R.string.collector_network_throughput_records,
        R.string.collector_network_throughput_not_recorded,
    )
    ParticipantDataKind.NETWORK_STATE -> CategoryCopy(
        Glyph.CONNECTION,
        R.string.collector_network_state_name,
        R.string.collector_network_state_records,
        R.string.collector_network_state_not_recorded,
    )
    ParticipantDataKind.VPN_STATE -> CategoryCopy(
        Glyph.CONNECTION,
        R.string.collector_vpn_state_name,
        R.string.collector_vpn_state_records,
        R.string.collector_vpn_state_not_recorded,
    )
    ParticipantDataKind.NETWORK_USAGE -> CategoryCopy(
        Glyph.DATA_VOLUME,
        R.string.collector_network_usage_name,
        R.string.collector_network_usage_records,
        R.string.collector_network_usage_not_recorded,
    )
    ParticipantDataKind.USAGE_EVENTS -> CategoryCopy(
        Glyph.SCREEN,
        R.string.collector_usage_events_name,
        R.string.collector_usage_events_records,
        R.string.collector_usage_events_not_recorded,
    )
    ParticipantDataKind.LOCATION -> CategoryCopy(
        Glyph.LOCATION,
        R.string.collector_location_name,
        R.string.collector_location_records,
        R.string.collector_location_not_recorded,
    )
    ParticipantDataKind.KEYBOARD_TOUCH -> CategoryCopy(
        Glyph.KEYBOARD,
        R.string.collector_keyboard_touch_name,
        R.string.collector_keyboard_touch_records,
        R.string.collector_keyboard_touch_not_recorded,
    )
}

@Composable
fun ParticipantDataCategory.summarize(): CollectorSummary {
    val copy = kind.categoryCopy()
    return CollectorSummary(
        glyph = copy.glyph,
        name = stringResource(copy.name),
        records = stringResource(copy.records),
        notRecorded = stringResource(R.string.data_not_recorded, stringResource(copy.notRecorded)),
        optional = optional,
    )
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
