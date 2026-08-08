package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.application.StudyUploadException
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.UploadConfiguration
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.export.UploadReceiptCodec
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import com.google.gson.JsonParser
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
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
    fun responseLossRetriesTheIdenticalFixedLengthBundleAndAcceptsExactReplay() = runBlocking {
        val directory = temporaryFolder.newFolder("replay")
        val outbox = FileUploadOutbox(directory) {}
        val body = "ciphertext bytes".toByteArray()
        val expected = stage(outbox, body)
        val sentBodies = mutableListOf<ByteArray>()
        var calls = 0
        val client = client { request ->
            sentBodies += Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            calls++
            if (calls == 1) throw IOException("response lost")
            response(request, 200, UploadReceiptCodec.encode(expected))
        }
        val uploader = uploader(outbox, client)

        val first = runCatching { uploader.upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1) }.exceptionOrNull()
        assertTrue(first is StudyUploadException)
        assertTrue((first as StudyUploadException).retryable)

        assertEquals(expected, uploader.upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1))
        assertEquals(2, sentBodies.size)
        assertArrayEquals(body, sentBodies[0])
        assertArrayEquals(sentBodies[0], sentBodies[1])
    }

    @Test
    fun requestVocabularyMatchesTheSharedProtocolCorpus() = runBlocking {
        // The only reader of these names is the TypeScript Worker, which asserts against the same
        // fixture. Comparing them here against this module's own constants would pass however
        // either side were spelled, which is exactly how a rename splits a wire contract in two.
        val corpus = JsonParser
            .parseString(
                Path.of(requireNotNull(System.getProperty("particeps.appProjectDir")))
                    .resolve("../protocol/v1/conformance-vectors.json")
                    .normalize()
                    .toFile()
                    .readText(),
            )
            .asJsonObject
            .getAsJsonObject("valid")
            .getAsJsonObject("upload_request")
        assertEquals(corpus.get("media_type").asString, OkHttpStudyUploader.MEDIA_TYPE)
        assertEquals(corpus.get("bundle_format").asString, ResearchExport.BUNDLE_FORMAT)

        val directory = temporaryFolder.newFolder("vocabulary")
        val outbox = FileUploadOutbox(directory) {}
        val expected = stage(outbox, byteArrayOf(1, 2, 3))
        var headers: okhttp3.Headers? = null
        val client = client { request ->
            headers = request.headers
            response(request, 201, UploadReceiptCodec.encode(expected))
        }

        uploader(outbox, client).upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)

        // HTTP header names are case-insensitive and the receiver matches them lowercased, so the
        // comparison is between the same two sets the wire actually carries.
        val sent = requireNotNull(headers).names()
            .map { it.lowercase() }
            .filter { it.startsWith("x-particeps-") }
            .sorted()
        val fixed = corpus.getAsJsonArray("routing_headers").map { it.asString.lowercase() }.sorted()
        assertEquals(fixed, sent)
    }

    @Test
    fun requestCarriesOnlyBoundedReceiverMetadataAndAnRfcContentDigest() = runBlocking {
        val directory = temporaryFolder.newFolder("headers")
        val outbox = FileUploadOutbox(directory) {}
        val expected = stage(outbox, byteArrayOf(1, 2, 3))
        var headers: okhttp3.Headers? = null
        val client = client { request ->
            headers = request.headers
            response(request, 201, UploadReceiptCodec.encode(expected))
        }

        uploader(outbox, client).upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)

        val sent = requireNotNull(headers)
        assertEquals(expected.byteCount.toString(), sent["Content-Length"])
        assertTrue(requireNotNull(sent["Content-Digest"]).matches(Regex("sha-256=:[A-Za-z0-9+/]+={0,2}:")))
        assertEquals(OkHttpStudyUploader.MEDIA_TYPE, sent["Content-Type"])
        assertEquals(CONFIGURATION_DIGEST, sent["X-Particeps-Configuration-SHA256"])
        assertTrue(sent.names().none { it.contains("Participant", ignoreCase = true) })
        assertTrue(sent.values("X-Particeps-Participant-Instance").none { it == METADATA.participantInstanceId })
    }

    @Test
    fun nonRetryableResponseIsPersistedAsTerminalWithoutAdvancingToAnotherRequest() = runBlocking {
        val directory = temporaryFolder.newFolder("terminal-http")
        val outbox = FileUploadOutbox(directory) {}
        stage(outbox, byteArrayOf(1, 2, 3))
        var calls = 0
        val client = client { request ->
            calls++
            response(request, 400, ByteArray(0), mediaType = null)
        }
        val uploader = uploader(outbox, client)

        val first = runCatching { uploader.upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1) }.exceptionOrNull()
        val recovered = runCatching { uploader.reconcile(VERIFIED, METADATA) }.exceptionOrNull()

        assertEquals("UPLOAD_HTTP_400", (first as StudyUploadException).reasonCode)
        assertFalse(first.retryable)
        assertEquals("UPLOAD_HTTP_400", (recovered as StudyUploadException).reasonCode)
        assertEquals(1, calls)
    }

    @Test
    fun onlySpecifiedHttpFailuresAreRetryable() = runBlocking {
        listOf(408, 425, 429, 500, 503).forEach { status ->
            val outbox = FileUploadOutbox(temporaryFolder.newFolder("retry-$status")) {}
            stage(outbox, byteArrayOf(status.toByte()))
            val client = client { request -> response(request, status, ByteArray(0), mediaType = null) }

            val failure = runCatching {
                uploader(outbox, client).upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)
            }.exceptionOrNull() as StudyUploadException

            assertTrue("HTTP $status", failure.retryable)
        }
    }

    @Test
    fun redirectsAcceptedButUndurableAndOversizedRequestsAreTerminal() = runBlocking {
        listOf(202, 301, 400, 413).forEach { status ->
            val outbox = FileUploadOutbox(temporaryFolder.newFolder("terminal-$status")) {}
            stage(outbox, byteArrayOf(status.toByte()))
            val client = client { request -> response(request, status, ByteArray(0), mediaType = null) }

            val failure = runCatching {
                uploader(outbox, client).upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)
            }.exceptionOrNull() as StudyUploadException

            assertEquals("UPLOAD_HTTP_$status", failure.reasonCode)
            assertFalse("HTTP $status", failure.retryable)
        }
    }

    @Test
    fun mismatchedReceiptPermanentlyStopsThatStage() = runBlocking {
        val outbox = FileUploadOutbox(temporaryFolder.newFolder("receipt-mismatch")) {}
        val expected = stage(outbox, byteArrayOf(1, 2, 3))
        val wrong = expected.copy(sha256 = "f".repeat(64))
        val client = client { request -> response(request, 201, UploadReceiptCodec.encode(wrong)) }
        val uploader = uploader(outbox, client)

        val failure = runCatching {
            uploader.upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)
        }.exceptionOrNull() as StudyUploadException

        assertEquals("UPLOAD_RECEIPT_MISMATCH", failure.reasonCode)
        assertFalse(failure.retryable)
    }

    @Test
    fun invalidReceiptMediaTypeAndSizePermanentlyStopTheirStages() = runBlocking {
        listOf(
            "text" to { request: okhttp3.Request ->
                response(request, 201, "{}".toByteArray(), mediaType = "text/plain")
            },
            "oversize" to { request: okhttp3.Request ->
                response(request, 201, ByteArray(2_049), mediaType = "application/json")
            },
        ).forEach { (name, responder) ->
            val outbox = FileUploadOutbox(temporaryFolder.newFolder("invalid-receipt-$name")) {}
            stage(outbox, byteArrayOf(1, 2, 3))
            val failure = runCatching {
                uploader(outbox, client(responder)).upload(VERIFIED, METADATA, UNUSED_STORE, 1, 1)
            }.exceptionOrNull() as StudyUploadException

            assertEquals("UPLOAD_RECEIPT_INVALID", failure.reasonCode)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun firstUploadStagesARealEncryptedBundleBeforeSendingIt() = runBlocking {
        val privateKey = ProtocolBase64Url.decodeExact(RESEARCHER_PRIVATE_KEY, 32, "test private key")
        val configuration = CONFIGURATION.copy(
            export = ExportConfiguration("export-key", RESEARCHER_PUBLIC_KEY),
        )
        val canonical = StudyConfigurationCodec.encode(configuration)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical).hex()
        val verified = VerifiedConfiguration(
            configuration,
            canonical,
            configuration.signer.keyId,
            ByteArray(64),
            digest,
            false,
        )
        val metadata = StudyMetadata.initial(
            configuration.experimentId,
            configuration.configurationId,
            configuration.assignedParticipantId,
            PARTICIPANT_ID,
        ).copy(eventCount = 1, nextSequenceNumber = 2)
        val event = RecordedEvent(
            sequenceNumber = 1,
            collectorId = AppLifecycleConfiguration.ID,
            payloadSchemaVersion = 1,
            observedTime = ResearchTime(1_000, 2_000, "boot-test"),
            payloadType = "ACTIVITY_RESUMED",
            fields = mapOf("activity_class" to "test.Activity"),
        )
        val store = storeOf(event)
        var sent: ByteArray? = null
        val client = client { request ->
            val bytes = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            sent = bytes
            val receipt = ExportReceipt(
                bundleId = UUID.fromString(requireNotNull(request.header("X-Particeps-Bundle-Id"))),
                configurationSha256 = requireNotNull(request.header("X-Particeps-Configuration-SHA256")),
                firstSequence = requireNotNull(request.header("X-Particeps-Sequence-From")).toLong(),
                lastSequence = requireNotNull(request.header("X-Particeps-Sequence-To")).toLong(),
                eventCount = requireNotNull(request.header("X-Particeps-Event-Count")).toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).hex(),
                byteCount = bytes.size.toLong(),
            )
            response(request, 201, UploadReceiptCodec.encode(receipt))
        }
        val outbox = FileUploadOutbox(temporaryFolder.newFolder("real-stage")) {}

        val receipt = uploader(outbox, client).upload(verified, metadata, store, 1, 1)
        val encrypted = requireNotNull(sent)
        val plaintext = ResearchExport.decrypt(encrypted, privateKey, configuration).toString(Charsets.UTF_8)

        assertEquals(encrypted.size.toLong(), receipt.byteCount)
        assertTrue(plaintext.contains("\"bundle_kind\":\"automatic_upload\""))
        assertTrue(plaintext.contains("\"sequence_number\":\"1\""))
        assertTrue(plaintext.contains("test.Activity"))
    }

    private suspend fun stage(outbox: FileUploadOutbox, bytes: ByteArray): ExportReceipt =
        outbox.stage { output ->
            output.write(bytes)
            ExportReceipt(
                bundleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                configurationSha256 = CONFIGURATION_DIGEST,
                firstSequence = 1,
                lastSequence = 1,
                eventCount = 1,
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                byteCount = bytes.size.toLong(),
            )
        }.receipt

    private fun uploader(outbox: FileUploadOutbox, client: OkHttpClient) = OkHttpStudyUploader(
        outbox = outbox,
        producer = BundleProducer(StudyConfiguration.ANDROID_PLATFORM, "1"),
        client = client,
        nowUtcMillis = { 1 },
    )

    private fun client(intercept: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> intercept(chain.request()) }.build()

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

    private fun storeOf(vararg events: RecordedEvent): StudyStore = object : StudyStore {
        override suspend fun loadMetadata(): StudyMetadata? = error("unused")
        override suspend fun initialize(metadata: StudyMetadata) = error("unused")
        override suspend fun saveMetadata(metadata: StudyMetadata) = error("unused")
        override suspend fun appendEvent(event: RecordedEvent) = error("unused")
        override suspend fun appendEventAtomically(
            event: RecordedEvent,
            metadata: StudyMetadata,
            failureTime: ResearchTime,
        ) = error("unused")
        override suspend fun resolvePendingAppendFailure(
            reason: cool.jacoblin.particeps.core.model.TransitionReason,
        ): StudyMetadata? = error("unused")
        override suspend fun readEvents(
            fromSequenceInclusive: Long,
            upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit,
        ) = events.filter { it.sequenceNumber in fromSequenceInclusive..upToSequenceInclusive }.forEach(consume)
        override suspend fun storageUsage(): StorageUsage = error("unused")
        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata = error("unused")
        override suspend fun clear() = error("unused")
    }

    private companion object {
        const val EXPERIMENT_ID = "upload-test"
        const val CONFIGURATION_ID = "upload-config"
        const val PARTICIPANT_ID = "00000000-0000-0000-0000-000000000001"
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val RESEARCHER_PRIVATE_KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2A"
        const val RESEARCHER_PUBLIC_KEY = "ZLEBsdC-WocEvQePmJUAH8A-jp-VIvGI3RKNmEbUhGY"
        const val CONFIGURATION_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"

        val CONFIGURATION = StudyConfiguration(
            schemaVersion = 1,
            experimentId = EXPERIMENT_ID,
            configurationId = CONFIGURATION_ID,
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = StudyConfiguration.ANDROID_PLATFORM,
            minimumClientVersion = 1,
            title = "Upload test",
            researcherName = "Researcher",
            researcherContact = "research@example.invalid",
            purpose = "Verify the replay-safe upload transaction.",
            durationHours = 1,
            consentDocumentVersion = "v1",
            consentSummary = "Test consent.",
            assignedParticipantId = "assigned-secret",
            collectors = listOf(AppLifecycleConfiguration(true)),
            surveys = emptyList(),
            interventions = emptyList(),
            maximumLocalBytes = 16_777_216,
            signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
            export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
            upload = UploadConfiguration("https://example.invalid/v1", 60, false),
        )
        val VERIFIED = VerifiedConfiguration(
            CONFIGURATION,
            byteArrayOf(1),
            CONFIGURATION.signer.keyId,
            ByteArray(64),
            CONFIGURATION_DIGEST,
            false,
        )
        val METADATA = StudyMetadata.initial(
            EXPERIMENT_ID,
            CONFIGURATION_ID,
            CONFIGURATION.assignedParticipantId,
            PARTICIPANT_ID,
        )
        val UNUSED_STORE = object : StudyStore {
            override suspend fun loadMetadata(): StudyMetadata? = error("unused")
            override suspend fun initialize(metadata: StudyMetadata) = error("unused")
            override suspend fun saveMetadata(metadata: StudyMetadata) = error("unused")
            override suspend fun appendEvent(event: RecordedEvent) = error("unused")
            override suspend fun appendEventAtomically(
                event: RecordedEvent,
                metadata: StudyMetadata,
                failureTime: ResearchTime,
            ) = error("unused")
            override suspend fun resolvePendingAppendFailure(
                reason: cool.jacoblin.particeps.core.model.TransitionReason,
            ): StudyMetadata? = error("unused")
            override suspend fun readEvents(
                fromSequenceInclusive: Long,
                upToSequenceInclusive: Long,
                consume: (RecordedEvent) -> Unit,
            ) = error("unused")
            override suspend fun storageUsage(): StorageUsage = error("unused")
            override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata = error("unused")
            override suspend fun clear() = error("unused")
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
