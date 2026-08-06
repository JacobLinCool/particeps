package cool.linc.particeps

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import cool.linc.particeps.core.application.StudySessionManager
import cool.linc.particeps.core.definition.MultipleChoiceQuestion
import cool.linc.particeps.core.definition.ScaleQuestion
import cool.linc.particeps.core.definition.ShortTextQuestion
import cool.linc.particeps.core.definition.SingleChoiceQuestion
import cool.linc.particeps.core.definition.SurveyAction
import cool.linc.particeps.core.definition.SurveyDefinition
import cool.linc.particeps.core.definition.SurveyQuestion
import cool.linc.particeps.core.model.OccurrenceState
import cool.linc.particeps.core.runtime.SurveyAnswer
import cool.linc.particeps.core.runtime.SurveySubmissionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SurveyActivity : ComponentActivity() {
    private val occurrenceId by lazy {
        requireNotNull(intent.getStringExtra(OCCURRENCE_ID)) { "Missing occurrence ID" }
    }
    private val viewModel by viewModels<SurveyViewModel> {
        SurveyViewModel.Factory((application as CollectorApplication).session, occurrenceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SurveyScreen(viewModel, onClose = ::finish)
            }
        }
    }

    companion object { const val OCCURRENCE_ID = "occurrence_id" }
}

data class SurveyScreenState(
    val loading: Boolean = true,
    val survey: SurveyDefinition? = null,
    val answers: Map<String, SurveyAnswer> = emptyMap(),
    val editable: Boolean = false,
    val submitted: Boolean = false,
    val message: String? = null,
)

class SurveyViewModel(
    private val session: StudySessionManager,
    private val occurrenceId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SurveyScreenState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = session.snapshot.first { it.initialized }
            if (ready.configuration == null) {
                mutableState.value = SurveyScreenState(loading = false, message = "unavailable")
                return@launch
            }
            val dispatch = session.openOccurrence(occurrenceId)
            val action = dispatch?.action as? SurveyAction
            val configuration = ready.configuration
            val survey = configuration?.surveys?.firstOrNull { it.id == action?.surveyId }
            if (dispatch == null || survey == null) {
                val occurrence = ready.runtime.metadata?.occurrences?.get(occurrenceId)
                val message = if (occurrence?.state == OccurrenceState.EXPIRED) "expired" else "unavailable"
                mutableState.value = SurveyScreenState(loading = false, message = message)
                return@launch
            }
            val submitted = dispatch.occurrence.state == OccurrenceState.SURVEY_SUBMITTED
            val restored = if (submitted) decodeAnswers(survey) else emptyMap()
            mutableState.value = SurveyScreenState(
                loading = false,
                survey = survey,
                answers = restored,
                editable = !submitted,
                submitted = submitted,
                message = if (submitted) "submitted" else null,
            )
        }
    }

    fun answer(questionId: String, answer: SurveyAnswer?) {
        if (!mutableState.value.editable) return
        mutableState.update { current ->
            current.copy(answers = if (answer == null) current.answers - questionId else current.answers + (questionId to answer))
        }
    }

    fun submit() {
        val current = mutableState.value
        if (!current.editable || current.survey == null) return
        mutableState.update { it.copy(editable = false, message = null) }
        viewModelScope.launch {
            val result = session.submitSurvey(occurrenceId, current.answers)
            val committed = if (result == SurveySubmissionResult.ALREADY_SUBMITTED) {
                decodeAnswers(current.survey)
            } else {
                emptyMap()
            }
            mutableState.update { submissionState(it, result, committed) }
        }
    }

    private suspend fun decodeAnswers(survey: SurveyDefinition): Map<String, SurveyAnswer> {
        val encoded = session.surveySubmissionEvent(occurrenceId)?.fields?.get("answers_json") ?: return emptyMap()
        val root = JSONObject(encoded)
        return survey.questions.mapNotNull { question ->
            if (!root.has(question.id)) return@mapNotNull null
            question.id to when (question) {
                is ShortTextQuestion -> SurveyAnswer.Text(root.getString(question.id))
                is ScaleQuestion -> SurveyAnswer.Integer(root.getInt(question.id))
                is SingleChoiceQuestion,
                is MultipleChoiceQuestion -> SurveyAnswer.Choices(root.getJSONArray(question.id).strings())
            }
        }.toMap()
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    class Factory(private val session: StudySessionManager, private val occurrenceId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SurveyViewModel(session, occurrenceId) as T
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
        topBar = { TopAppBar(title = { Text(survey?.title?.resolve(language) ?: stringResource(R.string.survey_title)) }) },
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
                    progress = { answeredFraction(survey, state.answers) },
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
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.survey_confirm_title)) },
        text = { Text(stringResource(R.string.survey_confirm_body)) },
        confirmButton = {
            Button(onClick = { confirm = false; viewModel.submit() }) { Text(stringResource(R.string.survey_submit)) }
        },
        dismissButton = { OutlinedButton(onClick = { confirm = false }) { Text(stringResource(R.string.survey_cancel)) } },
    )
}

