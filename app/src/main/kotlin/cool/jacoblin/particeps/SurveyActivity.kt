package cool.jacoblin.particeps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cool.jacoblin.particeps.core.application.ParticipantSurveyAnswer
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.definition.MultipleChoiceQuestion
import cool.jacoblin.particeps.core.definition.ScaleQuestion
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.SingleChoiceQuestion
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import cool.jacoblin.particeps.core.definition.SurveyQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SurveyActivity : ComponentActivity() {
    private val actionId by lazy {
        requireNotNull(intent.getStringExtra(ACTION_ID)) { "Missing action ID" }
    }
    private val viewModel by viewModels<SurveyViewModel> {
        SurveyViewModel.Factory((application as CollectorApplication).session, actionId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SurveyScreen(
                    viewModel = viewModel,
                    onClose = {
                        viewModel.dismiss()
                        finish()
                    },
                )
            }
        }
    }

    companion object { const val ACTION_ID = "action_id" }
}

data class SurveyScreenState(
    val loading: Boolean = true,
    val survey: SurveyDefinition? = null,
    val answers: Map<String, ParticipantSurveyAnswer> = emptyMap(),
    val editable: Boolean = false,
    val submitted: Boolean = false,
    val message: String? = null,
)

class SurveyViewModel(
    private val session: StudySessionManager,
    private val actionId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SurveyScreenState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = session.snapshot.first { it.initialized }
            val survey = if (ready.study == null) null else session.surveyForAction(actionId)
            if (survey == null || session.openSurvey(actionId) != StudyCommandResult.Success) {
                mutableState.value = SurveyScreenState(loading = false, message = "unavailable")
                return@launch
            }
            mutableState.value = SurveyScreenState(loading = false, survey = survey, editable = true)
        }
    }

    fun answer(questionId: String, answer: ParticipantSurveyAnswer?) {
        if (!mutableState.value.editable) return
        mutableState.update { current ->
            current.copy(
                answers = if (answer == null) current.answers - questionId else current.answers + (questionId to answer),
            )
        }
    }

    fun submit() {
        val current = mutableState.value
        if (!current.editable || current.survey == null) return
        mutableState.update { it.copy(editable = false, message = null) }
        viewModelScope.launch {
            val result = session.submitSurvey(actionId, current.answers)
            mutableState.update { submissionState(it, result) }
        }
    }

    fun dismiss() {
        if (!mutableState.value.editable) return
        viewModelScope.launch { session.dismissSurvey(actionId) }
    }

    class Factory(
        private val session: StudySessionManager,
        private val actionId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SurveyViewModel(session, actionId) as T
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SurveyScreen(viewModel: SurveyViewModel, onClose: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val survey = state.survey
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(survey?.title?.resolve(language) ?: stringResource(R.string.survey_title)) },
            )
        },
    ) { padding ->
        when {
            state.loading -> Text(stringResource(R.string.survey_loading), Modifier.padding(padding).padding(24.dp))
            survey == null -> SurveyUnavailable(state.message, onClose, Modifier.padding(padding))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(survey.description.resolve(language), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(
                    progress = { state.answers.size.toFloat() / survey.questions.size },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "${state.answers.size} / ${survey.questions.size}"
                    },
                )
                survey.questions.forEachIndexed { index, question ->
                    SurveyQuestionField(
                        index = index,
                        question = question,
                        language = language,
                        answer = state.answers[question.id],
                        readOnly = !state.editable,
                        onAnswer = { viewModel.answer(question.id, it) },
                    )
                    HorizontalDivider()
                }
                state.message?.let { Text(messageText(it), color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.survey_close)) }
                    if (state.editable) {
                        Button(
                            onClick = { confirm = true },
                            enabled = validSurveyAnswers(survey, state.answers),
                        ) { Text(stringResource(R.string.survey_submit)) }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.survey_confirm_title)) },
            text = { Text(stringResource(R.string.survey_confirm_body)) },
            confirmButton = {
                Button(onClick = { confirm = false; viewModel.submit() }) {
                    Text(stringResource(R.string.survey_submit))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirm = false }) { Text(stringResource(R.string.survey_cancel)) }
            },
        )
    }
}

internal fun submissionState(current: SurveyScreenState, result: StudyCommandResult): SurveyScreenState = when (result) {
    StudyCommandResult.Success -> current.copy(editable = false, submitted = true, message = "submitted")
    StudyCommandResult.InvalidInput -> current.copy(editable = true, message = "invalid")
    StudyCommandResult.InvalidState,
    StudyCommandResult.FailedClosed,
    StudyCommandResult.AccessRequired,
    -> current.copy(editable = false, message = "unavailable")
}

