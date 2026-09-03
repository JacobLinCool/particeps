package cool.jacoblin.particeps.fixtures.traffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
                runSaturation(intent);
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("10.0.2.2"), port), 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(70_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            if (input.read() != 1) {
                throw new IllegalStateException("Fixture barrier was not released");
            }
            while (!Thread.currentThread().isInterrupted()) {
                output.write(payload);
                attemptedBytes += payload.length;
                succeeded += 1;
            }
        } catch (Exception ignored) {
            // The host closes the socket at the exact measurement boundary.
        }
        writeMetrics(succeeded, attemptedBytes);
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
