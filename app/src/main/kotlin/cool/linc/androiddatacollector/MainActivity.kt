package cool.linc.androiddatacollector

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cool.linc.androiddatacollector.platform.InterventionWorker
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.protocol.JoinLink
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationCodec
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val collectorApplication: CollectorApplication
        get() = application as CollectorApplication
    private val viewModel by viewModels<StudyViewModel> {
        StudyViewModel.Factory(collectorApplication.session)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewModel.importSignedConfiguration {
            requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open signed configuration" }
                .use { it.readNBytes(SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES + 1) }
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewModel.export {
            requireNotNull(contentResolver.openOutputStream(uri, "w")) { "Cannot open export destination" }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            CollectorApp(
                state = state,
                actions = StudyUiActions(
                    import = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    demo = demoAction,
                    review = viewModel::reviewStudy,
                    acceptConsent = viewModel::acceptConsent,
                    completeAccess = viewModel::completeAccessSetup,
                    requestAccess = ::requestAccess,
                    start = viewModel::start,
                    pause = viewModel::pause,
                    resume = viewModel::resume,
                    finish = viewModel::finish,
                    withdraw = viewModel::withdraw,
                    export = {
                        val id = (state as? StudyUiState.ActiveStudy)?.configuration?.experimentId ?: "research"
                        exportLauncher.launch("$id-${Instant.now().epochSecond}.adcexp")
                    },
                    delete = viewModel::deleteLocalData,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            InterventionWorker.ACTION_OPEN_OCCURRENCE -> {
                val occurrenceId = intent.getStringExtra(InterventionWorker.KEY_OCCURRENCE_ID) ?: return
                lifecycleScope.launch {
                    val ready = collectorApplication.session.snapshot.first { it.initialized }
                    if (ready.configuration != null) collectorApplication.session.openOccurrence(occurrenceId)
                }
            }
            Intent.ACTION_VIEW -> {
                val encoded = intent.dataString ?: return
                // Prevent an Activity recreation from starting a second download for the same URI.
                intent.data = null
                val link = try {
                    JoinLink.parse(encoded)
                } catch (_: IllegalArgumentException) {
                    viewModel.reportMessage("JOIN_LINK_INVALID")
                    return
                }
                lifecycleScope.launch {
                    val ready = collectorApplication.session.snapshot.first { it.initialized }
                    if (ready.configuration != null || ready.deletionPending) {
                        viewModel.reportMessage("JOIN_ACTIVE_STUDY")
                    } else {
                        viewModel.importJoin(link) {
                            collectorApplication.joinArtifactDownloader.download(link)
                        }
                    }
                }
            }
        }
    }

    /**
     * Null in a release build, which ships no demonstration study, so the dashboard leaves the
     * entry point out entirely rather than showing something that cannot work.
     */
    private val demoAction: (() -> Unit)? = DemoStudy.load?.let { load ->
        { viewModel.importSignedConfiguration { load(resources) } }
    }

    private fun requestAccess(kind: AccessKind) {
        when (kind) {
            AccessKind.FINE_LOCATION -> permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            )
            AccessKind.BACKGROUND_LOCATION -> permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            )
            AccessKind.NOTIFICATIONS -> permissionLauncher.launch(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            )
            AccessKind.RESEARCH_KEYBOARD_SELECTED -> collectorApplication.accessManager.showInputMethodPicker()
            AccessKind.USAGE_ACCESS,
            AccessKind.RESEARCH_KEYBOARD_ENABLED -> collectorApplication.accessManager.settingsIntent(kind)?.let(::startActivity)
                ?: viewModel.reportMessage("ACCESS_SETTINGS_UNAVAILABLE")
            AccessKind.ACCELEROMETER_HARDWARE,
            AccessKind.GYROSCOPE_HARDWARE,
            AccessKind.AMBIENT_LIGHT_HARDWARE,
            AccessKind.PROXIMITY_HARDWARE -> Unit
        }
    }

}
