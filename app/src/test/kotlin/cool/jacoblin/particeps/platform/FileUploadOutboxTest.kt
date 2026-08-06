package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.application.StudyUploadException
import cool.jacoblin.particeps.core.export.ExportReceipt
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUploadOutboxTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stageIsRecoveredWithoutRegeneratingBytes() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("outbox")
        val outbox = FileUploadOutbox(directory) {}
        val bytes = "immutable ciphertext".toByteArray()
        var writes = 0
        val staged = outbox.stage { output ->
            writes++
            output.write(bytes)
            receipt(bytes)
        }

        val recovered = outbox.recover(
            CONFIGURATION_DIGEST,
            uploadedThroughSequence = 0,
        )

        assertEquals(1, writes)
        assertEquals(staged.receipt, recovered?.receipt)
        assertArrayEquals(bytes, recovered?.body?.readBytes())
    }

    @Test
    fun persistedWatermarkRemovesAlreadyAcknowledgedStageDuringRecovery() =
        kotlinx.coroutines.runBlocking {
            val directory = temporaryFolder.newFolder("covered")
            val outbox = FileUploadOutbox(directory) {}
            val bytes = byteArrayOf(1, 2, 3)
            outbox.stage { output ->
                output.write(bytes)
                receipt(bytes, first = 4, last = 6)
            }

            assertNull(
                outbox.recover(
                    CONFIGURATION_DIGEST,
                    uploadedThroughSequence = 6,
                ),
            )
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun corruptedBodyFailsClosed() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("corrupt")
        val outbox = FileUploadOutbox(directory) {}
        val bytes = byteArrayOf(1, 2, 3)
        val staged = outbox.stage { output ->
            output.write(bytes)
            receipt(bytes)
        }
        staged.body.writeBytes(byteArrayOf(9, 9, 9))

        val failure = runCatching {
            outbox.recover(
                CONFIGURATION_DIGEST,
                uploadedThroughSequence = 0,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(staged.body.exists())
    }

    @Test
    fun terminalFailureSurvivesProcessRecreationUntilDeletion() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("terminal")
        val outbox = FileUploadOutbox(directory) {}
        val bytes = byteArrayOf(1, 2, 3)
        val staged = outbox.stage { output ->
            output.write(bytes)
            receipt(bytes)
        }
        outbox.markTerminal(staged.receipt.bundleId, "UPLOAD_HTTP_400")

        val failure = runCatching {
            FileUploadOutbox(directory) {}.recover(
                CONFIGURATION_DIGEST,
                uploadedThroughSequence = 0,
            )
        }.exceptionOrNull()

        assertTrue(failure is StudyUploadException)
        assertEquals("UPLOAD_HTTP_400", (failure as StudyUploadException).reasonCode)
        assertFalse(failure.retryable)
        outbox.clear()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stagingStopsAtTheHardBodyLimitAndPublishesNothing() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("bounded")
        val outbox = FileUploadOutbox(directory) {}
        val chunk = ByteArray(64 * 1024)

        val failure = runCatching {
            outbox.stage { output ->
                repeat((MAXIMUM_BODY_BYTES / chunk.size).toInt() + 1) { output.write(chunk) }
                error("The bounded stream must reject this body")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun writerReceiptMustMatchTheBytesActuallyStaged() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("receipt-mismatch")
        val outbox = FileUploadOutbox(directory) {}
        val bytes = byteArrayOf(1, 2, 3)

        val failure = runCatching {
            outbox.stage { output ->
                output.write(bytes)
                receipt(bytes).copy(sha256 = "f".repeat(64))
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun orphanBodyFromDirectorySyncFailureIsRemovedOnRecovery() = kotlinx.coroutines.runBlocking {
        val directory = temporaryFolder.newFolder("orphan-body")
        val bytes = byteArrayOf(1, 2, 3)
        val outbox = FileUploadOutbox(directory) { throw IOException("sync failed") }

        assertTrue(runCatching {
            outbox.stage { output ->
                output.write(bytes)
                receipt(bytes)
            }
        }.isFailure)
        assertTrue(directory.resolve("stage.partexp").exists())

        assertNull(FileUploadOutbox(directory) {}.recover(CONFIGURATION_DIGEST, 0))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun publishedManifestRemainsRecoverableIfItsDirectorySyncReportsFailure() =
        kotlinx.coroutines.runBlocking {
            val directory = temporaryFolder.newFolder("published-manifest")
            val bytes = byteArrayOf(1, 2, 3)
            var syncCount = 0
            val outbox = FileUploadOutbox(directory) {
                syncCount += 1
                if (syncCount == 2) throw IOException("manifest sync failed")
            }

            assertTrue(runCatching {
                outbox.stage { output ->
                    output.write(bytes)
                    receipt(bytes)
                }
            }.isFailure)

            val recovered = FileUploadOutbox(directory) {}.recover(CONFIGURATION_DIGEST, 0)
            assertArrayEquals(bytes, recovered?.body?.readBytes())
        }

    private fun receipt(bytes: ByteArray, first: Long = 1, last: Long = 1): ExportReceipt = ExportReceipt(
        bundleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        configurationSha256 = CONFIGURATION_DIGEST,
        firstSequence = first,
        lastSequence = last,
        eventCount = last - first + 1,
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        byteCount = bytes.size.toLong(),
    )

    private companion object {
        const val CONFIGURATION_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"
        const val MAXIMUM_BODY_BYTES = 32L * 1024 * 1024
    }
}
