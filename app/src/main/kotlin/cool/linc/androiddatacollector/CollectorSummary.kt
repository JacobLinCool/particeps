package cool.linc.androiddatacollector

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cool.linc.androiddatacollector.core.definition.AccelerometerConfiguration
import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.KeyboardTouchConfiguration
import cool.linc.androiddatacollector.core.definition.LocationConfiguration
import cool.linc.androiddatacollector.core.definition.NetworkStateConfiguration
import cool.linc.androiddatacollector.core.definition.NetworkUsageConfiguration
import cool.linc.androiddatacollector.core.definition.UsageEventsConfiguration
import cool.linc.androiddatacollector.core.definition.UploadConfiguration

/**
 * One collector, described to the participant in their own language.
 *
 * [detail] is a template filled from the signed configuration's own parameters, so a study that
 * samples location every ten seconds and one that samples it every ten minutes do not read the
 * same. The wording is the app's rather than the researcher's, which is what keeps a study from
 * describing a source as less than it is.
 */
data class CollectorSummary(
    val glyph: Glyph,
    val name: String,
    val detail: String,
    val optional: Boolean,
)

@Composable
fun CollectorConfiguration.summarize(): CollectorSummary = when (this) {
    is AppLifecycleConfiguration -> CollectorSummary(
        glyph = Glyph.APP,
        name = stringResource(R.string.collector_app_lifecycle_name),
        detail = stringResource(R.string.collector_app_lifecycle_detail),
        optional = !required,
    )

    is AccelerometerConfiguration -> CollectorSummary(
        glyph = Glyph.MOTION,
        name = stringResource(R.string.collector_accelerometer_name),
        // "or more" is not hedging. Android treats a sampling period as a hint, and a device is
        // free to deliver faster than the study asked for — measured at over ten times the
        // requested rate on a current emulator image. Stating the configured rate alone would
        // understate what is recorded.
        detail = (1_000_000.0 / samplingPeriodUs).toInt().coerceAtLeast(1).let { hz ->
            pluralStringResource(R.plurals.collector_accelerometer_detail, hz, hz)
        },
        optional = !required,
    )

    is NetworkStateConfiguration -> CollectorSummary(
        glyph = Glyph.CONNECTION,
        name = stringResource(R.string.collector_network_state_name),
        detail = stringResource(R.string.collector_network_state_detail),
        optional = !required,
    )

    is NetworkUsageConfiguration -> CollectorSummary(
        glyph = Glyph.DATA_VOLUME,
        name = stringResource(R.string.collector_network_usage_name),
        detail = stringResource(R.string.collector_network_usage_detail, minutesLabel(pollIntervalMinutes)),
        optional = !required,
    )

    is UsageEventsConfiguration -> CollectorSummary(
        glyph = Glyph.SCREEN,
        name = stringResource(R.string.collector_usage_events_name),
        detail = stringResource(R.string.collector_usage_events_detail, minutesLabel(pollIntervalMinutes)),
        optional = !required,
    )

    is LocationConfiguration -> CollectorSummary(
        glyph = Glyph.LOCATION,
        name = stringResource(R.string.collector_location_name),
        detail = stringResource(
            R.string.collector_location_detail,
            millisLabel(intervalMillis),
            stringResource(R.string.unit_metres, minimumDisplacementMeters.toInt()),
        ),
        optional = !required,
    )

    is KeyboardTouchConfiguration -> CollectorSummary(
        glyph = Glyph.KEYBOARD,
        name = stringResource(R.string.collector_keyboard_touch_name),
        detail = stringResource(R.string.collector_keyboard_touch_detail),
        optional = !required,
    )
}

/** Renders a duration in the coarsest unit that stays exact, so no reader has to divide. */
@Composable
fun minutesLabel(minutes: Int): String = when {
    minutes % (60 * 24) == 0 -> (minutes / (60 * 24)).let { pluralStringResource(R.plurals.unit_days, it, it) }
    minutes % 60 == 0 -> stringResource(R.string.unit_hours, minutes / 60)
    else -> stringResource(R.string.unit_minutes, minutes)
}

@Composable
fun millisLabel(millis: Long): String = when {
    millis % 60_000L == 0L -> minutesLabel((millis / 60_000L).toInt())
    else -> stringResource(R.string.unit_seconds, (millis / 1_000L).toInt())
}

@Composable
fun durationLabel(hours: Int): String = when {
    hours < 24 -> pluralStringResource(R.plurals.study_duration_hours, hours, hours)
    hours % 24 == 0 -> (hours / 24).let { pluralStringResource(R.plurals.study_duration_days, it, it) }
    else -> stringResource(R.string.study_duration_days_hours, hours / 24, hours % 24)
}

@Composable
fun uploadCadenceLabel(upload: UploadConfiguration): String {
    val minutes = upload.intervalMinutes
    val every = when {
        minutes % (60 * 24) == 0 ->
            (minutes / (60 * 24)).let { pluralStringResource(R.plurals.upload_every_days, it, it) }
        minutes % 60 == 0 ->
            (minutes / 60).let { pluralStringResource(R.plurals.upload_every_hours, it, it) }
        else -> pluralStringResource(R.plurals.upload_every_minutes, minutes, minutes)
    }
    val network = if (upload.allowMetered) {
        stringResource(R.string.upload_any_network)
    } else {
        stringResource(R.string.upload_wifi_only)
    }
    return stringResource(R.string.consent_upload_cadence, every, network)
}
