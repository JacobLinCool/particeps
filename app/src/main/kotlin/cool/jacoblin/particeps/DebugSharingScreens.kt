package cool.jacoblin.particeps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown once, before anything else, until the participant chooses. Debug information is about the
 * App, not the study, so the choice comes before any study is imported and changes nothing about
 * what a study collects.
 */
@Composable
internal fun DebugSharingChoiceScreen(onChoose: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag(UiTags.DEBUG_SHARING_CHOICE),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.debug_sharing_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.debug_sharing_body))
        Text(stringResource(R.string.debug_sharing_includes_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.debug_sharing_includes))
        Text(stringResource(R.string.debug_sharing_excludes_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.debug_sharing_excludes))
        Text(
            stringResource(R.string.debug_sharing_change_later),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onChoose(true) },
            modifier = Modifier.fillMaxWidth().testTag(UiTags.DEBUG_SHARING_ALLOW),
        ) { Text(stringResource(R.string.debug_sharing_allow)) }
        OutlinedButton(
            onClick = { onChoose(false) },
            modifier = Modifier.fillMaxWidth().testTag(UiTags.DEBUG_SHARING_DECLINE),
        ) { Text(stringResource(R.string.debug_sharing_decline)) }
    }
}

/** A quiet row, below the main controls, where the participant can change the choice at any time. */
@Composable
internal fun DebugSharingRow(shared: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Switch) { onChange(!shared) }
                .testTag(UiTags.DEBUG_SHARING_ROW)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.debug_sharing_row), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.debug_sharing_row_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = shared, onCheckedChange = null)
        }
    }
}
