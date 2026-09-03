package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudyUploadPlan
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.export.UploadReceiptCodec
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

/**
 * Stages one immutable encrypted EngineCommit range, sends exactly those bytes, and accepts only
 * the canonical receipt for that same idempotency key and complete-commit boundary.
 */
class OkHttpStudyUploader internal constructor(
    private val outbox: FileUploadOutbox,
    private val client: OkHttpClient = defaultClient(),
    private val bundleIds: () -> UUID = UUID::randomUUID,
) {
    private val mutex = Mutex()
    private val deletionRequested = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    internal suspend fun reconcile(plan: StudyUploadPlan, uploadedThroughCommit: Long): StagedUpload? = mutex.withLock {
        requireUploadAllowed()
        recoverLocked(plan, uploadedThroughCommit)
    }

    internal suspend fun recover(plan: StudyUploadPlan, uploadedThroughCommit: Long): StagedUpload? = mutex.withLock {
        requireUploadAllowed()
        recoverLocked(plan, uploadedThroughCommit)
    }

    internal suspend fun stage(session: StudySessionManager, plan: StudyUploadPlan): StagedUpload? = mutex.withLock {
        requireUploadAllowed()
        recoverLocked(plan, session.snapshot.value.runtime.uploadedThroughCommit)?.let { return@withLock it }
        val runtime = session.snapshot.value.runtime
        if (runtime.uploadedThroughCommit >= runtime.durableThroughCommit) return@withLock null
        val bundleId = bundleIds()
        try {
            outbox.stage { destination ->
                checkNotNull(
                    session.prepareAutomaticUpload(
                        destination = destination,
                        bundleId = bundleId,
                        maximumPlaintextBytes = TARGET_PLAINTEXT_BYTES,
                    ),
                ) { "Upload range disappeared while staging" }
            }
        } catch (failure: Exception) {
            failure.rethrowCancellation()
            throw failure.asOutboxFailure()
        }
    }

    internal suspend fun send(plan: StudyUploadPlan, staged: StagedUpload): ExportReceipt = mutex.withLock {
        requireUploadAllowed()
        require(staged.receipt.configurationSha256 == plan.configurationSha256) {
            "Staged upload configuration digest mismatch"
        }
        staged.terminalFailureCode?.let { throw UploadTransportException(it, retryable = false) }
        val request = Request.Builder()
            .url(plan.endpoint)
            .apply {
                uploadHeaders(plan, staged.receipt).forEach { (name, value) -> header(name, value) }
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
                if (it.code.isRetryableStatus()) throw UploadTransportException(reason, retryable = true)
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
                val bytes = body.byteStream().use { input -> input.readNBytes(MAXIMUM_RECEIPT_BYTES + 1) }
                require(bytes.size <= MAXIMUM_RECEIPT_BYTES) { "Upload receipt body is too large" }
                UploadReceiptCodec.decode(bytes)
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                markTerminal(staged, "UPLOAD_RECEIPT_INVALID", failure)
            }
            if (receipt != staged.receipt) markTerminal(staged, "UPLOAD_RECEIPT_MISMATCH")
            receipt
        }
    }

    suspend fun acknowledge(bundleId: UUID) = mutex.withLock {
        outboxCall { outbox.acknowledge(bundleId) }
    }

    suspend fun prepareDeletion() {
        deletionRequested.set(true)
        activeCall.get()?.cancel()
        mutex.withLock { activeCall.get()?.cancel() }
    }

    suspend fun clear() = mutex.withLock {
        outboxCall { outbox.clear() }
        deletionRequested.set(false)
    }

    private fun recoverLocked(plan: StudyUploadPlan, uploadedThroughCommit: Long): StagedUpload? = outboxCall {
        outbox.recover(
            configurationSha256 = plan.configurationSha256,
            uploadedThroughCommit = uploadedThroughCommit,
        )
    }

    private fun markTerminal(staged: StagedUpload, reasonCode: String, cause: Throwable? = null): Nothing {
        outboxCall { outbox.markTerminal(staged.receipt.bundleId, reasonCode) }
        throw UploadTransportException(reasonCode, retryable = false, cause = cause)
    }

    private fun requireUploadAllowed() {
        if (deletionRequested.get()) throw deletionFailure()
    }

    private fun deletionFailure(cause: Throwable? = null) =
        UploadTransportException("UPLOAD_CANCELLED_FOR_DELETION", retryable = false, cause = cause)

    private fun <T> outboxCall(block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        failure.rethrowCancellation()
        if (failure is UploadTransportException) throw failure
        throw failure.asOutboxFailure()
    }

    companion object {
        const val TARGET_PLAINTEXT_BYTES = 16L * 1024 * 1024
        const val MEDIA_TYPE = "application/vnd.particeps.research-bundle"
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

class UploadTransportException(
    val reason: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(reason, cause) {
    init { require(REASON.matches(reason)) { "Invalid upload failure reason" } }

    private companion object { val REASON = Regex("[A-Z][A-Z0-9_]{2,63}") }
}

/** Complete unencrypted request surface. Participant and automation identities are absent. */
internal fun uploadHeaders(plan: StudyUploadPlan, receipt: ExportReceipt): Map<String, String> {
    require(receipt.configurationSha256 == plan.configurationSha256) {
        "Upload receipt configuration digest mismatch"
    }
    return mapOf(
        "Content-Type" to OkHttpStudyUploader.MEDIA_TYPE,
        "Content-Length" to receipt.byteCount.toString(),
        "Content-Digest" to "sha-256=:${receipt.sha256.hexDigestBase64()}:",
        "X-Particeps-Bundle-Id" to receipt.bundleId.toString(),
        "X-Particeps-Bundle-Format" to ResearchExport.BUNDLE_FORMAT,
        "X-Particeps-Configuration-SHA256" to receipt.configurationSha256,
        "X-Particeps-Researcher-Key-Id" to plan.researcherKeyId,
        "X-Particeps-Commit-From" to receipt.firstCommitSequence.toString(),
        "X-Particeps-Commit-To" to receipt.lastCommitSequence.toString(),
        "X-Particeps-Commit-Count" to receipt.commitCount.toString(),
        "X-Particeps-Event-Count" to receipt.eventCount.toString(),
    )
}

private fun Int.isRetryableStatus(): Boolean = this in setOf(408, 425, 429) || this in 500..599

private fun Throwable.asUploadFailure(): UploadTransportException = when (this) {
    is UploadTransportException -> this
    is java.net.SocketTimeoutException -> UploadTransportException("UPLOAD_TIMEOUT", retryable = true, cause = this)
    is java.net.UnknownHostException -> UploadTransportException("UPLOAD_HOST_UNRESOLVED", true, this)
    is java.net.ConnectException -> UploadTransportException("UPLOAD_CONNECT_REFUSED", true, this)
    is javax.net.ssl.SSLHandshakeException -> UploadTransportException("UPLOAD_TLS_HANDSHAKE_FAILED", true, this)
    is javax.net.ssl.SSLException -> UploadTransportException("UPLOAD_TLS_FAILED", true, this)
    is java.io.InterruptedIOException -> UploadTransportException("UPLOAD_INTERRUPTED", true, this)
    is IOException -> UploadTransportException("UPLOAD_IO_FAILED", true, this)
    else -> UploadTransportException("UPLOAD_FAILED", false, this)
}

private fun Throwable.asOutboxFailure(): UploadTransportException = when (this) {
    is UploadTransportException -> this
    is IOException -> UploadTransportException("UPLOAD_OUTBOX_IO", true, this)
    else -> UploadTransportException("UPLOAD_OUTBOX_CORRUPT", false, this)
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
