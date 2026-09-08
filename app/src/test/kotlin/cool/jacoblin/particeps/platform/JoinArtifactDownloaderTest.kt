package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JoinArtifactDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactArtifactIsReturnedAfterOneRequestAndStagingIsRemoved() = runBlocking {
        val bytes = "signed configuration".toByteArray()
        var calls = 0
        val downloader = downloader(bytes) { calls += 1 }

        val loaded = downloader.download(link(bytes))

        assertArrayEquals(bytes, loaded)
        assertTrue(temporaryFolder.root.walkTopDown().none { it.isFile })
        assertTrue(calls == 1)
    }

    @Test
    fun responseBodyReadsRunOutsideTheCallingDispatcher() = runBlocking {
        val bytes = "signed configuration".toByteArray()
        val reader = AtomicReference<Thread>()
        val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reader.set(Thread.currentThread())
                return super.read(sink, byteCount)
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = bytes.size.toLong()
            override fun source() = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(body)
                .build()
        }.build()
        val downloader = JoinArtifactDownloader(temporaryFolder.root.resolve("join"), client)

        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            withContext(caller) {
                val callingThread = Thread.currentThread()
                assertArrayEquals(bytes, downloader.download(link(bytes)))
                assertNotNull(reader.get())
                assertNotSame(callingThread, reader.get())
            }
        }
        assertTrue(temporaryFolder.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun digestStatusAndSizeFailuresPublishNoStagedBytes() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val wrongDigest = link(bytes).copy(artifactSha256 = "f".repeat(64))
        assertTrue(runCatching { downloader(bytes).download(wrongDigest) }.isFailure)
        assertTrue(runCatching { downloader(bytes, status = 302).download(link(bytes)) }.isFailure)
        val oversized = ByteArray(SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES + 1)
        assertTrue(runCatching { downloader(oversized).download(link(oversized)) }.isFailure)
        assertTrue(temporaryFolder.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun defaultClientForbidsRedirectsAndImplicitRetries() {
        val client = JoinArtifactDownloader.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun initializationRemovesAStalePartialArtifactBeforeAnyJoinRequest() {
        val directory = temporaryFolder.root.resolve("join")
        assertTrue(directory.mkdirs())
        val stale = directory.resolve("artifact.partcfg.tmp")
        Files.write(stale.toPath(), byteArrayOf(1, 2, 3))

        JoinArtifactDownloader(directory, OkHttpClient())

        assertFalse(stale.exists())
    }

    private fun downloader(
        body: ByteArray,
        status: Int = 200,
        onRequest: () -> Unit = {},
    ): JoinArtifactDownloader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            onRequest()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody())
                .build()
        }.build()
        return JoinArtifactDownloader(temporaryFolder.root.resolve("join"), client)
    }

    private fun link(bytes: ByteArray) = JoinLink(
        URI("https://artifacts.example.invalid/opaque-token"),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "A".repeat(32),
    )
}
