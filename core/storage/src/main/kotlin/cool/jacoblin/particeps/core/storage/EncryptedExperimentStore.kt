package cool.jacoblin.particeps.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineCommitIntegrity
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryException
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryFailure
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Layout-3 encrypted study store. Complete [EngineCommit] frames are the incremental truth;
 * snapshots are bounded recovery caches and can only advance by replaying authenticated frames.
 */
class EncryptedExperimentStore internal constructor(
    context: Context,
    private val experimentId: String,
    private val maximumLocalBytes: Long,
    private val deleteSegment: (File) -> Boolean,
    private val fileSystem: AcknowledgedFileSystem = AndroidAcknowledgedFileSystem,
    private val appendFrame: (File, ByteArray) -> Unit = ::appendFrameDurably,
) : StudyStore {
    constructor(
        context: Context,
        experimentId: String,
        maximumLocalBytes: Long,
    ) : this(
        context = context,
        experimentId = experimentId,
        maximumLocalBytes = maximumLocalBytes,
        deleteSegment = File::delete,
    )

    private val mutex = Mutex()
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val opaqueId = sha256(experimentId.toByteArray()).toHex()
    private val keyAlias = "particeps-engine-$opaqueId"
    private val rootDirectory = context.noBackupFilesDir.resolve(STORAGE_DIRECTORY)
    private val snapshotFile = AcknowledgedAtomicFile(
        rootDirectory.resolve("$opaqueId.runtime3.ptc"),
        fileSystem,
    )
    private val pendingFile = AcknowledgedAtomicFile(
        rootDirectory.resolve("$opaqueId.pending3.ptc"),
        fileSystem,
    )
    private val commitDirectory = rootDirectory.resolve("$opaqueId.commits3")
    private var runtime: RuntimeDocument? = null
    private var pending: PendingEngineInput? = null

    init {
        require(maximumLocalBytes in MINIMUM_LOCAL_BYTES..MAXIMUM_LOCAL_BYTES) {
            "Invalid storage quota"
        }
    }

    override suspend fun loadRuntime(): RuntimeDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!snapshotFile.exists()) {
                if (legacyStorageExists()) {
                    throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.UNSUPPORTED_LAYOUT)
                }
                require(segmentEntries().isEmpty()) { "Commit segments exist without a runtime snapshot" }
                require(!pendingFile.exists()) { "Pending input exists without a runtime snapshot" }
                runtime = null
                pending = null
                return@withLock null
            }
            val key = existingKey()
                ?: throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.KEY_UNAVAILABLE)
            val snapshot = recoverSnapshot(key)
            val recovered = try {
                replayAfter(snapshot, key, recoverTail = true)
            } catch (failure: Throwable) {
                if (failure is StudyStoreRecoveryException) throw failure
                throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.COMMIT_LOG_INVALID, failure)
            }
            val recoveredPending = recoverPending(key, recovered)
            if (recovered != snapshot || snapshotFile.candidates().size != 1) {
                writeSnapshot(recovered, key)
            }
            runtime = recovered
            pending = recoveredPending
            recovered
        }
    }

    override suspend fun initialize(runtime: RuntimeDocument) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(runtime.revision == 0L) { "Initial runtime must be at the genesis revision" }
            require(runtime.experimentId == experimentId) { "Experiment ID mismatch" }
            require(!snapshotFile.exists() && !pendingFile.exists() && segmentEntries().isEmpty()) {
                "Study storage is already initialized"
            }
            require(!legacyStorageExists()) { "A retired study storage layout is present" }
            val key = getOrCreateKey()
            writeSnapshot(runtime, key)
            this@EncryptedExperimentStore.runtime = runtime
            pending = null
        }
    }

    override suspend fun appendCommit(
        commit: EngineCommit,
        successor: RuntimeDocument,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(commit.consumedPendingInputSha256 == null) {
                "A normal append cannot consume the pending-input slot"
            }
            appendValidated(commit, successor, consumePending = false)
        }
    }

    override suspend fun stagePendingInput(input: PendingEngineInput) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(runtime) { "Study storage is not initialized" }
            require(pending == null && !pendingFile.exists()) { "The pending-input slot is occupied" }
            EngineCommitIntegrity.verify(input)
            val encoded = EngineDataJsonCodec.encodePending(input)
            require(encoded.size <= MAXIMUM_PENDING_BYTES) { "Pending input exceeds its bounded slot" }
            val key = existingKey() ?: error("Encrypted experiment key is unavailable")
            pendingFile.write(encryptDocument(encoded, key, PENDING_HEADER))
            pending = input
        }
    }

    override suspend fun replacePendingInput(
        expectedSha256: String,
        input: PendingEngineInput,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(runtime) { "Study storage is not initialized" }
            val current = pending ?: error("The pending-input slot is empty")
            require(current.encodedSha256 == expectedSha256) { "The pending-input generation is stale" }
            require(pendingFile.exists()) { "The pending-input file is missing" }
            require(input.conditionEpochId == current.conditionEpochId && input.stagedAt == current.stagedAt) {
                "A pending-input replacement changed its barrier identity"
            }
            require(
                input.submissions.size == current.submissions.size + 1 &&
                    input.submissions.take(current.submissions.size) == current.submissions,
            ) { "A pending-input replacement must append exactly one submission" }
            EngineCommitIntegrity.verify(input)
            val encoded = EngineDataJsonCodec.encodePending(input)
            require(encoded.size <= MAXIMUM_PENDING_BYTES) { "Pending input exceeds its bounded slot" }
            val key = existingKey() ?: error("Encrypted experiment key is unavailable")
            pendingFile.write(encryptDocument(encoded, key, PENDING_HEADER))
            pending = input
        }
    }

    override suspend fun loadPendingInput(): PendingEngineInput? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireNotNull(runtime) { "Study storage is not initialized" }
            pending ?: run {
                val key = existingKey() ?: error("Encrypted experiment key is unavailable")
                recoverPending(key, current).also { pending = it }
            }
        }
    }

    override suspend fun appendCommitConsumingPending(
        commit: EngineCommit,
        successor: RuntimeDocument,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock { appendValidated(commit, successor, consumePending = true) }
    }

    override suspend fun readCommits(
        fromCommitInclusive: Long,
        throughCommitInclusive: Long,
        consume: (EngineCommit) -> Unit,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireNotNull(runtime) { "Study storage is not initialized" }
            require(throughCommitInclusive in 0..current.revision) { "Invalid commit snapshot boundary" }
            require(fromCommitInclusive in current.retainedFromCommit..(throughCommitInclusive + 1)) {
                "Requested commits were reclaimed"
            }
            if (fromCommitInclusive > throughCommitInclusive) return@withLock
            val key = existingKey() ?: error("Encrypted experiment key is unavailable")
            // Keep the store locked while the synchronous consumer runs so append/eviction cannot
            // change the selected snapshot. Only one decrypted commit is live at a time.
            readCommitRange(fromCommitInclusive, throughCommitInclusive, key, consume)
        }
    }

    override suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        mutex.withLock { StorageUsage(storageBytes(), maximumLocalBytes) }
    }

    override suspend fun evictThrough(
        runtime: RuntimeDocument,
        targetBytes: Long,
    ): RuntimeDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = requireNotNull(this@EncryptedExperimentStore.runtime) {
                "Study storage is not initialized"
            }
            require(runtime == current) { "Runtime changed before eviction" }
            require(pending == null && !pendingFile.exists()) { "Cannot evict while input is staged" }
            require(targetBytes >= 0) { "Target size must be non-negative" }
            val safeThrough = minOf(runtime.uploadedThroughCommit, runtime.evaluatedThroughCommit)
            if (safeThrough < runtime.retainedFromCommit || storageBytes() <= targetBytes) {
                return@withLock runtime
            }
            val summaries = segmentSummaries()
            var projectedBytes = storageBytes()
            val removable = mutableListOf<SegmentSummary>()
            for (summary in summaries) {
                if (summary.lastCommit > safeThrough || projectedBytes <= targetBytes) break
                removable += summary
                projectedBytes -= summary.bytes
            }
            if (removable.isEmpty()) return@withLock runtime

            val retainedFrom = removable.last().lastCommit + 1
            val updated = runtime.copy(retainedFromCommit = retainedFrom)
            val key = existingKeyOrThrow()
            // Publish the safe logical floor before unlinking. A crash can leave harmless delivered
            // prefix frames, never a floor that references missing unacknowledged data.
            writeSnapshot(updated, key)
            this@EncryptedExperimentStore.runtime = updated
            for (summary in removable) {
                try {
                    if (summary.segment.file.exists() &&
                        (!deleteSegment(summary.segment.file) || summary.segment.file.exists())
                    ) {
                        break
                    }
                    fileSystem.syncDirectory(commitDirectory)
                } catch (_: Exception) {
                    break
                }
            }
            updated
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            snapshotFile.delete()
            pendingFile.delete()
            segmentEntries().forEach { entry ->
                fileSystem.deleteIfExists(entry)
                check(!fileSystem.exists(entry)) { "Cannot delete commit storage entry" }
            }
            if (fileSystem.exists(commitDirectory)) {
                fileSystem.syncDirectory(commitDirectory)
                fileSystem.deleteIfExists(commitDirectory)
                check(!fileSystem.exists(commitDirectory)) { "Cannot delete commit directory" }
                fileSystem.syncDirectory(rootDirectory)
            }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            runtime = null
            pending = null
        }
    }

    private fun appendValidated(
        commit: EngineCommit,
        successor: RuntimeDocument,
        consumePending: Boolean,
    ) {
        val current = requireNotNull(runtime) { "Study storage is not initialized" }
        val staged = pending
        if (consumePending) {
            val input = requireNotNull(staged) { "No pending input is staged" }
            require(commit.consumedPendingInputSha256 == input.encodedSha256) {
                "Commit does not consume the staged input"
            }
        } else {
            require(staged == null && !pendingFile.exists()) {
                "Only the containment path may append while input is staged"
            }
        }
        validateSuccessor(current, commit, successor)
        val encoded = EngineDataJsonCodec.encodeCommit(commit)
        require(encoded.size <= MAXIMUM_COMMIT_BYTES) { "Engine commit exceeds the frame contract" }
        val key = existingKeyOrThrow()
        val encrypted = encryptCommit(encoded, commit, key)
        val frame = encodeFrame(commit, encrypted)
        require(storageBytes() + frame.size <= maximumLocalBytes - SNAPSHOT_RESERVE_BYTES) {
            "Study commit quota exceeded"
        }
        val segment = writableSegment(frame.size)
        try {
            appendFrame(segment.file, frame)
        } catch (failure: Throwable) {
            var acknowledged = false
            runCatching {
                scanFrames(
                    key = key,
                    recoverTail = true,
                    decryptFromSequence = commit.commitSequence,
                    throughSequenceInclusive = commit.commitSequence,
                ) { scanned ->
                    acknowledged = scanned.sequence == commit.commitSequence && scanned.commit == commit
                }
            }.onFailure { acknowledged = false }
            if (!acknowledged) throw failure
        }

        // The frame is now acknowledged. A cache write or pending-slot cleanup may not turn that
        // durable fact into a reported append failure that invites a duplicate reducer input.
        runtime = successor
        runCatching { writeSnapshot(successor, key) }
        if (consumePending) {
            runCatching { pendingFile.delete() }
            pending = null
        }
    }

    private fun validateSuccessor(
        current: RuntimeDocument,
        commit: EngineCommit,
        successor: RuntimeDocument,
    ) {
        EngineCommitIntegrity.verify(commit)
        require(commit.commitSequence == current.nextCommitSequence) { "Non-contiguous commit append" }
        require(commit.previousCommitSha256 == current.lastCommitSha256) { "Commit chain mismatch" }
        require(successor == current.advance(commit)) { "Runtime is not the exact commit successor" }
        require(successor.projection() == commit.successorProjection) { "Successor projection mismatch" }
        require(successor.experimentId == experimentId) { "Experiment ID mismatch" }
        validateCommitRanges(current, commit, successor)
    }

    private fun validateCommitRanges(
        current: RuntimeDocument,
        commit: EngineCommit,
        successor: RuntimeDocument,
    ) {
        if (commit.events.isEmpty()) {
            require(successor.nextEventSequence == current.nextEventSequence) {
                "Empty commit advanced the event sequence"
            }
        } else {
            require(commit.events.first().sequenceNumber == current.nextEventSequence) {
                "Commit does not start at the next event sequence"
            }
            require(successor.nextEventSequence == commit.events.last().sequenceNumber + 1) {
                "Successor event sequence does not follow the commit"
            }
        }
        if (commit.sourceObservations.isEmpty()) {
            require(successor.nextObservationSequence == current.nextObservationSequence) {
                "Commit without observations advanced the observation sequence"
            }
        } else {
            require(commit.sourceObservations.first().observationSequence == current.nextObservationSequence) {
                "Commit does not start at the next observation sequence"
            }
            require(successor.nextObservationSequence ==
                commit.sourceObservations.last().observationSequence + 1
            ) { "Successor observation sequence does not follow the commit" }
        }
        val eventsBySequence = commit.events.associateBy { it.sequenceNumber }
        commit.sourceObservations.forEach { observation ->
            if (observation.eventCount == 0) return@forEach
            val first = requireNotNull(observation.firstEventSequence)
            val last = requireNotNull(observation.lastEventSequence)
            (first..last).forEach { sequence ->
                val event = requireNotNull(eventsBySequence[sequence]) {
                    "Observation references an event outside the commit"
                }
                require(event.type.sourceId == observation.sourceId &&
                    event.type.schemaVersion == observation.schemaVersion &&
                    event.conditionEpochId == observation.conditionEpochId
                ) { "Observation event contract mismatch" }
            }
        }
    }

    private fun recoverSnapshot(key: SecretKey): RuntimeDocument {
        val candidates = try {
            snapshotFile.candidates().map { candidate ->
                EngineDataJsonCodec.decodeRuntime(
                    decryptDocument(candidate.bytes, key, RUNTIME_HEADER, MAXIMUM_SNAPSHOT_BYTES),
                ).also { require(it.experimentId == experimentId) { "Encrypted experiment ID mismatch" } }
            }
        } catch (failure: Throwable) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.SNAPSHOT_INVALID, failure)
        }
        if (candidates.isEmpty()) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.SNAPSHOT_INVALID)
        }
        val revision = candidates.maxOf(RuntimeDocument::revision)
        val newest = candidates.filter { it.revision == revision }
        if (newest.distinct().size != 1) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.SNAPSHOT_INVALID)
        }
        return newest.single()
    }

    private fun recoverPending(
        key: SecretKey,
        current: RuntimeDocument,
    ): PendingEngineInput? {
        if (!pendingFile.exists()) return null
        val candidates = try {
            pendingFile.candidates().map { candidate ->
                EngineDataJsonCodec.decodePending(
                    decryptDocument(candidate.bytes, key, PENDING_HEADER, MAXIMUM_PENDING_BYTES),
                ).also(EngineCommitIntegrity::verify)
            }.distinct()
        } catch (failure: Throwable) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.PENDING_INPUT_INVALID, failure)
        }
        val newest = candidates.filter { candidate ->
            candidates.all { prior -> prior.isPendingPrefixOf(candidate) }
        }
        if (newest.size != 1) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.PENDING_INPUT_INVALID)
        }
        val input = newest.single()
        val consumed = findCommitByConsumedPendingDigest(input.encodedSha256, current, key)
        if (consumed) {
            runCatching { pendingFile.delete() }
            return null
        }
        return input
    }

    private fun PendingEngineInput.isPendingPrefixOf(other: PendingEngineInput): Boolean =
        conditionEpochId == other.conditionEpochId &&
            stagedAt == other.stagedAt &&
            submissions.size <= other.submissions.size &&
            other.submissions.take(submissions.size) == submissions

    private fun findCommitByConsumedPendingDigest(
        digest: String,
        current: RuntimeDocument,
        key: SecretKey,
    ): Boolean {
        if (current.revision == 0L || current.retainedFromCommit > current.revision) return false
        var found = false
        readCommitRange(current.retainedFromCommit, current.revision, key) { commit ->
            if (commit.consumedPendingInputSha256 == digest) found = true
        }
        return found
    }

    private fun replayAfter(
        snapshot: RuntimeDocument,
        key: SecretKey,
        recoverTail: Boolean,
    ): RuntimeDocument {
        var recovered = snapshot
        var expectedRetainedSequence = snapshot.retainedFromCommit
        var previousRetainedDigest: String? = null
        var snapshotBoundarySeen = snapshot.revision < snapshot.retainedFromCommit
        scanFrames(
            key = key,
            recoverTail = recoverTail,
            decryptFromSequence = snapshot.retainedFromCommit,
        ) { frame ->
            if (frame.sequence < snapshot.retainedFromCommit) return@scanFrames
            require(frame.sequence == expectedRetainedSequence) { "Retained commit sequence gap" }
            val commit = requireNotNull(frame.commit) { "Retained commit was not authenticated" }
            previousRetainedDigest?.let { previous ->
                require(commit.previousCommitSha256 == previous) { "Retained commit chain mismatch" }
            }
            previousRetainedDigest = commit.commitSha256
            expectedRetainedSequence = Math.addExact(expectedRetainedSequence, 1L)
            if (commit.commitSequence == snapshot.revision) {
                require(commit.commitSha256 == snapshot.lastCommitSha256) {
                    "Snapshot does not name the durable commit boundary"
                }
                snapshotBoundarySeen = true
            } else if (commit.commitSequence > snapshot.revision) {
                require(snapshotBoundarySeen) { "Durable suffix precedes the snapshot boundary" }
                require(commit.commitSequence == recovered.nextCommitSequence) { "Commit sequence gap" }
                require(commit.previousCommitSha256 == recovered.lastCommitSha256) { "Commit chain mismatch" }
                recovered = recovered.advance(commit)
            }
        }
        require(snapshotBoundarySeen) { "Retained log does not reach the snapshot boundary" }
        return recovered
    }

    private fun readCommitRange(
        from: Long,
        through: Long,
        key: SecretKey,
        consume: (EngineCommit) -> Unit,
    ) {
        var expectedSequence = from
        var consumed = 0L
        var previousDigest: String? = null
        scanFrames(
            key = key,
            recoverTail = false,
            decryptFromSequence = from,
            throughSequenceInclusive = through,
        ) { frame ->
            if (frame.sequence < from) return@scanFrames
            require(frame.sequence == expectedSequence) { "Commit range is unavailable" }
            val commit = requireNotNull(frame.commit) { "Commit range was not authenticated" }
            previousDigest?.let { previous ->
                require(commit.previousCommitSha256 == previous) {
                    "Commit range is not one authenticated chain"
                }
            }
            consume(commit)
            previousDigest = commit.commitSha256
            consumed = Math.addExact(consumed, 1L)
            if (frame.sequence < through) expectedSequence = Math.addExact(expectedSequence, 1L)
        }
        require(consumed == Math.addExact(Math.subtractExact(through, from), 1L)) {
            "Commit range is unavailable"
        }
    }

    private fun scanFrames(
        key: SecretKey,
        recoverTail: Boolean,
        decryptFromSequence: Long,
        throughSequenceInclusive: Long? = null,
        consume: (FrameResult) -> Unit,
    ) {
        repairSegmentResidue()
        val segments = segments()
        segments.zipWithNext().forEach { (left, right) ->
            require(right.index == left.index + 1) { "Commit segment index gap" }
        }
        var previousSequence: Long? = null
        var reachedUpperBound = false
        segments.forEachIndexed { segmentPosition, segment ->
            if (reachedUpperBound) return@forEachIndexed
            val isLastSegment = segmentPosition == segments.lastIndex
            var truncateAt: Long? = null
            DataInputStream(BufferedInputStream(FileInputStream(segment.file), SCAN_BUFFER_BYTES)).use { input ->
                validateSegmentHeader(input, segment.index)
                var offset = SEGMENT_HEADER_BYTES.toLong()
                val length = segment.file.length()
                while (offset < length) {
                    val frameStart = offset
                    if (length - offset < FRAME_FIXED_BYTES) {
                        if (recoverTail && isLastSegment) {
                            truncateAt = frameStart
                            break
                        }
                        throw EOFException("Commit frame header is torn")
                    }
                    val sequence = input.readLong()
                    val ciphertextBytes = input.readInt()
                    offset += Long.SIZE_BYTES + Int.SIZE_BYTES
                    require(ciphertextBytes in MINIMUM_COMMIT_CIPHERTEXT_BYTES..MAXIMUM_COMMIT_CIPHERTEXT_BYTES) {
                        "Invalid encrypted commit size"
                    }
                    val remainingBytes = IV_BYTES.toLong() + ciphertextBytes + COMMIT_DIGEST_BYTES
                    if (length - offset < remainingBytes) {
                        if (recoverTail && isLastSegment) {
                            truncateAt = frameStart
                            break
                        }
                        throw EOFException("Commit frame is torn")
                    }
                    val iv = ByteArray(IV_BYTES).also(input::readFully)
                    val shouldDecrypt = sequence >= decryptFromSequence &&
                        (throughSequenceInclusive == null || sequence <= throughSequenceInclusive)
                    val ciphertext = if (shouldDecrypt) {
                        ByteArray(ciphertextBytes).also(input::readFully)
                    } else {
                        input.skipFully(ciphertextBytes)
                        null
                    }
                    val footer = ByteArray(COMMIT_DIGEST_BYTES).also(input::readFully)
                    offset += remainingBytes
                    val footerHex = footer.toHex()
                    previousSequence?.let { previous ->
                        require(sequence == previous + 1) { "Commit sequence is not contiguous" }
                    }
                    previousSequence = sequence
                    val commit = ciphertext?.let {
                        val plaintext = decryptCommit(iv, it, sequence, footerHex, key)
                        EngineDataJsonCodec.decodeCommit(plaintext).also { decoded ->
                            require(decoded.commitSequence == sequence && decoded.commitSha256 == footerHex) {
                                "Encrypted commit frame identity mismatch"
                            }
                            EngineCommitIntegrity.verify(decoded)
                        }
                    }
                    consume(FrameResult(sequence, footerHex, commit))
                    if (throughSequenceInclusive != null && sequence >= throughSequenceInclusive) {
                        reachedUpperBound = true
                        break
                    }
                }
            }
            truncateAt?.let { truncateSegmentTail(segment.file, it) }
        }
    }

    private fun writableSegment(frameBytes: Int): Segment {
        fileSystem.ensureDirectory(commitDirectory)
        var segment = segments().lastOrNull() ?: createSegment(1)
        if (segment.file.length() + frameBytes > MAXIMUM_SEGMENT_BYTES) {
            segment = createSegment(segment.index + 1)
        }
        return segment
    }

    private fun createSegment(index: Int): Segment {
        require(index in 1..MAXIMUM_SEGMENT_INDEX) { "Commit segment index exhausted" }
        val file = commitDirectory.resolve("commits-${index.toString().padStart(8, '0')}.ptcs")
        require(!file.exists()) { "Commit segment already exists" }
        val header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES)
            .put(SEGMENT_HEADER)
            .putInt(index)
            .array()
        AcknowledgedAtomicFile(file, fileSystem).write(header)
        return Segment(index, file)
    }

    private fun segmentSummaries(): List<SegmentSummary> = segments().map { segment ->
        val range = segmentCommitHeaderRange(segment)
        require(!range.isEmpty()) { "Empty commit segment cannot be reclaimed" }
        SegmentSummary(
            segment = segment,
            firstCommit = range.first,
            lastCommit = range.last,
            bytes = segment.file.length(),
        )
    }

    private fun segmentCommitHeaderRange(segment: Segment): LongRange {
        var first: Long? = null
        var last: Long? = null
        DataInputStream(BufferedInputStream(FileInputStream(segment.file))).use { input ->
            validateSegmentHeader(input, segment.index)
            val length = segment.file.length()
            var offset = SEGMENT_HEADER_BYTES.toLong()
            while (offset < length) {
                val sequence = input.readLong()
                val ciphertextBytes = input.readInt()
                require(ciphertextBytes in MINIMUM_COMMIT_CIPHERTEXT_BYTES..MAXIMUM_COMMIT_CIPHERTEXT_BYTES)
                input.skipFully(IV_BYTES + ciphertextBytes + COMMIT_DIGEST_BYTES)
                offset += FRAME_FIXED_BYTES + ciphertextBytes
                if (first == null) first = sequence
                last = sequence
            }
        }
        return first?.let { it..requireNotNull(last) } ?: LongRange.EMPTY
    }

    private fun repairSegmentResidue() {
        val residueIndices = segmentEntries().mapNotNull { file ->
            (SEGMENT_PENDING_PATTERN.matchEntire(file.name)
                ?: SEGMENT_REPLACEMENT_PATTERN.matchEntire(file.name))
                ?.groupValues?.get(1)?.toInt()
        }.toSet()
        residueIndices.forEach { index ->
            val base = commitDirectory.resolve("commits-${index.toString().padStart(8, '0')}.ptcs")
            val atomic = AcknowledgedAtomicFile(base, fileSystem)
            val candidates = atomic.candidates()
            require(candidates.isNotEmpty()) { "Commit segment residue has no candidate" }
            candidates.forEach { validateSegmentHeader(it.bytes, index) }
            val authoritative = candidates.maxBy { it.bytes.size }
            require(candidates.all { authoritative.bytes.hasPrefix(it.bytes) }) {
                "Commit segment candidates do not share one append-only history"
            }
            atomic.write(authoritative.bytes)
        }
    }

    private fun segments(): List<Segment> = segmentEntries().map { file ->
        val residue = SEGMENT_PENDING_PATTERN.matchEntire(file.name)
            ?: SEGMENT_REPLACEMENT_PATTERN.matchEntire(file.name)
        residue?.let { throw IncompleteAtomicWrite(commitDirectory.resolve("commits-${it.groupValues[1]}.ptcs")) }
        val match = requireNotNull(SEGMENT_PATTERN.matchEntire(file.name)) {
            "Unexpected entry in commit storage: ${file.name}"
        }
        require(file.isFile) { "Commit segment is not a regular file" }
        Segment(match.groupValues[1].toInt(), file)
    }.sortedBy(Segment::index)

    private fun segmentEntries(): List<File> {
        if (!fileSystem.exists(commitDirectory)) return emptyList()
        require(fileSystem.isDirectory(commitDirectory)) { "Commit storage is not a directory" }
        return checkNotNull(fileSystem.listFiles(commitDirectory)) { "Cannot enumerate commit storage" }.toList()
    }

    private fun encodeFrame(commit: EngineCommit, encrypted: EncryptedCommit): ByteArray =
        ByteBuffer.allocate(FRAME_FIXED_BYTES + encrypted.ciphertext.size)
            .putLong(commit.commitSequence)
            .putInt(encrypted.ciphertext.size)
            .put(encrypted.iv)
            .put(encrypted.ciphertext)
            .put(commit.commitSha256.hexToBytes())
            .array()

    private fun encryptCommit(
        plaintext: ByteArray,
        commit: EngineCommit,
        key: SecretKey,
    ): EncryptedCommit {
        val aad = commitAad(commit.commitSequence, commit.commitSha256)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
        }
        check(cipher.iv.size == IV_BYTES) { "Android Keystore returned an invalid GCM IV" }
        return EncryptedCommit(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decryptCommit(
        iv: ByteArray,
        ciphertext: ByteArray,
        sequence: Long,
        commitSha256: String,
        key: SecretKey,
    ): ByteArray = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        updateAAD(commitAad(sequence, commitSha256))
        doFinal(ciphertext)
    }

    private fun commitAad(sequence: Long, commitSha256: String): ByteArray = ByteBuffer.allocate(
        SEGMENT_HEADER.size + opaqueId.length + Long.SIZE_BYTES + COMMIT_DIGEST_BYTES,
    )
        .put(SEGMENT_HEADER)
        .put(opaqueId.toByteArray(Charsets.US_ASCII))
        .putLong(sequence)
        .put(commitSha256.hexToBytes())
        .array()

    private fun encryptDocument(
        plaintext: ByteArray,
        key: SecretKey,
        header: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(documentAad(header))
        }
        check(cipher.iv.size == IV_BYTES) { "Android Keystore returned an invalid GCM IV" }
        return ByteBuffer.allocate(header.size + IV_BYTES + cipher.getOutputSize(plaintext.size))
            .put(header)
            .put(cipher.iv)
            .put(cipher.doFinal(plaintext))
            .array()
    }

    private fun decryptDocument(
        encoded: ByteArray,
        key: SecretKey,
        header: ByteArray,
        maximumPlaintextBytes: Int,
    ): ByteArray {
        require(encoded.size in header.size + IV_BYTES + GCM_TAG_BYTES + 2..
            header.size + IV_BYTES + GCM_TAG_BYTES + maximumPlaintextBytes
        ) { "Encrypted document has an invalid size" }
        val buffer = ByteBuffer.wrap(encoded)
        val actualHeader = ByteArray(header.size).also(buffer::get)
        require(actualHeader.contentEquals(header)) { "Unsupported encrypted storage document" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(documentAad(header))
            doFinal(ciphertext)
        }.also { require(it.size <= maximumPlaintextBytes) { "Decrypted document is too large" } }
    }

    private fun documentAad(header: ByteArray): ByteArray = header + opaqueId.toByteArray(Charsets.US_ASCII)

    private fun writeSnapshot(value: RuntimeDocument, key: SecretKey) {
        val encoded = EngineDataJsonCodec.encodeRuntime(value)
        require(encoded.size <= MAXIMUM_SNAPSHOT_BYTES) { "Runtime snapshot exceeds its bound" }
        snapshotFile.write(encryptDocument(encoded, key, RUNTIME_HEADER))
    }

    private fun storageBytes(): Long {
        val snapshotBytes = snapshotFile.candidates().sumOf { it.bytes.size.toLong() }
        val pendingBytes = if (pendingFile.exists()) pendingFile.candidates().sumOf { it.bytes.size.toLong() } else 0L
        return snapshotBytes + pendingBytes + segmentEntries().sumOf(File::length)
    }

    private fun legacyStorageExists(): Boolean =
        rootDirectory.resolve("$opaqueId.metadata.ptc").exists() ||
            rootDirectory.resolve("$opaqueId.transaction.ptc").exists() ||
            rootDirectory.resolve("$opaqueId.events").exists()

    private fun existingKey(): SecretKey? = keyStore.getKey(keyAlias, null) as? SecretKey
    private fun existingKeyOrThrow(): SecretKey = existingKey() ?: error("Encrypted experiment key is unavailable")

    private fun getOrCreateKey(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
        }
        .generateKey()

    private fun validateSegmentHeader(input: DataInputStream, expectedIndex: Int) {
        val header = ByteArray(SEGMENT_HEADER.size).also(input::readFully)
        require(header.contentEquals(SEGMENT_HEADER)) { "Unsupported commit segment format" }
        require(input.readInt() == expectedIndex) { "Commit segment index mismatch" }
    }

    private fun validateSegmentHeader(bytes: ByteArray, expectedIndex: Int) {
        require(bytes.size >= SEGMENT_HEADER_BYTES) { "Commit segment candidate is truncated" }
        val buffer = ByteBuffer.wrap(bytes)
        val header = ByteArray(SEGMENT_HEADER.size).also(buffer::get)
        require(header.contentEquals(SEGMENT_HEADER)) { "Unsupported commit segment format" }
        require(buffer.int == expectedIndex) { "Commit segment index mismatch" }
    }

    private fun truncateSegmentTail(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(length)
            output.fd.sync()
        }
    }

    private fun DataInputStream.skipFully(bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            check(skipped > 0) { "Commit segment ended inside a validated frame" }
            remaining -= skipped
        }
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun String.hexToBytes(): ByteArray {
        require(length == COMMIT_DIGEST_BYTES * 2 && all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid SHA-256 hex"
        }
        return ByteArray(COMMIT_DIGEST_BYTES) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class Segment(val index: Int, val file: File)
    private data class SegmentSummary(
        val segment: Segment,
        val firstCommit: Long,
        val lastCommit: Long,
        val bytes: Long,
    )
    private data class EncryptedCommit(val iv: ByteArray, val ciphertext: ByteArray)
    private data class FrameResult(
        val sequence: Long,
        val commitSha256: String,
        val commit: EngineCommit?,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val STORAGE_DIRECTORY = "experiments"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val KEY_SIZE_BITS = 256
        const val COMMIT_DIGEST_BYTES = 32
        const val MAXIMUM_SNAPSHOT_BYTES = 4 * 1024 * 1024
        const val MAXIMUM_PENDING_BYTES = 8 * 1024 * 1024
        const val MAXIMUM_COMMIT_BYTES = 32 * 1024 * 1024
        const val MINIMUM_COMMIT_CIPHERTEXT_BYTES = GCM_TAG_BYTES + 2
        const val MAXIMUM_COMMIT_CIPHERTEXT_BYTES = MAXIMUM_COMMIT_BYTES + GCM_TAG_BYTES
        const val MAXIMUM_SEGMENT_BYTES = 64L * 1024 * 1024
        const val SNAPSHOT_RESERVE_BYTES = 4L * 1024 * 1024
        const val SCAN_BUFFER_BYTES = 256 * 1024
        const val MAXIMUM_SEGMENT_INDEX = 1_000_000_000
        const val MINIMUM_LOCAL_BYTES = 8L shl 20
        const val MAXIMUM_LOCAL_BYTES = 8L shl 30
        val RUNTIME_HEADER = "PTCRUN03".toByteArray(Charsets.US_ASCII)
        val PENDING_HEADER = "PTCPND03".toByteArray(Charsets.US_ASCII)
        val SEGMENT_HEADER = "PTCENG03".toByteArray(Charsets.US_ASCII)
        val SEGMENT_PATTERN = Regex("commits-([0-9]{8})\\.ptcs")
        val SEGMENT_PENDING_PATTERN = Regex("\\.commits-([0-9]{8})\\.ptcs\\.pending")
        val SEGMENT_REPLACEMENT_PATTERN = Regex("\\.commits-([0-9]{8})\\.ptcs\\.replacement")
        val SEGMENT_HEADER_BYTES = SEGMENT_HEADER.size + Int.SIZE_BYTES
        val FRAME_FIXED_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES + IV_BYTES + COMMIT_DIGEST_BYTES

        fun appendFrameDurably(file: File, frame: ByteArray) {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write(frame)
                output.fd.sync()
            }
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
    }
}
