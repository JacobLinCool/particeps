package cool.jacoblin.particeps.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyStoreMutationFailedClosed
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryException
import cool.jacoblin.particeps.core.model.StudyStoreRecoveryFailure
import cool.jacoblin.particeps.core.model.TransitionReason
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
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
        context,
        experimentId,
        maximumLocalBytes,
        File::delete,
        AndroidAcknowledgedFileSystem,
    )

    private val mutex = Mutex()
    private val stateMachine = ExperimentStateMachine()
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val opaqueId = sha256(experimentId.toByteArray(Charsets.UTF_8)).toHex()
    private val keyAlias = "particeps-core-$opaqueId"
    private val rootDirectory = context.noBackupFilesDir.resolve(STORAGE_DIRECTORY)
    private val metadataFile = AcknowledgedAtomicFile(
        rootDirectory.resolve("$opaqueId.metadata.ptc"),
        fileSystem,
    )
    private val transactionFile = AcknowledgedAtomicFile(
        rootDirectory.resolve("$opaqueId.transaction.ptc"),
        fileSystem,
    )
    private val eventDirectory = rootDirectory.resolve("$opaqueId.events")
    private var persistedSequenceBoundary = 0L
    private var persistedRetainedFrom = 1L
    private var persistedMetadata: StudyMetadata? = null
    private var appendRecoveryRequired = false

    init {
        require(maximumLocalBytes in MINIMUM_LOCAL_BYTES..MAXIMUM_LOCAL_BYTES) { "Invalid storage quota" }
    }

    override suspend fun loadMetadata(): StudyMetadata? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!metadataFile.exists()) {
                require(eventDirectoryEntries().isEmpty()) { "Event segments exist without metadata" }
                require(!transactionFile.exists()) { "Append transaction exists without metadata" }
                persistedMetadata = null
                persistedSequenceBoundary = 0
                persistedRetainedFrom = 1
                return@withLock null
            }
            val key = existingKey()
                ?: throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.KEY_UNAVAILABLE)
            try {
                repairEventSegmentResidue(key)
            } catch (failure: Throwable) {
                throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.EVENT_LOG_INVALID, failure)
            }
            // Framing and contiguity come from plaintext frame headers. Normal opening decrypts no
            // event payload; the unique journal+durable-tail recovery state authenticates only the
            // last one through readDurableTail below.
            val scan = try {
                scanEvents(
                    key,
                    fromSequenceInclusive = 1,
                    Long.MAX_VALUE,
                    recoverTail = true,
                    decryptPayloads = false,
                )
            } catch (failure: Throwable) {
                throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.EVENT_LOG_INVALID, failure)
            }
            val mainCandidates = decodeDocumentCandidates(
                metadataFile,
                key,
                METADATA_HEADER,
                StudyStoreRecoveryFailure.METADATA_INVALID,
            )
            val transactionCandidates = if (transactionFile.exists()) {
                decodeDocumentCandidates(
                    transactionFile,
                    key,
                    TRANSACTION_HEADER,
                    StudyStoreRecoveryFailure.TRANSACTION_INVALID,
                )
            } else {
                emptyList()
            }
            val recovery = convergeCandidates(mainCandidates, transactionCandidates, scan, key)
            val metadata = recovery.metadata
            // Even an otherwise unchanged v1 base is immediately canonicalized to the one v2
            // layout. Residues are likewise retired only after convergence proved these bytes.
            val requiresMetadataRewrite = recovery.rewriteMetadata ||
                mainCandidates.any { it.decoded.migratedFromV1 } ||
                mainCandidates.size != 1 ||
                mainCandidates.single().candidate.role != AcknowledgedFileCandidateRole.BASE
            if (requiresMetadataRewrite) writeMetadataDocument(metadata, key)

            if (recovery.failureResolutionRequired) {
                // Preserve a clean fail-closed journal until the application supplies the winning
                // typed safety reason. This replaces every validated transaction residue.
                transactionFile.write(
                    encryptDocument(StudyDataJsonCodec.encodeMetadata(metadata), key, TRANSACTION_HEADER),
                )
            } else if (transactionCandidates.isNotEmpty()) {
                transactionFile.delete()
            }
            require(metadata.experimentId == experimentId) { "Encrypted experiment ID mismatch" }
            // The lifetime counter comes from metadata, not from the scan: reclaimed events are
            // gone from disk but their sequence numbers must never be handed out again.
            persistedSequenceBoundary = metadata.eventCount
            persistedRetainedFrom = metadata.retainedFromSequence
            persistedMetadata = metadata
            appendRecoveryRequired = recovery.failureResolutionRequired
            metadata
        }
    }

    private fun decodeDocumentCandidates(
        file: AcknowledgedFile,
        key: SecretKey,
        header: ByteArray,
        failureCode: StudyStoreRecoveryFailure,
    ): List<DecodedDocumentCandidate> = try {
        file.candidates().map { candidate ->
            val decoded = StudyDataJsonCodec.decodeMetadataDocument(
                decryptDocument(candidate.bytes, key, header),
            )
            require(decoded.metadata.experimentId == experimentId) { "Encrypted experiment ID mismatch" }
            DecodedDocumentCandidate(candidate, decoded)
        }.also { require(it.isNotEmpty()) { "Atomic document has no readable candidate" } }
    } catch (failure: Throwable) {
        if (failure is StudyStoreRecoveryException) throw failure
        throw StudyStoreRecoveryException(failureCode, failure)
    }

    private fun convergeCandidates(
        mains: List<DecodedDocumentCandidate>,
        transactions: List<DecodedDocumentCandidate>,
        scan: EventScan,
        key: SecretKey,
    ): AppendRecoveryResult {
        val transactionOptions: List<DecodedDocumentCandidate?> =
            if (transactions.isEmpty()) listOf(null) else transactions
        val valid = mutableListOf<CandidateRecovery>()
        mains.forEach { main ->
            transactionOptions.forEach { transaction ->
                val candidate = runCatching {
                    val transactionMetadata = transaction?.decoded?.metadata
                    val durableTail = if (
                        transactionMetadata != null && transactionMetadata.eventCount == scan.lastSequence
                    ) {
                        readDurableTail(key, scan.lastSequence)
                    } else {
                        null
                    }
                    val recovered = AppendTransactionRecovery.recover(
                        main = main.decoded.metadata,
                        transaction = transactionMetadata,
                        durableLastSequence = scan.lastSequence,
                        durableTail = durableTail,
                    )
                    val reconciled = StudyDataJsonCodec.reconcileMetadata(
                        recovered.metadata,
                        scan.firstSequence,
                        scan.lastSequence,
                    )
                    CandidateRecovery(
                        main = main,
                        transaction = transaction,
                        result = recovered.copy(
                            metadata = reconciled,
                            rewriteMetadata = recovered.rewriteMetadata || reconciled != recovered.metadata,
                        ),
                    )
                }.getOrNull()
                candidate?.let(valid::add)
            }
        }
        if (valid.isEmpty()) {
            val failure = if (mains.size == 1 && transactions.isEmpty()) {
                // One authenticated metadata document that disagrees with the only durable event
                // sequence is an event-log failure, not an ambiguity between atomic candidates.
                StudyStoreRecoveryFailure.EVENT_LOG_INVALID
            } else {
                StudyStoreRecoveryFailure.CANDIDATE_CONFLICT
            }
            throw StudyStoreRecoveryException(failure)
        }
        require(mains.all { main -> valid.any { it.main === main } }) {
            "A metadata candidate cannot be reconciled with the durable event tail"
        }
        require(transactions.all { transaction -> valid.any { it.transaction === transaction } }) {
            "A transaction candidate cannot be reconciled with the durable event tail"
        }
        val authoritative = valid.first().result
        if (valid.drop(1).any {
                it.result.metadata != authoritative.metadata ||
                    it.result.failureResolutionRequired != authoritative.failureResolutionRequired
            }
        ) {
            throw StudyStoreRecoveryException(StudyStoreRecoveryFailure.CANDIDATE_CONFLICT)
        }
        return authoritative.copy(rewriteMetadata = valid.any { it.result.rewriteMetadata })
    }

    override suspend fun initialize(metadata: StudyMetadata) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(
                !metadataFile.exists() &&
                    !transactionFile.exists() &&
                    eventDirectoryEntries().isEmpty(),
            ) {
                "Study storage is already initialized"
            }
            require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
            require(metadata.eventCount == 0L) { "Initial study metadata must not reference events" }
            persistMetadata(metadata, getOrCreateKey())
        }
    }

    override suspend fun saveMetadata(metadata: StudyMetadata) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNoPendingAppendRecovery()
            requireNotNull(persistedMetadata) { "Study storage is not initialized" }
            require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
            require(metadata.eventCount == persistedSequenceBoundary) { "Metadata event boundary changed" }
            require(metadata.retainedFromSequence >= persistedRetainedFrom) {
                "Metadata retained floor cannot move behind durable storage"
            }
            persistMetadata(metadata, existingKey() ?: error("Encrypted experiment key is unavailable"))
        }
    }

    override suspend fun appendEvent(event: RecordedEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNoPendingAppendRecovery()
            val metadata = requireNotNull(persistedMetadata) { "Study storage is not initialized" }
            appendTransaction(
                event,
                metadata.copy(
                    eventCount = event.sequenceNumber,
                    nextSequenceNumber = event.sequenceNumber + 1,
                    lastEvents = metadata.lastEvents + (event.collectorId to event),
                    retainedFromSequence = persistedRetainedFrom,
                ),
                event.observedTime,
            )
        }
    }

    override suspend fun appendEventAtomically(
        event: RecordedEvent,
        metadata: StudyMetadata,
        failureTime: ResearchTime,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNoPendingAppendRecovery()
            appendTransaction(event, metadata, failureTime)
        }
    }

    override suspend fun resolvePendingAppendFailure(reason: TransitionReason): StudyMetadata? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!appendRecoveryRequired) return@withLock null
                require(reason in SAFETY_PAUSE_TRANSITION_REASONS) {
                    "Append recovery requires a closed safety-pause reason"
                }
                val current = requireNotNull(persistedMetadata) {
                    "Study storage is not initialized"
                }
                require(current.state == ExperimentState.PAUSED) {
                    "Pending append recovery is not paused"
                }
                val failure = current.transitions.lastOrNull()
                    ?: error("Pending append recovery has no transition")
                require(
                    failure.from == ExperimentState.RUNNING &&
                        failure.to == ExperimentState.PAUSED &&
                        failure.reason == TransitionReason.STORAGE_FAILURE,
                ) { "Pending append recovery has no synthetic storage transition" }
                val resolved = if (reason == TransitionReason.STORAGE_FAILURE) {
                    current
                } else {
                    current.copy(
                        transitions = current.transitions.dropLast(1) + failure.copy(reason = reason),
                    )
                }
                if (resolved != current) writeMetadataDocument(resolved, existingKeyOrThrow())
                commitAuthoritativeMetadata(resolved)
                retireTransactionAfterCommit()
                appendRecoveryRequired = false
                resolved
            }
        }

    private fun appendTransaction(
        event: RecordedEvent,
        metadata: StudyMetadata,
        failureTime: ResearchTime,
    ) {
        val current = requireNotNull(persistedMetadata) { "Study storage is not initialized" }
        require(event.sequenceNumber == persistedSequenceBoundary + 1) { "Non-contiguous event append" }
        require(metadata.eventCount == event.sequenceNumber && metadata.nextSequenceNumber == event.sequenceNumber + 1) {
            "Atomic metadata boundary mismatch"
        }
        require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
        val key = existingKey() ?: error("Encrypted experiment key is unavailable")
        val encoded = StudyDataJsonCodec.encodeMetadata(metadata)
        require(encoded.size <= MAXIMUM_METADATA_BYTES) { "Experiment metadata quota exceeded" }
        val failClosedMetadata = stateMachine.transition(
            metadata,
            ExperimentState.PAUSED,
            TransitionReason.STORAGE_FAILURE,
            failureTime,
        )
        val validated = AppendTransactionRecovery.recover(
            main = current,
            transaction = failClosedMetadata,
            durableLastSequence = event.sequenceNumber,
            durableTail = event,
        )
        check(validated.rewriteMetadata && validated.metadata == failClosedMetadata) {
            "Atomic append metadata is not a valid one-event successor"
        }
        val failClosedEncoded = StudyDataJsonCodec.encodeMetadata(failClosedMetadata)
        require(failClosedEncoded.size <= MAXIMUM_METADATA_BYTES) {
            "Fail-closed append metadata quota exceeded"
        }
        appendRecoveryRequired = true
        try {
            transactionFile.write(encryptDocument(failClosedEncoded, key, TRANSACTION_HEADER))
        } catch (failure: Throwable) {
            if (!transactionFile.exists()) appendRecoveryRequired = false
            throw failure
        }
        try {
            appendEncryptedEvent(event, key)
            writeMetadataDocument(metadata, key)
            commitAuthoritativeMetadata(metadata)
            retireTransactionAfterCommit()
            appendRecoveryRequired = false
        } catch (failure: Throwable) {
            val recovered = try {
                recoverFailedAppend(current, failClosedMetadata, key)
            } catch (recoveryFailure: Throwable) {
                failure.addSuppressed(recoveryFailure)
                throw failure
            }
            throw StudyStoreMutationFailedClosed(recovered, failure)
        }
    }

    private fun recoverFailedAppend(
        main: StudyMetadata,
        failClosedMetadata: StudyMetadata,
        key: SecretKey,
    ): StudyMetadata {
        val scan = scanEvents(
            key = key,
            fromSequenceInclusive = 1,
            upToSequenceInclusive = Long.MAX_VALUE,
            recoverTail = true,
            decryptPayloads = false,
        )
        val durableTail = if (failClosedMetadata.eventCount == scan.lastSequence) {
            readDurableTail(key, scan.lastSequence)
        } else {
            null
        }
        val recovery = AppendTransactionRecovery.recover(
            main = main,
            transaction = failClosedMetadata,
            durableLastSequence = scan.lastSequence,
            durableTail = durableTail,
        )
        if (recovery.rewriteMetadata) writeMetadataDocument(recovery.metadata, key)
        commitAuthoritativeMetadata(recovery.metadata)
            // Keep the fail-closed journal as provenance until the runtime supplies the winning
            // application safety reason. This is what makes first-wins survive process death.
            appendRecoveryRequired = true
            return recovery.metadata
    }

    private fun retireTransactionAfterCommit() {
        try {
            transactionFile.delete()
        } catch (_: Exception) {
            // Main metadata is already authoritative. A surviving journal makes the next open
            // conservatively recover PAUSED; an absent journal leaves the acknowledged commit.
        }
    }

    override suspend fun readEvents(
        fromSequenceInclusive: Long,
        upToSequenceInclusive: Long,
        consume: (RecordedEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val key = mutex.withLock {
            require(upToSequenceInclusive in 0..persistedSequenceBoundary) { "Invalid event snapshot boundary" }
            require(fromSequenceInclusive in persistedRetainedFrom..(upToSequenceInclusive + 1)) {
                "Requested events were reclaimed after delivery"
            }
            existingKey() ?: error("Encrypted experiment key is unavailable")
        }
        val scan = scanEvents(
            key,
            fromSequenceInclusive,
            upToSequenceInclusive,
            recoverTail = false,
            consume = consume,
        )
        require(scan.lastSequence == upToSequenceInclusive) { "Event snapshot boundary is unavailable" }
    }

    override suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        mutex.withLock { StorageUsage(storageBytes(), maximumLocalBytes) }
    }

    override suspend fun evictThrough(
        metadata: StudyMetadata,
        targetBytes: Long,
    ): StudyMetadata = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNoPendingAppendRecovery()
            requireNotNull(persistedMetadata) { "Study storage is not initialized" }
            require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
            // The caller's copy is written back with a new floor, so it must not be stale in any
            // other field. A mismatched counter means events were appended since it was read.
            require(metadata.eventCount == persistedSequenceBoundary) { "Metadata event boundary changed" }
            val key = existingKey() ?: error("Encrypted experiment key is unavailable")

            val summaries = segments().mapNotNull { segment ->
                firstSequenceOf(segment)?.let {
                    SegmentSummary(segment.index, it, segment.file.length())
                }
            }
            val plan = EvictionPlanner.plan(
                segments = summaries,
                uploadedThroughSequence = metadata.uploadedThroughSequence,
                currentBytes = storageBytes(),
                targetBytes = targetBytes,
            )
            if (plan.isEmpty) return@withLock metadata

            // Order matters. The floor is persisted first, so a crash before the unlinks leaves more
            // on disk than the floor claims — harmless, and the next pass finishes the job. Deleting
            // first would leave a floor claiming data that is already gone, which on reload is
            // indistinguishable from a prefix having been tampered away.
            val updated = metadata.copy(retainedFromSequence = plan.retainedFromSequence)
            persistMetadata(updated, key)
            // Unlinking is physical cleanup after the logical floor commit. Stop at the first
            // failed unlink so the remaining files stay one contiguous suffix; returning the
            // authoritative metadata is safe because every planned segment was already confirmed
            // by the endpoint. A later reclaim or process recovery can retry the harmless prefix.
            for (index in plan.segmentIndices) {
                val file = eventDirectory.resolve("events-${index.toString().padStart(8, '0')}.ptcs")
                try {
                    if (file.exists()) {
                        if (!deleteSegment(file) || file.exists()) break
                    }
                    fileSystem.syncDirectory(eventDirectory)
                } catch (_: Exception) {
                    // The logical floor and authoritative metadata were acknowledged first. A
                    // surviving delivered prefix is harmless and will be retried on a later pass.
                    break
                }
            }
            updated
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            metadataFile.delete()
            transactionFile.delete()
            eventDirectoryEntries().forEach { file ->
                fileSystem.deleteIfExists(file)
                check(!fileSystem.exists(file)) { "Cannot delete event segment" }
            }
            if (eventDirectory.exists()) {
                fileSystem.syncDirectory(eventDirectory)
                fileSystem.deleteIfExists(eventDirectory)
                check(!fileSystem.exists(eventDirectory)) { "Cannot delete event directory" }
                fileSystem.syncDirectory(rootDirectory)
            }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            persistedSequenceBoundary = 0
            persistedRetainedFrom = 1
            persistedMetadata = null
            appendRecoveryRequired = false
        }
    }

    private fun requireNoPendingAppendRecovery() {
        check(!appendRecoveryRequired) {
            "Append transaction requires fail-closed recovery before another mutation"
        }
    }

    private fun existingKeyOrThrow(): SecretKey =
        existingKey() ?: error("Encrypted experiment key is unavailable")

    private fun persistMetadata(
        metadata: StudyMetadata,
        key: SecretKey,
    ) {
        writeMetadataDocument(metadata, key)
        commitAuthoritativeMetadata(metadata)
    }

    private fun writeMetadataDocument(
        metadata: StudyMetadata,
        key: SecretKey,
    ) {
        val encoded = StudyDataJsonCodec.encodeMetadata(metadata)
        require(encoded.size <= MAXIMUM_METADATA_BYTES) { "Experiment metadata quota exceeded" }
        writeMetadata(encryptDocument(encoded, key, METADATA_HEADER))
    }

    private fun commitAuthoritativeMetadata(metadata: StudyMetadata) {
        persistedSequenceBoundary = metadata.eventCount
        persistedRetainedFrom = metadata.retainedFromSequence
        persistedMetadata = metadata
    }

    private fun appendEncryptedEvent(
        event: RecordedEvent,
        key: SecretKey,
    ) {
        val plaintext = StudyDataJsonCodec.encodeEvent(event)
        require(plaintext.size <= MAXIMUM_EVENT_BYTES) { "Encoded event exceeds the storage contract" }
        val encrypted = encryptEvent(plaintext, event.sequenceNumber, key)
        val frameBytes = Long.SIZE_BYTES + Int.SIZE_BYTES + IV_BYTES + encrypted.ciphertext.size
        require(storageBytes() + frameBytes <= maximumLocalBytes - METADATA_RESERVE_BYTES) {
            "Experiment event quota exceeded"
        }
        fileSystem.ensureDirectory(eventDirectory)
        var segment = latestSegment() ?: createSegment(1)
        if (segment.file.length() + frameBytes > MAXIMUM_SEGMENT_BYTES) {
            segment = createSegment(segment.index + 1)
        }
        val frame = ByteBuffer.allocate(frameBytes)
            .putLong(event.sequenceNumber)
            .putInt(encrypted.ciphertext.size)
            .put(encrypted.iv)
            .put(encrypted.ciphertext)
            .array()
        appendFrame(segment.file, frame)
    }

    /**
     * Walks every frame up to [upToSequenceInclusive] to validate contiguity, and decrypts only the
     * ones at or above [fromSequenceInclusive].
     *
     * Skipping the prefix matters beyond speed. An upload streams its bundle as it is generated, so
     * time spent decrypting events the chunk will discard is time the connection sits silent. That
     * silence grows with the study's length, and once it outlasts the network's patience the upload
     * fails — later chunks first, which is exactly when a study can least afford it.
     */
    private fun scanEvents(
        key: SecretKey,
        fromSequenceInclusive: Long,
        upToSequenceInclusive: Long,
        recoverTail: Boolean,
        decryptPayloads: Boolean = true,
        consume: (RecordedEvent) -> Unit = {},
    ): EventScan {
        val segments = segments()
        // Survivors must be contiguous among themselves. They no longer have to start at index 1,
        // because reclaiming removes whole leading segments and never reuses an index.
        val baseIndex = segments.firstOrNull()?.index ?: 1
        segments.forEachIndexed { index, segment ->
            require(segment.index == baseIndex + index) { "Event segment sequence has a gap" }
        }
        var firstSequence = 0L
        var lastSequence = 0L
        segments.forEachIndexed { segmentOffset, segment ->
            if (lastSequence == upToSequenceInclusive) return EventScan(firstSequence, lastSequence)
            val isLast = segmentOffset == segments.lastIndex
            RandomAccessFile(segment.file, "rw").use { input ->
                require(input.length() >= SEGMENT_HEADER_BYTES) { "Event segment is truncated" }
                val header = ByteArray(SEGMENT_HEADER.size).also(input::readFully)
                require(header.contentEquals(SEGMENT_HEADER)) { "Unsupported event segment format" }
                require(input.readInt() == segment.index) { "Event segment index mismatch" }
                var lastCompleteOffset = input.filePointer
                while (input.filePointer < input.length()) {
                    val frameStart = input.filePointer
                    if (input.length() - frameStart < Long.SIZE_BYTES + Int.SIZE_BYTES) {
                        require(recoverTail) { "Event segment is truncated before the snapshot boundary" }
                        recoverTrailingPartialFrame(input, lastCompleteOffset, isLast)
                        break
                    }
                    val sequenceNumber = input.readLong()
                    val ciphertextBytes = input.readInt()
                    require(ciphertextBytes in MINIMUM_CIPHERTEXT_BYTES..MAXIMUM_CIPHERTEXT_BYTES) {
                        "Invalid encrypted event size"
                    }
                    if (input.length() - input.filePointer < IV_BYTES + ciphertextBytes) {
                        require(recoverTail) { "Event frame is truncated before the snapshot boundary" }
                        recoverTrailingPartialFrame(input, lastCompleteOffset, isLast)
                        break
                    }
                    // Sequence numbers are plaintext in the frame header, so a frame outside the
                    // requested range is skipped by seeking rather than by decrypting and dropping.
                    val wanted = decryptPayloads && sequenceNumber >= fromSequenceInclusive
                    if (!wanted) {
                        input.seek(input.filePointer + IV_BYTES + ciphertextBytes)
                    }
                    val event = if (wanted) {
                        val iv = ByteArray(IV_BYTES).also(input::readFully)
                        val ciphertext = ByteArray(ciphertextBytes).also(input::readFully)
                        StudyDataJsonCodec.decodeEvent(
                            decryptEvent(iv, ciphertext, sequenceNumber, key),
                        ).also {
                            require(it.sequenceNumber == sequenceNumber) { "Encrypted event sequence mismatch" }
                        }
                    } else {
                        null
                    }
                    // The cursor is seeded by the first surviving frame rather than by zero, so a
                    // store whose leading segments were reclaimed still validates as contiguous.
                    require(lastSequence == 0L || sequenceNumber == lastSequence + 1) {
                        "Event sequence is not contiguous"
                    }
                    if (firstSequence == 0L) firstSequence = sequenceNumber
                    lastSequence = sequenceNumber
                    event?.let(consume)
                    lastCompleteOffset = input.filePointer
                    if (lastSequence == upToSequenceInclusive) {
                        return EventScan(firstSequence, lastSequence)
                    }
                }
            }
        }
        return EventScan(firstSequence, lastSequence)
    }

    /** Reuses the range reader so recovery authenticates one event and adds no second framing path. */
    private fun readDurableTail(key: SecretKey, sequenceNumber: Long): RecordedEvent {
        var durableTail: RecordedEvent? = null
        val scan = scanEvents(
            key = key,
            fromSequenceInclusive = sequenceNumber,
            upToSequenceInclusive = sequenceNumber,
            recoverTail = false,
        ) { event ->
            check(durableTail == null) { "Durable event boundary is not unique" }
            durableTail = event
        }
        require(scan.lastSequence == sequenceNumber) { "Durable event tail is unavailable" }
        return requireNotNull(durableTail) { "Durable event tail was not decoded" }
    }

    private fun recoverTrailingPartialFrame(
        file: RandomAccessFile,
        lastCompleteOffset: Long,
        isLastSegment: Boolean,
    ) {
        require(isLastSegment) { "A non-final event segment is truncated" }
        file.setLength(lastCompleteOffset)
        file.fd.sync()
    }

    private fun createSegment(index: Int): Segment {
        require(index in 1..MAXIMUM_SEGMENT_INDEX) { "Event segment index exhausted" }
        require(segments().size < MAXIMUM_LIVE_SEGMENTS) { "Too many event segments" }
        val file = eventDirectory.resolve("events-${index.toString().padStart(8, '0')}.ptcs")
        require(!file.exists()) { "Event segment already exists" }
        val header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES)
            .put(SEGMENT_HEADER)
            .putInt(index)
            .array()
        AcknowledgedAtomicFile(file, fileSystem).write(header)
        return Segment(index, file)
    }

    /**
     * Resolves segment-creation residue without guessing. A staged header may be a strict prefix of
     * an acknowledged base because events are append-only after segment creation; any other byte
     * disagreement is corruption.
     */
    private fun repairEventSegmentResidue(key: SecretKey) {
        val residueIndices = eventDirectoryEntries().mapNotNull { file ->
            (SEGMENT_PENDING_PATTERN.matchEntire(file.name)
                ?: SEGMENT_REPLACEMENT_PATTERN.matchEntire(file.name))
                ?.groupValues?.get(1)?.toInt()
        }.toSet()
        residueIndices.forEach { index ->
            val base = eventDirectory.resolve("events-${index.toString().padStart(8, '0')}.ptcs")
            val atomic = AcknowledgedAtomicFile(base, fileSystem)
            val candidates = atomic.candidates()
            require(candidates.isNotEmpty()) { "Segment residue has no candidate bytes" }
            candidates.forEach { candidate -> validateSegmentCandidate(candidate.bytes, index, key) }
            val authoritative = candidates.maxBy { it.bytes.size }
            require(candidates.all { candidate -> authoritative.bytes.hasPrefix(candidate.bytes) }) {
                "Event segment candidates do not share one append-only history"
            }
            atomic.write(authoritative.bytes)
        }
    }

    private fun validateSegmentHeader(bytes: ByteArray, expectedIndex: Int) {
        require(bytes.size >= SEGMENT_HEADER_BYTES) { "Event segment candidate is truncated" }
        val buffer = ByteBuffer.wrap(bytes)
        val header = ByteArray(SEGMENT_HEADER.size).also(buffer::get)
        require(header.contentEquals(SEGMENT_HEADER)) { "Unsupported event segment format" }
        require(buffer.int == expectedIndex) { "Event segment index mismatch" }
    }

    /** Authenticates every complete frame before a segment candidate may influence authority. */
    private fun validateSegmentCandidate(bytes: ByteArray, expectedIndex: Int, key: SecretKey) {
        validateSegmentHeader(bytes, expectedIndex)
        val buffer = ByteBuffer.wrap(bytes).apply { position(SEGMENT_HEADER_BYTES) }
        var previousSequence: Long? = null
        while (buffer.hasRemaining()) {
            // An interrupted event append may leave only the final frame incomplete. The normal
            // scan truncates that tail after candidate convergence; every complete record still
            // authenticates here before its bytes can be selected.
            if (buffer.remaining() < Long.SIZE_BYTES + Int.SIZE_BYTES) return
            val sequenceNumber = buffer.long
            val ciphertextBytes = buffer.int
            require(ciphertextBytes in MINIMUM_CIPHERTEXT_BYTES..MAXIMUM_CIPHERTEXT_BYTES) {
                "Invalid encrypted event size"
            }
            if (buffer.remaining() < IV_BYTES + ciphertextBytes) return
            val iv = ByteArray(IV_BYTES).also(buffer::get)
            val ciphertext = ByteArray(ciphertextBytes).also(buffer::get)
            val event = StudyDataJsonCodec.decodeEvent(
                decryptEvent(iv, ciphertext, sequenceNumber, key),
            )
            require(event.sequenceNumber == sequenceNumber) { "Encrypted event sequence mismatch" }
            previousSequence?.let { previous ->
                require(sequenceNumber == previous + 1) { "Event sequence is not contiguous" }
            }
            previousSequence = sequenceNumber
        }
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun segments(): List<Segment> = eventDirectoryEntries()
        .map { file ->
            val incomplete = SEGMENT_PENDING_PATTERN.matchEntire(file.name)
                ?: SEGMENT_REPLACEMENT_PATTERN.matchEntire(file.name)
            incomplete?.let { match ->
                throw IncompleteAtomicWrite(
                    eventDirectory.resolve("events-${match.groupValues[1]}.ptcs"),
                )
            }
            val match = requireNotNull(SEGMENT_PATTERN.matchEntire(file.name)) {
                "Unexpected entry in event storage: ${file.name}"
            }
            require(file.isFile) { "Event segment is not a regular file" }
            Segment(match.groupValues[1].toInt(), file)
        }
        .sortedBy(Segment::index)

    private fun eventDirectoryEntries(): List<File> {
        if (!fileSystem.exists(eventDirectory)) return emptyList()
        check(fileSystem.isDirectory(eventDirectory)) { "Event storage is not a directory" }
        return checkNotNull(fileSystem.listFiles(eventDirectory)) {
            "Cannot enumerate event storage"
        }.toList()
    }

    private fun latestSegment(): Segment? = segments().maxByOrNull(Segment::index)

    /**
     * Reads a segment's first sequence from the plaintext frame header. Sequence numbers are stored
     * unencrypted at the front of each frame, so this needs no study key and decrypts nothing.
     */
    private fun firstSequenceOf(segment: Segment): Long? =
        RandomAccessFile(segment.file, "r").use { input ->
            if (input.length() < SEGMENT_HEADER_BYTES + Long.SIZE_BYTES) return null
            input.seek(SEGMENT_HEADER_BYTES.toLong())
            input.readLong()
        }

    private fun storageBytes(): Long = metadataFile.baseFile.length() +
        eventDirectoryEntries().sumOf(File::length)

    private fun encryptDocument(
        plaintext: ByteArray,
        key: SecretKey,
        header: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(header)
        }
        val ciphertext = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_BYTES) { "Android Keystore returned an invalid GCM IV" }
        return ByteBuffer.allocate(header.size + IV_BYTES + ciphertext.size)
            .put(header)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    private fun decryptMetadata(
        encoded: ByteArray,
        key: SecretKey,
    ): ByteArray = decryptDocument(encoded, key, METADATA_HEADER)

    private fun decryptDocument(encoded: ByteArray, key: SecretKey, expectedHeader: ByteArray): ByteArray {
        require(encoded.size in MINIMUM_METADATA_FILE_BYTES..MAXIMUM_METADATA_FILE_BYTES) {
            "Encrypted experiment metadata has an invalid size"
        }
        val buffer = ByteBuffer.wrap(encoded)
        val header = ByteArray(expectedHeader.size).also(buffer::get)
        require(header.contentEquals(expectedHeader)) { "Unsupported encrypted document format" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(expectedHeader)
            doFinal(ciphertext)
        }
    }

    private fun encryptEvent(
        plaintext: ByteArray,
        sequenceNumber: Long,
        key: SecretKey,
    ): EncryptedEvent {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(eventAad(sequenceNumber))
        }
        check(cipher.iv.size == IV_BYTES) { "Android Keystore returned an invalid GCM IV" }
        return EncryptedEvent(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decryptEvent(
        iv: ByteArray,
        ciphertext: ByteArray,
        sequenceNumber: Long,
        key: SecretKey,
    ): ByteArray = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        updateAAD(eventAad(sequenceNumber))
        doFinal(ciphertext)
    }

    private fun eventAad(sequenceNumber: Long): ByteArray = ByteBuffer.allocate(
        SEGMENT_HEADER.size + opaqueId.length + Long.SIZE_BYTES,
    )
        .put(SEGMENT_HEADER)
        .put(opaqueId.toByteArray(Charsets.US_ASCII))
        .putLong(sequenceNumber)
        .array()

    private fun writeMetadata(encrypted: ByteArray) = metadataFile.write(encrypted)

    private fun existingKey(): SecretKey? = keyStore.getKey(keyAlias, null) as? SecretKey

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

    private data class Segment(
        val index: Int,
        val file: File,
    )

    private data class EncryptedEvent(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private data class DecodedDocumentCandidate(
        val candidate: AcknowledgedFileCandidate,
        val decoded: DecodedStudyMetadata,
    )

    private data class CandidateRecovery(
        val main: DecodedDocumentCandidate,
        val transaction: DecodedDocumentCandidate?,
        val result: AppendRecoveryResult,
    )

    /** Sequence range actually present on disk; both zero when no segment exists. */
    private data class EventScan(
        val firstSequence: Long,
        val lastSequence: Long,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val STORAGE_DIRECTORY = "experiments"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val KEY_SIZE_BITS = 256
        const val MAXIMUM_METADATA_BYTES = 1024 * 1024
        const val METADATA_RESERVE_BYTES = 2 * 1024 * 1024
        const val MAXIMUM_EVENT_BYTES = 64 * 1024
        const val MINIMUM_CIPHERTEXT_BYTES = GCM_TAG_BYTES + 2
        const val MAXIMUM_CIPHERTEXT_BYTES = MAXIMUM_EVENT_BYTES + GCM_TAG_BYTES
        const val MAXIMUM_SEGMENT_BYTES = 4L * 1024 * 1024
        /**
         * Segments resident at once. At [MAXIMUM_SEGMENT_BYTES] each this covers the largest
         * permitted quota, so the quota is what actually binds and this stays a backstop. Small
         * segments keep reclaiming fine-grained: space comes back 4 MiB at a time.
         */
        const val MAXIMUM_LIVE_SEGMENTS = 2048

        /**
         * Indices are monotone and never reused, so this is a lifetime ceiling that reclaiming
         * cannot clear. At 4 MiB per segment it allows a study to ingest petabytes before the
         * counter matters.
         */
        const val MAXIMUM_SEGMENT_INDEX = 1_000_000_000
        const val MINIMUM_LOCAL_BYTES = 8L shl 20
        const val MAXIMUM_LOCAL_BYTES = 8L shl 30
        // The metadata codec has no fallback reader by design: a file whose header is not this
        // exact string is refused rather than migrated.
        val METADATA_HEADER = "PTCMET01".toByteArray(Charsets.US_ASCII)
        val TRANSACTION_HEADER = "PTCTXN01".toByteArray(Charsets.US_ASCII)
        val SAFETY_PAUSE_TRANSITION_REASONS = setOf(
            TransitionReason.REQUIRED_ACCESS_MISSING,
            TransitionReason.COLLECTION_HOST_FAILURE,
            TransitionReason.WORK_SCHEDULING_FAILURE,
            TransitionReason.COLLECTION_TEARDOWN_FAILURE,
            TransitionReason.STORAGE_FAILURE,
        )

        fun appendFrameDurably(
            file: File,
            frame: ByteArray,
        ) {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write(frame)
                output.fd.sync()
            }
        }
        val SEGMENT_HEADER = "PTCEVT01".toByteArray(Charsets.US_ASCII)
        val SEGMENT_PATTERN = Regex("events-([0-9]{8})\\.ptcs")
        val SEGMENT_PENDING_PATTERN = Regex("\\.events-([0-9]{8})\\.ptcs\\.pending")
        val SEGMENT_REPLACEMENT_PATTERN = Regex("\\.events-([0-9]{8})\\.ptcs\\.replacement")
        val SEGMENT_HEADER_BYTES = SEGMENT_HEADER.size + Int.SIZE_BYTES
        val MINIMUM_METADATA_FILE_BYTES = METADATA_HEADER.size + IV_BYTES + GCM_TAG_BYTES + 2
        val MAXIMUM_METADATA_FILE_BYTES = METADATA_HEADER.size + IV_BYTES + GCM_TAG_BYTES + MAXIMUM_METADATA_BYTES
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
