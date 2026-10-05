package cool.jacoblin.particeps

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingAndroidPrerequisites
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.time.Instant
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val collectorApplication: CollectorApplication
        get() = application as CollectorApplication
    private val pendingActions by viewModels<PendingActivityActions>()

    private fun studyViewModel(graph: ApplicationGraph): StudyViewModel =
        ViewModelProvider(this, StudyViewModel.Factory(graph.session))[StudyViewModel::class.java]

    private fun enqueue(type: String, payload: Bundle.() -> Unit = {}) {
        pendingActions.enqueue(Bundle().apply { putString(ACTION_TYPE, type); payload() })
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) enqueue(IMPORT) { putString(ACTION_URI, uri.toString()) }
    }

    private val qrScanLauncher = registerForActivityResult(QrScanContract()) { encoded ->
        if (encoded != null) enqueue(JOIN) { putString(ACTION_JOIN, encoded) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> enqueue(EXPORT) { putString(ACTION_URI, uri?.toString()) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        enqueue(PERMISSION) {
            putBundle(ACTION_GRANTS, Bundle().apply { grants.forEach { (key, value) -> putBoolean(key, value) } })
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> enqueue(LOCAL_NETWORK) { putBoolean(ACTION_GRANTED, granted) } }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> enqueue(VPN_CONSENT) { putBoolean(ACTION_GRANTED, result.resultCode == RESULT_OK) } }

    private var pendingTrafficAction: PendingTrafficAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTrafficAction = savedInstanceState
            ?.getString(STATE_PENDING_TRAFFIC_ACTION)
            ?.let(PendingTrafficAction::valueOf)
        handleIntent(intent)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val graph = try {
                collectorApplication.awaitReady()
            } catch (_: ApplicationStartupException) {
                // The shared startup screen owns this terminal failure; no action is acknowledged.
                return@launch
            }
            val viewModel = studyViewModel(graph)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pendingActions.pending.collect {
                    while (pendingActions.pending.value.isNotEmpty()) {
                        handlePendingAction(pendingActions.removeFirst(), graph, viewModel)
                    }
                }
            }
        }
        setContent {
            val startup = collectorApplication.startupState.collectAsStateWithLifecycle().value
            if (startup !is ApplicationStartupState.Ready) {
                ApplicationStartupScreen(startup)
                return@setContent
            }
            val graph = startup.graph
            val viewModel = studyViewModel(graph)
            val state = viewModel.state.collectAsStateWithLifecycle().value
            CollectorApp(
                state = state,
                actions = StudyUiActions(
                    scan = { qrScanLauncher.launch(Unit) },
                    import = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    demo = DemoStudy.load?.let { load ->
                        { viewModel.importSignedConfiguration { load(resources) } }
                    },
                    review = viewModel::reviewStudy,
                    acceptConsent = viewModel::acceptConsent,
                    completeAccess = {
                        runAfterTrafficPrerequisites(viewModel, PendingTrafficAction.COMPLETE_ACCESS)
                    },
                    requestAccess = { requestAccess(graph, viewModel, it) },
                    start = { runAfterTrafficPrerequisites(viewModel, PendingTrafficAction.START) },
                    pause = viewModel::pause,
                    resume = { runAfterTrafficPrerequisites(viewModel, PendingTrafficAction.RESUME) },
                    complete = viewModel::complete,
                    withdraw = viewModel::withdraw,
                    decline = viewModel::declineStudy,
                    export = {
                        if (state is StudyUiState.ActiveStudy && viewModel.chooseExportDestination()) {
                            val id = state.model.experimentId
                            try {
                                exportLauncher.launch("$id-${Instant.now().epochSecond}.partexp")
                            } catch (_: ActivityNotFoundException) {
                                viewModel.exportDestinationUnavailable()
                            }
                        }
                    },
                    cancelExport = viewModel::cancelExport,
                    delete = viewModel::deleteLocalData,
                    retryRecovery = viewModel::retryRecovery,
                    resetAndRestart = viewModel::resetAndRestart,
                    readLocalStorageSize = viewModel::localStorageSize,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enqueue(RESUME_ACCESS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system input-method picker is a window, not another Activity, so onResume is not a
        // reliable completion signal. Regaining focus is the first authoritative point at which
        // the selected keyboard can be re-inspected.
        if (hasFocus) enqueue(REFRESH_ACCESS)
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
        if (intent.action != Intent.ACTION_VIEW) return
        val encoded = intent.dataString ?: return
        // Save the result before consuming the Intent. The pending data survives recreation even
        // when the Activity waiting for the graph is destroyed before it can import the study.
        enqueue(JOIN) { putString(ACTION_JOIN, encoded) }
        intent.data = null
    }

    private fun handlePendingAction(action: Bundle, graph: ApplicationGraph, viewModel: StudyViewModel) {
        when (action.getString(ACTION_TYPE)) {
            IMPORT -> {
                val uri = android.net.Uri.parse(requireNotNull(action.getString(ACTION_URI)))
                viewModel.importSignedConfiguration {
                    requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open signed configuration" }
                        .use { it.readNBytes(SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES + 1) }
                }
            }
            EXPORT -> {
                val encoded = action.getString(ACTION_URI)
                if (encoded == null) {
                    viewModel.exportDestinationCancelled()
                } else {
                    val uri = android.net.Uri.parse(encoded)
                    val resolver = applicationContext.contentResolver
                    viewModel.export(
                        openDestination = {
                            requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open export destination" }
                        },
                        removeIncomplete = { DocumentsContract.deleteDocument(resolver, uri) },
                    )
                }
            }
            PERMISSION -> {
                val grants = requireNotNull(action.getBundle(ACTION_GRANTS))
                val result = when {
                    grants.containsKey(Manifest.permission.POST_NOTIFICATIONS) ->
                        SetupAction.RuntimePermission.NOTIFICATIONS to Manifest.permission.POST_NOTIFICATIONS
                    grants.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ->
                        SetupAction.RuntimePermission.FOREGROUND_LOCATION to Manifest.permission.ACCESS_FINE_LOCATION
                    else -> null
                }
                result?.let { (kind, permission) ->
                    val granted = grants.getBoolean(permission)
                    graph.accessManager.recordRuntimePermissionResult(
                        action = kind,
                        granted = granted,
                        canRequestAgain = granted || shouldShowRequestPermissionRationale(permission),
                    )
                }
                viewModel.refreshAccess()
            }
            LOCAL_NETWORK -> if (action.getBoolean(ACTION_GRANTED)) {
                continueTrafficPrerequisites(viewModel)
            } else {
                pendingTrafficAction = null
                viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
            }
            VPN_CONSENT -> if (action.getBoolean(ACTION_GRANTED)) {
                executePendingTrafficAction(viewModel)
            } else {
                pendingTrafficAction = null
                viewModel.reportMessage(ParticipantMessage.ACCESS_INSPECTION_FAILED)
            }
            JOIN -> importJoin(graph, viewModel, requireNotNull(action.getString(ACTION_JOIN)))
            RESUME_ACCESS -> {
                viewModel.refreshAccess()
                if (
                    viewModel.collectsWithTrafficShaping() &&
                    (
                        !TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this) ||
                            TrafficShapingAndroidPrerequisites.vpnConsentIntent(this) != null
                        )
                ) {
                    viewModel.safetyPauseForPlatformAccessLoss()
                }
            }
            REFRESH_ACCESS -> viewModel.refreshAccess()
            else -> error("Unknown pending Activity action")
        }
    }

    private fun importJoin(graph: ApplicationGraph, viewModel: StudyViewModel, encoded: String) {
        val link = try {
            JoinLink.parse(encoded)
        } catch (_: IllegalArgumentException) {
            viewModel.reportMessage(ParticipantMessage.JOIN_IMPORT_FAILED)
            return
        }
        val ready = graph.session.snapshot.value
        if (ready.study != null || ready.deletionPending) {
            viewModel.reportMessage(ParticipantMessage.JOIN_IMPORT_FAILED)
        } else {
            viewModel.importJoin(link) { graph.joinArtifactDownloader.download(link) }
        }
    }

    private fun runAfterTrafficPrerequisites(viewModel: StudyViewModel, action: PendingTrafficAction) {
        if (!viewModel.needsTrafficPrerequisites()) {
            executeTrafficAction(viewModel, action)
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
        continueTrafficPrerequisites(viewModel)
    }

    private fun continueTrafficPrerequisites(viewModel: StudyViewModel) {
        val consent = TrafficShapingAndroidPrerequisites.vpnConsentIntent(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            executePendingTrafficAction(viewModel)
        }
    }

    private fun executePendingTrafficAction(viewModel: StudyViewModel) {
        val action = pendingTrafficAction ?: return
        pendingTrafficAction = null
        executeTrafficAction(viewModel, action)
    }

    private fun executeTrafficAction(viewModel: StudyViewModel, action: PendingTrafficAction) = when (action) {
        PendingTrafficAction.COMPLETE_ACCESS -> viewModel.completeAccessSetup()
        PendingTrafficAction.START -> viewModel.start()
        PendingTrafficAction.RESUME -> viewModel.resume()
    }

    private fun requestAccess(graph: ApplicationGraph, viewModel: StudyViewModel, action: SetupAction) {
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
                val settingsIntent = graph.accessManager.settingsIntent(action)
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
            SetupAction.ShowInputMethodPicker -> graph.accessManager.showInputMethodPicker()
        }
    }

    private enum class PendingTrafficAction { COMPLETE_ACCESS, START, RESUME }

    private companion object {
        const val ACTION_TYPE = "type"
        const val ACTION_URI = "uri"
        const val ACTION_JOIN = "join"
        const val ACTION_GRANTS = "grants"
        const val ACTION_GRANTED = "granted"
        const val IMPORT = "import"
        const val EXPORT = "export"
        const val JOIN = "join"
        const val PERMISSION = "permission"
        const val LOCAL_NETWORK = "local_network"
        const val VPN_CONSENT = "vpn_consent"
        const val RESUME_ACCESS = "resume_access"
        const val REFRESH_ACCESS = "refresh_access"
        const val STATE_PENDING_TRAFFIC_ACTION = "pending_traffic_action"
    }

}
