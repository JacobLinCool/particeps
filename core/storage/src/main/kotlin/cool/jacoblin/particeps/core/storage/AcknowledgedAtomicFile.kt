package cool.jacoblin.particeps.core.storage

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Repo-owned atomic-file contract whose methods return only after the kernel-visible mutation is
 * verified and its directory entry is durable.
 *
 * Android's `AtomicFile.finishWrite` logs several durability failures instead of propagating them.
 * Safety and study stores cannot treat that as acknowledgement, so this implementation owns every
 * step and has no weaker fallback path.
 */
interface AcknowledgedFile {
    val baseFile: File

    fun exists(): Boolean

    fun readFully(): ByteArray

    fun write(bytes: ByteArray)

    fun delete()
}

/** Durable evidence that a prior writer died or failed before the atomic replace was acknowledged. */
class IncompleteAtomicWrite(file: File) : IOException(
    "Unresolved atomic write for ${file.name}",
)

class AcknowledgedAtomicFile internal constructor(
    override val baseFile: File,
    private val fileSystem: AcknowledgedFileSystem,
) : AcknowledgedFile {
    constructor(baseFile: File) : this(baseFile, AndroidAcknowledgedFileSystem)

    private val stagedFile: File
        get() = requireNotNull(baseFile.parentFile).resolve(".${baseFile.name}.pending")

    /** Separate rename source; [stagedFile] must survive until the parent-directory commit. */
    private val replacementFile: File
        get() = requireNotNull(baseFile.parentFile).resolve(".${baseFile.name}.replacement")

    /**
     * Android's framework AtomicFile used these names before this repository took ownership of the
     * acknowledgement protocol. An in-place rc.5 -> rc.6 update can therefore encounter either
     * artifact after a process death. They are evidence of an unresolved write, not alternate
     * inputs that this implementation may guess how to promote.
     */
    private val legacyStagedFiles: List<File>
        get() = listOf(
            requireNotNull(baseFile.parentFile).resolve("${baseFile.name}.new"),
            requireNotNull(baseFile.parentFile).resolve("${baseFile.name}.bak"),
        )

    private val unresolvedFiles: List<File>
        get() = listOf(stagedFile, replacementFile) + legacyStagedFiles

    override fun exists(): Boolean = fileSystem.exists(baseFile) || unresolvedFiles.any(fileSystem::exists)

    override fun readFully(): ByteArray {
        if (unresolvedFiles.any(fileSystem::exists)) throw IncompleteAtomicWrite(baseFile)
        return fileSystem.readFully(baseFile)
    }

    override fun write(bytes: ByteArray) {
        val parent = requireNotNull(baseFile.parentFile) { "Atomic file requires a parent directory" }
        fileSystem.ensureDirectory(parent)
        val unresolved = unresolvedFiles.filter(fileSystem::exists)
        if (unresolved.isNotEmpty()) {
            unresolved.forEach(fileSystem::deleteIfExists)
            check(unresolved.none(fileSystem::exists)) { "Cannot retire an incomplete atomic write" }
            fileSystem.syncDirectory(parent)
        }

        // Keep one independently durable copy as an uncertainty witness. Renaming the only staged
        // file would consume that evidence before the parent directory acknowledges the replace.
        writeStaged(stagedFile, bytes)
        writeStaged(replacementFile, bytes)
        fileSystem.syncDirectory(parent)
        fileSystem.atomicReplace(replacementFile, baseFile)
        check(fileSystem.readFully(baseFile).contentEquals(bytes)) {
            "Atomic-file readback did not match the acknowledged bytes"
        }
        fileSystem.syncDirectory(parent)

        // The base replacement is now acknowledged. Retiring the witness is cleanup rather than
        // part of the commit: a cleanup failure may conservatively block a future reopen, but must
        // never turn an acknowledged mutation into a reported failure that higher layers roll back.
        try {
            fileSystem.deleteIfExists(stagedFile)
            check(!fileSystem.exists(stagedFile)) { "Cannot retire an acknowledged atomic write" }
            fileSystem.syncDirectory(parent)
        } catch (_: Exception) {
            // Deliberately retained or ambiguously retired. A visible witness fails closed; if its
            // deletion persisted, accepting the already-acknowledged base is also correct.
        }
    }

    private fun writeStaged(
        target: File,
        bytes: ByteArray,
    ) {
        var output: FileOutputStream? = null
        var closeAttempted = false
        try {
            output = fileSystem.openOutput(target)
            output.write(bytes)
            fileSystem.syncFile(output)
            closeAttempted = true
            fileSystem.closeFile(output)
        } catch (failure: Throwable) {
            if (output != null && !closeAttempted) {
                try {
                    fileSystem.closeFile(output)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }

    override fun delete() {
        val parent = requireNotNull(baseFile.parentFile) { "Atomic file requires a parent directory" }
        if (!fileSystem.exists(parent)) {
            check(!fileSystem.exists(baseFile) && unresolvedFiles.none(fileSystem::exists)) {
                "Atomic-file parent is missing while its children remain visible"
            }
            return
        }

        var firstFailure: Throwable? = null
        (listOf(baseFile) + unresolvedFiles).forEach { file ->
            try {
                fileSystem.deleteIfExists(file)
                check(!fileSystem.exists(file)) { "Atomic-file delete left ${file.name} visible" }
            } catch (failure: Throwable) {
                firstFailure = firstFailure.suppressing(failure)
            }
        }
        try {
            fileSystem.syncDirectory(parent)
        } catch (failure: Throwable) {
            firstFailure = firstFailure.suppressing(failure)
        }
        firstFailure?.let { throw it }
    }
}

internal interface AcknowledgedFileSystem {
    fun exists(file: File): Boolean

    fun isDirectory(file: File): Boolean

    fun listFiles(directory: File): Array<File>?

    fun ensureDirectory(directory: File)

    fun openOutput(file: File): FileOutputStream

    fun syncFile(output: FileOutputStream)

    fun closeFile(output: FileOutputStream)

    fun atomicReplace(source: File, target: File)

    fun readFully(file: File): ByteArray

    fun deleteIfExists(file: File)

    fun syncDirectory(directory: File)
}

internal object AndroidAcknowledgedFileSystem : AcknowledgedFileSystem {
    override fun exists(file: File): Boolean = file.exists()

    override fun isDirectory(file: File): Boolean = file.isDirectory

    override fun listFiles(directory: File): Array<File>? = directory.listFiles()

    override fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            require(directory.isDirectory) { "Atomic-file parent is not a directory" }
            return
        }
        val parent = requireNotNull(directory.parentFile) { "Directory requires a parent" }
        require(parent.isDirectory) { "Directory parent does not exist" }
        try {
            Files.createDirectory(directory.toPath())
        } catch (_: FileAlreadyExistsException) {
            require(directory.isDirectory) { "Atomic-file parent is not a directory" }
            return
        }
        syncDirectory(parent)
    }

    override fun openOutput(file: File): FileOutputStream = FileOutputStream(file, false)

    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
    }

    override fun closeFile(output: FileOutputStream) {
        output.close()
    }

    override fun atomicReplace(source: File, target: File) {
        Os.rename(source.absolutePath, target.absolutePath)
    }

    override fun readFully(file: File): ByteArray = Files.readAllBytes(file.toPath())

    override fun deleteIfExists(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    override fun syncDirectory(directory: File) {
        require(directory.isDirectory) { "Cannot sync a missing directory" }
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        var failure: Throwable? = null
        try {
            Os.fsync(descriptor)
        } catch (syncFailure: Throwable) {
            failure = syncFailure
        }
        try {
            Os.close(descriptor)
        } catch (closeFailure: Throwable) {
            failure = failure.suppressing(closeFailure)
        }
        failure?.let { throw it }
    }
}

private fun Throwable?.suppressing(failure: Throwable): Throwable =
    this?.also { it.addSuppressed(failure) } ?: failure
