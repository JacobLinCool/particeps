package cool.jacoblin.particeps

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.work.Configuration
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.collector.accelerometer.AccelerometerCollectorPlugin
import cool.jacoblin.particeps.collector.ambientlight.AmbientLightCollectorPlugin
import cool.jacoblin.particeps.collector.applifecycle.AppLifecycleCollectorPlugin
import cool.jacoblin.particeps.collector.batterystate.BatteryStateCollectorPlugin
import cool.jacoblin.particeps.collector.gyroscope.GyroscopeCollectorPlugin
import cool.jacoblin.particeps.collector.keyboardime.KeyboardTouchCollectorPlugin
import cool.jacoblin.particeps.collector.keyboardime.ResearchInputMethodService
import cool.jacoblin.particeps.collector.location.LocationCollectorPlugin
import cool.jacoblin.particeps.collector.networkthroughput.NetworkThroughputCollectorPlugin
import cool.jacoblin.particeps.collector.networkstate.NetworkStateCollectorPlugin
import cool.jacoblin.particeps.collector.screenstate.ScreenStateCollectorPlugin
import cool.jacoblin.particeps.collector.networkusage.NetworkUsageCollectorPlugin
import cool.jacoblin.particeps.collector.proximity.ProximityCollectorPlugin
import cool.jacoblin.particeps.collector.temporalcontext.TemporalContextCollectorPlugin
import cool.jacoblin.particeps.collector.usageevents.UsageEventsCollectorPlugin
import cool.jacoblin.particeps.collector.vpnstate.VpnStateCollectorPlugin
import cool.jacoblin.particeps.core.access.AccessManager
import cool.jacoblin.particeps.core.application.AcceptedStudyVerifier
import cool.jacoblin.particeps.core.application.EventDrivenRuntimeAssemblyFactory
import cool.jacoblin.particeps.core.application.PlatformResourceActuatorFactory
import cool.jacoblin.particeps.core.application.StudyAccessPolicy
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudyRuntimeAssemblyFactory
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudyStoreFactory
import cool.jacoblin.particeps.core.application.StudyVerifier
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.protocol.ConfigurationVerificationPurpose
import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.storage.EncryptedActiveStudyStore
import cool.jacoblin.particeps.core.storage.EncryptedExperimentStore
import cool.jacoblin.particeps.core.storage.EncryptedStudyResetStore
import cool.jacoblin.particeps.core.storage.EncryptedStudyStorageResetter
import cool.jacoblin.particeps.platform.AndroidActionOutboxNotifier
import cool.jacoblin.particeps.platform.AndroidCollectorForegroundServiceDecorator
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import cool.jacoblin.particeps.platform.AndroidStudyUploadPlatform
import cool.jacoblin.particeps.platform.AndroidTimerWakeupAdapter
import cool.jacoblin.particeps.platform.FileUploadOutbox
import cool.jacoblin.particeps.platform.JoinArtifactDownloader
import cool.jacoblin.particeps.platform.OkHttpStudyUploader
import cool.jacoblin.particeps.platform.ensureDailyStatusWork
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CollectorApplication : Application(), Configuration.Provider {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // WorkManager opens its database on first use, outside the process-bind startup path.
    // Runtime timer/action/upload work remains responsible for requesting durable scheduling.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    private val startup = ApplicationStartup(applicationScope)
    internal val startupState: StateFlow<ApplicationStartupState> = startup.state

    internal suspend fun awaitReady(): ApplicationGraph = startup.awaitReady()

    override fun onCreate() {
        val startedAt = SystemClock.elapsedRealtime()
        val startedCpu = SystemClock.currentThreadTimeMillis()
        super.onCreate()
        // A redelivered foreground service must be able to post its neutral notification before
        // waiting for the graph. All other dependency construction stays outside process binding.
        ParticepsNotificationChannels.ensureCreated(this)
        startup.start { reportStage ->
            try {
                val graph = buildGraph()
                initializeSession(graph, reportStage)
                graph
            } catch (failure: Throwable) {
                Log.e(TAG, "Application initialization did not complete")
                throw failure
            }
        }
        Log.i(
            TAG,
            "Application onCreate returned in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                "with ${SystemClock.currentThreadTimeMillis() - startedCpu}ms thread CPU",
        )
    }

    private fun buildGraph(): ApplicationGraph {
        val startedAt = SystemClock.elapsedRealtime()
        val startedCpu = SystemClock.currentThreadTimeMillis()
        val currentTimerAdapter = AtomicReference<AndroidTimerWakeupAdapter?>(null)
        val accessManager = AccessManager(
            this,
            ResearchInputMethodService::class.java.name,
            ParticepsNotificationChannels.idsByFeature,
        )
        val joinArtifactDownloader = JoinArtifactDownloader(noBackupFilesDir.resolve("join-import"))
        val registry = CollectorRegistry(
            listOf(
                AppLifecycleCollectorPlugin(this),
                AccelerometerCollectorPlugin(this),
                BatteryStateCollectorPlugin(this),
                TemporalContextCollectorPlugin(this),
                GyroscopeCollectorPlugin(this),
                AmbientLightCollectorPlugin(this),
                ProximityCollectorPlugin(this),
                NetworkStateCollectorPlugin(this),
                VpnStateCollectorPlugin(this),
                NetworkThroughputCollectorPlugin(),
                ScreenStateCollectorPlugin(this),
                NetworkUsageCollectorPlugin(this),
                UsageEventsCollectorPlugin(this),
                LocationCollectorPlugin(this),
                KeyboardTouchCollectorPlugin(),
            ),
        )
        val collectorForegroundService = AndroidCollectorForegroundServiceDecorator(this)
        val actionOutboxNotifier = AndroidActionOutboxNotifier(this)
        val uploadPlatform = AndroidStudyUploadPlatform(
            context = this,
            uploader = OkHttpStudyUploader(
                outbox = FileUploadOutbox(noBackupFilesDir.resolve("engine-commit-upload-outbox")),
            ),
        )
        val clientVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val producer = BundleProducer(
            platform = StudyConfiguration.ANDROID_PLATFORM,
            clientVersion = clientVersion.toString(),
        )
        val runtimeFactory = StudyRuntimeAssemblyFactory { configuration, store ->
            val clocks = AndroidResearchClocks(this, configuration.configuration.experimentId)
            val timerAdapter = AndroidTimerWakeupAdapter(this, clocks)
            val platformActuators = PlatformResourceActuatorFactory { key, signed ->
                val shaping = signed.trafficShaping as? TrafficShapingConfiguration.Enabled
                    ?: return@PlatformResourceActuatorFactory null
                if (key != ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID)) {
                    return@PlatformResourceActuatorFactory null
                }
                TrafficShapingActuator.createAndroid(
                    context = this,
                    targetPackages = shaping.targetPackages,
                    allApps = shaping.allApps,
                    notificationFactory = {
                        CollectionService.trafficShapingForegroundNotification(it, signed.title)
                    },
                )
            }
            val assembly = EventDrivenRuntimeAssemblyFactory(
                collectorRegistry = registry,
                clocks = clocks,
                scope = applicationScope,
                platformActuators = platformActuators,
                collectorActuatorDecorator = collectorForegroundService,
                timerWakeups = timerAdapter,
                actionNotifier = actionOutboxNotifier,
            ).create(configuration, store)
            timerAdapter.bindRuntime(assembly.runtime)
            currentTimerAdapter.set(timerAdapter)
            assembly
        }
        val session = StudySessionManager(
            activeStudyStore = EncryptedActiveStudyStore(this),
            verifier = StudyVerifier { bytes ->
                configurationVerifier().verify(bytes).also { ResearchExport.validate(it.configuration) }
            },
            acceptedStudyVerifier = AcceptedStudyVerifier { bytes ->
                configurationVerifier().verify(
                    bytes,
                    ConfigurationVerificationPurpose.ACCEPTED_ACTIVE_STUDY_RECOVERY,
                ).also { ResearchExport.validate(it.configuration) }
            },
            storeFactory = StudyStoreFactory { experimentId, maximumLocalBytes ->
                EncryptedExperimentStore(this, experimentId, maximumLocalBytes)
            },
            runtimeFactory = runtimeFactory,
            collectorRegistry = registry,
            accessGateway = accessManager,
            resetStore = EncryptedStudyResetStore(this),
            storageResetter = EncryptedStudyStorageResetter(this),
            recoveryReporter = AndroidRecoveryReporter(this),
            accessPolicy = StudyAccessPolicy(),
            uploadCoordinator = uploadPlatform,
            uploadScheduler = uploadPlatform,
            bundleProducer = producer,
            exportedAtUtcMillis = { Instant.now().toEpochMilli() },
            scope = applicationScope,
        )
        Log.i(
            TAG,
            "Application graph constructed in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                "with ${SystemClock.currentThreadTimeMillis() - startedCpu}ms thread CPU",
        )
        return DefaultApplicationGraph(
            session, accessManager, joinArtifactDownloader, actionOutboxNotifier, uploadPlatform,
            currentTimerAdapter,
        )
    }

    private suspend fun initializeSession(
        graph: ApplicationGraph,
        reportStage: (StartupStage?) -> Unit,
    ) {
        val session = graph.session
        // Stage names and elapsed times only — never study data. Initialization runs behind a bare
        // starting screen, and logcat is the sole way to place a reported startup stall.
        val startedAtMillis = SystemClock.elapsedRealtime()
        val stageLog = applicationScope.launch {
            session.snapshot
                .map { it.startupStage }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { stage ->
                    reportStage(stage)
                    Log.i(TAG, "Startup stage $stage at ${SystemClock.elapsedRealtime() - startedAtMillis}ms")
                }
        }
        try {
            session.initialize()
        } finally {
            stageLog.cancel()
            Log.i(TAG, "Session initialization finished in ${SystemClock.elapsedRealtime() - startedAtMillis}ms")
        }
        val snapshot = session.snapshot.value
        if (snapshot.recoveryStatus != cool.jacoblin.particeps.core.application.StudyRecoveryStatus.ACTION_REQUIRED) {
            graph.reconcileTimerWakeups()
        }
        ensureDailyStatusWork(this)
        applicationScope.launch {
            session.snapshot
                .map { it.runtime.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (state == cool.jacoblin.particeps.core.model.ExperimentState.RUNNING) {
                        graph.reconcileTimerWakeups()
                    }
                }
        }
    }

    private fun configurationVerifier(): ConfigurationVerifier = ConfigurationVerifier(
        trustedSigningKeys = TRUSTED_SIGNING_KEYS,
        clientVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode,
    )

    private companion object {
        private const val TAG = "ParticepsStartup"

        /**
         * Signers this build pins, as key ID to unpadded-base64url raw Ed25519 public key. An empty
         * map accepts any correctly signed study while identifying the publisher as unanchored.
         */
        val TRUSTED_SIGNING_KEYS = emptyMap<String, String>()
    }
}

/** All fields are published together by ApplicationStartup after session initialization. */
private class DefaultApplicationGraph(
    override val session: StudySessionManager,
    override val accessManager: AccessManager,
    override val joinArtifactDownloader: JoinArtifactDownloader,
    override val actionOutboxNotifier: AndroidActionOutboxNotifier,
    override val uploadPlatform: AndroidStudyUploadPlatform,
    private val currentTimerAdapter: AtomicReference<AndroidTimerWakeupAdapter?>,
) : ApplicationGraph {
    override suspend fun reconcileTimerWakeups() {
        currentTimerAdapter.get()?.reconcile(session)
    }
}
