package cool.jacoblin.particeps.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcknowledgedAtomicFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulWritePerformsTheCompleteAcknowledgementSequence() {
        val operations = RecordingFileSystem()
        val target = temporaryFolder.root.resolve("record.ptc")
        val file = AcknowledgedAtomicFile(target, operations)

        file.write("durable".toByteArray())

        assertArrayEquals("durable".toByteArray(), target.readBytes())
        assertTrue(
            operations.calls.containsSubsequence(
                "open:.record.ptc.pending",
                "sync-file",
                "close-file",
                "open:.record.ptc.replacement",
                "sync-file",
                "close-file",
                "sync-directory:${temporaryFolder.root.name}",
                "rename:.record.ptc.replacement->record.ptc",
                "read:record.ptc",
                "sync-directory:${temporaryFolder.root.name}",
                "delete:.record.ptc.pending",
                "sync-directory:${temporaryFolder.root.name}",
            ),
        )
    }

    @Test
    fun fileSyncFailureCannotReplaceOrAcknowledgeTheOldValue() {
        assertWriteFailsBeforeReplace(FailurePoint.FILE_SYNC)
    }

    @Test
    fun checkedCloseFailureCannotReplaceOrAcknowledgeTheOldValue() {
        assertWriteFailsBeforeReplace(FailurePoint.FILE_CLOSE)
    }

    @Test
    fun atomicRenameFailureCannotReplaceOrAcknowledgeTheOldValue() {
        assertWriteFailsBeforeReplace(FailurePoint.ATOMIC_REPLACE)
    }

    @Test
    fun replacementDirectorySyncFailureRetainsAnIndependentUncertaintyWitness() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("old") }
        val operations = RecordingFileSystem(FailurePoint.REPLACEMENT_DIRECTORY_SYNC)
        val file = AcknowledgedAtomicFile(target, operations)

        assertThrows(IOException::class.java) {
            file.write("new".toByteArray())
        }

        // rename(2) is visible, but the independent witness survives the failed parent fsync so a
        // new process cannot mistake an unacknowledged RUNNING transition for a committed one.
        assertArrayEquals("new".toByteArray(), target.readBytes())
        assertThrows(IncompleteAtomicWrite::class.java) {
            AcknowledgedAtomicFile(target, RecordingFileSystem()).readFully()
        }
    }

    @Test
    fun witnessCleanupFailureDoesNotRollBackAnAcknowledgedReplacement() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("old") }
        val operations = RecordingFileSystem(FailurePoint.CLEANUP_DIRECTORY_SYNC)

        AcknowledgedAtomicFile(target, operations).write("new".toByteArray())

        assertArrayEquals("new".toByteArray(), target.readBytes())
        assertArrayEquals(
            "new".toByteArray(),
            AcknowledgedAtomicFile(target, RecordingFileSystem()).readFully(),
        )
    }

    @Test
    fun readbackIdentityMismatchCannotBeReportedAsSuccess() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("old") }
        val operations = RecordingFileSystem(FailurePoint.READBACK_MISMATCH)

        assertThrows(IllegalStateException::class.java) {
            AcknowledgedAtomicFile(target, operations).write("new".toByteArray())
        }

        assertTrue(operations.calls.any { it.startsWith("sync-directory:") })
        assertThrows(IncompleteAtomicWrite::class.java) {
            AcknowledgedAtomicFile(target, RecordingFileSystem()).readFully()
        }
    }

    @Test
    fun deleteFailureCannotBeReportedAsSuccess() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("value") }
        val operations = RecordingFileSystem(FailurePoint.DELETE)
        val file = AcknowledgedAtomicFile(target, operations)

        assertThrows(IOException::class.java) { file.delete() }

        assertTrue(target.exists())
    }

    @Test
    fun deleteRemovesBaseAndStagedDataBeforeSyncingTheDirectory() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("value") }
        val staged = temporaryFolder.root.resolve(".record.ptc.pending").apply { writeText("staged") }
        val operations = RecordingFileSystem()
        val file = AcknowledgedAtomicFile(target, operations)

        file.delete()

        assertFalse(target.exists())
        assertFalse(staged.exists())
        assertTrue(operations.calls.last().startsWith("sync-directory:"))
    }

    @Test
    fun explicitRetryCheckedDeletesAndSyncsAnIncompleteWriteBeforeStartingAgain() {
        val target = temporaryFolder.root.resolve("record.ptc").apply { writeText("old") }
        val failedOperations = RecordingFileSystem(FailurePoint.ATOMIC_REPLACE)
        assertThrows(IOException::class.java) {
            AcknowledgedAtomicFile(target, failedOperations).write("interrupted".toByteArray())
        }
        val retryOperations = RecordingFileSystem()

        AcknowledgedAtomicFile(target, retryOperations).write("retry".toByteArray())

        assertArrayEquals("retry".toByteArray(), target.readBytes())
        assertTrue(
            retryOperations.calls.containsSubsequence(
                "delete:.record.ptc.pending",
                "sync-directory:${temporaryFolder.root.name}",
                "open:.record.ptc.pending",
            ),
        )
    }

    private fun assertWriteFailsBeforeReplace(failurePoint: FailurePoint) {
        val target = temporaryFolder.root.resolve("record-${failurePoint.name}.ptc").apply {
            writeText("old")
        }
        val operations = RecordingFileSystem(failurePoint)
        val file = AcknowledgedAtomicFile(target, operations)

        assertThrows(IOException::class.java) {
            file.write("new".toByteArray())
        }

        assertArrayEquals("old".toByteArray(), target.readBytes())
        val reopened = AcknowledgedAtomicFile(target, operations)
        assertTrue(reopened.exists())
        assertThrows(IncompleteAtomicWrite::class.java) { reopened.readFully() }
    }

    private enum class FailurePoint {
        FILE_SYNC,
        FILE_CLOSE,
        ATOMIC_REPLACE,
        READBACK_MISMATCH,
        REPLACEMENT_DIRECTORY_SYNC,
        CLEANUP_DIRECTORY_SYNC,
        DELETE,
    }

    private class RecordingFileSystem(
        private val failurePoint: FailurePoint? = null,
    ) : AcknowledgedFileSystem {
        val calls = mutableListOf<String>()

        override fun exists(file: File): Boolean = file.exists()

        override fun isDirectory(file: File): Boolean = file.isDirectory

        override fun listFiles(directory: File): Array<File>? = directory.listFiles()

        override fun ensureDirectory(directory: File) {
            check(directory.isDirectory)
        }

        override fun openOutput(file: File): FileOutputStream {
            calls += "open:${file.name}"
            return FileOutputStream(file, false)
        }

        override fun syncFile(output: FileOutputStream) {
            calls += "sync-file"
            output.fd.sync()
            if (failurePoint == FailurePoint.FILE_SYNC) throw IOException("injected sync failure")
        }

        override fun closeFile(output: FileOutputStream) {
            calls += "close-file"
            output.close()
            if (failurePoint == FailurePoint.FILE_CLOSE) throw IOException("injected close failure")
        }

        override fun atomicReplace(source: File, target: File) {
            calls += "rename:${source.name}->${target.name}"
            if (failurePoint == FailurePoint.ATOMIC_REPLACE) throw IOException("injected rename failure")
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun readFully(file: File): ByteArray {
            calls += "read:${file.name}"
            val bytes = file.readBytes()
            return if (failurePoint == FailurePoint.READBACK_MISMATCH) {
                bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
            } else {
                bytes
            }
        }

        override fun deleteIfExists(file: File) {
            calls += "delete:${file.name}"
            if (failurePoint == FailurePoint.DELETE && !file.name.startsWith(".")) {
                throw IOException("injected delete failure")
            }
            Files.deleteIfExists(file.toPath())
        }

        override fun syncDirectory(directory: File) {
            calls += "sync-directory:${directory.name}"
            val syncAttempt = calls.count { it.startsWith("sync-directory:") }
            if (
                (failurePoint == FailurePoint.REPLACEMENT_DIRECTORY_SYNC && syncAttempt == 2) ||
                (failurePoint == FailurePoint.CLEANUP_DIRECTORY_SYNC && syncAttempt == 3)
            ) {
                throw IOException("injected directory sync failure")
            }
        }
    }
}

private fun List<String>.containsSubsequence(vararg expected: String): Boolean {
    var cursor = 0
    forEach { actual ->
        if (cursor < expected.size && actual == expected[cursor]) cursor += 1
    }
    return cursor == expected.size
}
