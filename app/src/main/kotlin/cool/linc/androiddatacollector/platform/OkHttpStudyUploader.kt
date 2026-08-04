package cool.linc.androiddatacollector.platform

import cool.linc.androiddatacollector.core.application.StudyUploadException
import cool.linc.androiddatacollector.core.application.StudyUploader
import cool.linc.androiddatacollector.core.export.BundleKind
import cool.linc.androiddatacollector.core.export.BundleProducer
import cool.linc.androiddatacollector.core.export.ExportReceipt
import cool.linc.androiddatacollector.core.export.ExportSnapshot
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.export.UploadReceiptCodec
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.protocol.VerifiedConfiguration
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

/** Stages one immutable encrypted bundle, sends it once, and accepts only its exact receipt. */
class OkHttpStudyUploader internal constructor(
    private val outbox: FileUploadOutbox,
    private val producer: BundleProducer,
    private val client: OkHttpClient = defaultClient(),
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) : StudyUploader {
    private val mutex = Mutex()
    private val deletionRequested = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    override suspend fun reconcile(configuration: VerifiedConfiguration, metadata: StudyMetadata) {
        mutex.withLock {
            requireUploadAllowed()
            recover(configuration, metadata)
        }
    }

    override suspend fun upload(
        configuration: VerifiedConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        fromSequence: Long,
        toSequence: Long,
    ): ExportReceipt = mutex.withLock {
        requireUploadAllowed()
        val upload = requireNotNull(configuration.configuration.upload) {
            "Study does not define an upload endpoint"
        }
        val staged = recover(configuration, metadata) ?: stage(
            configuration,
            metadata,
            events,
            fromSequence,
            toSequence,
        )
        require(staged.receipt.firstSequence == fromSequence) { "Staged upload range start mismatch" }
        require(staged.receipt.lastSequence in fromSequence..toSequence) { "Staged upload range end mismatch" }
        requireUploadAllowed()

        val request = Request.Builder()
            .url(upload.endpoint)
            .apply {
                uploadHeaders(configuration, staged.receipt).forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(staged.body.asRequestBody(BUNDLE_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "Only one upload call may be active" }
        if (deletionRequested.get()) call.cancel()
        val response = try {
            call.awaitResponse()
        } catch (failure: Exception) {
            failure.rethrowCancellation()
            if (deletionRequested.get()) throw deletionFailure(failure)
            throw failure.asUploadFailure()
        } finally {
            activeCall.compareAndSet(call, null)
        }
        response.use {
            if (it.code !in ACCEPTED_STATUS_CODES) {
                val reason = "UPLOAD_HTTP_${it.code}"
                if (it.code.isRetryableStatus()) {
                    throw StudyUploadException(reason, retryable = true)
                }
                markTerminal(staged, reason)
            }

            val receipt = try {
                val body = requireNotNull(it.body) { "Upload receipt body is missing" }
                require(body.contentType()?.let { type -> type.type == "application" && type.subtype == "json" } == true) {
                    "Upload receipt media type is invalid"
                }
                val declaredLength = body.contentLength()
                require(declaredLength == -1L || declaredLength <= MAXIMUM_RECEIPT_BYTES) {
                    "Upload receipt body is too large"
                }
                val bytes = body.byteStream().use { input ->
                    input.readNBytes(MAXIMUM_RECEIPT_BYTES + 1)
                }
                require(bytes.size <= MAXIMUM_RECEIPT_BYTES) { "Upload receipt body is too large" }
                UploadReceiptCodec.decode(bytes)
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                markTerminal(staged, "UPLOAD_RECEIPT_INVALID", failure)
            }
            if (receipt != staged.receipt) {
                markTerminal(staged, "UPLOAD_RECEIPT_MISMATCH")
            }
            receipt
        }
    }

    override suspend fun acknowledge(bundleId: java.util.UUID) = mutex.withLock {
        outboxCall { outbox.acknowledge(bundleId) }
    }

    override suspend fun prepareDeletion() {
        deletionRequested.set(true)
        activeCall.get()?.cancel()
        // Wait for staging/request teardown. A stage created concurrently observes the flag
        // before opening HTTP; an active call is cancelled above.
        mutex.withLock { activeCall.get()?.cancel() }
    }

    override suspend fun clear() = mutex.withLock {
        outboxCall { outbox.clear() }
        deletionRequested.set(false)
    }

    private suspend fun stage(
        configuration: VerifiedConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        fromSequence: Long,
        toSequence: Long,
    ): StagedUpload = try {
        outbox.stage { destination ->
            ResearchExport.encrypt(
                ExportSnapshot(
                    verifiedConfiguration = configuration,
                    metadata = metadata,
                    producer = producer,
                    bundleKind = BundleKind.AUTOMATIC_UPLOAD,
                    exportedAtUtcMillis = nowUtcMillis(),
                    fromSequence = fromSequence,
                    toSequence = toSequence,
                    maximumPlaintextBytes = TARGET_PLAINTEXT_BYTES,
                ),
                events,
                destination,
            )
        }
    } catch (failure: Exception) {
        failure.rethrowCancellation()
        throw failure.asOutboxFailure()
    }

    private fun recover(
        configuration: VerifiedConfiguration,
        metadata: StudyMetadata,
    ): StagedUpload? = outboxCall {
        outbox.recover(
            configurationSha256 = configuration.configurationSha256,
            uploadedThroughSequence = metadata.uploadedThroughSequence,
        )
    }

    private fun markTerminal(staged: StagedUpload, reasonCode: String, cause: Throwable? = null): Nothing {
        outboxCall { outbox.markTerminal(staged.receipt.bundleId, reasonCode) }
        throw StudyUploadException(reasonCode, retryable = false, cause = cause)
    }

    private fun requireUploadAllowed() {
        if (deletionRequested.get()) throw deletionFailure()
    }

    private fun deletionFailure(cause: Throwable? = null) =
        StudyUploadException("UPLOAD_CANCELLED_FOR_DELETION", retryable = false, cause = cause)

    private fun <T> outboxCall(block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        failure.rethrowCancellation()
        if (failure is StudyUploadException) throw failure
        throw failure.asOutboxFailure()
    }

    companion object {
        const val TARGET_PLAINTEXT_BYTES = 16L * 1024 * 1024
        const val MEDIA_TYPE = "application/vnd.adc.research-bundle"
        private const val MAXIMUM_RECEIPT_BYTES = 2_048
        private val ACCEPTED_STATUS_CODES = setOf(200, 201)
        private val BUNDLE_MEDIA_TYPE = MEDIA_TYPE.toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** Complete unencrypted request surface. Assigned participant IDs are deliberately absent. */
internal fun uploadHeaders(
    configuration: VerifiedConfiguration,
    receipt: ExportReceipt,
): Map<String, String> {
    require(receipt.configurationSha256 == configuration.configurationSha256) {
        "Upload receipt configuration digest mismatch"
    }
    return mapOf(
        "Content-Type" to OkHttpStudyUploader.MEDIA_TYPE,
        "Content-Length" to receipt.byteCount.toString(),
        "Content-Digest" to "sha-256=:${receipt.sha256.hexDigestBase64()}:",
        "X-ADC-Bundle-Id" to receipt.bundleId.toString(),
        "X-ADC-Bundle-Format" to ResearchExport.BUNDLE_FORMAT,
        "X-ADC-Configuration-SHA256" to receipt.configurationSha256,
        "X-ADC-Researcher-Key-Id" to configuration.configuration.export.researcherKeyId,
        "X-ADC-Sequence-From" to receipt.firstSequence.toString(),
        "X-ADC-Sequence-To" to receipt.lastSequence.toString(),
        "X-ADC-Event-Count" to receipt.eventCount.toString(),
    )
}

private fun Int.isRetryableStatus(): Boolean = this in setOf(408, 425, 429) || this in 500..599

private fun Throwable.asUploadFailure(): StudyUploadException = when (this) {
    is StudyUploadException -> this
    is java.net.SocketTimeoutException -> StudyUploadException("UPLOAD_TIMEOUT", retryable = true, cause = this)
    is java.net.UnknownHostException -> StudyUploadException("UPLOAD_HOST_UNRESOLVED", retryable = true, cause = this)
    is java.net.ConnectException -> StudyUploadException("UPLOAD_CONNECT_REFUSED", retryable = true, cause = this)
    is javax.net.ssl.SSLHandshakeException ->
        StudyUploadException("UPLOAD_TLS_HANDSHAKE_FAILED", retryable = true, cause = this)
    is javax.net.ssl.SSLException -> StudyUploadException("UPLOAD_TLS_FAILED", retryable = true, cause = this)
    is java.io.InterruptedIOException -> StudyUploadException("UPLOAD_INTERRUPTED", retryable = true, cause = this)
    is IOException -> StudyUploadException("UPLOAD_IO_FAILED", retryable = true, cause = this)
    else -> StudyUploadException("UPLOAD_FAILED", retryable = false, cause = this)
}

private fun Throwable.asOutboxFailure(): StudyUploadException = when (this) {
    is StudyUploadException -> this
    is IOException -> StudyUploadException("UPLOAD_OUTBOX_IO", retryable = true, cause = this)
    else -> StudyUploadException("UPLOAD_OUTBOX_CORRUPT", retryable = false, cause = this)
}

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                } else {
                    response.close()
                }
            }
        },
    )
}

private fun String.hexDigestBase64(): String {
    require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
    val bytes = ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return Base64.getEncoder().encodeToString(bytes)
}
