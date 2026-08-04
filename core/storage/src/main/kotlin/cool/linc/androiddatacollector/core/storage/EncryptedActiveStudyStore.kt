package cool.linc.androiddatacollector.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cool.linc.androiddatacollector.core.protocol.ActiveStudyRecord
import cool.linc.androiddatacollector.core.protocol.ActiveStudyStore
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

class EncryptedActiveStudyStore(
    context: Context,
) : ActiveStudyStore {
    private val mutex = Mutex()
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val atomicFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))

    override suspend fun load(): ActiveStudyRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!atomicFile.baseFile.exists()) return@withLock null
            val bytes = atomicFile.readFully()
            require(bytes.size in MINIMUM_ENCODED_BYTES..MAXIMUM_ENCODED_BYTES) {
                "Encrypted active-study file has an invalid size"
            }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Active-study encryption key is unavailable")
            decodeRecord(decrypt(bytes, key))
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
            require(atomicFile.baseFile.exists()) { "No active study to delete" }
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
        val output = atomicFile.startWrite()
        try {
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
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
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "adc-active-study-v1"
        const val FILE_NAME = "active-study.adc"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MAXIMUM_PLAINTEXT_BYTES = 1_100_000
        const val MAXIMUM_ENCODED_BYTES = MAXIMUM_PLAINTEXT_BYTES + 65
        val HEADER = "ADCACT01".toByteArray(Charsets.US_ASCII)
        val MINIMUM_ENCODED_BYTES = HEADER.size + IV_BYTES + TAG_BITS / 8 + 2
        const val ACTIVE_RECORD: Byte = 0
        const val DELETION_RECORD: Byte = 1
    }
}
