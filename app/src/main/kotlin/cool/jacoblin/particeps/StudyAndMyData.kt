package cool.jacoblin.particeps

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * The one way into *Study and my data*: a labeled row, not a filled button, so it never competes
 * with Pause, Resume, Export, or Withdraw. It is the same row for every study and every study arm,
 * because its presence or wording must not say anything about the study. Its title already names
 * the destination, so a screen reader announces the ordinary activate action rather than repeating
 * the title as a verb.
 */
@Composable
internal fun StudyAndMyDataEntry(onOpen: () -> Unit) {
    val label = stringResource(R.string.my_data_entry)
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag(UiTags.STUDY_AND_MY_DATA)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.my_data_entry_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron(forward = true)
        }
        HorizontalDivider()
    }
}

/**
 * What the participant agreed to and what their participation amounts to, restated after Start.
 *
 * Everything here comes from [ParticipantStudyUiModel], so it can show only platform-floor
 * information and coarse participation facts. It offers no lifecycle control: Pause, Resume,
 * Withdraw, and Delete are described here and used only from the collection screen.
 */
@Composable
internal fun StudyAndMyDataScreen(
    study: ParticipantStudyUiModel,
    readLocalStorageBytes: suspend () -> Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.my_data_entry)
    Column(
        modifier = modifier
            .fillMaxSize()
            // Opening replaces the collection screen in place, so the pane title is what tells a
            // screen reader that a different page is now showing.
            .semantics { paneTitle = title }
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.STUDY_AND_MY_DATA_SCREEN)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(UiTags.STUDY_AND_MY_DATA_BACK)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chevron(forward = false)
                    Text(stringResource(R.string.action_back))
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Section(R.string.my_data_about_title) { AboutThisStudy(study) }
        Section(R.string.my_data_collected_title) { WhatIsCollected(study) }
        Section(R.string.my_data_participation_title) { YourParticipation(study, readLocalStorageBytes) }
        Section(R.string.my_data_rights_title) { YourRights(uploads = study.upload != null) }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/**
 * A label above its value; identifiers can be long and must stay copyable. A screen reader hears
 * the pair as one item rather than a label with its value somewhere after it.
 */
@Composable
private fun FactLine(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

@Composable
private fun AboutThisStudy(study: ParticipantStudyUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(study.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(study.purpose)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FactRow(Glyph.PERSON, study.researcherName)
                FactRow(Glyph.CONTACT, study.researcherContact)
                FactRow(Glyph.CLOCK, durationLabel(study.durationHours))
            }
        }
        Text(stringResource(R.string.my_data_consent_title), fontWeight = FontWeight.Bold)
        SelectionContainer { Text(study.consentSummary) }
        // The signing-key fingerprint and any assigned code are things a participant may need to
        // quote to the research team, so they stay copyable here like the contact details.
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PublisherDisclosure(study.signerFingerprint, study.signerAnchored)
                IdentityDisclosure(study.assignedParticipantId)
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FactLine(stringResource(R.string.details_instance_id), study.participantInstanceId)
                FactLine(stringResource(R.string.details_consent_document), study.consentDocumentVersion)
            }
        }
    }
}

/**
 * The same fixed per-category copy as the Data step, plus the Android access each category uses.
 * Access is matched through [ParticipantAccessItem.owners], which is derived from the event-source
 * registry rather than from any profile, so a study arm cannot change what is listed.
 */
