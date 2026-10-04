package cool.jacoblin.particeps.fixtures.traffic;

import java.util.UUID;

/** Bounded receiver-side payload evidence for the fixed 60-second duplex fixture. */
public final class DownloadMeasurement {
    public static final int DURATION_SECONDS = 60;
    private static final long SECOND_NANOS = 1_000_000_000L;
    private final String measurementId;
    private final long[] buckets = new long[DURATION_SECONDS];
    private Long startedNanos;
    private Long endedNanos;
    private Long firstByteNanos;
    private Long lastByteNanos;
    private long receivedBytes;
    private boolean payloadValid = true;
    private String endReason;
    private String errorType;

    public DownloadMeasurement(String measurementId, String fixtureRole) {
        if (!"target_b".equals(fixtureRole)) throw new IllegalArgumentException("Unexpected receiver role");
        this.measurementId = UUID.fromString(measurementId).toString();
        if (!this.measurementId.equals(measurementId)) {
            throw new IllegalArgumentException("Non-canonical measurement ID");
        }
    }

    public void start(long now) {
        if (startedNanos != null || now < 0) throw new IllegalStateException("Invalid barrier origin");
        startedNanos = now;
    }

    public long deadlineNanos() {
        if (startedNanos == null) throw new IllegalStateException("Barrier has not completed");
        return Math.addExact(startedNanos, DURATION_SECONDS * SECOND_NANOS);
    }

    public void received(byte[] payload, int size, long completedNanos) {
        if (startedNanos == null || endedNanos != null || completedNanos < startedNanos
                || size <= 0 || size > payload.length) {
            throw new IllegalStateException("Invalid receive completion");
        }
        for (int index = 0; index < size; index++) {
            if (payload[index] != 'Z') {
                payloadValid = false;
                throw new IllegalStateException("Invalid fixture payload");
            }
        }
        // A read is attributed at completion; a whole chunk completing at/after the
        // deadline is excluded, even if its socket read began within the window.
        long elapsed = completedNanos - startedNanos;
        if (elapsed >= DURATION_SECONDS * SECOND_NANOS) return;
        buckets[(int) (elapsed / SECOND_NANOS)] += size;
        receivedBytes += size;
        if (firstByteNanos == null) firstByteNanos = elapsed;
        lastByteNanos = elapsed;
    }

    public void finish(String reason, String type, long now) {
        if (endedNanos != null || !("deadline".equals(reason) || "eof".equals(reason)
                || "error".equals(reason))) throw new IllegalStateException("Invalid terminal state");
        if ("deadline".equals(reason) && now < deadlineNanos()) {
            throw new IllegalStateException("Deadline has not elapsed");
        }
        if (type != null && !type.matches("[A-Za-z0-9_$]+")) {
            throw new IllegalArgumentException("Only exception class names may be recorded");
        }
        endReason = reason;
        errorType = type;
        endedNanos = now;
    }

    public String json() {
        if (endedNanos == null) throw new IllegalStateException("Measurement is not complete");
        StringBuilder result = new StringBuilder("{\"schema_version\":1,\"direction\":\"download\",\"fixture_role\":\"target_b\"")
                .append(",\"measurement_id\":\"").append(measurementId).append('"')
                .append(",\"duration_seconds\":").append(DURATION_SECONDS)
                .append(",\"timing_basis\":\"android_elapsed_realtime_since_validated_barrier\"")
                .append(",\"started_elapsed_realtime_nanos\":").append(startedNanos)
                .append(",\"ended_elapsed_realtime_nanos\":").append(endedNanos)
                .append(",\"received_bytes\":").append(receivedBytes)
                .append(",\"first_byte_elapsed_nanos\":").append(firstByteNanos)
                .append(",\"last_byte_elapsed_nanos\":").append(lastByteNanos)
                .append(",\"payload_valid\":").append(payloadValid)
                .append(",\"end_reason\":\"").append(endReason).append('"')
                .append(",\"error_type\":").append(errorType == null ? "null" : '"' + errorType + '"')
                .append(",\"bytes_by_second\":[");
        for (int second = 0; second < buckets.length; second++) {
            if (second != 0) result.append(',');
            result.append("{\"second\":").append(second)
                    .append(",\"bytes\":").append(buckets[second]).append('}');
        }
        return result.append("]}\n").toString();
    }
}
