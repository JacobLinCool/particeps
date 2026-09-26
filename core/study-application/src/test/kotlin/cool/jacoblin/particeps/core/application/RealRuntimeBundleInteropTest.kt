package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.crypto.HpkeKeyPair
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.export.BundleKind
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.export.ExportSnapshot
import cool.jacoblin.particeps.core.export.ResearchBundleVerifier
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.export.VerifiedResearchBundle
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyStorageResetter
import cool.jacoblin.particeps.core.protocol.ConfigurationVerificationPurpose
import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import cool.jacoblin.particeps.core.protocol.SignedConfigurationEnvelope
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real runtime output, verified by the Kotlin and Python readers.
 *
 * The production StudySessionManager, ExperimentRuntime, automation reducer and ResearchExport run
 * the five-day pilot configuration (re-signed here with test keys) through a pilot-shaped week on
 * deterministic platform doubles: setup and Start, collector events including usage-events polls
 * and barrier flushes that advance its cursor, the noon and 17:00 window barriers fired by their
 * timers, pause and resume, a survey requested at the 17:00 barrier and answered, process death
 * and recovery in the same boot, a phone clock change while running and another while paused, a
 * reboot while traffic shaping is applied and recovery in the new boot, and the signed-duration
 * deadline stop. A second phone starts inside the gyroscope window and ends with participant
 * Complete from RUNNING. The pilot configuration has no upload endpoint, so its data leaves the
 * phone as manual exports. Each export, and a partial export of the retained range starting at
 * every recovery and every condition-epoch boundary, must verify with ResearchBundleVerifier.
 *
 * With PARTICEPS_REAL_RUNTIME_INTEROP_DIR set, the exports, the test-only researcher private key
 * and expected.json are also written there, and
 * particeps-analysis/tests/test_real_runtime_interop.py materializes them through the sink.
 */
class RealRuntimeBundleInteropTest {
    @Test
    fun pilotShapedRuntimeBundlesVerifyAndAreWrittenForPythonWhenRequested() = runTest(timeout = 2.minutes) {
        val keys = InteropKeys.generate()
        val envelope = keys.signedPilotEnvelope()

        val pilot = InteropPhone(this, envelope, ordinal = 1, local("2026-09-14", "11:50:00"), BOOT_ONE)
        pilot.runPilotWeek()
        val pilotExport = pilot.manualExport()
        val pilotBundle = verify(pilotExport, pilot.configuration, keys.hpke.privateKey)
        pilot.assertPilotWeekShapes()
        assertBundleMatchesStore(pilotBundle, pilot, ExperimentState.COMPLETED)
        pilot.verifyRetainedRangesFromEveryBoundary(keys.hpke.privateKey)

        val completer = InteropPhone(this, envelope, ordinal = 2, local("2026-09-14", "16:50:00"), BOOT_THREE)
        completer.runCompleteFromRunning()
        val completerExport = completer.manualExport()
        val completerBundle = verify(completerExport, completer.configuration, keys.hpke.privateKey)
        completer.assertCompleteFromRunningShapes()
        assertBundleMatchesStore(completerBundle, completer, ExperimentState.COMPLETED)
        completer.verifyRetainedRangesFromEveryBoundary(keys.hpke.privateKey)

        System.getenv(INTEROP_DIRECTORY_ENV)?.takeIf(String::isNotBlank)?.let { directory ->
            writeInterop(
                Path.of(directory),
                keys,
                listOf(
                    InteropBundle("pilot-deadline.partexp", PILOT_SCENARIO, pilotExport, pilotBundle),
                    InteropBundle("complete-from-running.partexp", COMPLETE_SCENARIO, completerExport, completerBundle),
                ),
            )
        }
    }

    private fun assertBundleMatchesStore(bundle: VerifiedResearchBundle, phone: InteropPhone, state: ExperimentState) {
        val commits = phone.store.commits
        assertEquals(1L, bundle.experiment.firstCommitSequence)
        assertEquals(commits.size.toLong(), bundle.experiment.commitCount)
        assertEquals(commits.last().commitSequence, bundle.experiment.lastCommitSequence)
        assertEquals(commits.sumOf { it.events.size.toLong() }, bundle.experiment.eventCount)
        assertEquals(state, bundle.experiment.state)
        assertEquals(phone.participantInstanceId, bundle.experiment.participantInstanceId)
    }

