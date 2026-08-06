package cool.linc.particeps

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cool.linc.particeps.core.collector.AccessKind
import cool.linc.particeps.core.collector.AccessStatus
import cool.linc.particeps.core.collector.CollectorStatus
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.UploadConfiguration
import cool.linc.particeps.core.model.ExperimentState
import cool.linc.particeps.core.model.StudyMetadata
import java.text.NumberFormat
import kotlinx.coroutines.delay

object UiTags {
    const val STATE = "state"
    const val IMPORT_DEMO = "import_demo"
    const val REVIEW = "review"
    const val CONTINUE = "continue"
    const val CONSENT_CHECKBOX = "consent_checkbox"
    const val PREPARE = "prepare"
    const val ACCESS_COMPLETE = "access_complete"
    const val START = "start"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val FINISH = "finish"
    const val WITHDRAW = "withdraw"
    const val EXPORT = "export"
    const val EVENT_COUNT = "event_count"
    const val PAUSED_SINCE = "paused_since"
}

data class StudyUiActions(
    val import: () -> Unit,
    /** Null when the build ships no demonstration study, which is the case for a release. */
    val demo: (() -> Unit)?,
    val review: () -> Unit,
    val acceptConsent: () -> Unit,
    val completeAccess: () -> Unit,
    val requestAccess: (AccessKind) -> Unit,
    val start: () -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val finish: () -> Unit,
    val withdraw: () -> Unit,
    val export: () -> Unit,
    val delete: () -> Unit,
)

/**
 * Setup is a fixed sequence, and the screen shows exactly one of its steps at a time.
 *
 * [DATA] and [CONSENT] are both `CONSENT_PENDING` in the domain: what the study collects and what
 * the participant is agreeing to are one decision, but reading them as one wall of text is not the
 * same as reading them one at a time.
 */
private enum class SetupStep(val labelRes: Int) {
    STUDY(R.string.step_study),
    DATA(R.string.step_data),
    CONSENT(R.string.step_consent),
    ACCESS(R.string.step_access),
    START(R.string.step_start),
}

@Composable
fun CollectorApp(
    state: StudyUiState,
    actions: StudyUiActions,
) {
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var languageOpen by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF16476A),
            secondary = Color(0xFF00796B),
            background = Color(0xFFF5F7FA),
            surface = Color.White,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                Dashboard(
                    state = state,
                    actions = actions.copy(
                        finish = { confirmAction = ConfirmAction.FINISH },
                        withdraw = { confirmAction = ConfirmAction.WITHDRAW },
                        delete = { confirmAction = ConfirmAction.DELETE },
                    ),
                    onOpenLanguage = { languageOpen = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        if (languageOpen) LanguageDialog(onDismiss = { languageOpen = false })

        confirmAction?.let { action ->
            ConfirmDialog(
                action = action,
                onDismiss = { confirmAction = null },
                onConfirm = {
                    confirmAction = null
                    when (action) {
                        ConfirmAction.FINISH -> actions.finish()
                        ConfirmAction.WITHDRAW -> actions.withdraw()
                        ConfirmAction.DELETE -> actions.delete()
                    }
                },
            )
        }
    }
}

@Composable
private fun Dashboard(
    state: StudyUiState,
    actions: StudyUiActions,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val study = state as? StudyUiState.ActiveStudy
    val metadata = study?.metadata
    // Re-entering CONSENT_PENDING starts at the data page again, so nobody lands on the checkbox
    // without the list of sources having been on screen.
    var page by remember(metadata?.state) { mutableStateOf(SetupStep.DATA) }
    val step = metadata?.state?.let { setupStep(it, page) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(
            title = study?.configuration?.title ?: stringResource(R.string.header_no_study),
            step = step,
            initializing = state is StudyUiState.Initializing,
            state = metadata?.state,
            metadata = metadata,
            onOpenLanguage = onOpenLanguage,
        )
        state.message?.let { Alert(it) }

        if (state is StudyUiState.Initializing) return@Column
        if (study == null || metadata == null) {
            NoStudyPanel(actions)
            return@Column
        }

        when (step) {
            SetupStep.STUDY -> StudyPanel(study, actions, state.busy)
            SetupStep.DATA -> DataPanel(
                configuration = study.configuration,
                actions = actions,
                busy = state.busy,
                onContinue = { page = SetupStep.CONSENT },
            )
            SetupStep.CONSENT -> ConsentPanel(study, actions, state.busy)
            SetupStep.ACCESS -> AccessPanel(study.access, actions, state.busy)
            SetupStep.START -> StartPanel(actions, state.busy)
            null -> CollectionPanel(study, actions, state.busy)
        }
    }
}

