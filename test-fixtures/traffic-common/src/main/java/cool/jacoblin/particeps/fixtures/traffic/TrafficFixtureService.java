package cool.jacoblin.particeps.fixtures.traffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.util.AtomicFile;
import java.io.File;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/** Keeps the host-only saturation workload runnable while its launcher activity is backgrounded. */
public final class TrafficFixtureService extends Service {
    private static final String CHANNEL_ID = "particeps-fixture-traffic";
    private static final int NOTIFICATION_ID = 1;
    private static final int PAYLOAD_BYTES = 64 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean started = new AtomicBoolean();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY;
        executor.execute(() -> {
            try {
                if ("duplex-download".equals(intent.getStringExtra("mode"))) {
                    runDownload(intent);
                } else {
                    runSaturation(intent);
                }
            } finally {
                executor.shutdown();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Particeps traffic fixture",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Particeps traffic fixture")
                .setContentText("Running a local host-harness measurement")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setLocalOnly(true)
                .setOngoing(true)
                .build();
    }

    private void runSaturation(Intent intent) {
        int port = intent.getIntExtra("port", -1);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Missing host-selected fixture port");
        }
        long attemptedBytes = 0L;
        int succeeded = 0;
        byte[] payload = new byte[PAYLOAD_BYTES];
        SaturationProgress progress = new SaturationProgress();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        persistProgress(progress);
        sampler.scheduleAtFixedRate(() -> persistProgress(progress), 1, 1, TimeUnit.SECONDS);
        String errorType = null;
        Integer errorErrno = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("10.0.2.2"), port), 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(70_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            progress.awaitingBarrier();
            if (input.read() != 1) {
                throw new IllegalStateException("Fixture barrier was not released");
            }
            while (!Thread.currentThread().isInterrupted()) {
                progress.beginWrite(SystemClock.elapsedRealtimeNanos());
                output.write(payload);
                progress.completeWrite(SystemClock.elapsedRealtimeNanos(), payload.length);
                attemptedBytes += payload.length;
                succeeded += 1;
            }
        } catch (Exception failure) {
            // The host closes the socket at the exact measurement boundary.
            errorType = failure.getClass().getSimpleName();
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof ErrnoException) {
                    errorErrno = ((ErrnoException) cause).errno;
                    break;
                }
            }
        } finally {
            progress.finish(errorType, errorErrno, SystemClock.elapsedRealtimeNanos());
            sampler.shutdownNow();
            persistProgress(progress);
        }
        writeMetrics(succeeded, attemptedBytes);
    }

    private void runDownload(Intent intent) {
        int port = intent.getIntExtra("port", -1);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Missing fixture port");
        String measurementId = intent.getStringExtra("measurement_id");
        UUID expected = UUID.fromString(measurementId);
        DownloadMeasurement measurement = new DownloadMeasurement(measurementId, requireMetadata("fixture_role"));
        String endReason = "error";
        String errorType = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("10.0.2.2"), port), 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeLong(expected.getMostSignificantBits());
            output.writeLong(expected.getLeastSignificantBits());
            output.flush();
            int signal = input.readUnsignedByte();
            UUID received = new UUID(input.readLong(), input.readLong());
            if (signal != 1 || !expected.equals(received)) {
                throw new IllegalStateException("Invalid duplex barrier");
            }
            measurement.start(SystemClock.elapsedRealtimeNanos());
            output.writeByte(2);
            output.writeLong(expected.getMostSignificantBits());
            output.writeLong(expected.getLeastSignificantBits());
            output.flush();
            socket.setSoTimeout(250);
            byte[] payload = new byte[PAYLOAD_BYTES];
            while (true) {
                if (SystemClock.elapsedRealtimeNanos() >= measurement.deadlineNanos()) {
                    endReason = "deadline";
                    break;
                }
                int count;
                try {
                    count = input.read(payload);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (count < 0) {
                    endReason = "eof";
                    break;
                }
                measurement.received(payload, count, SystemClock.elapsedRealtimeNanos());
            }
        } catch (Exception failure) {
            endReason = "error";
            errorType = failure.getClass().getSimpleName();
        } finally {
            measurement.finish(endReason, errorType, SystemClock.elapsedRealtimeNanos());
            AtomicFile destination = new AtomicFile(new File(getFilesDir(), "duplex-download.json"));
            FileOutputStream file = null;
            try {
                file = destination.startWrite();
                file.write(measurement.json().getBytes(StandardCharsets.UTF_8));
                destination.finishWrite(file);
            } catch (Exception failure) {
                if (file != null) destination.failWrite(file);
                throw new IllegalStateException("Unable to persist download metrics", failure);
            }
        }
    }

    private synchronized void persistProgress(SaturationProgress progress) {
        AtomicFile destination = new AtomicFile(new File(getFilesDir(), "saturation-progress.json"));
        FileOutputStream output = null;
        try {
            output = destination.startWrite();
            output.write(progress.json(SystemClock.elapsedRealtimeNanos()).getBytes(StandardCharsets.UTF_8));
            destination.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) destination.failWrite(output);
            throw new IllegalStateException("Unable to persist saturation progress", failure);
        }
    }

    private void writeMetrics(int succeeded, long attemptedBytes) {
        String role = requireMetadata("fixture_role");
        long version = requireVersionCode();
        String json = "{\"attempted_bytes\":" + attemptedBytes
                + ",\"failed_operations\":0"
                + ",\"fixture_role\":\"" + role + "\""
                + ",\"succeeded_operations\":" + succeeded
                + ",\"total_attempts\":" + succeeded
                + ",\"version_code\":" + version + "}\n";
        File destination = new File(getFilesDir(), "fixture-metrics.json");
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist aggregate fixture metrics", failure);
        }
    }

    private String requireMetadata(String key) {
        try {
            Bundle metadata = getPackageManager()
                    .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA)
                    .metaData;
            String value = metadata == null ? null : metadata.getString(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("Missing fixture metadata");
            }
            return value;
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Fixture package disappeared", failure);
        }
    }

    private long requireVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Fixture package disappeared", failure);
        }
    }
}
