package cool.linc.androiddatacollector.platform

import cool.linc.androiddatacollector.core.application.StudyUploadException
import cool.linc.androiddatacollector.core.application.StudyUploader
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.export.ExportReceipt
import cool.linc.androiddatacollector.core.export.ExportSnapshot
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Posts one encrypted bundle per call to the study's endpoint.
 *
 * The body is the same HPKE-encrypted bundle a participant would export by hand, so the endpoint
 * receives ciphertext it cannot read. Everything the server needs in order to file and deduplicate
 * a chunk travels in headers, because reading any of it out of the body would require the
 * researcher's private key.
 */
class OkHttpStudyUploader(
    private val client: OkHttpClient = defaultClient(),
) : StudyUploader {

    override suspend fun upload(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        fromSequence: Long,
        toSequence: Long,
    ): ExportReceipt {
        val upload = requireNotNull(configuration.upload) { "Study does not define an upload endpoint" }
        var receipt: ExportReceipt? = null

        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            /**
             * The bundle is generated as it is written, so its length is not known up front and
             * OkHttp falls back to chunked transfer encoding.
             */
            override fun contentLength() = -1L

            /** The stream is built once from the store; OkHttp must not silently replay it. */
            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                receipt = runBlocking {
                    ResearchExport.encrypt(
                        ExportSnapshot(
                            configuration = configuration,
                            metadata = metadata,
                            exportedAtUtcMillis = System.currentTimeMillis(),
                            fromSequence = fromSequence,
                            toSequence = toSequence,
                            maximumPlaintextBytes = BUDGET_BYTES,
                        ),
                        events,
                        sink.outputStream(),
                    )
                }
            }
        }

        val request = Request.Builder()
            .url(upload.endpoint)
            .header("Content-Type", "application/octet-stream")
            .header("X-ADC-Bundle-Format", ResearchExport.BUNDLE_FORMAT)
            .header("X-ADC-Experiment-Id", configuration.experimentId)
            .header("X-ADC-Configuration-Id", configuration.configurationId)
            .header("X-ADC-Participant-Instance", metadata.participantInstanceId)
            .header("X-ADC-Sequence-From", fromSequence.toString())
            // Named "at most" because headers are sent before the body is generated, and a budget
            // can stop the bundle at any earlier event boundary. The endpoint cannot learn the true
            // upper bound — that is inside the ciphertext — so it must not file by this value.
            // `X-ADC-Sequence-From` is exact and strictly increasing per participant, which is what
            // makes a usable deduplication key.
            .header("X-ADC-Sequence-To-At-Most", toSequence.toString())
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw StudyUploadException("UPLOAD_HTTP_${response.code}")
                }
            }
        } catch (failure: StudyUploadException) {
            throw failure
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // The classified code is what reaches the participant's screen. The transport exception
            // is logged as well because a delivery problem is otherwise undiagnosable from a device,
            // and a network error carries no study data.
            android.util.Log.w("AdcUpload", "Upload failed", failure)
            throw StudyUploadException(failure.reasonCode(), failure)
        }
        return requireNotNull(receipt) { "Upload completed without producing a receipt" }
    }

    companion object {
        /**
         * Plaintext budget for one request. The study's configured cadence is what paces delivery;
         * this only keeps a single request to a size a phone on a real network can finish, and only
         * binds while a backlog is being worked off. A bundle stops at the first event boundary past
         * it and the next run picks up from there.
         */
        const val BUDGET_BYTES = 16L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(60, TimeUnit.SECONDS)
            // Left on, which is the default. OkHttp already refuses to replay a one-shot body once
            // it has started sending, so this only recovers the case where a pooled connection turns
            // out to be dead before anything was written — a hard failure otherwise, and a common
            // one between widely spaced uploads.
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Maps a transport failure onto a fixed code, so what reaches a screen or a log is never data. */
private fun Throwable.reasonCode(): String = when (this) {
    is java.net.SocketTimeoutException -> "UPLOAD_TIMEOUT"
    is java.net.UnknownHostException -> "UPLOAD_HOST_UNRESOLVED"
    is java.net.ConnectException -> "UPLOAD_CONNECT_REFUSED"
    is javax.net.ssl.SSLHandshakeException -> "UPLOAD_TLS_HANDSHAKE_FAILED"
    is javax.net.ssl.SSLException -> "UPLOAD_TLS_FAILED"
    is java.io.InterruptedIOException -> "UPLOAD_INTERRUPTED"
    is IOException -> "UPLOAD_IO_FAILED"
    else -> "UPLOAD_FAILED"
}