private fun setupStep(state: ExperimentState, page: SetupStep): SetupStep? = when (state) {
    ExperimentState.IMPORTED, ExperimentState.CONFIG_VERIFIED -> SetupStep.STUDY
    ExperimentState.CONSENT_PENDING -> page
    ExperimentState.ACCESS_SETUP -> SetupStep.ACCESS
    ExperimentState.READY -> SetupStep.START
    else -> null
}

/**
 * Study name, and where you are — nothing else.
 *
 * During setup that is a position in a fixed sequence, so it is dots. Afterwards there is no
 * sequence left to be positioned in, so it becomes what the study is doing and for how long. The
 * researcher's name and the duration are not here: they belong to the first step, where they are
 * something the participant is reading in order to decide.
 */
@Composable
private fun Header(
    title: String,
    initializing: Boolean,
    step: SetupStep?,
    state: ExperimentState?,
    metadata: StudyMetadata?,
    onOpenLanguage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            val languageLabel = stringResource(R.string.cd_language)
            Box(
                Modifier
                    .clickable(onClick = onOpenLanguage)
                    .semantics { contentDescription = languageLabel }
                    .padding(4.dp),
            ) {
                GlyphIcon(Glyph.LANGUAGE, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
            }
        }
        when {
            initializing -> Text(
                stringResource(R.string.header_starting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step != null -> StepRail(step)
            state != null -> StatusLine(state, metadata)
            // No study imported: the title is the app's own name and there is no position to
            // report, so the header stops here rather than inventing a line.
            else -> Unit
        }
    }
}

/**
 * Five dots and the line between them: the shape of the flow, and how much of it is left.
 *
 * The step names exist only as the row's content description. Sighted readers get the position from
 * the dots and the content from the panel below, so printing five labels would be five words that
 * say nothing new — but a screen reader has no dots to read, so it gets the name.
 */
@Composable
private fun StepRail(step: SetupStep) {
    val reached = step.ordinal
    val label = stringResource(step.labelRes)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupStep.entries.forEachIndexed { index, _ ->
            Connector(visible = index > 0, filled = index <= reached)
            StepDot(done = index < reached, current = index == reached)
            Connector(visible = index < SetupStep.entries.lastIndex, filled = index < reached)
        }
    }
}

@Composable
private fun StatusLine(state: ExperimentState, metadata: StudyMetadata?) {
    val started = metadata?.transitions?.firstOrNull { it.to == ExperimentState.RUNNING }
        ?.time?.wallTimeUtcMillis
    // A study that is over has a length, not an age. Freezing it at the terminal transition is
    // what makes the figure mean the same thing on a finished study as on a running one.
    val ended = metadata?.transitions?.lastOrNull { it.to in TERMINAL_STATES }?.time?.wallTimeUtcMillis
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    // A pause is the one state a participant can leave the study in by accident, so it reports both
    // halves: when it started, and how long ago that was. The elapsed figure beside the state name
    // is the study's own age and keeps running through a pause, which is why it cannot carry this.
    val pausedAt = state.takeIf { it == ExperimentState.PAUSED }?.let {
        metadata?.transitions?.lastOrNull { transition -> transition.to == ExperimentState.PAUSED }
            ?.time?.wallTimeUtcMillis
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(stateTint(state), CircleShape))
            Text(
                stringResource(state.labelRes()),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(UiTags.STATE),
            )
            started?.let {
                Text(elapsedLabel((ended ?: now) - it), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        pausedAt?.let {
            Text(
                stringResource(R.string.status_paused_since, wallClockLabel(it), elapsedLabel(now - it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.PAUSED_SINCE),
            )
        }
    }
}

/**
 * Android's own date/time rendering, so the participant sees their locale and their 12/24-hour
 * setting rather than a format this app invented. The date is always shown: a pause that reads
 * "2:32" is indistinguishable from one three days old, and that is exactly the case the line exists
 * to catch.
 */
@Composable
private fun wallClockLabel(millis: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
    )
}

@Composable
private fun elapsedLabel(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) / 60_000L).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.header_elapsed_minutes, minutes)
        minutes < 60 * 24 -> stringResource(R.string.header_elapsed_hours, minutes / 60, minutes % 60)
        else -> stringResource(R.string.header_elapsed_days, minutes / (60 * 24), minutes % (60 * 24) / 60)
    }
}

