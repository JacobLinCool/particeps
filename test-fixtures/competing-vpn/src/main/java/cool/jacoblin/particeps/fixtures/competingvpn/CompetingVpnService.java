package cool.jacoblin.particeps.fixtures.competingvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Minimal non-forwarding VPN whose only purpose is to replace the Particeps VPN in emulator tests. */
public final class CompetingVpnService extends VpnService {
    private static final String CHANNEL_ID = "particeps_fixture_vpn";
    private static final int NOTIFICATION_ID = 901;
    private ParcelFileDescriptor descriptor;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("VPN test fixture")
                .setContentText("Host-orchestrated test is active")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        descriptor = new Builder()
                .setSession("Particeps competing VPN fixture")
                .addAddress("10.112.223.1", 32)
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)
                .establish();
        boolean established = descriptor != null;
        writeState(this, established);
        if (!established) stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onRevoke() {
        closeDescriptor();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        closeDescriptor();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Host VPN fixture",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void closeDescriptor() {
        ParcelFileDescriptor current = descriptor;
        descriptor = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // Test cleanup has no recovery path and stores no exception details.
            }
        }
    }

    static void writeState(Context context, boolean established) {
        File destination = new File(context.getFilesDir(), "vpn-fixture-state.json");
        String value = "{\"established\":" + established + ",\"fixture_role\":\"competing_vpn\"}\n";
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist competing VPN fixture state", failure);
        }
    }
}
