package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.StorageRecoveryProgress
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryException
import cool.jacoblin.particeps.core.model.withComputedDigest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real store, codec and AES-GCM; only the Android key/namespace and persistence boundary are ports. */
class CommitRecoveryPropertyTest {
    @Test
    fun readbackMustPersistACompleteFrameBeforeAcknowledgingIt() = runBlocking {
        withFixture { fixture ->
            fixture.append(AppendMode.VISIBLE_THEN_THROW)
            fixture.powerLoss()
            fixture.reopen()
        }
    }

    @Test
    fun processRecoveryMustPersistVisibleFramesBeforePublishingASnapshot() = runBlocking {
        withFixture { fixture ->
            // The process died after write, before sync or append's exception handler.
            fixture.writeUnacknowledgedFrame()
            fixture.reopen(acceptUnacknowledged = true)
            fixture.powerLoss()
            fixture.reopen()
        }
    }

    @Test
    fun failedReconciliationSyncCannotAcknowledgeOrPublishRecovery() = runBlocking {
        withFixture { fixture ->
            fixture.files.failLogSync = true
            assertThrows(IOException::class.java) { runBlocking { fixture.append(AppendMode.VISIBLE_THEN_THROW) } }
            assertThrows(StudyStoreRecoveryException::class.java) { runBlocking { fixture.reopen() } }
            fixture.files.failLogSync = false
            fixture.powerLoss()
            fixture.reopen()
        }
    }

    @Test
    @OptIn(io.kotest.common.ExperimentalKotest::class)
    fun generatedHistoriesPreserveAcknowledgementsAndConsumePendingAtMostOnce() = runBlocking<Unit> {
        checkAll(
            PropTestConfig(
                iterations = requireNotNull(System.getProperty("particeps.storage.property.iterations", "48")).toInt().also { require(it > 0) },
                seed = requireNotNull(System.getProperty("particeps.storage.property.seed", "5782993918480303440")).toLong(),
            ),
            Arb.list(Arb.enum<Command>(), 1..24),
        ) { commands ->
            withFixture { fixture ->
                commands.forEach { command ->
                    when (command) {
                        Command.APPEND -> fixture.append(AppendMode.DURABLE)
                        Command.VISIBLE_FAILURE -> fixture.append(AppendMode.VISIBLE_THEN_THROW)
                        Command.DURABLE_FAILURE -> fixture.append(AppendMode.SYNCED_THEN_THROW)
                        Command.TORN_FAILURE -> fixture.failedAppend(AppendMode.TORN)
                        Command.STAGE -> fixture.stage()
                        Command.REOPEN -> fixture.reopen()
                        Command.POWER_LOSS -> { fixture.powerLoss(); fixture.reopen() }
                        Command.CHECKPOINT_FAILURE -> {
                            fixture.files.failCheckpoint = true
                            try { fixture.append(AppendMode.DURABLE) } finally { fixture.files.failCheckpoint = false }
                            fixture.reopen()
                        }
                        Command.CLEANUP_FAILURE -> {
                            fixture.stage()
                            fixture.files.failPendingDelete = true
                            try { fixture.append(AppendMode.DURABLE) } finally { fixture.files.failPendingDelete = false }
                            fixture.reopen()
                        }
                    }
                }
                fixture.powerLoss()
                fixture.reopen()
            }
        }
    }

    private enum class Command {
        APPEND, VISIBLE_FAILURE, DURABLE_FAILURE, TORN_FAILURE, STAGE, REOPEN,
        POWER_LOSS, CHECKPOINT_FAILURE, CLEANUP_FAILURE,
    }