    private fun writeInterop(directory: Path, keys: InteropKeys, bundles: List<InteropBundle>) {
        Files.createDirectories(directory)
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".partexp") }.toList().forEach(Files::delete)
        }
        bundles.forEach { Files.write(directory.resolve(it.file), it.encoded) }
        Files.writeString(directory.resolve(PRIVATE_KEY_FILE), ProtocolBase64Url.encode(keys.hpke.privateKey))
        val manifest = buildString {
            append("{\"format\":\"").append(INTEROP_FORMAT).append('"')
            append(",\"bundles\":[")
            bundles.forEachIndexed { index, bundle ->
                if (index > 0) append(',')
                val experiment = bundle.verified.experiment
                append("{\"byte_count\":").append(bundle.encoded.size)
                append(",\"commit_count\":").append(experiment.commitCount)
                append(",\"event_count\":").append(experiment.eventCount)
                append(",\"file\":\"").append(bundle.file).append('"')
                append(",\"participant_instance_id\":\"").append(experiment.participantInstanceId).append('"')
                append(",\"scenario\":\"").append(bundle.scenario).append('"')
                append(",\"sha256\":\"").append(sha256Hex(bundle.encoded)).append('"')
                append(",\"state\":\"").append(experiment.state.name).append("\"}")
            }
            append("],\"researcher_key_id\":\"").append(RESEARCHER_KEY_ID).append("\"}")
        }
        Files.writeString(directory.resolve(MANIFEST_FILE), manifest)
    }

    private class InteropBundle(
        val file: String,
        val scenario: String,
        val encoded: ByteArray,
        val verified: VerifiedResearchBundle,
    )

    internal companion object {
        const val INTEROP_DIRECTORY_ENV = "PARTICEPS_REAL_RUNTIME_INTEROP_DIR"
        const val PILOT_CONFIGURATION_PROPERTY = "particeps.pilot.configuration"
        const val INTEROP_FORMAT = "particeps-real-runtime-interop-v1"
        const val MANIFEST_FILE = "expected.json"
        const val PRIVATE_KEY_FILE = "researcher-private-key.base64url"
        const val RESEARCHER_KEY_ID = "real-runtime-interop-export"
        const val SIGNER_KEY_ID = "real-runtime-interop-signer"
        const val CLIENT_VERSION = 39L
        const val ZONE_ID = "Asia/Taipei"
        const val BOOT_ONE = "46fbdc844f961c90556fa310a23853f5"
        const val BOOT_TWO = "ae341a42ab1a8775c4c19b2a338f3a01"
        const val BOOT_THREE = "0c7d3f9e21b84a55a6e0f1d2c3b4a596"
        const val PILOT_SCENARIO =
            "Setup, Start, window barriers, pauses, a survey at the 17:00 barrier, same-boot process recovery, " +
                "clock changes while running and while paused, a reboot while traffic shaping is applied, " +
                "then the signed-duration deadline stop"
        const val COMPLETE_SCENARIO =
            "Setup, Start inside the gyroscope window, a pause, then participant Complete from RUNNING"
        const val USAGE_EVENTS = "usage_events.v1"
        val SETUP_STATES = ExperimentState.entries.takeWhile { it != ExperimentState.ACTIVATING }.toSet()

        fun local(date: String, time: String): Long =
            LocalDateTime.parse("${date}T$time").atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli()

        fun verify(
            encoded: ByteArray,
            configuration: StudyConfiguration,
            privateKey: ByteArray,
        ): VerifiedResearchBundle {
            val plaintext = ByteArrayOutputStream()
            val header = ResearchExport.decrypt(encoded.inputStream(), plaintext, privateKey, configuration)
            return ResearchBundleVerifier.verify(plaintext.toByteArray(), header, configuration)
        }
    }
}

/** Test-only signing and researcher keys; the pilot configuration's own keys are replaced. */
private class InteropKeys(
    private val signing: java.security.KeyPair,
    val hpke: HpkeKeyPair,
) {
    fun signedPilotEnvelope(): ByteArray {
        val path = Path.of(
            requireNotNull(System.getProperty(RealRuntimeBundleInteropTest.PILOT_CONFIGURATION_PROPERTY)) {
                "The Gradle test task names the published pilot configuration"
            },
        )
        val published = StudyConfigurationCodec.decode(StudyConfigurationCodec.canonicalize(Files.readAllBytes(path)))
        val rawSigningKey = signing.public.encoded.copyOfRange(ED25519_X509_PREFIX_BYTES, ED25519_X509_BYTES)
        val configuration = published.copy(
            signer = SignerIdentity(
                RealRuntimeBundleInteropTest.SIGNER_KEY_ID,
                ProtocolBase64Url.encode(rawSigningKey),
            ),
            export = ExportConfiguration(
                RealRuntimeBundleInteropTest.RESEARCHER_KEY_ID,
                ProtocolBase64Url.encode(hpke.publicKey),
            ),
        )
        val bytes = StudyConfigurationCodec.encode(configuration)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signing.private)
            update(bytes)
            sign()
        }
        return SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope(configuration.signer.keyId, bytes, signature),
        )
    }

    companion object {
        private const val ED25519_X509_PREFIX_BYTES = 12
        private const val ED25519_X509_BYTES = 44

        fun generate() = InteropKeys(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            HpkeCrypto.generateKeyPair(),
        )
    }
}

