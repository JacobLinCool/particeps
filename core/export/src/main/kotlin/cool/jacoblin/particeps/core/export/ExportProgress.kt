package cool.jacoblin.particeps.core.export

enum class ExportStage { PREPARING, SELECTING, ENCRYPTING, FINALIZING }

/** Progress describes a phase, never completion of the destination's final close. */
data class ExportProgress(
    val stage: ExportStage,
    val completedCommits: Long = 0,
    val totalCommits: Long? = null,
) {
    init {
        require(completedCommits >= 0) { "Invalid export progress" }
        require(totalCommits == null || totalCommits >= completedCommits) { "Invalid export total" }
    }
}

internal class ExportProgressReporter(private val report: (ExportProgress) -> Unit) {
    private var lastReportedAt = 0L

    fun report(stage: ExportStage, completed: Long, total: Long, force: Boolean = false) {
        val now = System.nanoTime()
        if (force || completed == total || now - lastReportedAt >= REPORT_INTERVAL_NANOS) {
            report(ExportProgress(stage, completed, total))
            lastReportedAt = now
        }
    }

    private companion object { const val REPORT_INTERVAL_NANOS = 100_000_000L }
}
