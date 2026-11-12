package cool.jacoblin.particeps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.ln

/**
 * How far the study has come, as one bar over the signed duration. It shows only the study clock,
 * which every arm shares; paused spans are drawn on it only when [timeline] is present, that is,
 * when the signed disclosure policy allows the participant's own collection history.
 */
@Composable
internal fun StudyProgressBar(
    participation: ParticipantParticipationSummary,
    durationHours: Int,
    timeline: ParticipantCollectionTimeline?,
    nowUtcMillis: Long,
) {
    val durationMillis = durationHours * MILLIS_PER_HOUR
    val elapsed = participation.studyLength?.millisAt(nowUtcMillis)?.coerceIn(0, durationMillis) ?: return
    val fraction = if (durationMillis == 0L) 0f else elapsed.toFloat() / durationMillis
    val percent = (fraction * 100).toInt()
    val description = stringResource(R.string.participation_progress_description, percent)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val done = MaterialTheme.colorScheme.secondary
    val paused = MaterialTheme.colorScheme.outline
    val divider = MaterialTheme.colorScheme.surface
    val days = participation.studyDayCount
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.participation_progress), style = MaterialTheme.typography.labelLarge)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .testTag(UiTags.STUDY_PROGRESS)
                .semantics { contentDescription = description },
        ) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            drawRoundRect(done, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            timeline?.pausedSpans?.forEach { span ->
                val start = span.startMillis.coerceIn(0, durationMillis).toFloat() / durationMillis
                val end = (span.endMillis ?: elapsed).coerceIn(0, durationMillis).toFloat() / durationMillis
                if (end > start) {
                    drawRect(
                        paused,
                        topLeft = Offset(size.width * start, 0f),
                        size = Size(size.width * (end - start), size.height),
                    )
                }
            }
            for (day in 1 until days) {
                val x = size.width * (day * HOURS_PER_DAY).toFloat() / durationHours
                drawLine(divider, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        DayAxis(days)
        if (timeline != null && timeline.pausedSpans.isNotEmpty()) {
            Legend(listOf(paused to stringResource(R.string.timeline_paused)))
        }
    }
}

/**
 * One strip per data category, one cell per hour of study time (several hours per cell in a study
 * longer than a week), shaded by how much the category recorded relative to its own busiest cell.
 * Hours still to come are left blank. Text is limited to the category names, the day axis and a
 * legend; each strip carries a spoken summary for screen readers.
 */
@Composable
internal fun CollectionTimelineChart(
    study: ParticipantStudyUiModel,
    timeline: ParticipantCollectionTimeline,
    nowUtcMillis: Long,
) {
    val durationHours = study.durationHours
    val elapsedHours = study.participation.studyLength
        ?.millisAt(nowUtcMillis)
        ?.let { ceil(it.toDouble() / MILLIS_PER_HOUR).toInt() }
        ?.coerceIn(0, durationHours)
        ?: 0
    val hoursPerCell = ceil(durationHours.toDouble() / MAXIMUM_CELLS).toInt().coerceAtLeast(1)
    val cells = ceil(durationHours.toDouble() / hoursPerCell).toInt()
    val shades = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.primary,
    )
    val future = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    Column(
        Modifier.testTag(UiTags.COLLECTION_TIMELINE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.labelLarge)
        study.dataCategories.forEach { category ->
            val name = category.summarize().name
            val counts = timeline.hourlyCounts[category.kind].orEmpty()
            val perCell = LongArray(cells).also { buckets ->
                counts.forEach { (hour, count) ->
                    if (hour in 0 until durationHours) buckets[hour / hoursPerCell] += count
                }
            }
            val busiest = perCell.maxOrNull() ?: 0L
            val hoursWithData = counts.keys.count { it in 0 until durationHours }
            val description = stringResource(
                R.string.timeline_row_description,
                name,
                hoursWithData,
                elapsedHours,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .semantics { contentDescription = description },
                ) {
                    val gap = 1.dp.toPx()
                    val cellWidth = size.width / cells
                    for (cell in 0 until cells) {
                        val started = cell * hoursPerCell < elapsedHours
                        val color = if (!started) future else shades[shadeLevel(perCell[cell], busiest)]
                        drawRect(
                            color,
                            topLeft = Offset(cell * cellWidth, 0f),
                            size = Size((cellWidth - gap).coerceAtLeast(1f), size.height),
                        )
                    }
                }
            }
        }
        DayAxis(study.participation.studyDayCount)
        Legend(
            listOf(
                shades[0] to stringResource(R.string.timeline_less),
                shades[3] to stringResource(R.string.timeline_more),
            ),
        )
    }
}

/** Day labels spread under a bar; a long study labels only some days so the labels never collide. */
@Composable
private fun DayAxis(days: Int) {
    if (days < 1) return
    val step = ceil(days / MAXIMUM_DAY_LABELS.toDouble()).toInt().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth()) {
        for (day in 1..days) {
            Box(Modifier.weight(1f)) {
                if ((day - 1) % step == 0) {
                    Text(
                        stringResource(R.string.timeline_day, day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend(entries: List<Pair<Color, String>>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (color, label) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(10.dp)) { drawRect(color) }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 0 for no data, then 1–3 on a log scale against the busiest cell, so a quiet hour still shows. */
internal fun shadeLevel(count: Long, busiest: Long): Int {
    if (count <= 0L || busiest <= 0L) return 0
    val ratio = ln(1.0 + count) / ln(1.0 + busiest)
    return when {
        ratio > 2.0 / 3 -> 3
        ratio > 1.0 / 3 -> 2
        else -> 1
    }
}

private const val MILLIS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24
private const val MAXIMUM_CELLS = 168
private const val MAXIMUM_DAY_LABELS = 7
