package cool.jacoblin.particeps.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryException
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryFailure
import cool.jacoblin.particeps.core.model.withComputedDigest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedExperimentStoreTest {
    private lateinit var context: Context
    private lateinit var experimentId: String
    private lateinit var store: EncryptedExperimentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        experimentId = "study-${UUID.randomUUID()}"
        store = newStore()
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { store.clear() }
        legacyFiles().forEach(File::deleteRecursively)
    }

    @Test
    fun completeCommitAndSuccessorReopenAsOneAuthenticatedFact() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)

        val reopened = newStore()
        assertEquals(successor, reopened.loadRuntime())
        val commits = mutableListOf<EngineCommit>()
        reopened.readCommits(1, 1, commits::add)
        assertEquals(listOf(commit), commits)
    }

    @Test
    fun recoveryShowsEachRetainedCommitInOrderDuringItsAuthenticationPass() = runBlocking {
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 2),
        )
        var current = initialRuntime()
        store.initialize(current)
        val appended = mutableListOf<EngineCommit>()
        // With a two-commit checkpoint budget, recovery sees commits both before and after the
        // snapshot boundary it restores from.
        listOf(
            ExperimentState.CONFIG_VERIFIED to EngineInputKind.LIFECYCLE_COMMAND,
            ExperimentState.CONSENT_PENDING to EngineInputKind.LIFECYCLE_COMMAND,
            ExperimentState.CONSENT_PENDING to EngineInputKind.SOURCE_OBSERVATION,
        ).forEach { (state, inputKind) ->
            val (commit, successor) = lifecycleCommit(current, state, inputKind = inputKind)
            store.appendCommit(commit, successor)
            appended += commit
            current = successor
        }

        val observed = mutableListOf<EngineCommit>()
        assertEquals(current, newStore().loadRuntime(observed::add))
        assertEquals(appended, observed)
    }

    @Test
    fun sourceCommitsRecoverFromTheLogBeforeTheirSnapshotCheckpoint() = runBlocking {
        var current = initialRuntime()
        store.initialize(current)
        val genesisSnapshot = snapshotFile().readBytes()
        repeat(3) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
            assertArrayEquals(genesisSnapshot, snapshotFile().readBytes())
        }
        val segment = commitSegments().single()
        val acknowledgedLength = segment.length()
        RandomAccessFile(segment, "rw").use { file ->
            file.seek(file.length())
            file.writeLong(current.nextCommitSequence)
            file.writeInt(1024)
            file.fd.sync()
        }

        assertEquals(current, newStore().loadRuntime())
        assertEquals(acknowledgedLength, segment.length())
        assertFalse(genesisSnapshot.contentEquals(snapshotFile().readBytes()))
    }

    @Test
    fun sourceAppendCheckpointsAtTheCommitBudget() = runBlocking {
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 2),
        )
        var current = initialRuntime()
        store.initialize(current)
        val genesisSnapshot = snapshotFile().readBytes()
        repeat(2) { index ->
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
            assertEquals(index == 0, genesisSnapshot.contentEquals(snapshotFile().readBytes()))
        }
        assertEquals(current, newStore().loadRuntime())
    }

    @Test
    fun interruptedCheckpointKeepsAcknowledgedFramesAndEquivalentRecoveryCandidates() = runBlocking {
        var failCheckpoint = false
        val operations = object : AcknowledgedFileSystem by AndroidAcknowledgedFileSystem {
            override fun atomicReplace(source: File, target: File) {
                if (failCheckpoint && target.name.endsWith(".runtime3.ptc")) {
                    throw IOException("injected checkpoint interruption")
                }
                AndroidAcknowledgedFileSystem.atomicReplace(source, target)
            }
        }
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            fileSystem = operations,
            snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 2),
        )
        var current = initialRuntime()
        store.initialize(current)
        failCheckpoint = true
        repeat(2) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
        }

        val reopened = newStore()
        assertEquals(current, reopened.loadRuntime())
        val recoveredSequences = mutableListOf<Long>()
        reopened.readCommits(1, 2) { recoveredSequences += it.commitSequence }
        assertEquals(listOf(1L, 2L), recoveredSequences)
    }

    @Test
    fun tornCheckpointStagingReplaysAcknowledgedCommitsBeforeRetiringResidue() = runBlocking {
        for (suffix in listOf("pending", "replacement")) {
            for (writePrefix in listOf(false, true)) {
                var interruptCheckpoint = false
                val operations = object : AcknowledgedFileSystem by AndroidAcknowledgedFileSystem {
                    override fun openOutput(file: File): FileOutputStream {
                        if (!interruptCheckpoint || !file.name.endsWith(".runtime3.ptc.$suffix")) {
                            return AndroidAcknowledgedFileSystem.openOutput(file)
                        }
                        return object : FileOutputStream(file, false) {
                            override fun write(bytes: ByteArray) {
                                if (writePrefix) super.write(bytes, 0, bytes.size / 2)
                                fd.sync()
                                throw IOException("injected interrupted checkpoint staging")
                            }
                        }
                    }
                }
                store = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES, File::delete,
                    fileSystem = operations)
                val initial = initialRuntime()
                store.initialize(initial)
                interruptCheckpoint = true
                val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
                // The durable frame is acknowledged even though its cache checkpoint tears.
                store.appendCommit(commit, successor)
                assertTrue(snapshotResidue().isNotEmpty())
                val reopened = newStore()
                assertEquals(successor, reopened.loadRuntime())
                val retained = mutableListOf<EngineCommit>()
                reopened.readCommits(1, 1, retained::add)
                assertEquals(listOf(commit), retained)
                assertTrue(snapshotResidue().isEmpty())
                assertEquals(successor, newStore().loadRuntime())
                reopened.clear()
            }
        }
    }

    @Test
    fun invalidBaseCannotBeReplacedByAnAuthenticatedStagingSnapshot() = runBlocking {
        store.initialize(initialRuntime())
        val staging = checkpointStagingFile("pending")
        staging.writeBytes(snapshotFile().readBytes())
        snapshotFile().writeBytes(byteArrayOf(0))
        assertSnapshotRecoveryFails()
        assertTrue(staging.exists())
    }

    @Test
    fun invalidStagingWithoutAnAcknowledgedBaseCannotRecover() = runBlocking {
        store.initialize(initialRuntime())
        val staging = checkpointStagingFile("pending")
        staging.writeBytes(byteArrayOf(0))
        assertTrue(snapshotFile().delete())
        assertSnapshotRecoveryFails()
        assertTrue(staging.exists())
    }

    @Test
    fun tornCheckpointCannotHideCorruptOrMissingRetainedCommits() = runBlocking {
        for (removeLog in listOf(false, true)) {
            val initial = initialRuntime()
            store = newStore()
            store.initialize(initial)
            val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
            store.appendCommit(commit, successor)
            val staging = checkpointStagingFile("pending")
            staging.writeBytes(byteArrayOf(0))
            if (removeLog) {
                commitSegments().forEach { assertTrue(it.delete()) }
            } else {
                corruptCommitCiphertext(commit.commitSequence)
            }
            val failure = assertThrows(StudyStoreRecoveryException::class.java) {
                runBlocking { newStore().loadRuntime() }
            }
            assertEquals(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure.failure)
            // Failed authentication must leave the evidence intact.
            assertTrue(staging.exists())
            store.clear()
        }
    }

    @Test
    fun authenticatedMalformedOrConflictingStagingStillFailsClosed() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        for (plaintext in listOf(
            "{broken json".toByteArray(),
            EngineDataJsonCodec.encodeRuntime(initial.copy(experimentId = "another-study")),
            EngineDataJsonCodec.encodeRuntime(initial.copy(state = ExperimentState.CONFIG_VERIFIED)),
        )) {
            val staging = checkpointStagingFile("pending")
            staging.writeBytes(encryptSnapshotForTest(plaintext))
            assertSnapshotRecoveryFails()
            assertTrue(staging.exists())
            assertTrue(staging.delete())
        }
    }

    @Test
    fun uncheckpointedSuffixStillRequiresFullAuthentication() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(
            initial, initial.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
        )
        store.appendCommit(commit, successor)
        corruptCommitCiphertext(commit.commitSequence)

        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure.failure)
    }

    @Test
    fun retainedCommitCorruptionFailsClosedEvenWhenTheSnapshotIsCurrent() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (first, afterFirst) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(first, afterFirst)
        val (second, afterSecond) = lifecycleCommit(afterFirst, ExperimentState.CONSENT_PENDING)
        store.appendCommit(second, afterSecond)

        val segment = commitSegments().single()
        RandomAccessFile(segment, "rw").use { file ->
            file.seek(FIRST_CIPHERTEXT_OFFSET)
            val original = file.readByte().toInt()
            file.seek(FIRST_CIPHERTEXT_OFFSET)
            file.writeByte(original xor 0x01)
            file.fd.sync()
        }

        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure.failure)
        Unit
    }

    @Test
    fun missingRetainedCommitSegmentFailsClosed() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)
        assertTrue(commitSegments().single().delete())

        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure.failure)
    }

    @Test
    fun rangedReadStopsAtItsAuthenticatedUpperBound() = runBlocking {
        var current = initialRuntime()
        store.initialize(current)
        val commits = mutableListOf<EngineCommit>()
        repeat(3) {
            val (commit, successor) = lifecycleCommit(current, ExperimentState.CONFIG_VERIFIED)
            store.appendCommit(commit, successor)
            commits += commit
            current = successor
        }
        corruptCommitCiphertext(3)

        val firstTwo = mutableListOf<EngineCommit>()
        store.readCommits(1, 2, firstTwo::add)
        assertEquals(commits.take(2), firstTwo)
        assertThrows(Exception::class.java) {
            runBlocking { store.readCommits(1, 3) {} }
        }
        Unit
    }

    @Test
    fun largeRangeStreamsInOrderWithoutMaterializingACommitList() = runBlocking {
        var current = initialRuntime()
        store.initialize(current)
        repeat(STREAMING_RANGE_COMMITS) {
            val (commit, successor) = lifecycleCommit(current, ExperimentState.CONFIG_VERIFIED)
            store.appendCommit(commit, successor)
            current = successor
        }

        var count = 0L
        var previous = 0L
        store.readCommits(1, STREAMING_RANGE_COMMITS.toLong()) { commit ->
            assertEquals(previous + 1, commit.commitSequence)
            previous = commit.commitSequence
            count++
        }
        assertEquals(STREAMING_RANGE_COMMITS.toLong(), count)
    }

    @Test
    fun warmSnapshotUsesAcknowledgedRuntimeWithoutReopeningTheRecoveryCheckpoint() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)
        snapshotFile().writeBytes(byteArrayOf(0))

        store.withReadSnapshot { snapshot ->
            assertEquals(successor, snapshot.runtime)
            snapshot.readCommits(1, 1) {
                assertEquals(commit, it)
                true
            }
        }
        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.SNAPSHOT_INVALID, failure.failure)
    }

    @Test
    fun snapshotConsumerCanAppendWhileItsRuntimeAndReadBoundaryStayFixed() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (first, afterFirst) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(first, afterFirst)
        val (second, afterSecond) = lifecycleCommit(afterFirst, ExperimentState.CONSENT_PENDING)

        store.withReadSnapshot { snapshot ->
            snapshot.readCommits(1, 1) {
                runBlocking { withTimeout(5_000) { store.appendCommit(second, afterSecond) } }
                true
            }
            assertEquals(afterFirst, snapshot.runtime)
            val sequences = mutableListOf<Long>()
            snapshot.readCommits(1, 1) {
                sequences += it.commitSequence
                true
            }
            assertEquals(listOf(1L), sequences)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { snapshot.readCommits(1, 2) { true } }
            }
        }
        store.withReadSnapshot { assertEquals(afterSecond, it.runtime) }
    }

    @Test
    fun snapshotRuntimeOwnsItsCollectionValues() = runBlocking {
        val component = RuntimeComponentKey(RuntimeComponentKind.RESOURCE, "test-resource")
        val components = mutableMapOf(component to "before")
        store.initialize(initialRuntime().copy(components = components))

        store.withReadSnapshot { snapshot ->
            components[component] = "after"
            assertEquals("before", snapshot.runtime.components[component])
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.runtime.components as MutableMap<RuntimeComponentKey, String>)[component] = "changed"
            }
        }
        Unit
    }

    @Test
    fun coldTailRecoveryPreservesAnOpenReadSnapshot() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)
        val segment = commitSegments().single()
        val acknowledgedLength = segment.length()

        store.withReadSnapshot { snapshot ->
            RandomAccessFile(segment, "rw").use { file ->
                file.seek(file.length())
                file.writeLong(2)
                file.writeInt(1024)
                file.fd.sync()
            }
            assertEquals(successor, store.loadRuntime())
            assertEquals(acknowledgedLength, segment.length())
            snapshot.readCommits(1, 1) {
                assertEquals(commit, it)
                true
            }
        }
    }

    @Test
    fun snapshotConsumerStopsBeforeDecryptingTheRemainingRequestedRange() = runBlocking {
        var current = initialRuntime()
        store.initialize(current)
        repeat(3) {
            val (commit, successor) = lifecycleCommit(current, ExperimentState.CONFIG_VERIFIED)
            store.appendCommit(commit, successor)
            current = successor
        }
        corruptCommitCiphertext(3)

        store.withReadSnapshot { snapshot ->
            val sequences = mutableListOf<Long>()
            snapshot.readCommits(1, 3) {
                sequences += it.commitSequence
                it.commitSequence < 2
            }
            assertEquals(listOf(1L, 2L), sequences)
            assertThrows(Exception::class.java) {
                runBlocking { snapshot.readCommits(1, 3) { true } }
            }
        }
        Unit
    }

    @Test
    fun cancellationStopsTheScanAndReleasesTheSnapshotPin() = runBlocking {
        var current = initialRuntime()
        store.initialize(current)
        repeat(3) {
            val (commit, successor) = lifecycleCommit(current, ExperimentState.CONFIG_VERIFIED)
            store.appendCommit(commit, successor)
            current = successor
        }
        var consumed = 0
        val reader = launch {
            val context = currentCoroutineContext()
            store.withReadSnapshot { snapshot ->
                snapshot.readCommits(1, 3) {
                    consumed++
                    context.cancel()
                    true
                }
            }
        }
        withTimeout(5_000) { reader.join() }
        assertTrue(reader.isCancelled)
        assertEquals(1, consumed)
        store.clear()
        assertTrue(commitSegments().isEmpty())
    }

    @Test
    fun readSnapshotCannotBeUsedOutsideItsScope() = runBlocking {
        store.initialize(initialRuntime())
        lateinit var released: StudyReadSnapshot
        store.withReadSnapshot { released = it }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { released.readCommits(1, 0) { true } }
        }
        Unit
    }

    @Test
    fun pinnedSnapshotDefersEvictionAndRejectsClearUntilReleased() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(
            initial,
            ExperimentState.CONFIG_VERIFIED,
            uploadedThroughCommit = 1,
        )
        store.appendCommit(commit, successor)

        store.withReadSnapshot { snapshot ->
            assertEquals(successor, store.evictThrough(successor, targetBytes = 0))
            assertThrows(IllegalStateException::class.java) { runBlocking { store.clear() } }
            snapshot.readCommits(1, 1) {
                assertEquals(commit, it)
                true
            }
        }
        val evicted = store.evictThrough(successor, targetBytes = 0)
        assertEquals(2L, evicted.retainedFromCommit)
        assertTrue(commitSegments().isEmpty())
    }

    @Test
    fun tornUncommittedTailIsTruncatedWithoutChangingTheSnapshot() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)
        val segment = commitSegments().single()
        val acknowledgedLength = segment.length()
        RandomAccessFile(segment, "rw").use { file ->
            file.seek(file.length())
            file.writeLong(2)
            file.writeInt(1024)
            file.fd.sync()
        }

        assertEquals(successor, newStore().loadRuntime())
        assertEquals(acknowledgedLength, segment.length())
    }

    @Test
    fun appendThatThrowsAfterFsyncIsRecoveredAsCommittedWithoutRetry() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val afterWriteFailure = EncryptedExperimentStore(
            context = context,
            experimentId = experimentId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            appendFrame = { file, bytes ->
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(output.length())
                    output.write(bytes)
                    output.fd.sync()
                }
                error("injected post-fsync failure")
            },
        )
        assertEquals(initial, afterWriteFailure.loadRuntime())
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)

        afterWriteFailure.appendCommit(commit, successor)
        assertEquals(successor, newStore().loadRuntime())
    }

    @Test
    fun pendingInputSurvivesRestartAndIsConsumedOnlyByItsNamedCommit() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val pending = pendingInput()
        store.stagePendingInput(pending)
        val beforeConsumption = newStore()
        beforeConsumption.loadRuntime()
        assertEquals(pending, beforeConsumption.loadPendingInput())

        val (commit, successor) = lifecycleCommit(
            current = initial,
            state = ExperimentState.PAUSED,
            inputKind = EngineInputKind.SAFETY_FAILURE,
            consumedPendingInputSha256 = pending.encodedSha256,
        )
        store.appendCommitConsumingPending(commit, successor)

        val reopened = newStore()
        assertEquals(successor, reopened.loadRuntime())
        assertNull(reopened.loadPendingInput())
    }

    @Test
    fun differentPendingDigestCannotBeConsumed() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        store.stagePendingInput(pendingInput())
        val (commit, successor) = lifecycleCommit(
            current = initial,
            state = ExperimentState.PAUSED,
            inputKind = EngineInputKind.SAFETY_FAILURE,
            consumedPendingInputSha256 = "f".repeat(64),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.appendCommitConsumingPending(commit, successor) }
        }
        Unit
    }

    @Test
    fun everyOpenLooksTheEngineKeyUpAgain() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        store.appendCommit(commit, successor)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$ENGINE_KEY_ALIAS_PREFIX${opaqueId()}")

        // The open store held a handle for its appends; reopening it must still find the key gone.
        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { store.loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.KEY_UNAVAILABLE, failure.failure)
    }

    @Test
    fun storageLedgerMatchesAFreshScanAfterEveryKindOfMutation() = runBlocking {
        var appendFault = AppendFault.NONE
        var failCheckpoint = false
        val operations = object : AcknowledgedFileSystem by AndroidAcknowledgedFileSystem {
            override fun atomicReplace(source: File, target: File) {
                if (failCheckpoint && target.name.endsWith(".runtime3.ptc")) {
                    throw IOException("injected checkpoint interruption")
                }
                AndroidAcknowledgedFileSystem.atomicReplace(source, target)
            }
        }
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            fileSystem = operations,
            appendFrame = { file, frame -> appendFault.append(file, frame) },
            snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 3),
            maximumSegmentBytes = SMALL_SEGMENT_BYTES,
        )
        assertLedgerMatchesDisk()
        var current = initialRuntime()
        store.initialize(current)
        assertLedgerMatchesDisk()

        suspend fun append(
            inputKind: EngineInputKind = EngineInputKind.SOURCE_OBSERVATION,
            uploadedThroughCommit: Long = current.uploadedThroughCommit,
        ) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = inputKind, uploadedThroughCommit = uploadedThroughCommit,
            )
            store.appendCommit(commit, successor)
            current = successor
            assertLedgerMatchesDisk()
        }

        // Appends within a segment, segment rotations and budgeted checkpoints.
        val genesisSnapshot = snapshotFile().readBytes()
        repeat(8) { append() }
        assertTrue(commitSegments().size >= 3)
        assertFalse(genesisSnapshot.contentEquals(snapshotFile().readBytes()))

        // The pending slot: staged, replaced, then consumed and retired by a commit.
        val staged = pendingInput()
        store.stagePendingInput(staged)
        assertLedgerMatchesDisk()
        val replacement = staged.copy(
            submissions = staged.submissions + staged.submissions.single().copy(producerOrdinal = 1),
        ).withComputedDigest()
        store.replacePendingInput(staged.encodedSha256, replacement)
        assertLedgerMatchesDisk()
        val (consuming, afterConsuming) = lifecycleCommit(
            current, ExperimentState.PAUSED, EngineInputKind.SAFETY_FAILURE,
            consumedPendingInputSha256 = replacement.encodedSha256,
        )
        store.appendCommitConsumingPending(consuming, afterConsuming)
        current = afterConsuming
        assertFalse(pendingFile().exists())
        assertLedgerMatchesDisk()

        // A failed append that wrote nothing, and one that left a torn tail its readback truncated.
        appendFault = AppendFault.BEFORE_WRITE
        assertThrows(IOException::class.java) { runBlocking { append() } }
        assertLedgerMatchesDisk()
        appendFault = AppendFault.NONE
        append()
        val acknowledgedLength = commitSegments().sumOf(File::length)
        appendFault = AppendFault.TORN
        assertThrows(IOException::class.java) { runBlocking { append() } }
        // The torn bytes are gone; only the header of a segment that append opened may remain.
        val grown = commitSegments().sumOf(File::length) - acknowledgedLength
        assertTrue("grew by $grown", grown == 0L || grown == SEGMENT_HEADER_BYTES)
        assertLedgerMatchesDisk()
        appendFault = AppendFault.NONE
        append()

        // A forced checkpoint whose replace fails leaves witnesses the quota must still count.
        failCheckpoint = true
        append(inputKind = EngineInputKind.LIFECYCLE_COMMAND)
        assertTrue(snapshotResidue().isNotEmpty())
        failCheckpoint = false
        append()
        assertTrue(snapshotResidue().isEmpty())

        // Torn-tail truncation during a reopen of the same store.
        RandomAccessFile(commitSegments().last(), "rw").use { file ->
            file.seek(file.length())
            file.writeLong(current.nextCommitSequence)
            file.writeInt(1024)
            file.fd.sync()
        }
        assertEquals(current, store.loadRuntime())
        assertLedgerMatchesDisk()
        append()

        // Eviction of every delivered segment, then a fresh first segment.
        append(uploadedThroughCommit = current.revision + 1)
        current = store.evictThrough(current, targetBytes = 0)
        assertTrue(commitSegments().isEmpty())
        assertLedgerMatchesDisk()
        append()
        assertEquals(current, newStore().loadRuntime())

        store.clear()
        assertLedgerMatchesDisk()
        assertEquals(0L, store.storageUsage().usedBytes)
    }

    @Test
    fun quotaDecisionsMatchAFreshDirectoryScanAtTheExactBoundary() = runBlocking {
        store = EncryptedExperimentStore(context, experimentId, MINIMUM_QUOTA_BYTES)
        var current = initialRuntime()
        store.initialize(current)
        val budget = MINIMUM_QUOTA_BYTES - SNAPSHOT_RESERVE_BYTES

        // Each decision is compared with what a fresh directory scan admits at that moment.
        suspend fun attempt(payloadBytes: Int): Boolean {
            val (commit, successor) = payloadCommit(current, payloadBytes)
            val fits = freshScanBytes() + frameBytes(commit) <= budget
            if (fits) {
                store.appendCommit(commit, successor)
                current = successor
            } else {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { store.appendCommit(commit, successor) }
                }
                assertEquals("Study commit quota exceeded", failure.message)
            }
            assertLedgerMatchesDisk()
            return fits
        }

        var admitted = 0
        while (attempt(LARGE_PAYLOAD_BYTES)) admitted++
        assertTrue(admitted > 0)
        // A frame one byte over what remains is refused; the frame that fills it exactly is admitted.
        val remaining = budget - freshScanBytes()
        val exactPayload = (1 + remaining - frameBytes(payloadCommit(current, 1).first)).toInt()
        assertTrue("remaining $remaining", exactPayload >= 1)
        assertEquals(remaining, frameBytes(payloadCommit(current, exactPayload).first))
        assertFalse(attempt(exactPayload + 1))
        assertTrue(attempt(exactPayload))
        attempt(1)
        Unit
    }

    @Test
    fun steadyAppendsNeitherEnumerateNorMeasureTheCommitLog() = runBlocking {
        val calls = mutableListOf<String>()
        val operations = object : AcknowledgedFileSystem by AndroidAcknowledgedFileSystem {
            override fun listFiles(directory: File): Array<File>? {
                calls += "list:${directory.name}"
                return AndroidAcknowledgedFileSystem.listFiles(directory)
            }

            override fun regularFileSize(file: File): Long {
                calls += "size:${file.name}"
                return AndroidAcknowledgedFileSystem.regularFileSize(file)
            }
        }
        store = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES, File::delete, fileSystem = operations)
        var current = initialRuntime()
        store.initialize(current)
        suspend fun append() {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
        }
        // The first append creates the segment; the next reconciles the ledger from one scan.
        repeat(2) { append() }
        calls.clear()

        repeat(STEADY_APPENDS) { append() }

        assertEquals(emptyList<String>(), calls)
        assertLedgerMatchesDisk()
        assertEquals(current, newStore().loadRuntime())
    }

    @Test
    fun rangedReadsAcrossSegmentsReturnExactlyTheirAuthenticatedCommits() = runBlocking {
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            snapshotPolicy = SnapshotCheckpointPolicy(maximumCommits = 5),
            maximumSegmentBytes = SMALL_SEGMENT_BYTES,
        )
        var current = initialRuntime()
        store.initialize(current)
        val commits = mutableListOf<EngineCommit>()
        repeat(SEGMENTED_COMMITS) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            commits += commit
            current = successor
        }
        assertTrue(commitSegments().size >= 4)

        for (from in 1..SEGMENTED_COMMITS) {
            listOf(from, minOf(from + 2, SEGMENTED_COMMITS), SEGMENTED_COMMITS).distinct().forEach { through ->
                val read = mutableListOf<EngineCommit>()
                store.readCommits(from.toLong(), through.toLong(), read::add)
                assertEquals("commits $from..$through", commits.subList(from - 1, through), read)
            }
        }
        // Cold recovery walks every segment, with its snapshot boundary inside a middle one.
        assertEquals(current, newStore().loadRuntime())
        // A tampered frame inside the requested range still fails closed.
        corruptCommitCiphertext(SEGMENTED_COMMITS - 1L)
        assertThrows(Exception::class.java) {
            runBlocking { store.readCommits(SEGMENTED_COMMITS - 1L, SEGMENTED_COMMITS.toLong()) {} }
        }
        val last = mutableListOf<EngineCommit>()
        store.readCommits(SEGMENTED_COMMITS.toLong(), SEGMENTED_COMMITS.toLong(), last::add)
        assertEquals(listOf(commits.last()), last)
    }

    @Test
    fun sequenceGapBetweenSegmentsFailsEveryRangedReadClosed() = runBlocking {
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            maximumSegmentBytes = SMALL_SEGMENT_BYTES,
        )
        var current = initialRuntime()
        store.initialize(current)
        repeat(SEGMENTED_COMMITS) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
        }
        val segments = commitSegments()
        assertTrue(segments.size >= 4)
        val lastBeforeGap = firstFrameSequence(segments[1]) - 1
        val firstAfterGap = firstFrameSequence(segments[2])
        val lastSegmentStart = firstFrameSequence(segments.last())
        assertTrue(lastSegmentStart > firstAfterGap)
        // Drop the second segment and renumber the rest: indices stay contiguous, sequences do not.
        assertTrue(segments[1].delete())
        segments.drop(2).forEach { segment ->
            val index = segment.name.removePrefix("commits-").removeSuffix(".ptcs").toInt() - 1
            RandomAccessFile(segment, "rw").use { file ->
                file.seek(SEGMENT_HEADER_BYTES - Int.SIZE_BYTES)
                file.writeInt(index)
                file.fd.sync()
            }
            assertTrue(segment.renameTo(segment.resolveSibling("commits-${index.toString().padStart(8, '0')}.ptcs")))
        }

        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure.failure)
        // A ranged read fails too: across the gap from either side, and wholly after it, because
        // locating any range still walks the retained frame headers before it.
        listOf(
            1L to firstAfterGap,
            lastBeforeGap to firstAfterGap,
            lastSegmentStart to current.revision,
        ).forEach { (from, through) ->
            assertThrows("commits $from..$through", Exception::class.java) {
                runBlocking { store.readCommits(from, through) {} }
            }
        }
    }

    @Test
    fun headerDamageInAnEarlierRetainedSegmentFailsALaterRangedReadClosed() = runBlocking {
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            maximumSegmentBytes = SMALL_SEGMENT_BYTES,
        )
        var current = initialRuntime()
        store.initialize(current)
        val commits = mutableListOf<EngineCommit>()
        repeat(SEGMENTED_COMMITS) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            commits += commit
            current = successor
        }
        val segments = commitSegments()
        assertTrue(segments.size >= 4)
        val earliest = segments.first()
        val intact = earliest.readBytes()
        // The requested range lies wholly in the last segment, well after the damaged one.
        val from = firstFrameSequence(segments.last())
        assertTrue(from > firstFrameSequence(segments[1]))
        val expected = commits.subList((from - 1).toInt(), commits.size)
        suspend fun readSuffix(): List<EngineCommit> = mutableListOf<EngineCommit>().also { read ->
            store.readCommits(from, current.revision, read::add)
        }
        assertEquals(expected, readSuffix())

        mapOf<String, (RandomAccessFile) -> Unit>(
            "garbled frame size" to { file ->
                file.seek(SEGMENT_HEADER_BYTES + Long.SIZE_BYTES)
                file.writeInt(0)
            },
            "broken sequence" to { file ->
                file.seek(SEGMENT_HEADER_BYTES)
                file.writeLong(0)
            },
            "torn final frame" to { file -> file.setLength(file.length() - 1) },
        ).forEach { (damage, apply) ->
            RandomAccessFile(earliest, "rw").use { file ->
                apply(file)
                file.fd.sync()
            }
            assertThrows(damage, Exception::class.java) { runBlocking { readSuffix() } }
            earliest.writeBytes(intact)
            assertEquals(damage, expected, readSuffix())
        }
    }

    @Test
    fun tornFirstFrameOfANewSegmentIsTruncatedAndTheLogKeepsAppending() = runBlocking {
        var appendFault = AppendFault.NONE
        store = EncryptedExperimentStore(
            context, experimentId, QUOTA_BYTES, File::delete,
            appendFrame = { file, frame -> appendFault.append(file, frame) },
            maximumSegmentBytes = SMALL_SEGMENT_BYTES,
        )
        var current = initialRuntime()
        store.initialize(current)
        fun next() = lifecycleCommit(current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION)
        while (commitSegments().isEmpty() ||
            commitSegments().last().length() + frameBytes(next().first) <= SMALL_SEGMENT_BYTES
        ) {
            val (commit, successor) = next()
            store.appendCommit(commit, successor)
            current = successor
        }
        val segmentsBefore = commitSegments().size

        appendFault = AppendFault.TORN
        val (commit, successor) = next()
        assertThrows(IOException::class.java) { runBlocking { store.appendCommit(commit, successor) } }
        assertEquals(segmentsBefore + 1, commitSegments().size)
        assertEquals(SEGMENT_HEADER_BYTES, commitSegments().last().length())
        assertLedgerMatchesDisk()

        appendFault = AppendFault.NONE
        store.appendCommit(commit, successor)
        assertLedgerMatchesDisk()
        assertEquals(successor, newStore().loadRuntime())
        val read = mutableListOf<EngineCommit>()
        store.readCommits(commit.commitSequence, commit.commitSequence, read::add)
        assertEquals(listOf(commit), read)
    }

    @Test
    fun recoveryResolvesAConsumedButUnretiredPendingInputInItsOneAuthenticationPass() = runBlocking {
        var failPendingDelete = false
        val commitListings = AtomicInteger()
        val operations = object : AcknowledgedFileSystem by AndroidAcknowledgedFileSystem {
            override fun deleteIfExists(file: File) {
                if (failPendingDelete && file.name.contains(".pending3.ptc")) {
                    throw IOException("injected pending-slot cleanup failure")
                }
                AndroidAcknowledgedFileSystem.deleteIfExists(file)
            }

            override fun listFiles(directory: File): Array<File>? {
                if (directory.name.endsWith(".commits3")) commitListings.incrementAndGet()
                return AndroidAcknowledgedFileSystem.listFiles(directory)
            }
        }
        store = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES, File::delete, fileSystem = operations)
        var current = initialRuntime()
        store.initialize(current)
        repeat(3) {
            val (commit, successor) = lifecycleCommit(
                current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            )
            store.appendCommit(commit, successor)
            current = successor
        }
        val pending = pendingInput()
        store.stagePendingInput(pending)
        val (consuming, successor) = lifecycleCommit(
            current, ExperimentState.PAUSED, EngineInputKind.SAFETY_FAILURE,
            consumedPendingInputSha256 = pending.encodedSha256,
        )
        failPendingDelete = true
        store.appendCommitConsumingPending(consuming, successor)
        failPendingDelete = false
        // The acknowledged commit consumed the slot, but its file survived as a crash leaves it.
        assertTrue(pendingFile().exists())

        val reopened = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES, File::delete, fileSystem = operations)
        commitListings.set(0)
        assertEquals(successor, reopened.loadRuntime())
        // One residue check and one segment listing: the log was read once, not again for the slot.
        assertEquals(2, commitListings.get())
        assertFalse(pendingFile().exists())
        assertNull(reopened.loadPendingInput())
    }

    @Test
    fun retiredStorageLayoutIsRejectedInsteadOfMigrated() = runBlocking {
        val legacy = legacyFiles().first()
        legacy.parentFile?.mkdirs()
        legacy.writeText("retired")

        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { store.loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.UNSUPPORTED_LAYOUT, failure.failure)
    }

    @Test
    fun commitWithAlteredAuthenticatedContentIsRejectedBeforeWrite() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val (commit, successor) = lifecycleCommit(initial, ExperimentState.CONFIG_VERIFIED)
        val altered = commit.copy(resultingCheckpointSha256 = "9".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.appendCommit(altered, successor) }
        }
        assertTrue(commitSegments().isEmpty())
    }

    @Test
    fun successorThatIsNotTheExactAdvanceIsRejectedBeforeWrite() = runBlocking {
        val initial = initialRuntime()
        store.initialize(initial)
        val checkpoint = RuntimeComponentKey(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "main")
        val (commit, successor) = lifecycleCommit(
            initial,
            ExperimentState.CONFIG_VERIFIED,
            mutations = listOf(RuntimeMutation(checkpoint, RuntimeMutationOperation.UPSERT, "checkpoint-1")),
        )
        val extra = RuntimeComponentKey(RuntimeComponentKind.TIMER, "extra")

        listOf(
            successor.copy(components = successor.components + (extra to "timer")),
            successor.copy(components = successor.components + (checkpoint to "checkpoint-2")),
            successor.copy(components = successor.components - checkpoint),
            successor.copy(lifetimeDataEventCount = successor.lifetimeDataEventCount + 1),
            successor.copy(participantInstanceId = UUID.randomUUID().toString()),
        ).forEach { forged ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.appendCommit(commit, forged) }
            }
        }
        assertTrue(commitSegments().isEmpty())
        store.appendCommit(commit, successor)
        assertEquals(successor, newStore().loadRuntime())
    }

    private enum class AppendFault {
        NONE,
        BEFORE_WRITE,
        TORN;

        fun append(file: File, frame: ByteArray) {
            when (this) {
                NONE -> appendDurably(file, frame)
                BEFORE_WRITE -> throw IOException("injected append failure")
                TORN -> {
                    appendDurably(file, frame.copyOf(frame.size / 2))
                    throw IOException("injected torn append")
                }
            }
        }
    }

    private suspend fun assertLedgerMatchesDisk() {
        val fresh = freshScanBytes()
        assertEquals("open store ledger", fresh, store.storageUsage().usedBytes)
        assertEquals("fresh store scan", fresh, newStore().storageUsage().usedBytes)
    }

    /** Every candidate file and commit-log entry the quota counts, measured independently. */
    private fun freshScanBytes(): Long {
        val root = context.noBackupFilesDir.resolve("experiments")
        val documents = listOf("runtime3", "pending3").flatMap { kind ->
            val name = "${opaqueId()}.$kind.ptc"
            listOf(name, ".$name.pending", ".$name.replacement")
        }.map(root::resolve).filter(File::isFile).sumOf(File::length)
        val commits = root.resolve("${opaqueId()}.commits3").listFiles().orEmpty().sumOf(File::length)
        return documents + commits
    }

    private fun firstFrameSequence(segment: File): Long = RandomAccessFile(segment, "r").use { file ->
        file.seek(SEGMENT_HEADER_BYTES)
        file.readLong()
    }

    private fun snapshotResidue(): List<File> = listOf(".pending", ".replacement")
        .map { suffix -> snapshotFile().resolveSibling(".${snapshotFile().name}$suffix") }
        .filter(File::exists)

    private fun checkpointStagingFile(suffix: String): File =
        snapshotFile().resolveSibling(".${snapshotFile().name}.$suffix")

    private fun assertSnapshotRecoveryFails() {
        val failure = assertThrows(StudyStoreRecoveryException::class.java) {
            runBlocking { newStore().loadRuntime() }
        }
        assertEquals(StudyStoreRecoveryFailure.SNAPSHOT_INVALID, failure.failure)
    }

    private fun encryptSnapshotForTest(plaintext: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey("$ENGINE_KEY_ALIAS_PREFIX${opaqueId()}", null) as SecretKey
        val header = "PTCRUN03".toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(header + opaqueId().toByteArray(Charsets.US_ASCII))
        }
        return header + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun pendingFile(): File = context.noBackupFilesDir.resolve("experiments")
        .resolve("${opaqueId()}.pending3.ptc")

    private fun frameBytes(commit: EngineCommit): Long =
        FRAME_OVERHEAD_BYTES + EngineDataJsonCodec.encodeCommit(commit).size

    private fun payloadCommit(current: RuntimeDocument, payloadBytes: Int): Pair<EngineCommit, RuntimeDocument> =
        lifecycleCommit(
            current, current.state, inputKind = EngineInputKind.SOURCE_OBSERVATION,
            mutations = listOf(
                RuntimeMutation(
                    RuntimeComponentKey(RuntimeComponentKind.RESOURCE, "payload"),
                    RuntimeMutationOperation.UPSERT,
                    "x".repeat(payloadBytes),
                ),
            ),
        )

    private fun lifecycleCommit(
        current: RuntimeDocument,
        state: ExperimentState,
        inputKind: EngineInputKind = EngineInputKind.LIFECYCLE_COMMAND,
        consumedPendingInputSha256: String? = null,
        events: List<RecordedEvent> = emptyList(),
        observations: List<SourceObservation> = emptyList(),
        mutations: List<RuntimeMutation> = emptyList(),
        uploadedThroughCommit: Long = current.uploadedThroughCommit,
    ): Pair<EngineCommit, RuntimeDocument> {
        val projection = RuntimeProjection(
            state = state,
            revision = current.revision + 1,
            nextCommitSequence = current.nextCommitSequence + 1,
            nextObservationSequence = observations.lastOrNull()?.observationSequence?.plus(1)
                ?: current.nextObservationSequence,
            nextEventSequence = events.lastOrNull()?.sequenceNumber?.plus(1) ?: current.nextEventSequence,
            sourceCheckpoints = current.sourceCheckpoints,
            clockCheckpoint = current.clockCheckpoint,
            activeConditionEpoch = current.activeConditionEpoch,
            lifetimeDataEventCount = current.lifetimeDataEventCount + events.size,
            uploadedThroughCommit = uploadedThroughCommit,
            evaluatedThroughCommit = current.revision + 1,
            retainedFromCommit = current.retainedFromCommit,
        )
        val commit = EngineCommit(
            commitSequence = current.nextCommitSequence,
            previousCommitSha256 = current.lastCommitSha256,
            inputKind = inputKind,
            consumedPendingInputSha256 = consumedPendingInputSha256,
            sourceObservations = observations,
            events = events,
            mutations = mutations,
            committedAt = TIME,
            successorProjection = projection,
            resultingCheckpointSha256 = "1".repeat(64),
            commitSha256 = GENESIS_DIGEST,
        ).withComputedDigest()
        return commit to current.advance(commit)
    }

    private fun pendingInput(): PendingEngineInput = PendingEngineInput(
        conditionEpochId = EPOCH_ID,
        submissions = listOf(
            PendingSourceSubmission(
                sourceId = SOURCE_ID,
                schemaVersion = 1,
                resourceGeneration = 1,
                producerOrdinal = 0,
                admissionKind = ObservationAdmissionKind.NORMAL,
                events = listOf(
                    EventDraft(
                        type = EventTypeKey(SOURCE_ID, 1, "ACTIVITY_RESUMED"),
                        observedTime = TIME,
                        fields = mapOf("package_name" to "com.example.target"),
                    ),
                ),
                coverage = null,
            ),
        ),
        stagedAt = TIME,
        encodedSha256 = GENESIS_DIGEST,
    ).withComputedDigest()

    private fun initialRuntime() = RuntimeDocument.initial(
        experimentId = experimentId,
        configurationId = "config-001",
        configurationSha256 = "a".repeat(64),
        activityTokenKeyBase64Url = "A".repeat(43),
        participantInstanceId = "018f3ca4-7a82-4f47-8b5c-a4415b9b2290",
    )

    private fun newStore() = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES)

    private suspend fun EncryptedExperimentStore.readCommits(
        fromCommitInclusive: Long,
        throughCommitInclusive: Long,
        consume: (EngineCommit) -> Unit,
    ) = withReadSnapshot { snapshot ->
        snapshot.readCommits(fromCommitInclusive, throughCommitInclusive) {
            consume(it)
            true
        }
    }

    private fun snapshotFile(): File = context.noBackupFilesDir.resolve("experiments")
        .resolve("${opaqueId()}.runtime3.ptc")

    private fun commitSegments(): List<File> {
        val commits = context.noBackupFilesDir.resolve("experiments").resolve("${opaqueId()}.commits3")
        return commits.listFiles()?.filter { it.name.matches(Regex("commits-[0-9]{8}\\.ptcs")) }
            .orEmpty()
            .sortedBy(File::getName)
    }

    private fun corruptCommitCiphertext(targetSequence: Long) {
        commitSegments().forEach { segment ->
            RandomAccessFile(segment, "rw").use { file ->
                file.seek(SEGMENT_HEADER_BYTES)
                while (file.filePointer < file.length()) {
                    val sequence = file.readLong()
                    val ciphertextBytes = file.readInt()
                    val ciphertextOffset = file.filePointer + IV_BYTES
                    if (sequence == targetSequence) {
                        file.seek(ciphertextOffset)
                        val original = file.readByte().toInt()
                        file.seek(ciphertextOffset)
                        file.writeByte(original xor 0x01)
                        file.fd.sync()
                        return
                    }
                    file.seek(ciphertextOffset + ciphertextBytes + COMMIT_DIGEST_BYTES)
                }
            }
        }
        error("Commit $targetSequence was not found")
    }

    private fun legacyFiles(): List<File> {
        val root = context.noBackupFilesDir.resolve("experiments")
        return listOf(
            root.resolve("${opaqueId()}.metadata.ptc"),
            root.resolve("${opaqueId()}.transaction.ptc"),
            root.resolve("${opaqueId()}.events"),
        )
    }

    private fun opaqueId(): String = MessageDigest.getInstance("SHA-256")
        .digest(experimentId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val QUOTA_BYTES = 128L * 1024 * 1024
        const val MINIMUM_QUOTA_BYTES = 8L shl 20
        const val SNAPSHOT_RESERVE_BYTES = 4L shl 20
        const val LARGE_PAYLOAD_BYTES = 256 * 1024
        const val SMALL_SEGMENT_BYTES = 2_048L
        const val SEGMENTED_COMMITS = 16
        const val STEADY_APPENDS = 32
        const val FRAME_OVERHEAD_BYTES = 8L + 4L + 12L + 32L + 16L
        const val FIRST_CIPHERTEXT_OFFSET = 12L + 8L + 4L + 12L
        const val SEGMENT_HEADER_BYTES = 12L
        const val IV_BYTES = 12L
        const val COMMIT_DIGEST_BYTES = 32L
        const val STREAMING_RANGE_COMMITS = 256
        val TIME = ResearchTime(1_000, 2_000, "boot-a")
        val SOURCE_ID = EventSourceId("usage_events.v1")
        val EPOCH_ID = ConditionEpochId("018f3ca4-7a82-4f47-8b5c-a4415b9b2290")

        fun appendDurably(file: File, bytes: ByteArray) {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write(bytes)
                output.fd.sync()
            }
        }
    }
}
