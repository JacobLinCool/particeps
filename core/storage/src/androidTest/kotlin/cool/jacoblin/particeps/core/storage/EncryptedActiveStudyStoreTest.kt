package cool.jacoblin.particeps.core.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedActiveStudyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val operations = FaultInjectingFileSystem()
    private val store = EncryptedActiveStudyStore(context, operations)

    @Before
    fun setUp() = runBlocking {
        operations.failure = null
        store.clear()
    }

    @After
    fun tearDown() = runBlocking {
        operations.failure = null
        store.clear()
    }

    @Test
    fun deletionTombstoneRenameFailureLeavesCrashEvidenceThatBlocksTheOldActiveStudy() = runBlocking {
        val envelope = "signed-study-envelope".toByteArray()
        store.save(envelope)
        operations.failure = Failure.ATOMIC_REPLACE

        assertThrows(IOException::class.java) {
            runBlocking { store.markDeletionPending("experiment-test", 16L * 1024 * 1024) }
        }

        operations.failure = null
        val reopened = EncryptedActiveStudyStore(context, operations)
        assertThrows(IncompleteAtomicWrite::class.java) {
            runBlocking { reopened.load() }
        }
        Unit
    }

    @Test
    fun activeStudyDeleteFailureCannotBeReportedAsClear() = runBlocking {
        val envelope = "signed-study-envelope".toByteArray()
        store.save(envelope)
        operations.failure = Failure.DELETE

        assertThrows(IOException::class.java) { runBlocking { store.clear() } }

        operations.failure = null
        val active = store.load() as ActiveStudyRecord.Active
        assertArrayEquals(envelope, active.envelopeBytes)
    }

    @Test
    fun rc5FrameworkAtomicFileResidueBlocksActiveStudyRecovery() = runBlocking {
        store.save("signed-study-envelope".toByteArray())
        context.noBackupFilesDir.resolve("active-study.ptc.new").writeText("unresolved rc.5 write")

        val reopened = EncryptedActiveStudyStore(context, operations)

        assertThrows(IncompleteAtomicWrite::class.java) {
            runBlocking { reopened.load() }
        }
        Unit
    }

    private enum class Failure {
        ATOMIC_REPLACE,
        DELETE,
    }

    private class FaultInjectingFileSystem : AcknowledgedFileSystem {
        var failure: Failure? = null

        override fun exists(file: File): Boolean = AndroidAcknowledgedFileSystem.exists(file)

        override fun isDirectory(file: File): Boolean =
            AndroidAcknowledgedFileSystem.isDirectory(file)

        override fun listFiles(directory: File): Array<File>? =
            AndroidAcknowledgedFileSystem.listFiles(directory)

        override fun ensureDirectory(directory: File) =
            AndroidAcknowledgedFileSystem.ensureDirectory(directory)

        override fun openOutput(file: File): FileOutputStream =
            AndroidAcknowledgedFileSystem.openOutput(file)

        override fun syncFile(output: FileOutputStream) =
            AndroidAcknowledgedFileSystem.syncFile(output)

        override fun closeFile(output: FileOutputStream) =
            AndroidAcknowledgedFileSystem.closeFile(output)

        override fun atomicReplace(source: File, target: File) {
            if (failure == Failure.ATOMIC_REPLACE) throw IOException("injected rename failure")
            AndroidAcknowledgedFileSystem.atomicReplace(source, target)
        }

        override fun readFully(file: File): ByteArray =
            AndroidAcknowledgedFileSystem.readFully(file)

        override fun deleteIfExists(file: File) {
            if (failure == Failure.DELETE && file.name == "active-study.ptc") {
                throw IOException("injected delete failure")
            }
            AndroidAcknowledgedFileSystem.deleteIfExists(file)
        }

        override fun syncDirectory(directory: File) =
            AndroidAcknowledgedFileSystem.syncDirectory(directory)
    }
}
