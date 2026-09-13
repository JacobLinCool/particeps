package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.export.ExportProgress
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.ExportStage
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only participant-safe phases and counts cross into the interface. */
sealed interface ParticipantExportState {
    data object Idle : ParticipantExportState
    data object ChoosingDestination : ParticipantExportState
    data class Running(
        val phase: ParticipantExportPhase,
        val completedBatches: Long = 0,
        val totalBatches: Long? = null,
    ) : ParticipantExportState {
        /** A fraction of this phase, never an estimate of overall completion. */
        val phaseFraction: Float?
            get() = totalBatches?.takeIf {
                it > 0 && (phase == ParticipantExportPhase.READING || phase == ParticipantExportPhase.ENCRYPTING)
            }?.let { (completedBatches.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    data object Cancelling : ParticipantExportState
    data object Succeeded : ParticipantExportState
    data class Cancelled(val incompleteFileRemains: Boolean) : ParticipantExportState
    data class Failed(val incompleteFileRemains: Boolean) : ParticipantExportState

    val isActive: Boolean
        get() = this is ChoosingDestination || this is Running || this is Cancelling
}

enum class ParticipantExportPhase { PREPARING, READING, ENCRYPTING, FINISHING }

/**
 * Owns one export independently of collection commands. Start/cancel run on the scope's dispatcher;
 * blocking document operations and non-cancellable cleanup run on [ioDispatcher].
 * [writeExport] takes ownership of its stream, including closing it on every outcome.
 */
internal class ParticipantExportController(
    private val scope: CoroutineScope,
    private val writeExport: suspend (OutputStream, (ExportProgress) -> Unit) -> ExportReceipt,
    private val reportDiagnostic: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow<ParticipantExportState>(ParticipantExportState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun chooseDestination(): Boolean {
        val current = mutableState.value
        return !current.isActive && mutableState.compareAndSet(current, ParticipantExportState.ChoosingDestination)
    }

    fun destinationCancelled() {
        mutableState.compareAndSet(ParticipantExportState.ChoosingDestination, ParticipantExportState.Idle)
    }

    fun destinationUnavailable() {
        mutableState.compareAndSet(
            ParticipantExportState.ChoosingDestination,
            ParticipantExportState.Failed(incompleteFileRemains = false),
        )
    }

    fun clearResult() {
        val current = mutableState.value
        if (!current.isActive) mutableState.compareAndSet(current, ParticipantExportState.Idle)
    }

    fun start(openDestination: () -> OutputStream, removeIncomplete: () -> Boolean): Boolean {
        val current = mutableState.value
        // Idle is accepted when Android delivers a picker result after process recreation.
        if (current.isActive && current != ParticipantExportState.ChoosingDestination) return false
        if (!mutableState.compareAndSet(current, ParticipantExportState.Running(ParticipantExportPhase.PREPARING))) {
            return false
        }
        // Enter the cleanup boundary before this job can be cancelled by another UI action.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val timings = ExportTimings(reportDiagnostic)
            try {
                timings.begin(ExportTimingStage.OPENING)
                val receipt = withContext(ioDispatcher) {
                    var destination: OutputStream? = null
                    var handedToSession = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val opened = openDestination()
                        destination = opened
                        currentCoroutineContext().ensureActive()
                        timings.begin(ExportTimingStage.PREPARING)
                        handedToSession = true
                        writeExport(opened) { progress ->
                            timings.begin(progress.stage.timingStage())
                            reportProgress(progress)
                        }
                    } finally {
                        if (!handedToSession) {
                            withContext(NonCancellable) { destination?.close() }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = ParticipantExportState.Succeeded
                timings.finish("succeeded", receipt = receipt)
            } catch (cancelled: CancellationException) {
                // Include the return to the UI dispatcher and the terminal state in cleanup.
                // Otherwise prompt cancellation can discard the result of the IO context switch.
                withContext(NonCancellable) {
                    mutableState.value = ParticipantExportState.Cancelling
                    timings.begin(ExportTimingStage.CLEANUP)
                    val removed = removeIncompleteSafely(removeIncomplete)
                    mutableState.value = ParticipantExportState.Cancelled(incompleteFileRemains = !removed)
                    timings.finish("cancelled", incompleteFileRemains = !removed)
                }
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable) {
                    timings.begin(ExportTimingStage.CLEANUP)
                    val removed = removeIncompleteSafely(removeIncomplete)
                    mutableState.value = ParticipantExportState.Failed(incompleteFileRemains = !removed)
                    timings.finish("failed", incompleteFileRemains = !removed)
                }
            }
        }
        return true
    }

    fun cancel() {
        while (true) {
            val current = mutableState.value
            if (current !is ParticipantExportState.Running) return
            if (mutableState.compareAndSet(current, ParticipantExportState.Cancelling)) {
                job?.cancel()
                return
            }
        }
    }

    private fun reportProgress(progress: ExportProgress) {
        val phase = when (progress.stage) {
            ExportStage.PREPARING -> ParticipantExportPhase.PREPARING
            ExportStage.SELECTING -> ParticipantExportPhase.READING
            ExportStage.ENCRYPTING -> ParticipantExportPhase.ENCRYPTING
            ExportStage.FINALIZING -> ParticipantExportPhase.FINISHING
        }
        mutableState.update { current ->
            if (current is ParticipantExportState.Running) {
                ParticipantExportState.Running(phase, progress.completedCommits, progress.totalCommits)
            } else {
                current
            }
        }
    }

    private suspend fun removeIncompleteSafely(removeIncomplete: () -> Boolean): Boolean =
        withContext(NonCancellable + ioDispatcher) {
            try {
                removeIncomplete()
            } catch (_: Exception) {
                false
            }
        }
}

private enum class ExportTimingStage { OPENING, PREPARING, SELECTING, ENCRYPTING, FINALIZING, CLEANUP }

private fun ExportStage.timingStage(): ExportTimingStage = when (this) {
    ExportStage.PREPARING -> ExportTimingStage.PREPARING
    ExportStage.SELECTING -> ExportTimingStage.SELECTING
    ExportStage.ENCRYPTING -> ExportTimingStage.ENCRYPTING
    ExportStage.FINALIZING -> ExportTimingStage.FINALIZING
}

/** Diagnostic output contains only fixed phase names, durations and aggregate counts. */
private class ExportTimings(private val log: (String) -> Unit) {
    private val started = System.nanoTime()
    private var stage: ExportTimingStage? = null
    private var stageStarted = started

    fun begin(next: ExportTimingStage) {
        if (stage == next) return
        val now = System.nanoTime()
        endStage(now)
        stage = next
        stageStarted = now
        log("Export phase=$next started elapsed_ms=${millis(now - started)}")
    }

    fun finish(outcome: String, receipt: ExportReceipt? = null, incompleteFileRemains: Boolean = false) {
        val now = System.nanoTime()
        endStage(now)
        log(
            "Export outcome=$outcome elapsed_ms=${millis(now - started)} " +
                "incomplete_file_remains=$incompleteFileRemains" +
                (receipt?.let {
                    " commits=${it.commitCount} events=${it.eventCount} bytes=${it.byteCount}"
                } ?: ""),
        )
    }

    private fun endStage(now: Long) {
        stage?.let {
            log("Export phase=$it finished duration_ms=${millis(now - stageStarted)} elapsed_ms=${millis(now - started)}")
        }
    }

    private fun millis(nanos: Long): Long = nanos / 1_000_000
}
