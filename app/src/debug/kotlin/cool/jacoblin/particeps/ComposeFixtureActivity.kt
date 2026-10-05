package cool.jacoblin.particeps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Component-test host whose fixture survives the same Activity recreation as participant UI. */
class ComposeFixtureActivity : ComponentActivity() {
    private val fixture by viewModels<ComposeFixtureContent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { fixture.content?.invoke() }
    }

    internal fun setFixtureContent(content: @Composable () -> Unit) {
        check(fixture.content == null) { "A fixture must be installed only once per test" }
        fixture.content = content
    }

    internal fun clearFixtureContent() {
        fixture.content = null
    }
}

/** Callers must capture only fixture models/actions, not an Activity or a Compose test rule. */
internal class ComposeFixtureContent : ViewModel() {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)

    override fun onCleared() {
        content = null
    }
}