/**
 * One phone: the durable state that survives a process death or a reboot (store, active-study
 * record, reset marker, entropy, clocks) and the process that runs on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class InteropPhone(
    private val test: TestScope,
    private val envelope: ByteArray,
    ordinal: Int,
    startWallMillis: Long,
    bootSessionId: String,
) {
    val clocks = InteropClocks(startWallMillis, INITIAL_ELAPSED_NANOS, bootSessionId)
    val store = InteropStudyStore()
    private val entropy = InteropEntropy(ordinal)
    private val activeStudyStore = InteropActiveStudyStore()
    private val resetStore = InteropResetStore()
    private val plugins = listOf(
        InteropCollectorPlugin("gyroscope.v1", setOf(AccessKind.GYROSCOPE_HARDWARE)),
        InteropCollectorPlugin("network_state.v1", emptySet()),
        InteropCollectorPlugin("network_throughput.v1", emptySet()),
        InteropCollectorPlugin("screen_state.v1", emptySet()),
        InteropCollectorPlugin("temporal_context.v1", emptySet()),
        InteropCollectorPlugin(RealRuntimeBundleInteropTest.USAGE_EVENTS, setOf(AccessKind.USAGE_ACCESS)),
        InteropCollectorPlugin("vpn_state.v1", emptySet()),
    ).associateBy { it.descriptor.id }
    private var assembly: StudyRuntimeAssembly? = null
    private var verified: VerifiedConfiguration? = null
    private val acceptedTimers = mutableSetOf<Pair<String, ULong>>()
    private lateinit var manager: StudySessionManager

    val configuration: StudyConfiguration get() = checkNotNull(verified).configuration
    val participantInstanceId: String get() = checkNotNull(store.document).participantInstanceId

    init {
        startProcess()
    }

    suspend fun runPilotWeek() {
        enrolAndStart()
        // Day 1 before noon: every collector's first events and a usage-events poll with history.
        clocks.advanceMillis(5_000)
        screen(on = true)
        clocks.advanceMillis(1_000)
        emit("network_state.v1", networkSnapshot())
        clocks.advanceMillis(1_000)
        emit("vpn_state.v1", draft("vpn_state.v1", "VPN_STATUS", "connected" to "true"))
        clocks.advanceMillis(1_000)
        temporalContext("STUDY_STARTED")
        clocks.advanceMillis(10_000)
        emit("network_throughput.v1", throughput(12_345, 2_345))
        usageActivity(local("2026-09-14", "11:50:20"), "ACTIVITY_RESUMED", READER)
        usageActivity(local("2026-09-14", "11:50:50"), "ACTIVITY_PAUSED", READER)
        advanceTo(local("2026-09-14", "11:51:10"))
        poll()
        advanceTo(local("2026-09-14", "11:52:00"))
        command("pause") { manager.pause() }
        advanceTo(local("2026-09-14", "11:54:00"))
        command("resume") { manager.resume() }
        clocks.advanceMillis(3_000)
        screen(on = false)
        clocks.advanceMillis(10_000)
        emit("network_throughput.v1", throughput(22_000, 4_100))
        usageActivity(local("2026-09-14", "11:58:00"), "ACTIVITY_RESUMED", MAPS)
        // The gyroscope window opens at noon: its timer closes the epoch and usage events flush.
        advanceTo(local("2026-09-14", "12:00:02"))
        gyroscope(5)
        clocks.advanceMillis(1_000)
        gyroscope(1)
        usageHistory(local("2026-09-14", "12:00:10"), "SCREEN_NON_INTERACTIVE", null, null)
        advanceTo(local("2026-09-14", "12:01:05"))
        poll()
        advanceTo(local("2026-09-14", "12:05:00"))

        // The process dies while RUNNING; the next process in the same boot recovers to PAUSED.
        processDeath()
        clocks.advanceToWall(local("2026-09-14", "12:06:00"))
        startProcess()
        command("initialize after process death") { manager.initialize(); StudyCommandResult.Success }
        check(manager.snapshot.value.runtime.state == ExperimentState.PAUSED) { "Recovery did not pause the study" }
        clocks.advanceMillis(5_000)
        command("reconcile access") { manager.reconcileAccess(); StudyCommandResult.Success }
        command("resume after recovery") { manager.resume() }
        clocks.advanceMillis(2_000)
        screen(on = true)
        clocks.advanceMillis(2_000)
        gyroscope(3)
        usageActivity(local("2026-09-14", "12:06:30"), "ACTIVITY_RESUMED", MAPS)
        advanceTo(local("2026-09-14", "12:07:10"))
        poll()
        // The phone's clock is corrected forward while the study runs.
        clocks.changeWallClock(90_000)
        command("wall-clock change while running") { manager.onClockDiscontinuity() }
        gyroscope(2)
        advanceTo(local("2026-09-14", "12:10:00"))
        command("pause day 1") { manager.pause() }

        // Day 2: the clock is set back while the study is paused.
        clocks.advanceToWall(local("2026-09-15", "09:00:00"))
        clocks.changeWallClock(-120_000)
        command("wall-clock change while paused") { manager.onClockDiscontinuity() }

        // Day 3: the traffic-shaping window opens at noon and closes at 17:00, where the day-3
        // survey's condition rises in the same barrier.
        clocks.advanceToWall(local("2026-09-16", "11:58:00"))
        command("resume day 3") { manager.resume() }
        clocks.advanceMillis(1_000)
        temporalContext("RECONCILED")
        advanceTo(local("2026-09-16", "12:00:30"))
        gyroscope(4)
        clocks.advanceMillis(5_000)
        emit("network_throughput.v1", throughput(1_000, 300))
        advanceTo(local("2026-09-16", "12:03:00"))
        command("pause day 3 noon") { manager.pause() }
        clocks.advanceToWall(local("2026-09-16", "16:58:00"))
        command("resume day 3 afternoon") { manager.resume() }
        clocks.advanceMillis(1_000)
        gyroscope(2)
        usageActivity(local("2026-09-16", "16:59:00"), "ACTIVITY_PAUSED", MAPS)
        advanceTo(local("2026-09-16", "17:00:30"))
        answerPendingSurveys()
        clocks.advanceMillis(1_000)
        screen(on = false)
        advanceTo(local("2026-09-16", "17:03:00"))
        command("pause day 3 evening") { manager.pause() }

        // Day 4: the phone reboots while the limited profile is applied. Recovery in the new boot
        // closes the epoch the old boot opened.
        clocks.advanceToWall(local("2026-09-17", "12:58:00"))
        command("resume day 4") { manager.resume() }
        clocks.advanceMillis(1_000)
        gyroscope(3)
        usageActivity(local("2026-09-17", "12:58:20"), "ACTIVITY_RESUMED", READER)
        advanceTo(local("2026-09-17", "13:01:00"))
        processDeath()
        clocks.reboot(downtimeMillis = 60_000, elapsedAfterBootNanos = 45_000_000_000L, nextBootSessionId = BOOT_TWO)
        startProcess()
        command("initialize after reboot") { manager.initialize(); StudyCommandResult.Success }
        check(manager.snapshot.value.runtime.state == ExperimentState.PAUSED) { "Recovery did not pause the study" }
        clocks.advanceMillis(5_000)
        command("reconcile access after reboot") { manager.reconcileAccess(); StudyCommandResult.Success }
        command("resume after reboot") { manager.resume() }
        clocks.advanceMillis(2_000)
        screen(on = true)
        gyroscope(2)
        advanceTo(local("2026-09-17", "13:05:00"))
        command("pause day 4") { manager.pause() }

        // Day 6: the study ends at its signed 120-hour deadline, shortly before 11:50 local on
        // 2026-09-19 because the clock changes above moved the wall clock back by a net 30 s.
        clocks.advanceToWall(local("2026-09-19", "11:48:00"))
        command("resume day 6") { manager.resume() }
        clocks.advanceMillis(2_000)
        screen(on = true)
        advanceTo(local("2026-09-19", "11:50:30"))
        check(manager.snapshot.value.runtime.state == ExperimentState.COMPLETED) {
            "The deadline did not complete the study"
        }
        clocks.advanceMillis(30_000)
    }

    suspend fun runCompleteFromRunning() {
        enrolAndStart()
        clocks.advanceMillis(2_000)
        screen(on = true)
        clocks.advanceMillis(1_000)
        gyroscope(3)
        usageActivity(local("2026-09-14", "16:50:10"), "ACTIVITY_RESUMED", READER)
        advanceTo(local("2026-09-14", "16:51:00"))
        poll()
        advanceTo(local("2026-09-14", "16:52:00"))
        command("pause") { manager.pause() }
        advanceTo(local("2026-09-14", "16:53:00"))
        command("resume") { manager.resume() }
        clocks.advanceMillis(1_000)
        gyroscope(2)
        usageActivity(local("2026-09-14", "16:53:30"), "ACTIVITY_PAUSED", READER)
        advanceTo(local("2026-09-14", "16:55:00"))
        command("complete") { manager.complete() }
        check(manager.snapshot.value.runtime.state == ExperimentState.COMPLETED) { "Complete did not end the study" }
        clocks.advanceMillis(30_000)
    }

    fun assertPilotWeekShapes() {
        val commits = store.commits
        assertSetupAndCursorShapes(commits)
        assertEquals(ExperimentState.COMPLETED, commits.last().successorProjection.state)

        val barrierClosures = commits.filter { commit ->
            commit.inputKind == EngineInputKind.TIMER_WAKE && commit.closesEpoch()
        }
        assertTrue("window barriers fired by their timers", barrierClosures.size >= 3)

        val surveyRequest = commits.single { commit -> commit.events.any { it.named("ACTION_REQUESTED") } }
        assertEquals(EngineInputKind.TIMER_WAKE, surveyRequest.inputKind)
        assertTrue("survey requested in the closing commit", surveyRequest.closesEpoch())
        assertTrue("survey answered", commits.any { commit -> commit.events.any { it.named("SURVEY_SUBMITTED") } })

        val recoveries = commits.filter { it.inputKind == EngineInputKind.RECOVERY }
        assertEquals(2, recoveries.size)
        assertEquals(BOOT_ONE, recoveries[0].committedAt.bootSessionId)
        val reboot = recoveries[1]
        assertEquals(BOOT_TWO, reboot.committedAt.bootSessionId)
        val closedEpoch = checkNotNull(commits[commits.indexOf(reboot) - 1].successorProjection.activeConditionEpoch)
        assertEquals(BOOT_ONE, closedEpoch.activatedAt.bootSessionId)
        assertTrue(
            "the rebooted epoch had the limited profile applied",
            commits.any { commit ->
                commit.events.any {
                    it.named("TRAFFIC_SHAPING_PROFILE_APPLIED") &&
                        it.fields["condition_epoch_id"] == closedEpoch.id.value &&
                        it.fields["profile_id"] == "limited-500"
                }
            },
        )
        assertTrue(reboot.closesEpoch())
        assertTrue(reboot.events.none { it.type.sourceId.value == "traffic_shaping.v1" })
        recoveries.forEach { recovery ->
            assertEquals(ExperimentState.PAUSED, recovery.successorProjection.state)
            assertEquals(1, recovery.events.count { it.named("SOURCE_QUALITY_GAP") })
        }

        val clockGaps = commits.filter { commit ->
            commit.events.any { it.named("SOURCE_QUALITY_GAP") && it.fields["reason"] == "WALL_CLOCK_CHANGED" }
        }
        assertEquals(2, clockGaps.size)
        val (runningGap, pausedGap) = clockGaps
        assertEquals(ExperimentState.RUNNING, commits[commits.indexOf(runningGap) - 1].successorProjection.state)
        assertTrue("a running clock change rotates the epoch", runningGap.closesEpoch())
        assertEquals(ExperimentState.PAUSED, pausedGap.successorProjection.state)
        clockGaps.forEach { gap ->
            assertNull(gap.successorProjection.sourceCheckpoints[EventSourceId(USAGE_EVENTS)])
        }

        val deadline = commits.single { commit ->
            commit.events.any { it.named("TIMER_DUE") && it.fields["producer_key"] == "study-deadline" }
        }
        assertEquals(EngineInputKind.TIMER_WAKE, deadline.inputKind)
        assertEquals(ExperimentState.PAUSING, deadline.successorProjection.state)
    }

    fun assertCompleteFromRunningShapes() {
        val commits = store.commits
        assertSetupAndCursorShapes(commits)
        val index = commits.indexOfFirst { commit -> commit.events.any { it.named("STUDY_COMPLETE_REQUESTED") } }
        val request = commits[index]
        assertEquals(EngineInputKind.LIFECYCLE_COMMAND, request.inputKind)
        assertEquals(ExperimentState.RUNNING, commits[index - 1].successorProjection.state)
        assertTrue("Complete flushes usage events", request.flushes(USAGE_EVENTS))
        assertEquals(ExperimentState.COMPLETED, commits.last().successorProjection.state)
    }

    private fun assertSetupAndCursorShapes(commits: List<EngineCommit>) {
        val setup = commits.takeWhile { it.successorProjection.state in RealRuntimeBundleInteropTest.SETUP_STATES }
        assertTrue("setup commits precede Start", setup.size >= 4)
        val flushes = commits.indices.filter { index -> index > 0 && commits[index].flushes(USAGE_EVENTS) }
        assertTrue("usage events flush at a barrier", flushes.isNotEmpty())
        flushes.forEach { index ->
            val before = commits[index - 1].successorProjection.sourceCheckpoints[EventSourceId(USAGE_EVENTS)]?.cursor
            val after = commits[index].successorProjection.sourceCheckpoints[EventSourceId(USAGE_EVENTS)]?.cursor
            assertNotNull(after)
            assertNotEquals(before, after)
        }
        assertTrue(
            "usage events poll with coverage",
            commits.any { commit ->
                commit.sourceObservations.any {
                    it.sourceId.value == USAGE_EVENTS &&
                        it.admissionKind == ObservationAdmissionKind.NORMAL &&
                        it.eventCount > 0
                }
            },
        )
        assertTrue(commits.any { commit -> commit.events.any { it.named("STUDY_PAUSE_REQUESTED") } })
        assertTrue(commits.any { commit -> commit.events.any { it.named("STUDY_RESUMED") } })
    }

    /** A partial bundle from every recovery and every condition-epoch boundary verifies without history. */
    suspend fun verifyRetainedRangesFromEveryBoundary(privateKey: ByteArray) {
        val commits = store.commits
        val starts = commits.filter { commit ->
            commit.commitSequence > 1 && (
                commit.inputKind == EngineInputKind.RECOVERY ||
                    commit.sourceObservations.any { it.admissionKind == ObservationAdmissionKind.BARRIER_FLUSH } ||
                    commit.events.any { it.type.sourceId.value == "study_condition.v1" }
                )
        }.map(EngineCommit::commitSequence)
        assertTrue(starts.size >= 4)
        starts.forEach { start ->
            val verified = runCatching {
                RealRuntimeBundleInteropTest.verify(export(start), configuration, privateKey)
            }.getOrElse { throw AssertionError("Retained range from commit $start: ${it.message}", it) }
            assertEquals(start, verified.experiment.firstCommitSequence)
            assertEquals(commits.last().commitSequence, verified.experiment.lastCommitSequence)
        }
    }

    suspend fun manualExport(): ByteArray {
        val destination = ByteArrayOutputStream()
        val receipt = manager.exportTo(destination)
        settle()
        assertEquals(store.commits.size.toLong(), receipt.commitCount)
        return destination.toByteArray()
    }

    private suspend fun export(fromCommit: Long): ByteArray {
        val destination = ByteArrayOutputStream()
        store.withReadSnapshot { reader ->
            ResearchExport.encrypt(
                ExportSnapshot(
                    verifiedConfiguration = checkNotNull(verified),
                    runtime = reader.runtime,
                    producer = PRODUCER,
                    bundleKind = BundleKind.MANUAL_EXPORT,
                    exportedAtUtcMillis = clocks.wallMillis,
                    fromCommit = fromCommit,
                ),
                reader,
                destination,
            )
        }
        return destination.toByteArray()
    }

    private suspend fun enrolAndStart() {
        command("initialize") { manager.initialize(); StudyCommandResult.Success }
        command("import") { manager.importSignedConfiguration(envelope); StudyCommandResult.Success }
        command("review") { manager.reviewStudy() }
        command("consent") { manager.acceptConsent() }
        command("access setup") { manager.completeAccessSetup() }
        command("start") { manager.start() }
    }

    private suspend fun answerPendingSurveys() {
        val actions = manager.pendingActions()
        assertEquals(1, actions.size)
        actions.forEach { action ->
            clocks.advanceMillis(2_000)
            // ActionOutboxWorker claims the READY action and posts its notification first.
            command("claim action") {
                val claimed = manager.claimAction(action.actionId)
                if (claimed != null) StudyCommandResult.Success else StudyCommandResult.InvalidState
            }
            clocks.advanceMillis(10_000)
            command("open survey") { manager.openSurvey(action.actionId) }
            clocks.advanceMillis(60_000)
            command("submit survey") {
                manager.submitSurvey(
                    action.actionId,
                    mapOf(
                        "off-phone-activities" to ParticipantSurveyAnswer.MultipleChoice(listOf("meals", "outdoor")),
                        "screen-free-time" to ParticipantSurveyAnswer.Choice("30-to-60-min"),
                        "other-devices" to ParticipantSurveyAnswer.MultipleChoice(listOf("computer")),
                        "device-substitution" to ParticipantSurveyAnswer.Choice("used-no-substitution"),
                        "non-screen-substitution" to ParticipantSurveyAnswer.Choice("none"),
                        "activity-context" to ParticipantSurveyAnswer.Text("Walked to the library"),
                    ),
                )
            }
        }
    }

    private fun startProcess() {
        val configurationVerifier = ConfigurationVerifier(
            emptyMap(),
            RealRuntimeBundleInteropTest.CLIENT_VERSION,
            now = { Instant.ofEpochMilli(clocks.wallMillis) },
        )
        val registry = CollectorRegistry(plugins.values.toList())
        val factory = EventDrivenRuntimeAssemblyFactory(
            collectorRegistry = registry,
            clocks = clocks,
            scope = test.backgroundScope,
            platformActuators = PlatformResourceActuatorFactory { key, signed ->
                val shaping = signed.trafficShaping as? TrafficShapingConfiguration.Enabled
                    ?: return@PlatformResourceActuatorFactory null
                val targets = if (shaping.allApps) {
                    "\"all\""
                } else {
                    shaping.targetPackages.joinToString(",", "[", "]") { "\"$it\"" }
                }
                InteropTrafficActuator(
                    key,
                    sha256Hex(targets.toByteArray()),
                    shaping.profiles.associate { it.id to (it.uplinkKbps?.toLong() to it.downlinkKbps?.toLong()) },
                )
            },
            timerWakeups = InteropTimerWakeups,
            actionNotifier = InteropActionNotifier,
            zoneId = { RealRuntimeBundleInteropTest.ZONE_ID },
            entropy = entropy,
        )
        acceptedTimers.clear()
        manager = StudySessionManager(
            activeStudyStore = activeStudyStore,
            verifier = StudyVerifier { bytes ->
                configurationVerifier.verify(bytes).also { ResearchExport.validate(it.configuration) }
            },
            acceptedStudyVerifier = AcceptedStudyVerifier { bytes ->
                configurationVerifier.verify(bytes, ConfigurationVerificationPurpose.ACCEPTED_ACTIVE_STUDY_RECOVERY)
                    .also { ResearchExport.validate(it.configuration) }
            },
            storeFactory = StudyStoreFactory { _, _ -> store },
            runtimeFactory = StudyRuntimeAssemblyFactory { configuration, studyStore ->
                verified = configuration
                factory.create(configuration, studyStore).also { assembly = it }
            },
            collectorRegistry = registry,
            accessGateway = InteropGrantedAccess,
            resetStore = resetStore,
            storageResetter = StudyStorageResetter { store.clear() },
            recoveryReporter = object : RecoveryReporter {
                override fun actionRequired(failure: Throwable?) {
                    throw AssertionError("Recovery requires action", failure)
                }

                override fun clear() = Unit
            },
            accessPolicy = StudyAccessPolicy(),
            bundleProducer = PRODUCER,
            exportedAtUtcMillis = { clocks.wallMillis },
            scope = test.backgroundScope,
        )
    }

    private suspend fun processDeath() {
        manager.shutdownProcess()
        settle()
        assembly = null
    }

    private fun settle() = test.runCurrent()

    private suspend fun command(name: String, block: suspend () -> StudyCommandResult) {
        val result = block()
        settle()
        check(result == StudyCommandResult.Success) { "$name returned $result" }
    }

    /** Fires every durable timer due by [target], in due order, the way WorkManager delivers them. */
    private suspend fun advanceTo(target: Long) {
        while (true) {
            if (assembly?.runtime?.snapshot?.value?.state != ExperimentState.RUNNING) break
            val (timer, due) = manager.pendingTimers()
                .filter { (it.id to it.generation) !in acceptedTimers }
                .mapNotNull { timer -> dueWallMillis(timer)?.let { timer to it } }
                .filter { (_, due) -> due <= target }
                .minWithOrNull(
                    compareBy<Pair<DurableTimer, Long>>({ it.second }, { it.first.automationId }, { it.first.id }),
                )
                ?: break
            if (due > clocks.wallMillis) clocks.advanceToWall(due)
            fire(timer)
        }
        if (target > clocks.wallMillis) clocks.advanceToWall(target)
    }

    private fun dueWallMillis(timer: DurableTimer): Long? {
        val runtime = assembly?.runtime?.snapshot?.value ?: return null
        return when (val target = timer.target) {
            is TimerTarget.CalendarUtc -> target.utcMillis
            is TimerTarget.SameBootMonotonic -> if (target.bootSessionId != clocks.bootSessionId) {
                null
            } else {
                val remaining = target.elapsedRealtimeNanos - clocks.elapsedNanos
                clocks.wallMillis + Math.floorDiv(remaining + NANOS_ROUNDING, NANOS_PER_MILLI)
            }
            is TimerTarget.ActiveElapsed -> {
                val anchor = runtime.clockAnchorWallTimeUtcMillis ?: return null
                val activeNow = runtime.activeRunningElapsedNanos + (clocks.wallMillis - anchor) * NANOS_PER_MILLI
                clocks.wallMillis + Math.floorDiv(target.elapsedNanos - activeNow + NANOS_ROUNDING, NANOS_PER_MILLI)
            }
        }
    }

    private suspend fun fire(timer: DurableTimer) {
        repeat(MAXIMUM_TIMER_ATTEMPTS) {
            val result = manager.onTimerDue(timer.id, timer.generation)
            settle()
            val stillPending = manager.pendingTimers().any { it.id == timer.id && it.generation == timer.generation }
            if (!stillPending) return
            if (result == StudyCommandResult.Success) {
                acceptedTimers += timer.id to timer.generation
                return
            }
            clocks.advanceMillis(1)
        }
        error("Timer ${timer.automationId}/${timer.id} was never accepted")
    }

    private fun draft(
        source: String,
        type: String,
        vararg fields: Pair<String, String>,
        observed: ResearchTime = clocks.now(),
    ) = EventDraft(EventTypeKey(EventSourceId(source), 1, type), observed, mapOf(*fields))

    private suspend fun screen(on: Boolean) = emit(
        "screen_state.v1",
        draft(
            "screen_state.v1",
            "SCREEN_STATE",
            "display_state" to if (on) "ON" else "OFF",
            "interactive" to on.toString(),
            "keyguard_locked" to (!on).toString(),
        ),
    )

    private suspend fun temporalContext(reason: String) = emit(
        "temporal_context.v1",
        draft(
            "temporal_context.v1",
            "TEMPORAL_CONTEXT",
            "change_reason" to reason,
            "daylight_saving_time" to "false",
            "timezone_id" to RealRuntimeBundleInteropTest.ZONE_ID,
            "utc_offset_seconds" to "28800",
        ),
    )

    private fun networkSnapshot() = draft(
        "network_state.v1",
        "NETWORK_SNAPSHOT",
        "connected" to "true",
        "downstream_kbps" to "50000",
        "ethernet" to "false",
        "metered" to "false",
        "mobile" to "false",
        "roaming" to "false",
        "upstream_kbps" to "10000",
        "validated" to "true",
        "vpn" to "true",
        "wifi" to "true",
    )

    private fun throughput(rx: Long, tx: Long) = draft(
        "network_throughput.v1",
        "NETWORK_THROUGHPUT",
        "interval_end_elapsed_nanos" to clocks.elapsedNanos.toString(),
        "interval_start_elapsed_nanos" to (clocks.elapsedNanos - THROUGHPUT_INTERVAL_NANOS).toString(),
        "rx_bytes" to rx.toString(),
        "tx_bytes" to tx.toString(),
    )

    private suspend fun gyroscope(count: Int) {
        val now = clocks.now()
        val samples = (0 until count).map { index ->
            val offsetMillis = (count - index) * 200L
            val observed = ResearchTime(
                now.wallTimeUtcMillis - offsetMillis,
                now.elapsedRealtimeNanos - offsetMillis * NANOS_PER_MILLI,
                now.bootSessionId,
            )
            draft(
                "gyroscope.v1",
                "GYROSCOPE_SAMPLE",
                "accuracy" to "3",
                "source_elapsed_realtime_nanos" to (observed.elapsedRealtimeNanos - 5_000_000L).toString(),
                "x_radians_per_second" to listOf("0.5", "-0.25", "0.125", "0.75", "-0.5")[index % 5],
                "y_radians_per_second" to "0.25",
                "z_radians_per_second" to "-0.125",
                observed = observed,
            )
        }
        emit("gyroscope.v1", *samples.toTypedArray())
    }

    private suspend fun emit(source: String, vararg drafts: EventDraft) {
        collector(source).emitLive(drafts.toList())
        settle()
    }

    private fun usageActivity(timestamp: Long, type: String, packageName: String) =
        usageHistory(timestamp, type, packageName, "$packageName.MainActivity")

    private fun usageHistory(timestamp: Long, type: String, packageName: String?, component: String?) {
        val collector = collector(USAGE_EVENTS)
        val tokens = collector.context.tokenEncoder
        val fields = buildMap {
            put("source_time_utc_millis", timestamp.toString())
            packageName?.let { put("package_name", it) }
            component?.let { put("activity_component_token", tokens.encode(ACTIVITY_TOKEN_DOMAIN, it)) }
        }
        val event = EventDraft(EventTypeKey(EventSourceId(USAGE_EVENTS), 1, type), clocks.now(), fields)
        collector.addSourceHistory(timestamp, event)
    }

    private suspend fun poll() {
        collector(USAGE_EVENTS).poll()
        settle()
    }

    private fun collector(source: String): InteropCollector =
        checkNotNull(plugins.getValue(source).current) { "$source has no active collector" }

    private companion object {
        const val INITIAL_ELAPSED_NANOS = 3_600_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_ROUNDING = NANOS_PER_MILLI - 1
        const val THROUGHPUT_INTERVAL_NANOS = 10_000_000_000L
        const val MAXIMUM_TIMER_ATTEMPTS = 5
        const val BOOT_ONE = RealRuntimeBundleInteropTest.BOOT_ONE
        const val BOOT_TWO = RealRuntimeBundleInteropTest.BOOT_TWO
        const val USAGE_EVENTS = RealRuntimeBundleInteropTest.USAGE_EVENTS
        const val ACTIVITY_TOKEN_DOMAIN = "usage-events.activity-component.v1"
        const val READER = "com.example.reader"
        const val MAPS = "com.example.maps"
        val PRODUCER = BundleProducer(
            StudyConfiguration.ANDROID_PLATFORM,
            RealRuntimeBundleInteropTest.CLIENT_VERSION.toString(),
        )

        fun local(date: String, time: String) = RealRuntimeBundleInteropTest.local(date, time)
    }
}

private fun RecordedEvent.named(eventType: String) = type.eventType == eventType

private fun EngineCommit.closesEpoch() = events.any { it.named("CONDITION_EPOCH_DEACTIVATED") }

private fun EngineCommit.flushes(sourceId: String) = sourceObservations.any {
    it.sourceId.value == sourceId && it.admissionKind == ObservationAdmissionKind.BARRIER_FLUSH
}