@Composable
private fun NoStudyPanel(actions: StudyUiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions.import, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_choose_configuration))
        }
        actions.demo?.let { demo ->
            OutlinedButton(
                onClick = demo,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.IMPORT_DEMO),
            ) { Text(stringResource(R.string.action_load_demo)) }
        }
    }
}

/** Step 1. What this study is, who is asking, and for how long — all of it the researcher's text. */
@Composable
private fun StudyPanel(study: StudyUiState.ActiveStudy, actions: StudyUiActions, busy: Boolean) {
    val configuration = study.configuration
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(configuration.purpose)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FactRow(Glyph.PERSON, configuration.researcherName)
            FactRow(Glyph.CONTACT, configuration.researcherContact)
            FactRow(Glyph.CLOCK, durationLabel(configuration.durationHours))
        }
        Button(
            onClick = actions.review,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.REVIEW),
        ) { Text(stringResource(R.string.action_continue)) }
        WithdrawLink(actions, busy)
    }
}

@Composable
private fun FactRow(glyph: Glyph, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/**
 * Step 2. Every source the signed study enables, described from a template rather than from the
 * researcher's prose, so the parameters on screen are the ones the app will actually run.
 */
@Composable
private fun DataPanel(
    configuration: StudyConfiguration,
    actions: StudyUiActions,
    busy: Boolean,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        configuration.collectors.forEach { collector ->
            val summary = collector.summarize()
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphIcon(summary.glyph, MaterialTheme.colorScheme.primary, 22.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(summary.name, fontWeight = FontWeight.Bold)
                        if (summary.optional) {
                            Text(
                                stringResource(R.string.data_optional),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(summary.detail)
                }
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTINUE),
        ) { Text(stringResource(R.string.action_continue)) }
        WithdrawLink(actions, busy)
    }
}

/** Step 3. The researcher's consent text, then the two things the app asserts on its own. */
@Composable
private fun ConsentPanel(study: StudyUiState.ActiveStudy, actions: StudyUiActions, busy: Boolean) {
    var consentChecked by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(study.configuration.consentSummary)
        // Both blocks are rendered from the signed configuration rather than the researcher's
        // prose, so they describe what the app will actually do even if the summary leaves it out.
        // Their wording is deliberately complete.
        PublisherDisclosure(study.configuration, study.signerAnchored)
        IdentityDisclosure(study.configuration.assignedParticipantId)
        UploadDisclosure(study.configuration.upload)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = consentChecked,
                onCheckedChange = { consentChecked = it },
                modifier = Modifier.testTag(UiTags.CONSENT_CHECKBOX),
            )
            Text(stringResource(R.string.consent_checkbox))
        }
        Button(
            onClick = actions.acceptConsent,
            enabled = consentChecked && !busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.PREPARE),
        ) { Text(stringResource(R.string.action_agree)) }
        WithdrawLink(actions, busy)
    }
}

@Composable
private fun IdentityDisclosure(assignedParticipantId: String?) {
    Disclosure(
        mark = { GlyphIcon(Glyph.PERSON, MaterialTheme.colorScheme.primary, 16.dp) },
        title = stringResource(
            if (assignedParticipantId == null) R.string.consent_identity_anonymous_title
            else R.string.consent_identity_personalized_title,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (assignedParticipantId == null) {
                Text(stringResource(R.string.consent_identity_anonymous_body))
            } else {
                Text(stringResource(R.string.consent_identity_assigned_code, assignedParticipantId), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.consent_identity_personalized_body))
            }
            Text(stringResource(R.string.consent_identity_instance_body))
        }
    }
}

/**
 * A block the app asserts itself, set apart from the researcher's prose above it by ground rather
 * than by being a separate card: it belongs to this step, and the step shows one thing.
 */