internal fun submissionState(
    current: SurveyScreenState,
    result: SurveySubmissionResult,
    committedAnswers: Map<String, SurveyAnswer> = emptyMap(),
): SurveyScreenState = when (result) {
    SurveySubmissionResult.ACCEPTED -> current.copy(editable = false, submitted = true, message = "submitted")
    SurveySubmissionResult.ALREADY_SUBMITTED -> current.copy(
        answers = committedAnswers,
        editable = false,
        submitted = true,
        message = "submitted",
    )
    SurveySubmissionResult.EXPIRED -> current.copy(editable = false, message = "expired")
    SurveySubmissionResult.INVALID -> current.copy(editable = true, message = "invalid")
}

@Composable
private fun SurveyQuestionField(
    index: Int,
    question: SurveyQuestion,
    language: String,
    answer: SurveyAnswer?,
    readOnly: Boolean,
    onAnswer: (SurveyAnswer?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${index + 1}. ${question.prompt.resolve(language)}", style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (question.required) R.string.survey_required else R.string.survey_optional),
            style = MaterialTheme.typography.labelMedium,
        )
        when (question) {
            is ShortTextQuestion -> OutlinedTextField(
                value = (answer as? SurveyAnswer.Text)?.value.orEmpty(),
                onValueChange = { if (it.length <= question.maximumLength) onAnswer(SurveyAnswer.Text(it)) },
                enabled = !readOnly,
                supportingText = { Text("${(answer as? SurveyAnswer.Text)?.value?.length ?: 0}/${question.maximumLength}") },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = question.prompt.resolve(language)
                },
            )
            is ScaleQuestion -> {
                val value = (answer as? SurveyAnswer.Integer)?.value ?: question.minimum
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onAnswer(SurveyAnswer.Integer(it.toInt())) },
                    valueRange = question.minimum.toFloat()..question.maximum.toFloat(),
                    steps = (question.maximum - question.minimum - 1).coerceAtLeast(0),
                    enabled = !readOnly,
                    modifier = Modifier.semantics {
                        contentDescription = "${question.prompt.resolve(language)}: $value"
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${question.minimum} · ${question.minimumLabel.resolve(language)}")
                    Text("$value")
                    Text("${question.maximum} · ${question.maximumLabel.resolve(language)}")
                }
            }
            is SingleChoiceQuestion -> Column(Modifier.selectableGroup()) {
                question.options.forEach { option ->
                    val selected = (answer as? SurveyAnswer.Choices)?.optionIds?.singleOrNull() == option.id
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            enabled = !readOnly,
                            role = Role.RadioButton,
                            onClick = { onAnswer(SurveyAnswer.Choices(listOf(option.id))) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = !readOnly,
                        )
                        Text(option.label.resolve(language))
                    }
                }
            }
            is MultipleChoiceQuestion -> question.options.forEach { option ->
                val selected = option.id in ((answer as? SurveyAnswer.Choices)?.optionIds ?: emptyList())
                val update = {
                    val current = (answer as? SurveyAnswer.Choices)?.optionIds.orEmpty()
                    val next = if (selected) current - option.id else current + option.id
                    if (next.size <= question.maximumSelections) onAnswer(SurveyAnswer.Choices(next))
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = selected,
                        enabled = !readOnly,
                        role = Role.Checkbox,
                        onValueChange = { update() },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        enabled = !readOnly,
                    )
                    Text(option.label.resolve(language))
                }
            }
        }
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

private fun answeredFraction(survey: SurveyDefinition, answers: Map<String, SurveyAnswer>): Float =
    answers.size.toFloat() / survey.questions.size

private fun validSurveyAnswers(survey: SurveyDefinition, answers: Map<String, SurveyAnswer>): Boolean =
    survey.questions.all { question ->
        val answer = answers[question.id] ?: return@all !question.required
        when (question) {
            is ShortTextQuestion -> answer is SurveyAnswer.Text &&
                answer.value.length <= question.maximumLength && (!question.required || answer.value.isNotBlank())
            is ScaleQuestion -> answer is SurveyAnswer.Integer && answer.value in question.minimum..question.maximum
            is SingleChoiceQuestion -> answer is SurveyAnswer.Choices && answer.optionIds.size == 1 &&
                answer.optionIds.single() in question.options.map { it.id }
            is MultipleChoiceQuestion -> answer is SurveyAnswer.Choices &&
                answer.optionIds.distinct().size == answer.optionIds.size &&
                answer.optionIds.size in question.minimumSelections..question.maximumSelections &&
                answer.optionIds.all { id -> question.options.any { it.id == id } }
        }
    }
