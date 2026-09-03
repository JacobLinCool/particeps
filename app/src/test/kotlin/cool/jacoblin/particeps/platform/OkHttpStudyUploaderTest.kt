package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.application.StudyUploadPlan
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.UploadReceiptCodec
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OkHttpStudyUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun responseLossRetriesIdenticalCiphertextAndIdempotencyKey() = runBlocking {
        val outbox = FileUploadOutbox(temporaryFolder.newFolder("replay")) {}
        val bytes = "immutable ciphertext".toByteArray()
        val staged = stage(outbox, bytes)
        val bodies = mutableListOf<ByteArray>()
        val ids = mutableListOf<String?>()
        var calls = 0
        val uploader = uploader(outbox) { request ->
            bodies += Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            ids += request.header("X-Particeps-Bundle-Id")
            calls++
            if (calls == 1) throw IOException("response lost")
            response(request, 200, UploadReceiptCodec.encode(staged.receipt))
        }

        val first = runCatching { uploader.send(PLAN, staged) }.exceptionOrNull() as UploadTransportException
        assertTrue(first.retryable)
        assertEquals(staged.receipt, uploader.send(PLAN, staged))
        assertArrayEquals(bodies[0], bodies[1])
        assertEquals(ids[0], ids[1])
        assertArrayEquals(bytes, bodies[0])
    }

    @Test
    fun requestUsesCommitBoundaryVocabularyAndNoParticipantIdentity() = runBlocking {
        val outbox = FileUploadOutbox(temporaryFolder.newFolder("headers")) {}
        val staged = stage(outbox, byteArrayOf(1, 2, 3), first = 4, last = 6, events = 12)
        var headers: okhttp3.Headers? = null
        val uploader = uploader(outbox) { request ->
            headers = request.headers
            response(request, 201, UploadReceiptCodec.encode(staged.receipt))
        }

        uploader.send(PLAN, staged)

        val sent = requireNotNull(headers)
        assertEquals("4", sent["X-Particeps-Commit-From"])
        assertEquals("6", sent["X-Particeps-Commit-To"])
        assertEquals("3", sent["X-Particeps-Commit-Count"])
        assertEquals("12", sent["X-Particeps-Event-Count"])
        assertEquals("export-key", sent["X-Particeps-Researcher-Key-Id"])
        assertTrue(sent.names().none { it.contains("Sequence", ignoreCase = true) })
        assertTrue(sent.names().none { it.contains("Participant", ignoreCase = true) })
        assertTrue(requireNotNull(sent["Content-Digest"]).matches(Regex("sha-256=:[A-Za-z0-9+/]+={0,2}:")))
    }

    @Test
    fun onlyExplicitTransientStatusesAreRetryable() = runBlocking {
        listOf(408, 425, 429, 500, 503).forEach { status ->
            val outbox = FileUploadOutbox(temporaryFolder.newFolder("retry-$status")) {}
            val staged = stage(outbox, byteArrayOf(status.toByte()))
            val failure = runCatching {
                uploader(outbox) { request -> response(request, status, ByteArray(0), null) }
                    .send(PLAN, staged)
            }.exceptionOrNull() as UploadTransportException
            assertTrue("HTTP $status", failure.retryable)
        }
    }

    @Test
    fun redirectAndNonDurableSuccessAreTerminalAndPersisted() = runBlocking {
        listOf(202, 301, 400, 413).forEach { status ->
            val directory = temporaryFolder.newFolder("terminal-$status")
            val outbox = FileUploadOutbox(directory) {}
            val staged = stage(outbox, byteArrayOf(status.toByte()))
            val failure = runCatching {
                uploader(outbox) { request -> response(request, status, ByteArray(0), null) }
                    .send(PLAN, staged)
            }.exceptionOrNull() as UploadTransportException
            assertFalse("HTTP $status", failure.retryable)
            val reopened = runCatching {
                FileUploadOutbox(directory) {}.recover(CONFIGURATION_DIGEST, 0)
            }.exceptionOrNull() as UploadTransportException
            assertEquals("UPLOAD_HTTP_$status", reopened.reason)
        }
    }

    @Test
    fun mismatchedReceiptPermanentlyStopsStage() = runBlocking {
        val outbox = FileUploadOutbox(temporaryFolder.newFolder("mismatch")) {}
        val staged = stage(outbox, byteArrayOf(1, 2, 3))
        val wrong = staged.receipt.copy(sha256 = "f".repeat(64))
        val failure = runCatching {
            uploader(outbox) { request -> response(request, 201, UploadReceiptCodec.encode(wrong)) }
                .send(PLAN, staged)
        }.exceptionOrNull() as UploadTransportException
        assertEquals("UPLOAD_RECEIPT_MISMATCH", failure.reason)
        assertFalse(failure.retryable)
    }

    @Test
    fun invalidReceiptMediaTypeAndSizePermanentlyStopStage() = runBlocking {
        listOf(
            "type" to { request: okhttp3.Request -> response(request, 201, "{}".toByteArray(), "text/plain") },
            "size" to { request: okhttp3.Request -> response(request, 201, ByteArray(2_049), "application/json") },
        ).forEach { (name, responder) ->
            val outbox = FileUploadOutbox(temporaryFolder.newFolder("invalid-$name")) {}
            val staged = stage(outbox, byteArrayOf(1, 2, 3))
            val failure = runCatching { uploader(outbox, responder).send(PLAN, staged) }
                .exceptionOrNull() as UploadTransportException
            assertEquals("UPLOAD_RECEIPT_INVALID", failure.reason)
            assertFalse(failure.retryable)
        }
    }

    private suspend fun stage(
        outbox: FileUploadOutbox,
        bytes: ByteArray,
        first: Long = 1,
        last: Long = 1,
        events: Long = 1,
    ): StagedUpload = outbox.stage { output ->
        output.write(bytes)
        ExportReceipt(
            bundleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            configurationSha256 = CONFIGURATION_DIGEST,
            firstCommitSequence = first,
            lastCommitSequence = last,
            commitCount = last - first + 1,
            eventCount = events,
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).hex(),
            byteCount = bytes.size.toLong(),
        )
    }

    private fun uploader(
        outbox: FileUploadOutbox,
        responder: (okhttp3.Request) -> Response,
    ) = OkHttpStudyUploader(
        outbox = outbox,
        client = OkHttpClient.Builder().addInterceptor { chain -> responder(chain.request()) }.build(),
    )

    private fun response(
        request: okhttp3.Request,
        status: Int,
        body: ByteArray,
        mediaType: String? = "application/json",
    ) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .body(body.toResponseBody(mediaType?.toMediaType()))
        .build()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CONFIGURATION_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val PLAN = StudyUploadPlan(
            experimentId = "upload-test",
            configurationSha256 = CONFIGURATION_DIGEST,
            researcherKeyId = "export-key",
            endpoint = "https://example.invalid/v1",
            intervalMinutes = 60,
            allowMetered = false,
        )
    }
}
