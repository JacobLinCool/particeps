package cool.jacoblin.particeps

import android.app.Application
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
import cool.jacoblin.particeps.core.application.ExperimentRuntimeFactory
import cool.jacoblin.particeps.core.application.StudyAccessPolicy
import cool.jacoblin.particeps.core.application.StudyExporter
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudyStoreFactory
import cool.jacoblin.particeps.core.application.StudyVerifier
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.export.BundleKind
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.export.ExportSnapshot
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.storage.EncryptedActiveStudyStore
import cool.jacoblin.particeps.core.storage.EncryptedExperimentStore
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import cool.jacoblin.particeps.platform.AndroidStudyCollectionHost
import cool.jacoblin.particeps.platform.AndroidStudyWorkScheduler
import cool.jacoblin.particeps.platform.FileUploadOutbox
import cool.jacoblin.particeps.platform.InterventionDeliveryCoordinator
import cool.jacoblin.particeps.platform.JoinArtifactDownloader
import cool.jacoblin.particeps.platform.OkHttpStudyUploader
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CollectorApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var session: StudySessionManager
        private set
    lateinit var accessManager: AccessManager
        private set
    lateinit var joinArtifactDownloader: JoinArtifactDownloader
        private set

    override fun onCreate() {
        super.onCreate()
        accessManager = AccessManager(this, ResearchInputMethodService::class.java.name)
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
        val workScheduler = AndroidStudyWorkScheduler(this)
        val clientVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val producer = BundleProducer(
            platform = StudyConfiguration.ANDROID_PLATFORM,
            clientVersion = clientVersion.toString(),
        )
        session = StudySessionManager(
            activeStudyStore = EncryptedActiveStudyStore(this),
            verifier = StudyVerifier { bytes ->
                configurationVerifier().verify(bytes).also { ResearchExport.validate(it.configuration) }
            },
            storeFactory = StudyStoreFactory { experimentId, maximumLocalBytes ->
                EncryptedExperimentStore(this, experimentId, maximumLocalBytes)
            },
            runtimeFactory = ExperimentRuntimeFactory { configuration, store, availableAccess ->
                ExperimentRuntime(
                    configuration,
                    store,
                    registry,
                    AndroidResearchClocks(this, configuration.experimentId),
                    applicationScope,
                    availableAccess,
                )
            },
            collectorRegistry = registry,
            accessGateway = accessManager,
            collectionHost = AndroidStudyCollectionHost(this),
            workScheduler = workScheduler,
            exporter = StudyExporter { verified, metadata, events, destination ->
                ResearchExport.encrypt(
                    // Starts at the retained floor, not at 1: anything below it was delivered to
                    // the study's endpoint and reclaimed, and the bundle says so in
                    // first_sequence_number rather than appearing to be a complete history.
                    ExportSnapshot(
                        verifiedConfiguration = verified,
                        metadata = metadata,
                        producer = producer,
                        bundleKind = BundleKind.MANUAL_EXPORT,
                        exportedAtUtcMillis = Instant.now().toEpochMilli(),
                        fromSequence = metadata.retainedFromSequence,
                    ),
                    events,
                    destination,
                )
            },
            uploader = OkHttpStudyUploader(
                outbox = FileUploadOutbox(noBackupFilesDir.resolve("upload-outbox")),
                producer = producer,
            ),
            accessPolicy = StudyAccessPolicy(),
            scope = applicationScope,
        )
        applicationScope.launch {
            session.initialize()
            // The delivery chain is one-time work, so it has no platform-side repetition to fall
            // back on. Re-establishing it here covers a link lost to a crash or a force stop.
            session.snapshot.value.configuration?.let(workScheduler::reschedulePendingWork)
            InterventionDeliveryCoordinator.recoverStalePosting {
                session.rescheduleInterventions(recoverStalePosting = true)
            }
        }
    }

    private fun configurationVerifier(): ConfigurationVerifier = ConfigurationVerifier(
        trustedSigningKeys = TRUSTED_SIGNING_KEYS,
        clientVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode,
    )

    private companion object {
        /**
         * Signers this build pins, as key ID to unpadded-base64url raw Ed25519 public key.
         *
         * Empty on purpose: a study configuration carries its own signing key, so this build runs
         * any correctly signed study and tells the participant the publisher is unverified. An
         * institution that wants one build to accept only its own studies adds its key here and
         * ships that build; every other signer is then refused outright.
         */
        val TRUSTED_SIGNING_KEYS = emptyMap<String, String>()
    }
}
