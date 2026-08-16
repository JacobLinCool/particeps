package cool.jacoblin.particeps.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecoveryException
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecoveryFailure
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedActiveStudyStore private constructor(
    private val atomicFile: AcknowledgedFile,
    private val keyStore: KeyStore,
) : ActiveStudyStore {
    constructor(context: Context) : this(
        atomicFile = AcknowledgedAtomicFile(context.noBackupFilesDir.resolve(FILE_NAME)),
        keyStore = androidKeyStore(),
    )

    internal constructor(
        context: Context,
        fileSystem: AcknowledgedFileSystem,
    ) : this(
        atomicFile = AcknowledgedAtomicFile(
            context.noBackupFilesDir.resolve(FILE_NAME),
            fileSystem,
        ),
        keyStore = androidKeyStore(),
    )

    private val mutex = Mutex()

    override suspend fun load(): ActiveStudyRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!atomicFile.exists()) return@withLock null
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw ActiveStudyRecoveryException(ActiveStudyRecoveryFailure.KEY_UNAVAILABLE)
            val decoded = try {
                atomicFile.candidates().map { candidate ->
                    require(candidate.bytes.size in MINIMUM_ENCODED_BYTES..MAXIMUM_ENCODED_BYTES) {
                        "Encrypted active-study file has an invalid size"
                    }
                    DecodedCandidate(candidate, decodeRecord(decrypt(candidate.bytes, key)))
                }
            } catch (failure: Throwable) {
                if (failure is ActiveStudyRecoveryException) throw failure
                throw ActiveStudyRecoveryException(ActiveStudyRecoveryFailure.RECORD_INVALID, failure)
            }
            if (decoded.isEmpty()) return@withLock null

            val deletions = decoded.filter { it.record is ActiveStudyRecord.DeletionPending }
            val authoritative = if (deletions.isNotEmpty()) {
                val distinct = deletions.map { it.record as ActiveStudyRecord.DeletionPending }.distinct()
                if (distinct.size != 1) {
                    throw ActiveStudyRecoveryException(ActiveStudyRecoveryFailure.CANDIDATE_CONFLICT)
                }
                deletions.first()
            } else {
                val active = decoded.map { it.record as ActiveStudyRecord.Active }
                if (active.drop(1).any { !it.envelopeBytes.contentEquals(active.first().envelopeBytes) }) {
                    throw ActiveStudyRecoveryException(ActiveStudyRecoveryFailure.CANDIDATE_CONFLICT)
                }
                decoded.first()
            }
            if (
                decoded.size != 1 ||
                decoded.single().candidate.role != AcknowledgedFileCandidateRole.BASE
            ) {
                // Authority was established above from authenticated plaintext. Rewriting the exact
                // authenticated ciphertext cleans both residue names without inventing new state.
                atomicFile.write(authoritative.candidate.bytes)
            }
            authoritative.record
        }
    }

    override suspend fun save(envelopeBytes: ByteArray) = withContext(Dispatchers.IO) {
        require(envelopeBytes.size in 1..MAXIMUM_PLAINTEXT_BYTES) { "Invalid signed study size" }
        mutex.withLock {
            writeRecord(byteArrayOf(ACTIVE_RECORD) + envelopeBytes, getOrCreateKey())
        }
    }

    override suspend fun markDeletionPending(
        experimentId: String,
        maximumLocalBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val deletion = ActiveStudyRecord.DeletionPending(experimentId, maximumLocalBytes)
        mutex.withLock {
            require(atomicFile.exists()) { "No active study to delete" }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Active-study encryption key is unavailable")
            val id = deletion.experimentId.toByteArray(Charsets.US_ASCII)
            val plaintext = ByteBuffer.allocate(1 + 1 + id.size + Long.SIZE_BYTES)
                .put(DELETION_RECORD)
                .put(id.size.toByte())
                .put(id)
                .putLong(deletion.maximumLocalBytes)
                .array()
            writeRecord(plaintext, key)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            atomicFile.delete()
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(HEADER)
        }
        val ciphertext = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_BYTES) { "Android Keystore returned an invalid IV" }
        return ByteBuffer.allocate(HEADER.size + IV_BYTES + ciphertext.size)
            .put(HEADER)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    private fun writeRecord(plaintext: ByteArray, key: SecretKey) {
        val encrypted = encrypt(plaintext, key)
        atomicFile.write(encrypted)
    }

    private fun decodeRecord(plaintext: ByteArray): ActiveStudyRecord {
        require(plaintext.isNotEmpty()) { "Active-study record is empty" }
        val buffer = ByteBuffer.wrap(plaintext)
        return when (buffer.get()) {
            ACTIVE_RECORD -> {
                require(buffer.hasRemaining()) { "Active-study envelope is empty" }
                ActiveStudyRecord.Active(ByteArray(buffer.remaining()).also(buffer::get))
            }
            DELETION_RECORD -> {
                require(buffer.remaining() >= 1 + Long.SIZE_BYTES) { "Deletion record is truncated" }
                val idLength = buffer.get().toInt() and 0xff
                require(idLength in 3..64 && buffer.remaining() == idLength + Long.SIZE_BYTES) {
                    "Deletion record length is invalid"
                }
                val experimentId = ByteArray(idLength).also(buffer::get).toString(Charsets.US_ASCII)
                ActiveStudyRecord.DeletionPending(experimentId, buffer.long)
            }
            else -> throw IllegalArgumentException("Unsupported active-study record")
        }
    }

    private data class DecodedCandidate(
        val candidate: AcknowledgedFileCandidate,
        val record: ActiveStudyRecord,
    )

    private fun decrypt(
        encoded: ByteArray,
        key: SecretKey,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(encoded)
        val header = ByteArray(HEADER.size).also(buffer::get)
        require(header.contentEquals(HEADER)) { "Unsupported active-study storage format" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance(CIPHER).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(HEADER)
            doFinal(ciphertext)
        }
    }

    private fun getOrCreateKey(): SecretKey =
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()

    private companion object {
        fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "particeps-active-study-v1"
        const val FILE_NAME = "active-study.ptc"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MAXIMUM_PLAINTEXT_BYTES = 1_100_000
        const val MAXIMUM_ENCODED_BYTES = MAXIMUM_PLAINTEXT_BYTES + 65
        val HEADER = "PTCACT01".toByteArray(Charsets.US_ASCII)
        val MINIMUM_ENCODED_BYTES = HEADER.size + IV_BYTES + TAG_BITS / 8 + 2
        const val ACTIVE_RECORD: Byte = 0
        const val DELETION_RECORD: Byte = 1
    }
}
