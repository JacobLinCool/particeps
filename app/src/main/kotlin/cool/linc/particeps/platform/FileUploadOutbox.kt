package cool.linc.particeps.platform

import android.system.Os
import android.system.OsConstants
import cool.linc.particeps.core.application.StudyUploadException
import cool.linc.particeps.core.export.ExportReceipt
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** One immutable ciphertext bundle durably staged before any HTTP request starts. */
internal data class StagedUpload(
    val body: File,
    val receipt: ExportReceipt,
    val terminalFailureCode: String?,
)

/**
 * A single-entry upload outbox.
 *
 * The manifest is authoritative and is published only after the body has been flushed, synced and
 * atomically renamed. HTTP never sees a temporary or newly regenerated body. Removing a completed
 * stage reverses the order: manifest first, then the harmless orphan body.
 */
internal class FileUploadOutbox(
    private val directory: File,
    private val directorySync: (File) -> Unit = ::syncDirectory,
) {
    @Synchronized
    fun recover(
        configurationSha256: String,
        uploadedThroughSequence: Long,
    ): StagedUpload? {
        ensureDirectory()
        deleteIfExists(temporaryBody, sync = false)
        deleteIfExists(temporaryManifest, sync = false)
        if (!manifest.exists()) {
            deleteIfExists(body, sync = true)
            return null
        }

        val staged = readManifest()
        require(staged.receipt.configurationSha256 == configurationSha256) {
            "Staged upload configuration digest mismatch"
        }
        if (staged.receipt.lastSequence <= uploadedThroughSequence) {
            acknowledge(staged.receipt.bundleId)
            return null
        }
        verifyBody(staged)
        staged.terminalFailureCode?.let { code ->
            throw StudyUploadException(code, retryable = false)
        }
        return staged
    }

    suspend fun stage(
        writeBundle: suspend (OutputStream) -> ExportReceipt,
    ): StagedUpload {
        ensureDirectory()
        check(!manifest.exists() && !body.exists()) { "Upload outbox already contains a stage" }
        deleteIfExists(temporaryBody, sync = false)
        deleteIfExists(temporaryManifest, sync = false)

        val receipt = try {
            FileOutputStream(temporaryBody).use { output ->
                val bounded = BoundedDigestingOutputStream(output, MAXIMUM_BODY_BYTES)
                val written = writeBundle(bounded)
                bounded.flush()
                require(written.byteCount == bounded.count) { "Staged upload length mismatch" }
                require(written.sha256 == bounded.digestHex()) { "Staged upload digest mismatch" }
                output.fd.sync()
                written
            }
        } catch (failure: Exception) {
            suppressCleanupFailure(failure) { Files.deleteIfExists(temporaryBody.toPath()) }
            throw failure
        }
        val staged = StagedUpload(
            body = body,
            receipt = receipt,
            terminalFailureCode = null,
        )
        verifyReceipt(staged.receipt, temporaryBody)
        moveAtomically(temporaryBody, body)
        directorySync(directory)
        writeManifest(staged)
        return staged
    }

    @Synchronized
    fun markTerminal(bundleId: UUID, reasonCode: String) {
        val staged = readManifest()
        require(staged.receipt.bundleId == bundleId) { "Upload outbox bundle mismatch" }
        writeManifest(staged.copy(terminalFailureCode = reasonCode))
    }

    @Synchronized
    fun acknowledge(bundleId: UUID) {
        ensureDirectory()
        if (!manifest.exists()) {
            deleteIfExists(body, sync = true)
            return
        }
        val staged = readManifest()
        require(staged.receipt.bundleId == bundleId) { "Upload outbox bundle mismatch" }
        Files.delete(manifest.toPath())
        directorySync(directory)
        deleteIfExists(body, sync = true)
    }

    @Synchronized
    fun clear() {
        ensureDirectory()
        listOf(manifest, body, temporaryManifest, temporaryBody)
            .forEach { Files.deleteIfExists(it.toPath()) }
        directorySync(directory)
    }

    private fun verifyBody(staged: StagedUpload) {
        val receipt = staged.receipt
        verifyReceipt(receipt, staged.body)
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(staged.body)).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        require(digest.digest().toHex() == receipt.sha256) { "Staged upload digest mismatch" }
    }

    private fun verifyReceipt(receipt: ExportReceipt, file: File) {
        require(receipt.byteCount in 1..MAXIMUM_BODY_BYTES) { "Staged upload size is out of bounds" }
        require(file.isFile && file.length() == receipt.byteCount) { "Staged upload length mismatch" }
        require(SHA256_HEX.matches(receipt.sha256)) { "Staged upload digest is malformed" }
    }

    private fun readManifest(): StagedUpload = try {
        DataInputStream(FileInputStream(manifest).buffered()).use { input ->
            val magic = ByteArray(MANIFEST_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MANIFEST_MAGIC)) { "Unsupported upload outbox manifest" }
            val bundleId = UUID(input.readLong(), input.readLong())
            val configurationDigest = ByteArray(SHA256_BYTES).also(input::readFully).toHex()
            val first = input.readLong()
            val last = input.readLong()
            val count = input.readLong()
            val byteCount = input.readLong()
            val bodyDigest = ByteArray(SHA256_BYTES).also(input::readFully).toHex()
            val terminalCode = input.readAscii(MAXIMUM_REASON_CODE_BYTES).ifEmpty { null }
            require(input.read() == -1) { "Trailing upload outbox manifest bytes" }
            StagedUpload(
                body = body,
                receipt = ExportReceipt(
                    bundleId = bundleId,
                    configurationSha256 = configurationDigest,
                    firstSequence = first,
                    lastSequence = last,
                    eventCount = count,
                    sha256 = bodyDigest,
                    byteCount = byteCount,
                ),
                terminalFailureCode = terminalCode,
            )
        }
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        throw StudyUploadException("UPLOAD_OUTBOX_CORRUPT", retryable = false, cause = failure)
    }

    private fun writeManifest(staged: StagedUpload) {
        try {
            FileOutputStream(temporaryManifest).use { fileOutput ->
                DataOutputStream(fileOutput.buffered()).use { output ->
                    output.write(MANIFEST_MAGIC)
                    output.writeLong(staged.receipt.bundleId.mostSignificantBits)
                    output.writeLong(staged.receipt.bundleId.leastSignificantBits)
                    output.write(staged.receipt.configurationSha256.hexToBytes())
                    output.writeLong(staged.receipt.firstSequence)
                    output.writeLong(staged.receipt.lastSequence)
                    output.writeLong(staged.receipt.eventCount)
                    output.writeLong(staged.receipt.byteCount)
                    output.write(staged.receipt.sha256.hexToBytes())
                    output.writeAscii(staged.terminalFailureCode.orEmpty(), MAXIMUM_REASON_CODE_BYTES)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            moveAtomically(temporaryManifest, manifest)
            directorySync(directory)
        } catch (failure: Exception) {
            suppressCleanupFailure(failure) { Files.deleteIfExists(temporaryManifest.toPath()) }
            throw failure
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create upload outbox")
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun deleteIfExists(file: File, sync: Boolean) {
        if (Files.deleteIfExists(file.toPath()) && sync) directorySync(directory)
    }

    private inline fun suppressCleanupFailure(primary: Exception, cleanup: () -> Unit) {
        try {
            cleanup()
        } catch (failure: Exception) {
            primary.addSuppressed(failure)
        }
    }

    private val manifest get() = directory.resolve("stage.manifest")
    private val body get() = directory.resolve("stage.partexp")
    private val temporaryManifest get() = directory.resolve("stage.manifest.tmp")
    private val temporaryBody get() = directory.resolve("stage.partexp.tmp")

    private companion object {
        val MANIFEST_MAGIC = "PTCOUT01".toByteArray(Charsets.US_ASCII)
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        const val SHA256_BYTES = 32
        const val DIGEST_BUFFER_BYTES = 64 * 1024
        const val MAXIMUM_BODY_BYTES = 32L * 1024 * 1024
        const val MAXIMUM_REASON_CODE_BYTES = 64
    }
}

private fun DataOutputStream.writeAscii(value: String, maximumBytes: Int) {
    val encoded = value.toByteArray(Charsets.US_ASCII)
    require(encoded.size <= maximumBytes && value == encoded.toString(Charsets.US_ASCII)) {
        "Upload outbox value is not bounded ASCII"
    }
    writeByte(encoded.size)
    write(encoded)
}

private fun DataInputStream.readAscii(maximumBytes: Int): String {
    val length = readUnsignedByte()
    require(length <= maximumBytes) { "Upload outbox value exceeds its bound" }
    return ByteArray(length).also(::readFully).toString(Charsets.US_ASCII)
}

private fun String.hexToBytes(): ByteArray {
    require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
    return ByteBuffer.allocate(length / 2).run {
        chunked(2).forEach { put(it.toInt(16).toByte()) }
        array()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun syncDirectory(directory: File) {
    try {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (failure: android.system.ErrnoException) {
        throw IOException("Cannot sync upload outbox directory", failure)
    }
}

private class BoundedDigestingOutputStream(
    private val destination: OutputStream,
    private val maximumBytes: Long,
) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(value: Int) {
        requireCapacity(1)
        destination.write(value)
        digest.update(value.toByte())
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid upload write range" }
        requireCapacity(length)
        destination.write(bytes, offset, length)
        digest.update(bytes, offset, length)
        count += length
    }

    override fun flush() = destination.flush()

    fun digestHex(): String = digest.digest().toHex()

    private fun requireCapacity(additionalBytes: Int) {
        require(count <= maximumBytes - additionalBytes) { "Upload bundle exceeds 32 MiB" }
    }
}