@Composable
private fun SurveyQuestionField(
    index: Int,
    question: SurveyQuestion,
    language: String,
    answer: ParticipantSurveyAnswer?,
    readOnly: Boolean,
    onAnswer: (ParticipantSurveyAnswer?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${index + 1}. ${question.prompt.resolve(language)}", style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (question.required) R.string.survey_required else R.string.survey_optional),
            style = MaterialTheme.typography.labelMedium,
        )
        when (question) {
            is ShortTextQuestion -> ShortTextField(question, language, answer, readOnly, onAnswer)
            is ScaleQuestion -> ScaleField(question, language, answer, readOnly, onAnswer)
            is SingleChoiceQuestion -> Column(Modifier.selectableGroup()) {
                question.options.forEach { option ->
                    val selected = (answer as? ParticipantSurveyAnswer.Choice)?.optionId == option.id
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            enabled = !readOnly,
                            role = Role.RadioButton,
                            onClick = { onAnswer(ParticipantSurveyAnswer.Choice(option.id)) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !readOnly)
                        Text(option.label.resolve(language))
                    }
                }
            }
            is MultipleChoiceQuestion -> question.options.forEach { option ->
                val current = (answer as? ParticipantSurveyAnswer.MultipleChoice)?.optionIds.orEmpty()
                val selected = option.id in current
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = selected,
                        enabled = !readOnly,
                        role = Role.Checkbox,
                        onValueChange = {
                            val next = (if (selected) current - option.id else current + option.id).sorted()
                            if (next.size <= question.maximumSelections) {
                                onAnswer(ParticipantSurveyAnswer.MultipleChoice(next))
                            }
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selected, onCheckedChange = null, enabled = !readOnly)
                    Text(option.label.resolve(language))
                }
            }
        }
    }
}

@Composable
private fun ShortTextField(
    question: ShortTextQuestion,
    language: String,
    answer: ParticipantSurveyAnswer?,
    readOnly: Boolean,
    onAnswer: (ParticipantSurveyAnswer?) -> Unit,
) {
    val text = (answer as? ParticipantSurveyAnswer.Text)?.value.orEmpty()
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= question.maximumLength) onAnswer(ParticipantSurveyAnswer.Text(it)) },
        enabled = !readOnly,
        supportingText = { Text("${text.length}/${question.maximumLength}") },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = question.prompt.resolve(language) },
    )
}

@Composable
private fun ScaleField(
    question: ScaleQuestion,
    language: String,
    answer: ParticipantSurveyAnswer?,
    readOnly: Boolean,
    onAnswer: (ParticipantSurveyAnswer?) -> Unit,
) {
    val value = (answer as? ParticipantSurveyAnswer.Scale)?.value ?: question.minimum
    Slider(
        value = value.toFloat(),
        onValueChange = { onAnswer(ParticipantSurveyAnswer.Scale(it.toInt())) },
        valueRange = question.minimum.toFloat()..question.maximum.toFloat(),
        steps = (question.maximum - question.minimum - 1).coerceAtLeast(0),
        enabled = !readOnly,
        modifier = Modifier.semantics { contentDescription = "${question.prompt.resolve(language)}: $value" },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${question.minimum} · ${question.minimumLabel.resolve(language)}")
        Text("$value")
        Text("${question.maximum} · ${question.maximumLabel.resolve(language)}")
    }
}

@Composable
private fun SurveyUnavailable(code: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (code == "expired") stringResource(R.string.survey_expired) else stringResource(R.string.survey_unavailable))
        Button(onClick = onClose) { Text(stringResource(R.string.survey_close)) }
    }
}

@Composable
private fun messageText(code: String): String = when (code) {
    "submitted" -> stringResource(R.string.survey_submitted)
    "expired" -> stringResource(R.string.survey_expired)
    "invalid" -> stringResource(R.string.survey_invalid)
    else -> stringResource(R.string.survey_unavailable)
}

private fun validSurveyAnswers(
    survey: SurveyDefinition,
    answers: Map<String, ParticipantSurveyAnswer>,
): Boolean = survey.questions.all { question ->
    val answer = answers[question.id] ?: return@all !question.required
    when (question) {
        is ShortTextQuestion -> answer is ParticipantSurveyAnswer.Text &&
            answer.value.length <= question.maximumLength && (!question.required || answer.value.isNotBlank())
        is ScaleQuestion -> answer is ParticipantSurveyAnswer.Scale && answer.value in question.minimum..question.maximum
        is SingleChoiceQuestion -> answer is ParticipantSurveyAnswer.Choice &&
            question.options.any { it.id == answer.optionId }
        is MultipleChoiceQuestion -> answer is ParticipantSurveyAnswer.MultipleChoice &&
            answer.optionIds == answer.optionIds.sorted().distinct() &&
            answer.optionIds.size in question.minimumSelections..question.maximumSelections &&
            answer.optionIds.all { id -> question.options.any { it.id == id } }
    }
}
