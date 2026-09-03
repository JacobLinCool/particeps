package cool.jacoblin.particeps

import android.app.Application
import android.os.SystemClock
import android.util.Log
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.collector.accelerometer.AccelerometerCollectorPlugin
import cool.jacoblin.particeps.collector.ambientlight.AmbientLightCollectorPlugin
import cool.jacoblin.particeps.collector.applifecycle.AppLifecycleCollectorPlugin
import cool.jacoblin.particeps.collector.batterystate.BatteryStateCollectorPlugin
import cool.jacoblin.particeps.collector.gyroscope.GyroscopeCollectorPlugin
import cool.jacoblin.particeps.collector.keyboardime.KeyboardTouchCollectorPlugin
import cool.jacoblin.particeps.collector.keyboardime.ResearchInputMethodService
import cool.jacoblin.particeps.collector.location.LocationCollectorPlugin
import cool.jacoblin.particeps.collector.networkstate.NetworkStateCollectorPlugin
import cool.jacoblin.particeps.collector.networkusage.NetworkUsageCollectorPlugin
import cool.jacoblin.particeps.collector.proximity.ProximityCollectorPlugin
import cool.jacoblin.particeps.collector.temporalcontext.TemporalContextCollectorPlugin
import cool.jacoblin.particeps.collector.usageevents.UsageEventsCollectorPlugin
import cool.jacoblin.particeps.core.access.AccessManager
import cool.jacoblin.particeps.core.application.AcceptedStudyVerifier
import cool.jacoblin.particeps.core.application.EventDrivenRuntimeAssemblyFactory
import cool.jacoblin.particeps.core.application.PlatformResourceActuatorFactory
import cool.jacoblin.particeps.core.application.StudyAccessPolicy
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CollectorApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var session: StudySessionManager
        private set
    lateinit var accessManager: AccessManager
        private set
    lateinit var joinArtifactDownloader: JoinArtifactDownloader
        private set
    internal lateinit var actionOutboxNotifier: AndroidActionOutboxNotifier
        private set
    internal lateinit var uploadPlatform: AndroidStudyUploadPlatform
        private set

    @Volatile
    internal var currentTimerAdapter: AndroidTimerWakeupAdapter? = null
        private set

    override fun onCreate() {
        super.onCreate()
        ParticepsNotificationChannels.ensureCreated(this)
        accessManager = AccessManager(
            this,
            ResearchInputMethodService::class.java.name,
            ParticepsNotificationChannels.idsByFeature,
        )
        joinArtifactDownloader = JoinArtifactDownloader(noBackupFilesDir.resolve("join-import"))
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
                NetworkUsageCollectorPlugin(this),
                UsageEventsCollectorPlugin(this),
                LocationCollectorPlugin(this),
                KeyboardTouchCollectorPlugin(),
            ),
        )
        val collectorForegroundService = AndroidCollectorForegroundServiceDecorator(this)
        actionOutboxNotifier = AndroidActionOutboxNotifier(this)
        uploadPlatform = AndroidStudyUploadPlatform(
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
            currentTimerAdapter = timerAdapter
            assembly
        }
        session = StudySessionManager(
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
        applicationScope.launch { initializeSession() }
    }

    private suspend fun initializeSession() {
        // Stage names and elapsed times only — never study data. Initialization runs behind a bare
        // starting screen, and logcat is the sole way to place a reported startup stall.
        val startedAtMillis = SystemClock.elapsedRealtime()
        val stageLog = applicationScope.launch {
            session.snapshot
                .map { it.startupStage }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { stage ->
                    Log.i(TAG, "Startup stage $stage at ${SystemClock.elapsedRealtime() - startedAtMillis}ms")
                }
        }
        try {
            session.initialize()
        } finally {
            stageLog.cancel()
            Log.i(TAG, "Session initialized in ${SystemClock.elapsedRealtime() - startedAtMillis}ms")
        }
        val snapshot = session.snapshot.value
        if (snapshot.recoveryStatus != cool.jacoblin.particeps.core.application.StudyRecoveryStatus.ACTION_REQUIRED) {
            currentTimerAdapter?.reconcile(session)
        }
        ensureDailyStatusWork(this)
        applicationScope.launch {
            session.snapshot
                .map { it.runtime.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (state == cool.jacoblin.particeps.core.model.ExperimentState.RUNNING) {
                        currentTimerAdapter?.reconcile(session)
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
