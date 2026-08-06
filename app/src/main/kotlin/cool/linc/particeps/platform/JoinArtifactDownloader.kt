package cool.linc.particeps.platform

import cool.linc.particeps.core.protocol.JoinLink
import cool.linc.particeps.core.protocol.SignedConfigurationCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads one immutable join artifact into no-backup staging, then removes every staged byte. */
class JoinArtifactDownloader internal constructor(
    private val directory: File,
    private val client: OkHttpClient = defaultClient(),
) {
    private val mutex = Mutex()

    init {
        ensureDirectory()
        deleteStaging(directory.resolve(STAGING_FILE))
    }

    suspend fun download(link: JoinLink): ByteArray = mutex.withLock {
        val staging = directory.resolve(STAGING_FILE)
        deleteStaging(staging)
        try {
            val request = Request.Builder()
                .url(link.artifactUrl.toASCIIString())
                .get()
                .build()
            val response = client.newCall(request).awaitResponse()
            response.use {
                require(it.code == 200) { "Join artifact HTTP ${it.code}" }
                val body = requireNotNull(it.body) { "Join artifact body is missing" }
                val declared = body.contentLength()
                require(
                    declared == -1L ||
                        declared in 1L..SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES.toLong(),
                ) {
                    "Join artifact size is out of bounds"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                FileOutputStream(staging).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES.toLong()) {
                                "Join artifact is too large"
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    output.fd.sync()
                }
                require(count > 0 && (declared == -1L || count == declared)) {
                    "Join artifact length mismatch"
                }
                require(digest.digest().toHex() == link.artifactSha256) {
                    "Join artifact digest mismatch"
                }
            }
            staging.readBytes()
        } finally {
            deleteStaging(staging)
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create join staging")
    }

    private fun deleteStaging(staging: File) {
        Files.deleteIfExists(staging.toPath())
    }

    companion object {
        private const val STAGING_FILE = "artifact.partcfg.tmp"
        private const val BUFFER_BYTES = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
