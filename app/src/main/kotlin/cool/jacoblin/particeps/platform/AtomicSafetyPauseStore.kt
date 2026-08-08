package cool.jacoblin.particeps.platform

import android.content.Context
import cool.jacoblin.particeps.core.application.SafetyPauseStore
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.storage.AcknowledgedAtomicFile
import cool.jacoblin.particeps.core.storage.AcknowledgedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Acknowledged, app-private persistence for one typed reason without study or participant identity. */
class AtomicSafetyPauseStore internal constructor(
    private val file: AcknowledgedFile,
) : SafetyPauseStore {
    constructor(context: Context) : this(
        AcknowledgedAtomicFile(context.noBackupFilesDir.resolve(FILE_NAME)),
    )

    private val mutex = Mutex()

    override suspend fun pendingReason(): SafetyPauseReason? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            SafetyPauseMarkerCodec.decode(file.readFully())
        }
    }

    override suspend fun markPending(reason: SafetyPauseReason) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.write(SafetyPauseMarkerCodec.encode(reason))
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "safety-pause.marker"
    }
}

/** Strict codec: an unknown reason or any extra/truncated bytes keeps recovery fail-closed. */
internal object SafetyPauseMarkerCodec {
    private const val MAGIC = "PARTICEPS_SAFETY_PAUSE_V1"
    private const val MAXIMUM_MARKER_BYTES = 96

    fun encode(reason: SafetyPauseReason): ByteArray =
        "$MAGIC\n${reason.name}\n".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): SafetyPauseReason {
        check(bytes.size in 1..MAXIMUM_MARKER_BYTES) { "Safety-pause marker has an invalid size" }
        val fields = bytes.toString(Charsets.US_ASCII).split('\n')
        check(fields.size == 3 && fields[0] == MAGIC && fields[2].isEmpty()) {
            "Safety-pause marker is corrupt"
        }
        return SafetyPauseReason.entries.singleOrNull { it.name == fields[1] }
            ?: error("Safety-pause marker has an unknown reason")
    }
}
