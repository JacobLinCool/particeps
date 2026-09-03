package cool.jacoblin.particeps

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingAndroidPrerequisites
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
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
    ) { grants ->
        val result = when {
            Manifest.permission.POST_NOTIFICATIONS in grants ->
                SetupAction.RuntimePermission.NOTIFICATIONS to Manifest.permission.POST_NOTIFICATIONS
            Manifest.permission.ACCESS_FINE_LOCATION in grants ->
                SetupAction.RuntimePermission.FOREGROUND_LOCATION to Manifest.permission.ACCESS_FINE_LOCATION
            else -> null
        }
        result?.let { (action, permission) ->
            val granted = grants.getValue(permission)
            collectorApplication.accessManager.recordRuntimePermissionResult(
                action = action,
                granted = granted,
                canRequestAgain = granted || shouldShowRequestPermissionRationale(permission),
            )
        }
        viewModel.refreshAccess()
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            continueTrafficPrerequisites()
        } else {
            pendingTrafficAction = null
            viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
        }
    }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            executePendingTrafficAction()
        } else {
            pendingTrafficAction = null
            viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
        }
    }

    private var pendingTrafficAction: PendingTrafficAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTrafficAction = savedInstanceState
            ?.getString(STATE_PENDING_TRAFFIC_ACTION)
            ?.let(PendingTrafficAction::valueOf)
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
                    completeAccess = {
                        runAfterTrafficPrerequisites(PendingTrafficAction.COMPLETE_ACCESS)
                    },
                    requestAccess = ::requestAccess,
                    start = { runAfterTrafficPrerequisites(PendingTrafficAction.START) },
                    pause = viewModel::pause,
                    resume = { runAfterTrafficPrerequisites(PendingTrafficAction.RESUME) },
                    complete = viewModel::complete,
                    withdraw = viewModel::withdraw,
                    export = {
                        val id = (state as? StudyUiState.ActiveStudy)?.model?.experimentId ?: "research"
                        exportLauncher.launch("$id-${Instant.now().epochSecond}.partexp")
                    },
                    delete = viewModel::deleteLocalData,
                    retryRecovery = viewModel::retryRecovery,
                    resetAndRestart = viewModel::resetAndRestart,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
        val active = viewModel.state.value as? StudyUiState.ActiveStudy
        if (
            active?.model?.trafficShapingDisclosureRequired == true &&
            active.model.state == ExperimentState.RUNNING &&
            (
                !TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this) ||
                    TrafficShapingAndroidPrerequisites.vpnConsentIntent(this) != null
                )
        ) {
            viewModel.safetyPauseForPlatformAccessLoss()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system input-method picker is a window, not another Activity, so onResume is not a
        // reliable completion signal. Regaining focus is the first authoritative point at which
        // the selected keyboard can be re-inspected.
        if (hasFocus) viewModel.refreshAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingTrafficAction?.let { outState.putString(STATE_PENDING_TRAFFIC_ACTION, it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val encoded = intent.dataString ?: return
                // Prevent an Activity recreation from starting a second download for the same URI.
                intent.data = null
                val link = try {
                    JoinLink.parse(encoded)
                } catch (_: IllegalArgumentException) {
                    viewModel.reportMessage(ParticipantMessage.JOIN_IMPORT_FAILED)
                    return
                }
                lifecycleScope.launch {
                    val ready = collectorApplication.session.snapshot.first { it.initialized }
                    if (ready.study != null || ready.deletionPending) {
                        viewModel.reportMessage(ParticipantMessage.JOIN_IMPORT_FAILED)
                    } else {
                        viewModel.importJoin(link) {
                            collectorApplication.joinArtifactDownloader.download(link)
                        }
                    }
                }
            }
        }
    }

    private fun runAfterTrafficPrerequisites(action: PendingTrafficAction) {
        val active = viewModel.state.value as? StudyUiState.ActiveStudy
        if (active?.model?.trafficShapingDisclosureRequired != true) {
            executeTrafficAction(action)
            return
        }
        pendingTrafficAction = action
        if (
            Build.VERSION.SDK_INT >= 37 &&
            !TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this)
        ) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }
        continueTrafficPrerequisites()
    }

    private fun continueTrafficPrerequisites() {
        val consent = TrafficShapingAndroidPrerequisites.vpnConsentIntent(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            executePendingTrafficAction()
        }
    }

    private fun executePendingTrafficAction() {
        val action = pendingTrafficAction ?: return
        pendingTrafficAction = null
        executeTrafficAction(action)
    }

    private fun executeTrafficAction(action: PendingTrafficAction) = when (action) {
        PendingTrafficAction.COMPLETE_ACCESS -> viewModel.completeAccessSetup()
        PendingTrafficAction.START -> viewModel.start()
        PendingTrafficAction.RESUME -> viewModel.resume()
    }

    /**
     * Null in a release build, which ships no demonstration study, so the dashboard leaves the
     * entry point out entirely rather than showing something that cannot work.
     */
    private val demoAction: (() -> Unit)? = DemoStudy.load?.let { load ->
        { viewModel.importSignedConfiguration { load(resources) } }
    }

    private fun requestAccess(action: SetupAction) {
        when (action) {
            is SetupAction.RuntimePermission -> when (action) {
                SetupAction.RuntimePermission.FOREGROUND_LOCATION -> permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                )
                SetupAction.RuntimePermission.NOTIFICATIONS -> permissionLauncher.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                )
            }
            is SetupAction.SystemSettings -> {
                val settingsIntent = collectorApplication.accessManager.settingsIntent(action)
                if (settingsIntent == null) {
                    viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
                    return
                }
                try {
                    startActivity(settingsIntent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
                    viewModel.refreshAccess()
                } catch (_: SecurityException) {
                    viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
                    viewModel.refreshAccess()
                }
            }
            SetupAction.ShowInputMethodPicker -> collectorApplication.accessManager.showInputMethodPicker()
        }
    }

    private enum class PendingTrafficAction { COMPLETE_ACCESS, START, RESUME }

    private companion object {
        const val STATE_PENDING_TRAFFIC_ACTION = "pending_traffic_action"
    }

}
