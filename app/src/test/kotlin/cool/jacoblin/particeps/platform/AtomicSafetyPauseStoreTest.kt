package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.storage.AcknowledgedFile
import cool.jacoblin.particeps.core.storage.AcknowledgedFileCandidate
import cool.jacoblin.particeps.core.storage.AcknowledgedFileCandidateRole
import cool.jacoblin.particeps.core.storage.IncompleteAtomicWrite
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicSafetyPauseStoreTest {
    @Test
    fun acknowledgedWriteFailurePropagatesToTheTypedWorkerFallbackCaller() {
        val file = FakeAcknowledgedFile().apply {
            writeFailure = IOException("injected marker durability failure")
        }
        val store = AtomicSafetyPauseStore(file)

        assertThrows(IOException::class.java) {
            runBlocking { store.markPending(SafetyPauseReason.STORAGE_FAILURE) }
        }

        assertEquals(null, file.bytes)
    }

    @Test
    fun acknowledgedDeleteFailureCannotClearTheMarker() {
        val file = FakeAcknowledgedFile().apply {
            bytes = SafetyPauseMarkerCodec.encode(SafetyPauseReason.STORAGE_FAILURE)
            deleteFailure = IOException("injected marker delete failure")
        }
        val store = AtomicSafetyPauseStore(file)

        assertThrows(IOException::class.java) { runBlocking { store.clear() } }

        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runBlocking { store.pendingReason() })
    }

    @Test
    fun incompleteAtomicMarkerBlocksRecoveryInsteadOfAppearingAbsent() {
        val file = FakeAcknowledgedFile().apply {
            incompleteWrite = true
        }
        val reopenedStore = AtomicSafetyPauseStore(file)

        assertThrows(IncompleteAtomicWrite::class.java) {
            runBlocking { reopenedStore.pendingReason() }
        }
    }

    private class FakeAcknowledgedFile : AcknowledgedFile {
        override val baseFile = File("unused-safety-marker")
        var bytes: ByteArray? = null
        var writeFailure: IOException? = null
        var deleteFailure: IOException? = null
        var incompleteWrite = false

        override fun exists(): Boolean = bytes != null || incompleteWrite

        override fun readFully(): ByteArray {
            if (incompleteWrite) throw IncompleteAtomicWrite(baseFile)
            return requireNotNull(bytes).copyOf()
        }

        override fun candidates(): List<AcknowledgedFileCandidate> = bytes?.let {
            listOf(AcknowledgedFileCandidate(AcknowledgedFileCandidateRole.BASE, it.copyOf()))
        } ?: emptyList()

        override fun write(bytes: ByteArray) {
            writeFailure?.let { throw it }
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            deleteFailure?.let { throw it }
            bytes = null
        }
    }
}