    private enum class AppendMode { DURABLE, VISIBLE_THEN_THROW, SYNCED_THEN_THROW, TORN }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("particeps-recovery-").toFile()
        try {
            val fixture = Fixture(directory)
            fixture.store.initialize(fixture.expected)
            block(fixture)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Fixture(private val directory: File) {
        val files = PersistenceFileSystem()
        private val keyAccess = object : ExperimentKeyAccess {
            private var key: SecretKey? = null
            override fun existingKey() = key
            override fun createKey(): SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES").also { key = it }
            override fun deleteKey() { key = null }
        }
        var expected = RuntimeDocument.initial(
            experimentId = "property-study", configurationId = "property-config",
            configurationSha256 = "a".repeat(64), activityTokenKeyBase64Url = "A".repeat(43),
            participantInstanceId = "018f3ca4-7a82-4f47-8b5c-a4415b9b2290",
        )
        private var mode = AppendMode.DURABLE
        private var pending: PendingEngineInput? = null
        private val acknowledged = mutableListOf<EngineCommit>()
        private var nextInput = 0L
        private var unacknowledged: EngineCommit? = null
        var store = newStore()
            private set

        private fun newStore() = EncryptedExperimentStore(
            rootDirectory = directory.resolve("experiments"), experimentId = "property-study",
            maximumLocalBytes = 8L shl 20, keyAccess = keyAccess, fileSystem = files,
            // Small segments and checkpoints exercise boundaries without large/slow histories.
            maximumSegmentBytes = 4_096, snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 3),
            appendFrame = { file, frame -> files.append(file, frame, mode) },
        )

        suspend fun stage() {
            if (pending != null) return
            nextInput++
            val time = ResearchTime(nextInput, nextInput, "property-boot")
            val input = PendingEngineInput(
                conditionEpochId = ConditionEpochId("018f3ca4-7a82-4f47-8b5c-a4415b9b2290"),
                submissions = listOf(PendingSourceSubmission(
                    sourceId = EventSourceId("app_lifecycle.v1"), schemaVersion = 1,
                    resourceGeneration = 1, producerOrdinal = 0,
                    admissionKind = ObservationAdmissionKind.NORMAL,
                    events = listOf(EventDraft(EventTypeKey(EventSourceId("app_lifecycle.v1"), 1, "ACTIVITY_RESUMED"), time, emptyMap())),
                    coverage = null,
                )),
                stagedAt = time, encodedSha256 = "0".repeat(64),
            ).withComputedDigest()
            store.stagePendingInput(input)
            pending = input
        }

        suspend fun append(appendMode: AppendMode) {
            mode = appendMode
            val commit = commit()
            val successor = successor(commit)
            if (pending == null) store.appendCommit(commit, successor)
            else store.appendCommitConsumingPending(commit, successor)
            acknowledged += commit
            expected = successor
            pending = null
        }

        suspend fun failedAppend(appendMode: AppendMode) {
            assertThrows(IOException::class.java) { runBlocking { append(appendMode) } }
            reopen()
        }

        suspend fun writeUnacknowledgedFrame() {
            // Seed a segment using the public store, then stop a full append before reconciliation.
            append(AppendMode.DURABLE)
            unacknowledged = commit()
            files.failLogSync = true
            assertThrows(IOException::class.java) { runBlocking { append(AppendMode.VISIBLE_THEN_THROW) } }
            files.failLogSync = false
        }

        fun powerLoss() = files.powerLoss()

        suspend fun reopen(acceptUnacknowledged: Boolean = false) {
            store = newStore()
            files.syncedSegments.clear()
            val seen = mutableListOf<EngineCommit>()
            val progress = mutableListOf<StorageRecoveryProgress>()
            val actual = store.loadRuntime(seen::add, progress::add)
            if (acceptUnacknowledged) {
                val recovered = requireNotNull(unacknowledged)
                expected = successor(recovered)
                acknowledged += recovered
                unacknowledged = null
            }
            assertEquals("recovered projection", expected, actual)
            assertEquals("authenticated log, exactly once and in order", acknowledged, seen)
            assertEquals("pending input", pending, store.loadPendingInput())
            val consumers = seen.mapNotNull(EngineCommit::consumedPendingInputSha256)
            assertEquals("pending consumption is unique", consumers.size, consumers.toSet().size)
            if (seen.isNotEmpty()) assertEquals(100, progress.last().percent)
            assertTrue(progress.zipWithNext().all { (a, b) -> a.percent < b.percent })
            assertEquals("one sync per segment, not per commit", files.segmentFiles().sorted(), files.syncedSegments.sorted())
        }

        private fun commit(): EngineCommit = EngineCommit(
            commitSequence = expected.nextCommitSequence, previousCommitSha256 = expected.lastCommitSha256,
            inputKind = if (pending == null) EngineInputKind.SOURCE_OBSERVATION else EngineInputKind.RECOVERY,
            consumedPendingInputSha256 = pending?.encodedSha256, sourceObservations = emptyList(),
            events = emptyList(), mutations = listOf(RuntimeMutation(
                RuntimeComponentKey(RuntimeComponentKind.RESOURCE, "property-${expected.nextCommitSequence % 3}"),
                if (expected.nextCommitSequence % 5 == 0L) RuntimeMutationOperation.REMOVE else RuntimeMutationOperation.UPSERT,
                if (expected.nextCommitSequence % 5 == 0L) null else "value-${expected.nextCommitSequence}",
            )),
            committedAt = ResearchTime(expected.nextCommitSequence, expected.nextCommitSequence, "property-boot"),
            successorProjection = expected.projection().copy(
                revision = expected.revision + 1, nextCommitSequence = expected.nextCommitSequence + 1,
                evaluatedThroughCommit = expected.revision + 1,
            ),
            resultingCheckpointSha256 = "1".repeat(64), commitSha256 = "0".repeat(64),
        ).withComputedDigest()

        // Deliberately do not use RuntimeDocument.advance (the production replay operation).
        private fun successor(commit: EngineCommit): RuntimeDocument = expected.copy(
            revision = expected.revision + 1, nextCommitSequence = expected.nextCommitSequence + 1,
            evaluatedThroughCommit = expected.revision + 1, lastCommitSha256 = commit.commitSha256,
            components = expected.components.toMutableMap().apply {
                val key = RuntimeComponentKey(RuntimeComponentKind.RESOURCE, "property-${expected.nextCommitSequence % 3}")
                if (expected.nextCommitSequence % 5 == 0L) remove(key)
                else put(key, "value-${expected.nextCommitSequence}")
            },
        )
    }

