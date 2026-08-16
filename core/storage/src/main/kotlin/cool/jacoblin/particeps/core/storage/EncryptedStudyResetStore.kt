package cool.jacoblin.particeps.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cool.jacoblin.particeps.core.model.StudyResetMarker
import cool.jacoblin.particeps.core.model.StudyResetStore
import cool.jacoblin.particeps.core.model.StudyStorageResetter
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

/** Independent reset witness that remains readable while every study key is being rotated. */
class EncryptedStudyResetStore(
    context: Context,
) : StudyResetStore {
    private val file = AcknowledgedAtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))
    private val keyStore = androidKeyStore()
    private val mutex = Mutex()

    override suspend fun load(): StudyResetMarker? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Reset-marker encryption key is unavailable")
            val candidates = file.candidates().map { candidate ->
                candidate to decode(decrypt(candidate.bytes, key))
            }
            require(candidates.isNotEmpty()) { "Reset marker has no candidate" }
            val first = candidates.first().second
            require(candidates.drop(1).all { (_, marker) -> marker.sameEnvelope(first) }) {
                "Reset-marker candidates conflict"
            }
            if (
                candidates.size != 1 ||
                candidates.single().first.role != AcknowledgedFileCandidateRole.BASE
            ) {
                file.write(candidates.first().first.bytes)
            }
            first
        }
    }

    override suspend fun mark(retainedEnvelopeBytes: ByteArray?) = withContext(Dispatchers.IO) {
        retainedEnvelopeBytes?.let {
            require(it.size in 1..MAXIMUM_ENVELOPE_BYTES) { "Invalid retained reset envelope" }
        }
        mutex.withLock {
            val payload = retainedEnvelopeBytes
            val plaintext = ByteBuffer.allocate(1 + Int.SIZE_BYTES + (payload?.size ?: 0))
                .put(RECORD_VERSION)
                .putInt(payload?.size ?: NO_ENVELOPE)
                .apply { payload?.let(::put) }
                .array()
            file.write(encrypt(plaintext, getOrCreateKey()))
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            // This app-infrastructure key is intentionally stable. Deleting it after the marker
            // file would create an unrepeatable half-clear if alias deletion failed; deleting it
            // first would make a still-visible marker unreadable. Study and active-record aliases
            // are rotated by the durable reset while this independent witness key remains.
        }
    }

    private fun decode(plaintext: ByteArray): StudyResetMarker {
        require(plaintext.size >= 1 + Int.SIZE_BYTES) { "Reset marker is truncated" }
        val buffer = ByteBuffer.wrap(plaintext)
        require(buffer.get() == RECORD_VERSION) { "Unsupported reset marker" }
        val length = buffer.int
        require(length == NO_ENVELOPE || length in 1..MAXIMUM_ENVELOPE_BYTES) {
            "Reset marker envelope length is invalid"
        }
        require(buffer.remaining() == length.coerceAtLeast(0)) { "Reset marker length mismatch" }
        return StudyResetMarker(
            if (length == NO_ENVELOPE) null else ByteArray(length).also(buffer::get),
        )
    }

    private fun StudyResetMarker.sameEnvelope(other: StudyResetMarker): Boolean =
        when {
            retainedEnvelopeBytes == null -> other.retainedEnvelopeBytes == null
            other.retainedEnvelopeBytes == null -> false
            else -> retainedEnvelopeBytes.contentEquals(other.retainedEnvelopeBytes)
        }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(HEADER)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER.size + IV_BYTES + ciphertext.size)
            .put(HEADER)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(encoded: ByteArray, key: SecretKey): ByteArray {
        require(encoded.size >= HEADER.size + IV_BYTES + TAG_BYTES + 1 + Int.SIZE_BYTES) {
            "Encrypted reset marker is truncated"
        }
        val buffer = ByteBuffer.wrap(encoded)
        val header = ByteArray(HEADER.size).also(buffer::get)
        require(header.contentEquals(HEADER)) { "Unsupported reset-marker format" }
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
        const val KEY_ALIAS = "particeps-reset-v1"
        const val FILE_NAME = "study-reset.ptc"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MAXIMUM_ENVELOPE_BYTES = 1_100_000
        const val NO_ENVELOPE = -1
        const val RECORD_VERSION: Byte = 1
        val HEADER = "PTCRST01".toByteArray(Charsets.US_ASCII)
    }
}

/** Strictly clears the single-study encrypted storage namespace and every matching core alias. */
class EncryptedStudyStorageResetter(
    context: Context,
) : StudyStorageResetter {
    private val root = context.noBackupFilesDir.resolve("experiments")
    private val noBackupRoot = context.noBackupFilesDir

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (root.exists()) {
            require(root.isDirectory) { "Experiment storage root is not a directory" }
            root.listFiles()?.sortedBy { it.name }?.forEach { entry ->
                if (entry.isDirectory) {
                    entry.listFiles()?.sortedBy { it.name }?.forEach { child ->
                        require(child.isFile) { "Unexpected nested experiment storage" }
                        AndroidAcknowledgedFileSystem.deleteIfExists(child)
                        check(!child.exists()) { "Experiment child survived reset deletion" }
                    } ?: error("Cannot enumerate experiment event storage")
                    AndroidAcknowledgedFileSystem.syncDirectory(entry)
                }
                AndroidAcknowledgedFileSystem.deleteIfExists(entry)
                check(!entry.exists()) { "Experiment entry survived reset deletion" }
                AndroidAcknowledgedFileSystem.syncDirectory(root)
            } ?: error("Cannot enumerate experiment storage")
            AndroidAcknowledgedFileSystem.deleteIfExists(root)
            check(!root.exists()) { "Experiment storage root survived reset deletion" }
            AndroidAcknowledgedFileSystem.syncDirectory(noBackupRoot)
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.aliases().toList().filter { it.startsWith(CORE_KEY_PREFIX) }.forEach(keyStore::deleteEntry)
    }

    private companion object {
        const val CORE_KEY_PREFIX = "particeps-core-"
    }
}
