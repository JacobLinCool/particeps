package cool.linc.androiddatacollector

import android.app.Application
import cool.linc.androiddatacollector.collector.accelerometer.AccelerometerCollectorPlugin
import cool.linc.androiddatacollector.collector.applifecycle.AppLifecycleCollectorPlugin
import cool.linc.androiddatacollector.collector.keyboardime.KeyboardTouchCollectorPlugin
import cool.linc.androiddatacollector.collector.keyboardime.ResearchInputMethodService
import cool.linc.androiddatacollector.collector.location.LocationCollectorPlugin
import cool.linc.androiddatacollector.collector.networkstate.NetworkStateCollectorPlugin
import cool.linc.androiddatacollector.collector.networkusage.NetworkUsageCollectorPlugin
import cool.linc.androiddatacollector.collector.usageevents.UsageEventsCollectorPlugin
import cool.linc.androiddatacollector.core.access.AccessManager
import cool.linc.androiddatacollector.core.application.ExperimentRuntimeFactory
import cool.linc.androiddatacollector.core.application.StudyAccessPolicy
import cool.linc.androiddatacollector.core.application.StudyExporter
import cool.linc.androiddatacollector.core.application.StudySessionManager
import cool.linc.androiddatacollector.core.application.StudyStoreFactory
import cool.linc.androiddatacollector.core.application.StudyVerifier
import cool.linc.androiddatacollector.core.collector.CollectorRegistry
import cool.linc.androiddatacollector.core.export.ExportSnapshot
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.protocol.ConfigurationVerifier
import cool.linc.androiddatacollector.core.runtime.ExperimentRuntime
import cool.linc.androiddatacollector.core.storage.EncryptedActiveStudyStore
import cool.linc.androiddatacollector.core.storage.EncryptedExperimentStore
import cool.linc.androiddatacollector.platform.AndroidResearchClocks
import cool.linc.androiddatacollector.platform.AndroidStudyCollectionHost
import cool.linc.androiddatacollector.platform.AndroidStudyWorkScheduler
import cool.linc.androiddatacollector.platform.OkHttpStudyUploader
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

    override fun onCreate() {
        super.onCreate()
        accessManager = AccessManager(this, ResearchInputMethodService::class.java.name)
        val registry = CollectorRegistry(
            listOf(
                AppLifecycleCollectorPlugin(this),
                AccelerometerCollectorPlugin(this),
                NetworkStateCollectorPlugin(this),
                NetworkUsageCollectorPlugin(this),
                UsageEventsCollectorPlugin(this),
                LocationCollectorPlugin(this),
                KeyboardTouchCollectorPlugin(),
            ),
        )
        val workScheduler = AndroidStudyWorkScheduler(this)
        session = StudySessionManager(
            activeStudyStore = EncryptedActiveStudyStore(this),
            verifier = StudyVerifier { bytes ->
                configurationVerifier().verify(bytes).also { ResearchExport.validate(it.configuration) }
            },
            storeFactory = StudyStoreFactory { configuration ->
                EncryptedExperimentStore(this, configuration.experimentId, configuration.maximumLocalBytes)
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
            exporter = StudyExporter { configuration, metadata, events, destination ->
                ResearchExport.encrypt(
                    // Starts at the retained floor, not at 1: anything below it was delivered to
                    // the study's endpoint and reclaimed, and the bundle says so in
                    // first_sequence_number rather than appearing to be a complete history.
                    ExportSnapshot(
                        configuration,
                        metadata,
                        Instant.now().toEpochMilli(),
                        fromSequence = metadata.retainedFromSequence,
                    ),
                    events,
                    destination,
                )
            },
            uploader = OkHttpStudyUploader(),
            accessPolicy = StudyAccessPolicy(),
            scope = applicationScope,
        )
        applicationScope.launch {
            session.initialize()
            // The delivery chain is one-time work, so it has no platform-side repetition to fall
            // back on. Re-establishing it here covers a link lost to a crash or a force stop.
            session.snapshot.value.configuration?.let(workScheduler::reschedulePendingWork)
            session.rescheduleInterventions()
        }
    }

    private fun configurationVerifier(): ConfigurationVerifier = ConfigurationVerifier(
        trustedSigningKeys = TRUSTED_SIGNING_KEYS,
        appVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt(),
    )

    private companion object {
        /**
         * Signers this build pins, as key ID to Base64 X.509 Ed25519 public key.
         *
         * Empty on purpose: a study configuration carries its own signing key, so this build runs
         * any correctly signed study and tells the participant the publisher is unverified. An
         * institution that wants one build to accept only its own studies adds its key here and
         * ships that build; every other signer is then refused outright.
         */
        val TRUSTED_SIGNING_KEYS = emptyMap<String, String>()
    }
}
