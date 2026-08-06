package cool.linc.particeps

import android.app.Application
import cool.linc.particeps.collector.accelerometer.AccelerometerCollectorPlugin
import cool.linc.particeps.collector.ambientlight.AmbientLightCollectorPlugin
import cool.linc.particeps.collector.applifecycle.AppLifecycleCollectorPlugin
import cool.linc.particeps.collector.batterystate.BatteryStateCollectorPlugin
import cool.linc.particeps.collector.gyroscope.GyroscopeCollectorPlugin
import cool.linc.particeps.collector.keyboardime.KeyboardTouchCollectorPlugin
import cool.linc.particeps.collector.keyboardime.ResearchInputMethodService
import cool.linc.particeps.collector.location.LocationCollectorPlugin
import cool.linc.particeps.collector.networkstate.NetworkStateCollectorPlugin
import cool.linc.particeps.collector.networkusage.NetworkUsageCollectorPlugin
import cool.linc.particeps.collector.proximity.ProximityCollectorPlugin
import cool.linc.particeps.collector.temporalcontext.TemporalContextCollectorPlugin
import cool.linc.particeps.collector.usageevents.UsageEventsCollectorPlugin
import cool.linc.particeps.core.access.AccessManager
import cool.linc.particeps.core.application.ExperimentRuntimeFactory
import cool.linc.particeps.core.application.StudyAccessPolicy
import cool.linc.particeps.core.application.StudyExporter
import cool.linc.particeps.core.application.StudySessionManager
import cool.linc.particeps.core.application.StudyStoreFactory
import cool.linc.particeps.core.application.StudyVerifier
import cool.linc.particeps.core.collector.CollectorRegistry
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.export.BundleKind
import cool.linc.particeps.core.export.BundleProducer
import cool.linc.particeps.core.export.ExportSnapshot
import cool.linc.particeps.core.export.ResearchExport
import cool.linc.particeps.core.protocol.ConfigurationVerifier
import cool.linc.particeps.core.runtime.ExperimentRuntime
import cool.linc.particeps.core.storage.EncryptedActiveStudyStore
import cool.linc.particeps.core.storage.EncryptedExperimentStore
import cool.linc.particeps.platform.AndroidResearchClocks
import cool.linc.particeps.platform.AndroidStudyCollectionHost
import cool.linc.particeps.platform.AndroidStudyWorkScheduler
import cool.linc.particeps.platform.FileUploadOutbox
import cool.linc.particeps.platform.InterventionDeliveryCoordinator
import cool.linc.particeps.platform.JoinArtifactDownloader
import cool.linc.particeps.platform.OkHttpStudyUploader
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
