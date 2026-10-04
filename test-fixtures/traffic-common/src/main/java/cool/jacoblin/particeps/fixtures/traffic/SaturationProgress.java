package cool.jacoblin.particeps.fixtures.traffic;

/** Constant-size diagnostic state. A completed write means kernel acceptance, not host delivery. */
public final class SaturationProgress {
    private String stage = "CONNECTING";
    private String terminalStage;
    private String errorType;
    private Integer errorErrno;
    private long completedBytes;
    private long completedWrites;
    private long completedWriteNanos;
    private long longestWriteNanos;
    private Long writeStartedNanos;
    private Long lastCompletedNanos;
    private Long finishedNanos;

    public synchronized void awaitingBarrier() { stage = "AWAITING_BARRIER"; }

    public synchronized void beginWrite(long now) {
        stage = "WRITING";
        writeStartedNanos = now;
    }

    public synchronized void completeWrite(long now, int bytes) {
        if (writeStartedNanos == null || now < writeStartedNanos || bytes <= 0) {
            throw new IllegalStateException("Invalid saturation write completion");
        }
        long elapsed = now - writeStartedNanos;
        completedBytes += bytes;
        completedWrites += 1;
        completedWriteNanos += elapsed;
        longestWriteNanos = Math.max(longestWriteNanos, elapsed);
        lastCompletedNanos = now;
        writeStartedNanos = null;
    }

    public synchronized void finish(String type, Integer errno, long now) {
        if (type != null && !type.matches("[A-Za-z0-9_$]+")) {
            throw new IllegalArgumentException("Only an exception class name may be recorded");
        }
        terminalStage = stage;
        stage = "FINISHED";
        errorType = type;
        errorErrno = errno;
        finishedNanos = now;
        // Preserve an interrupted/failed write's start; it never becomes completed bytes.
    }

    public synchronized String json(long now) {
        return "{\"schema_version\":1,\"sample_elapsed_realtime_nanos\":" + now
                + ",\"stage\":\"" + stage + "\",\"terminal_stage\":" + quoted(terminalStage)
                + ",\"completed_bytes\":" + completedBytes
                + ",\"completed_writes\":" + completedWrites
                + ",\"completed_write_nanos\":" + completedWriteNanos
                + ",\"longest_write_nanos\":" + longestWriteNanos
                + ",\"write_started_elapsed_realtime_nanos\":" + writeStartedNanos
                + ",\"current_write_elapsed_nanos\":"
                + (writeStartedNanos == null ? "null" : Long.toString((finishedNanos == null ? now : finishedNanos) - writeStartedNanos))
                + ",\"last_completed_elapsed_realtime_nanos\":" + lastCompletedNanos
                + ",\"error_type\":" + quoted(errorType)
                + ",\"error_errno\":" + errorErrno + "}\n";
    }

    private static String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