@Composable
private fun WhatIsCollected(study: ParticipantStudyUiModel) {
    val separator = stringResource(R.string.list_separator)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        study.dataCategories.forEach { category ->
            val summary = category.summarize()
            val access = study.access
                .filter { item ->
                    item.owners.any { it is ParticipantAccessOwner.DataCategory && it.kind == category.kind }
                }
                .map { stringResource(it.kind.labelRes()) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphIcon(summary.glyph, MaterialTheme.colorScheme.primary, 22.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(summary.name, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(if (summary.optional) R.string.data_optional else R.string.data_required),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(summary.records)
                    Text(
                        summary.notRecorded,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (access.isEmpty()) {
                            stringResource(R.string.data_access_none)
                        } else {
                            stringResource(R.string.data_access_line, access.joinToString(separator))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.my_data_notifications),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UploadDisclosure(study.upload)
        if (study.trafficShapingDisclosureRequired) {
            Text(
                stringResource(R.string.traffic_shaping_disclosure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.TRAFFIC_SHAPING_DISCLOSURE),
            )
        }
    }
}

private sealed interface StorageReading {
    data object Checking : StorageReading
    data object Unavailable : StorageReading
    data class Measured(val bytes: Long) : StorageReading
}

@Composable
private fun YourParticipation(
    study: ParticipantStudyUiModel,
    readLocalStorageBytes: suspend () -> Long?,
) {
    val context = LocalContext.current
    val now by rememberWallClockMillis()
    // One read per visit. The figure is measured off the main thread by the caller and is never
    // refreshed in the background; reopening the screen measures it again.
    val storage by produceState<StorageReading>(StorageReading.Checking) {
        value = readLocalStorageBytes()?.let(StorageReading::Measured) ?: StorageReading.Unavailable
    }
    val participation = study.participation
    val numbers = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FactLine(stringResource(R.string.participation_status), stringResource(study.state.labelRes()))
        participation.studyDayAt(now)?.let { day ->
            FactLine(
                stringResource(R.string.participation_day),
                stringResource(R.string.participation_day_value, day, participation.studyDayCount),
            )
        }
        if (!participation.ended) {
            FactLine(
                stringResource(R.string.participation_planned_end),
                participation.plannedEndUtcMillis?.let { wallClockLabel(it) }
                    ?: stringResource(R.string.participation_planned_end_unconfirmed),
            )
        }
        FactLine(
            stringResource(R.string.participation_active),
            elapsedLabel(participation.activeCollection.millisAt(now)),
        )
        participation.pausedMillisAt(now)?.let { paused ->
            FactLine(stringResource(R.string.participation_paused), elapsedLabel(paused))
        }
        FactLine(
            stringResource(R.string.details_last_export),
            study.lastExport?.let {
                stringResource(
                    R.string.details_last_export_value,
                    Formatter.formatShortFileSize(context, it.byteCount),
                    numbers.format(it.eventCount),
                )
            } ?: stringResource(R.string.participation_no_export),
        )
        FactLine(
            stringResource(R.string.participation_storage),
            when (val reading = storage) {
                StorageReading.Checking -> stringResource(R.string.participation_storage_checking)
                StorageReading.Unavailable -> stringResource(R.string.participation_storage_unavailable)
                is StorageReading.Measured -> Formatter.formatShortFileSize(context, reading.bytes)
            },
            modifier = Modifier.testTag(UiTags.PARTICIPATION_STORAGE),
        )
    }
}

/**
 * What each participant control does, stated as the code does it. Upload sentences appear only for
 * a study that sends data automatically: Pause and Withdraw leave sending in place, and only Delete
 * local data cancels it.
 */
@Composable
private fun YourRights(uploads: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Right(
            R.string.rights_pause_title,
            R.string.rights_pause_body,
            R.string.rights_pause_upload.takeIf { uploads },
        )
        Right(
            R.string.rights_withdraw_title,
            R.string.rights_withdraw_body,
            R.string.rights_withdraw_upload.takeIf { uploads },
        )
        Right(
            R.string.rights_delete_title,
            R.string.rights_delete_body,
            R.string.rights_delete_upload.takeIf { uploads },
        )
    }
}

@Composable
private fun Right(title: Int, body: Int, uploadConsequence: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.Bold)
        Text(stringResource(body))
        uploadConsequence?.let { Text(stringResource(it)) }
    }
}