    /** Directory mutations are reliable here. File visibility and durable log bytes are separate. */
    private class PersistenceFileSystem : AcknowledgedFileSystem {
        var failLogSync = false
        var failCheckpoint = false
        var failPendingDelete = false
        private val stable = mutableMapOf<File, ByteArray>()
        val syncedSegments = mutableListOf<File>()

        fun segmentFiles() = stable.keys.toList()

        fun append(file: File, bytes: ByteArray, mode: AppendMode) {
            FileOutputStream(file, true).use { output ->
                output.write(if (mode == AppendMode.TORN) bytes.copyOf(bytes.size / 2) else bytes)
                if (mode != AppendMode.VISIBLE_THEN_THROW) {
                    output.fd.sync()
                    stable[file] = file.readBytes()
                }
            }
            if (mode != AppendMode.DURABLE) throw IOException("injected $mode")
        }

        fun powerLoss() {
            stable.forEach { (file, bytes) -> file.writeBytes(bytes) }
        }

        override fun exists(file: File) = file.exists()
        override fun isDirectory(file: File) = file.isDirectory
        override fun listFiles(directory: File) = directory.listFiles()
        override fun ensureDirectory(directory: File) { Files.createDirectories(directory.toPath()) }
        override fun openOutput(file: File): FileOutputStream {
            if (failCheckpoint && file.name.contains(".runtime3.ptc")) throw IOException("injected checkpoint failure")
            return FileOutputStream(file)
        }
        override fun syncFile(output: FileOutputStream) = output.fd.sync()
        override fun syncFile(file: File) {
            if (failLogSync) throw IOException("injected reconciliation sync failure")
            AndroidAcknowledgedFileSystem.syncFile(file)
            stable[file] = file.readBytes()
            syncedSegments += file
        }
        override fun closeFile(output: FileOutputStream) = output.close()
        override fun atomicReplace(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Segment creation uses a synced atomic file; no log frame exists yet.
            if (target.extension == "ptcs") stable[target] = target.readBytes()
        }
        override fun readFully(file: File) = file.readBytes()
        override fun regularFileSize(file: File) = Files.size(file.toPath())
        override fun deleteIfExists(file: File) {
            if (failPendingDelete && file.name.endsWith(".pending3.ptc")) throw IOException("injected cleanup failure")
            Files.deleteIfExists(file.toPath())
        }
        override fun syncDirectory(directory: File) { check(directory.isDirectory) }
    }
}
