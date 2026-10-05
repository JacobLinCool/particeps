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
    private Long connectStartedNanos;
    private Long connectCompletedNanos;
    private Long barrierReceivedNanos;
    private Long firstWriteStartedNanos;
    private Long firstWriteCompletedNanos;
    private Long writeStartedNanos;
    private Long lastCompletedNanos;
    private Long finishedNanos;

    public synchronized void beginConnect(long now) { connectStartedNanos = now; }

    public synchronized void completeConnect(long now) {
        connectCompletedNanos = now;
        stage = "AWAITING_BARRIER";
    }

    public synchronized void receiveBarrier(long now) { barrierReceivedNanos = now; }

    public synchronized void beginWrite(long now) {
        stage = "WRITING";
        if (firstWriteStartedNanos == null) firstWriteStartedNanos = now;
        writeStartedNanos = now;
    }

    public synchronized void completeWrite(long now, int bytes) {
        if (writeStartedNanos == null || now < writeStartedNanos || bytes <= 0) {
            throw new IllegalStateException("Invalid saturation write completion");
        }
        long elapsed = now - writeStartedNanos;
        if (firstWriteCompletedNanos == null) firstWriteCompletedNanos = now;
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
                + ",\"connect_started_elapsed_realtime_nanos\":" + connectStartedNanos
                + ",\"connect_completed_elapsed_realtime_nanos\":" + connectCompletedNanos
                + ",\"barrier_received_elapsed_realtime_nanos\":" + barrierReceivedNanos
                + ",\"first_write_started_elapsed_realtime_nanos\":" + firstWriteStartedNanos
                + ",\"first_write_completed_elapsed_realtime_nanos\":" + firstWriteCompletedNanos
                + ",\"write_started_elapsed_realtime_nanos\":" + writeStartedNanos
                + ",\"current_write_elapsed_nanos\":"
                + (writeStartedNanos == null ? "null" : Long.toString((finishedNanos == null ? now : finishedNanos) - writeStartedNanos))
                + ",\"last_completed_elapsed_realtime_nanos\":" + lastCompletedNanos
                + ",\"error_type\":" + quoted(errorType)
                + ",\"error_errno\":" + errorErrno + "}\n";
    }

    private static String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