@Composable
private fun Disclosure(
    mark: @Composable () -> Unit,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            mark()
            Text(title, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/**
 * Who signed this study, and whether the app can vouch for them.
 *
 * A configuration carries its own signing key, so a valid signature proves the file is unchanged
 * since it was signed — not who wrote it. Unless this build pins the signer, the fingerprint is the
 * only thing tying a study to a real research team.
 *
 * An unpinned signer is not an error, it is the deployment model, so nothing here is drawn in the
 * error colour: painting the normal case red would say something is broken when nothing is, and a
 * reader who learns to skip red learns to skip the one line that matters. What carries the weight
 * instead is an instruction — check this fingerprint — with the reason kept quiet underneath it.
 */
@Composable
private fun PublisherDisclosure(configuration: StudyConfiguration, anchored: Boolean) {
    Disclosure(
        mark = {
            if (anchored) CheckMark(MaterialTheme.colorScheme.secondary, 16.dp) else PendingMark(blocking = false)
        },
        title = stringResource(R.string.consent_signature_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(configuration.signer.fingerprint, fontWeight = FontWeight.SemiBold)
            if (anchored) {
                Text(stringResource(R.string.consent_signature_anchored))
            } else {
                Text(stringResource(R.string.consent_signature_compare))
                Text(
                    stringResource(R.string.consent_signature_unverified),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The upload terms, derived from the signed configuration. This is the one screen the participant
 * must read before accepting, so every term stays spelled out.
 *
 * A study without an upload block renders the negative case rather than nothing. Leaving the block
 * out would make "this study does not send data anywhere" something the participant has to infer
 * from an absence, and an absence is not a disclosure.
 */
@Composable
private fun UploadDisclosure(upload: UploadConfiguration?) {
    if (upload == null) {
        Disclosure(
            mark = { CheckMark(MaterialTheme.colorScheme.secondary, 16.dp) },
            title = stringResource(R.string.consent_upload_none_title),
        ) { Text(stringResource(R.string.consent_upload_none_body)) }
        return
    }
    val host = runCatching { java.net.URI(upload.endpoint).host }.getOrNull() ?: upload.endpoint
    Disclosure(
        mark = { GlyphIcon(Glyph.DATA_VOLUME, MaterialTheme.colorScheme.primary, 16.dp) },
        title = stringResource(R.string.consent_upload_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.consent_upload_destination, host))
            Text(uploadCadenceLabel(upload))
            Text(stringResource(R.string.consent_upload_encrypted))
            Text(stringResource(R.string.consent_upload_instance_id))
            Text(
                stringResource(R.string.consent_upload_mandatory),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** Step 4. Each row is the control: tapping an outstanding item opens the screen that grants it. */
@Composable
private fun AccessPanel(checks: List<AccessStatus>, actions: StudyUiActions, busy: Boolean) {
    val requiredReady = checks.none { it.requirement.required && !it.granted }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        checks.forEach { check ->
            val actionable = !check.granted && check.requirement.kind !in HARDWARE_ACCESS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (actionable) {
                            Modifier.clickable { actions.requestAccess(check.requirement.kind) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (check.granted) {
                    CheckMark(MaterialTheme.colorScheme.secondary, 16.dp)
                } else {
                    PendingMark(blocking = check.requirement.required)
                }
                Text(stringResource(check.requirement.kind.labelRes()), Modifier.weight(1f))
                if (!check.granted && !check.requirement.required) {
                    Text(
                        stringResource(R.string.data_optional),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = actions.completeAccess,
            enabled = requiredReady && !busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.ACCESS_COMPLETE),
        ) { Text(stringResource(R.string.action_done)) }
        WithdrawLink(actions, busy)
    }
}

/** Step 5. Importing collects nothing; this press is what starts it. */
@Composable
private fun StartPanel(actions: StudyUiActions, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            onClick = actions.start,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.START),
        ) { Text(stringResource(R.string.action_start)) }
        WithdrawLink(actions, busy)
    }
}

@Composable
private fun CollectionPanel(study: StudyUiState.ActiveStudy, actions: StudyUiActions, busy: Boolean) {
    val state = study.metadata.state
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CollectorGrid(study)
        EventMeter(study)
        when (state) {
            ExperimentState.RUNNING -> CollectionControls(
                stringResource(R.string.action_pause), UiTags.PAUSE, actions.pause, actions.finish, busy,
            )
            ExperimentState.PAUSED -> CollectionControls(
                stringResource(R.string.action_resume), UiTags.RESUME, actions.resume, actions.finish, busy,
            )
            else -> Unit
        }
        Button(
            onClick = actions.export,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.EXPORT),
        ) { Text(stringResource(R.string.action_export)) }
        if (state in TERMINAL_STATES) {
            OutlinedButton(onClick = actions.delete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_delete))
            }
        }
        if (state != ExperimentState.WITHDRAWN) WithdrawLink(actions, busy)
        StudyDetails(study)
    }
}

@Composable
private fun WithdrawLink(actions: StudyUiActions, busy: Boolean) {
    TextButton(
        onClick = actions.withdraw,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.WITHDRAW),
    ) { Text(stringResource(R.string.action_withdraw)) }
}

@Composable
private fun CollectorGrid(study: StudyUiState.ActiveStudy) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        study.configuration.collectors.forEach { collector ->
            val health = study.collectorHealth[collector.id]
            val summary = collector.summarize()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).background(collectorTint(health?.status), CircleShape))
                GlyphIcon(summary.glyph, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp)
                Text(summary.name, Modifier.weight(1f))
                // Normal is silent. CollectorHealth carries a reason code for exactly the states
                // that need attention and for no others, so its presence is the condition, and the
                // code itself is what a participant would quote to the research team.
                health?.reasonCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/**
 * Recorded events, and how much of that an endpoint has confirmed. A bar carries the ratio that two
 * sentences of running totals used to.
 */
@Composable
private fun EventMeter(study: StudyUiState.ActiveStudy) {
    val total = study.metadata.eventCount
    val delivered = study.metadata.uploadedThroughSequence
    val uploads = study.configuration.upload != null
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(
                    R.plurals.meter_events,
                    total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    numbers.format(total),
                ),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).testTag(UiTags.EVENT_COUNT),
            )
            if (uploads && total > 0) {
                Text(
                    study.upload?.lastFailureCode
                        ?: stringResource(R.string.meter_sent, numbers.format(delivered)),
                    color = if (study.upload?.lastFailureCode != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (uploads && total > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (delivered.toFloat() / total).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)),
                )
            }
        }
        if (study.metadata.retainedFromSequence > 1) {
            Text(
                stringResource(
                    R.string.meter_reclaimed,
                    numbers.format(study.metadata.retainedFromSequence - 1),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Technical identifiers matter when something goes wrong, and are noise otherwise. */
@Composable
private fun StudyDetails(study: StudyUiState.ActiveStudy) {
    var expanded by remember { mutableStateOf(false) }
    val configuration = study.configuration
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (expanded) {
            HorizontalDivider()
            DetailRow(stringResource(R.string.details_configuration), configuration.configurationId)
            DetailRow(stringResource(R.string.details_instance_id), study.metadata.participantInstanceId)
            configuration.assignedParticipantId?.let {
                DetailRow(stringResource(R.string.details_assigned_id), it)
            }
            DetailRow(stringResource(R.string.details_consent_document), configuration.consentDocumentVersion)
            DetailRow(stringResource(R.string.details_signature), configuration.signer.fingerprint)
            study.lastExport?.let {
                DetailRow(
                    stringResource(R.string.details_last_export),
                    stringResource(R.string.details_last_export_value, it.eventCount, it.sha256.take(12)),
                )
            }
        }
        val detailsLabel = stringResource(R.string.cd_details)
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { contentDescription = detailsLabel }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Chevron(up = expanded)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CollectionControls(
    primaryLabel: String,
    primaryTag: String,
    onPrimary: () -> Unit,
    onFinish: () -> Unit,
    busy: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onPrimary,
            enabled = !busy,
            modifier = Modifier.weight(1f).testTag(primaryTag),
        ) { Text(primaryLabel) }
        OutlinedButton(
            onClick = onFinish,
            enabled = !busy,
            modifier = Modifier.weight(1f).testTag(UiTags.FINISH),
        ) { Text(stringResource(R.string.action_finish)) }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val selected = AppLocale.selected(context)
    val options = listOf<String?>(null) + AppLocale.supported(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column {
                options.forEach { tag ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppLocale.select(context, tag)
                                onDismiss()
                            }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tag == selected) {
                            CheckMark(MaterialTheme.colorScheme.primary, 16.dp)
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                        Text(tag?.let(AppLocale::endonym) ?: stringResource(R.string.language_system))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Irreversible actions are exactly where text earns its place.
    val (title, body) = when (action) {
        ConfirmAction.FINISH -> R.string.confirm_finish_title to R.string.confirm_finish_body
        ConfirmAction.WITHDRAW -> R.string.confirm_withdraw_title to R.string.confirm_withdraw_body
        ConfirmAction.DELETE -> R.string.confirm_delete_title to R.string.confirm_delete_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RowScope.Connector(visible: Boolean, filled: Boolean) {
    Box(
        Modifier
            .weight(1f)
            .height(2.dp)
            .background(
                when {
                    !visible -> Color.Transparent
                    filled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
    )
}

@Composable
private fun StepDot(done: Boolean, current: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    when {
        done -> CheckMark(primary, 16.dp)
        current -> Box(Modifier.size(16.dp).border(4.dp, primary, CircleShape))
        else -> Box(Modifier.size(16.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

/** A filled circle with a check, drawn rather than pulled from an icon set. */
@Composable
private fun CheckMark(tint: Color, diameter: Dp) {
    Canvas(Modifier.size(diameter)) {
        val s = size.minDimension
        drawCircle(color = tint)
        drawPath(
            Path().apply {
                moveTo(s * 0.28f, s * 0.52f)
                lineTo(s * 0.44f, s * 0.68f)
                lineTo(s * 0.74f, s * 0.34f)
            },
            color = Color.White,
            style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Hollow ring for something still outstanding; solid when it blocks progress. */
@Composable
private fun PendingMark(blocking: Boolean) {
    val tint = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(16.dp)
            .then(
                if (blocking) Modifier.background(tint, CircleShape)
                else Modifier.border(2.dp, tint, CircleShape),
            ),
    )
}

@Composable
private fun Chevron(up: Boolean) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val top = if (up) s * 0.62f else s * 0.38f
        val bottom = if (up) s * 0.38f else s * 0.62f
        drawPath(
            Path().apply {
                moveTo(s * 0.22f, top)
                lineTo(s * 0.5f, bottom)
                lineTo(s * 0.78f, top)
            },
            color = tint,
            style = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Incident codes are the one place raw identifiers reach the participant, so they stay legible. */
@Composable
private fun Alert(code: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PendingMark(blocking = true)
        Text(code, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private enum class ConfirmAction { FINISH, WITHDRAW, DELETE }

@Composable
private fun stateTint(state: ExperimentState): Color = when (state) {
    ExperimentState.RUNNING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun collectorTint(status: CollectorStatus?): Color = when (status) {
    CollectorStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
    CollectorStatus.BLOCKED_ACCESS, CollectorStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

/** Setup states never reach here: during setup the header shows a position, not a name. */
private fun ExperimentState.labelRes(): Int = when (this) {
    ExperimentState.PAUSED -> R.string.state_paused
    ExperimentState.COMPLETED -> R.string.state_completed
    ExperimentState.WITHDRAWN -> R.string.state_withdrawn
    else -> R.string.state_running
}

private fun AccessKind.labelRes(): Int = when (this) {
    AccessKind.FINE_LOCATION -> R.string.access_fine_location
    AccessKind.BACKGROUND_LOCATION -> R.string.access_background_location
    AccessKind.NOTIFICATIONS -> R.string.access_notifications
    AccessKind.USAGE_ACCESS -> R.string.access_usage_access
    AccessKind.RESEARCH_KEYBOARD_ENABLED -> R.string.access_research_keyboard_enabled
    AccessKind.RESEARCH_KEYBOARD_SELECTED -> R.string.access_research_keyboard_selected
    AccessKind.ACCELEROMETER_HARDWARE -> R.string.access_accelerometer_hardware
    AccessKind.GYROSCOPE_HARDWARE -> R.string.access_gyroscope_hardware
    AccessKind.AMBIENT_LIGHT_HARDWARE -> R.string.access_ambient_light_hardware
    AccessKind.PROXIMITY_HARDWARE -> R.string.access_proximity_hardware
}

private val HARDWARE_ACCESS = setOf(
    AccessKind.ACCELEROMETER_HARDWARE,
    AccessKind.GYROSCOPE_HARDWARE,
    AccessKind.AMBIENT_LIGHT_HARDWARE,
    AccessKind.PROXIMITY_HARDWARE,
)

private val TERMINAL_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)

private const val TICK_MILLIS = 30_000L
