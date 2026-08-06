package cool.linc.particeps.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.StorageUsage
import cool.linc.particeps.core.model.StudyMetadata
import cool.linc.particeps.core.model.StudyStore
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

class EncryptedExperimentStore(
    context: Context,
    private val experimentId: String,
    private val maximumLocalBytes: Long,
) : StudyStore {
    private val mutex = Mutex()
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val opaqueId = sha256(experimentId.toByteArray(Charsets.UTF_8)).toHex()
    private val keyAlias = "particeps-core-$opaqueId"
    private val rootDirectory = context.noBackupFilesDir.resolve(STORAGE_DIRECTORY)
    private val metadataFile = AtomicFile(rootDirectory.resolve("$opaqueId.metadata.ptc"))
    private val transactionFile = AtomicFile(rootDirectory.resolve("$opaqueId.transaction.ptc"))
    private val eventDirectory = rootDirectory.resolve("$opaqueId.events")
    private var persistedSequenceBoundary = 0L
    private var persistedRetainedFrom = 1L
    private var persistedMetadata: StudyMetadata? = null

    init {
        require(maximumLocalBytes in MINIMUM_LOCAL_BYTES..MAXIMUM_LOCAL_BYTES) { "Invalid storage quota" }
    }

    override suspend fun loadMetadata(): StudyMetadata? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!metadataFile.baseFile.exists()) {
                require(eventDirectory.listFiles().isNullOrEmpty()) { "Event segments exist without metadata" }
                persistedMetadata = null
                persistedSequenceBoundary = 0
                return@withLock null
            }
            val key = existingKey() ?: error("Encrypted experiment key is unavailable")
            // Framing and contiguity come from plaintext frame headers. Normal opening decrypts no
            // event payload; the unique journal+durable-tail recovery state authenticates only the
            // last one through readDurableTail below.
            val scan = scanEvents(
                key,
                fromSequenceInclusive = 1,
                Long.MAX_VALUE,
                recoverTail = true,
                decryptPayloads = false,
            )
            val mainMetadata = StudyDataJsonCodec.decodeMetadata(
                decryptMetadata(metadataFile.readFully(), key),
            )
            val hasTransaction = transactionFile.baseFile.exists()
            val transactionMetadata = if (hasTransaction) {
                StudyDataJsonCodec.decodeMetadata(
                    decryptDocument(transactionFile.readFully(), key, TRANSACTION_HEADER),
                )
            } else {
                null
            }
            val durableTail = if (
                transactionMetadata != null &&
                transactionMetadata.eventCount == mainMetadata.eventCount + 1 &&
                transactionMetadata.eventCount == scan.lastSequence
            ) {
                readDurableTail(key, scan.lastSequence)
            } else {
                null
            }
            val recovery = AppendTransactionRecovery.recover(
                main = mainMetadata,
                transaction = transactionMetadata,
                durableLastSequence = scan.lastSequence,
                durableTail = durableTail,
            )
            val metadata = StudyDataJsonCodec.reconcileMetadata(
                recovery.metadata,
                scan.firstSequence,
                scan.lastSequence,
            )
            if (recovery.rewriteMetadata || metadata != recovery.metadata) {
                writeMetadata(encryptDocument(StudyDataJsonCodec.encodeMetadata(metadata), key, METADATA_HEADER))
            }
            if (hasTransaction) {
                transactionFile.delete()
            }
            require(metadata.experimentId == experimentId) { "Encrypted experiment ID mismatch" }
            // The lifetime counter comes from metadata, not from the scan: reclaimed events are
            // gone from disk but their sequence numbers must never be handed out again.
            persistedSequenceBoundary = metadata.eventCount
            persistedRetainedFrom = metadata.retainedFromSequence
            persistedMetadata = metadata
            metadata
        }
    }

    override suspend fun initialize(metadata: StudyMetadata) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!metadataFile.baseFile.exists() && eventDirectory.listFiles().isNullOrEmpty()) {
                "Study storage is already initialized"
            }
            require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
            require(metadata.eventCount == 0L) { "Initial study metadata must not reference events" }
            persistMetadata(metadata, getOrCreateKey())
        }
    }

    override suspend fun saveMetadata(metadata: StudyMetadata) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(persistedMetadata) { "Study storage is not initialized" }
            require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
            require(metadata.eventCount == persistedSequenceBoundary) { "Metadata event boundary changed" }
            persistMetadata(metadata, existingKey() ?: error("Encrypted experiment key is unavailable"))
        }
    }

    override suspend fun appendEvent(event: RecordedEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val metadata = requireNotNull(persistedMetadata) { "Study storage is not initialized" }
            appendTransaction(
                event,
                metadata.copy(
                    eventCount = event.sequenceNumber,
                    nextSequenceNumber = event.sequenceNumber + 1,
                    lastEvents = metadata.lastEvents + (event.collectorId to event),
                    retainedFromSequence = persistedRetainedFrom,
                ),
            )
        }
    }

    override suspend fun appendEventAtomically(event: RecordedEvent, metadata: StudyMetadata) =
        withContext(Dispatchers.IO) { mutex.withLock { appendTransaction(event, metadata) } }

    private fun appendTransaction(event: RecordedEvent, metadata: StudyMetadata) {
        val current = requireNotNull(persistedMetadata) { "Study storage is not initialized" }
        require(event.sequenceNumber == persistedSequenceBoundary + 1) { "Non-contiguous event append" }
        require(metadata.eventCount == event.sequenceNumber && metadata.nextSequenceNumber == event.sequenceNumber + 1) {
            "Atomic metadata boundary mismatch"
        }
        require(metadata.experimentId == experimentId) { "Experiment ID mismatch" }
        val validated = AppendTransactionRecovery.recover(
            main = current,
            transaction = metadata,
            durableLastSequence = event.sequenceNumber,
            durableTail = event,
        )
        check(validated.rewriteMetadata && validated.metadata == metadata) {
            "Atomic append metadata is not a valid one-event successor"
        }
        val key = existingKey() ?: error("Encrypted experiment key is unavailable")
        val encoded = StudyDataJsonCodec.encodeMetadata(metadata)
        require(encoded.size <= MAXIMUM_METADATA_BYTES) { "Experiment metadata quota exceeded" }
        writeAtomic(transactionFile, encryptDocument(encoded, key, TRANSACTION_HEADER))
        appendEncryptedEvent(event, key)
        persistedSequenceBoundary = event.sequenceNumber
        persistMetadata(metadata, key)
        transactionFile.delete()
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
            plan.segmentIndices.forEach { index ->
                val file = eventDirectory.resolve("events-${index.toString().padStart(8, '0')}.ptcs")
                check(!file.exists() || file.delete()) { "Cannot delete event segment" }
            }
            persistedRetainedFrom = plan.retainedFromSequence
            updated
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            metadataFile.delete()
            transactionFile.delete()
            eventDirectory.listFiles()?.forEach { file -> check(file.delete()) { "Cannot delete event segment" } }
            if (eventDirectory.exists()) check(eventDirectory.delete()) { "Cannot delete event directory" }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            persistedSequenceBoundary = 0
            persistedRetainedFrom = 1
            persistedMetadata = null
        }
    }

    private fun persistMetadata(
        metadata: StudyMetadata,
        key: SecretKey,
    ) {
        val encoded = StudyDataJsonCodec.encodeMetadata(metadata)
        require(encoded.size <= MAXIMUM_METADATA_BYTES) { "Experiment metadata quota exceeded" }
        writeMetadata(encryptDocument(encoded, key, METADATA_HEADER))
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
        require(eventDirectory.exists() || eventDirectory.mkdirs()) { "Cannot create event directory" }
        var segment = latestSegment() ?: createSegment(1)
        if (segment.file.length() + frameBytes > MAXIMUM_SEGMENT_BYTES) {
            segment = createSegment(segment.index + 1)
        }
        RandomAccessFile(segment.file, "rw").use { output ->
            output.seek(output.length())
            output.writeLong(event.sequenceNumber)
            output.writeInt(encrypted.ciphertext.size)
            output.write(encrypted.iv)
            output.write(encrypted.ciphertext)
            output.fd.sync()
        }
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
        val segments = eventDirectory.listFiles()
            .orEmpty()
            .mapNotNull { file -> SEGMENT_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toInt()?.let {
                Segment(it, file)
            } }
            .sortedBy(Segment::index)
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
        require(file.createNewFile()) { "Cannot create event segment" }
        RandomAccessFile(file, "rw").use { output ->
            output.write(SEGMENT_HEADER)
            output.writeInt(index)
            output.fd.sync()
        }
        return Segment(index, file)
    }

    private fun segments(): List<Segment> = eventDirectory.listFiles()
        .orEmpty()
        .mapNotNull { file -> SEGMENT_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toInt()?.let {
            Segment(it, file)
        } }
        .sortedBy(Segment::index)

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
        eventDirectory.listFiles().orEmpty().sumOf(File::length)

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

    private fun writeMetadata(encrypted: ByteArray) = writeAtomic(metadataFile, encrypted)

    private fun writeAtomic(file: AtomicFile, encrypted: ByteArray) {
        require(rootDirectory.exists() || rootDirectory.mkdirs()) { "Cannot create experiment storage directory" }
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

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
        val SEGMENT_HEADER = "PTCEVT01".toByteArray(Charsets.US_ASCII)
        val SEGMENT_PATTERN = Regex("events-([0-9]{8})\\.ptcs")
        val SEGMENT_HEADER_BYTES = SEGMENT_HEADER.size + Int.SIZE_BYTES
        val MINIMUM_METADATA_FILE_BYTES = METADATA_HEADER.size + IV_BYTES + GCM_TAG_BYTES + 2
        val MAXIMUM_METADATA_FILE_BYTES = METADATA_HEADER.size + IV_BYTES + GCM_TAG_BYTES + MAXIMUM_METADATA_BYTES
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
