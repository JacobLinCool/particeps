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
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryException
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryFailure
import cool.jacoblin.particeps.core.model.withComputedDigest
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun lifecycleCommit(
        current: RuntimeDocument,
        state: ExperimentState,
        inputKind: EngineInputKind = EngineInputKind.LIFECYCLE_COMMAND,
        consumedPendingInputSha256: String? = null,
        events: List<RecordedEvent> = emptyList(),
        observations: List<SourceObservation> = emptyList(),
        mutations: List<RuntimeMutation> = emptyList(),
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
            uploadedThroughCommit = current.uploadedThroughCommit,
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
        const val FIRST_CIPHERTEXT_OFFSET = 12L + 8L + 4L + 12L
        const val SEGMENT_HEADER_BYTES = 12L
        const val IV_BYTES = 12L
        const val COMMIT_DIGEST_BYTES = 32L
        const val STREAMING_RANGE_COMMITS = 256
        val TIME = ResearchTime(1_000, 2_000, "boot-a")
        val SOURCE_ID = EventSourceId("usage_events.v1")
        val EPOCH_ID = ConditionEpochId("018f3ca4-7a82-4f47-8b5c-a4415b9b2290")
    }
}
