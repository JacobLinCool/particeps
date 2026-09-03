package cool.jacoblin.particeps.fixtures.traffic;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Non-shipping traffic producer for host-orchestrated VPN tests.
 *
 * The addresses and lookup name are fixed documentation-only fixtures and are never written to a
 * file or log. The only durable output is an aggregate count that lets the host prove the same
 * workload ran in target and control applications.
 */
public final class TrafficFixtureActivity extends Activity {
    private static final int ITERATIONS = 2;
    private static final int PAYLOAD_BYTES = 256;
    private static final int CONNECT_TIMEOUT_MILLIS = 250;
    private static final byte[] DNS_QUERY = new byte[] {
            0x50, 0x54, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x11, 'p', 'a', 'r', 't', 'i', 'c', 'e', 'p', 's', '-',
            'f', 'i', 'x', 't', 'u', 'r', 'e',
            0x07, 'i', 'n', 'v', 'a', 'l', 'i', 'd', 0x00,
            0x00, 0x01, 0x00, 0x01,
    };
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ("saturate".equals(getIntent().getStringExtra("mode"))) {
            Intent service = new Intent(this, TrafficFixtureService.class)
                    .putExtras(getIntent());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            finish();
            return;
        }
        executor.execute(this::runWorkload);
    }

    private void runWorkload() {
        try {
            int succeeded = 0;
            int attempts = 0;
            long attemptedBytes = 0L;
            for (int iteration = 0; iteration < ITERATIONS; iteration += 1) {
                attempts += 1;
                attemptedBytes += PAYLOAD_BYTES;
                if (sendUdp(false)) succeeded += 1;

                attempts += 1;
                attemptedBytes += PAYLOAD_BYTES;
                if (sendUdp(true)) succeeded += 1;

                attempts += 1;
                if (connectTcp(false)) succeeded += 1;

                attempts += 1;
                if (connectTcp(true)) succeeded += 1;

                attempts += 1;
                attemptedBytes += DNS_QUERY.length;
                if (sendDnsQuery()) succeeded += 1;
            }
            writeMetrics(attempts, succeeded, attemptedBytes);
            runOnUiThread(this::finish);
        } finally {
            // Release the finite smoke-test worker only after its aggregate result is durable.
            // Saturation runs in TrafficFixtureService and does not use this executor.
            executor.shutdown();
        }
    }

    private boolean sendUdp(boolean ipv6) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(ipv6 ? "2001:db8::1" : "192.0.2.1");
            socket.send(new DatagramPacket(payload, payload.length, address, 9));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean connectTcp(boolean ipv6) {
        try (Socket socket = new Socket()) {
            InetAddress address = ipv6
                    ? Inet6Address.getByName("2001:db8::1")
                    : InetAddress.getByName("192.0.2.1");
            socket.connect(new InetSocketAddress(address, 9), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean sendDnsQuery() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName("192.0.2.53");
            socket.send(new DatagramPacket(DNS_QUERY, DNS_QUERY.length, address, 53));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeMetrics(int attempts, int succeeded, long attemptedBytes) {
        String role = requireMetadata("fixture_role");
        long version = requireVersionCode();
        String json = "{\"attempted_bytes\":" + attemptedBytes
                + ",\"failed_operations\":" + (attempts - succeeded)
                + ",\"fixture_role\":\"" + role + "\""
                + ",\"succeeded_operations\":" + succeeded
                + ",\"total_attempts\":" + attempts
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
                    .getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA)
                    .metaData;
            String value = metadata == null ? null : metadata.getString(key);
            if (value == null || value.isEmpty()) throw new IllegalStateException("Missing fixture metadata");
            return value;
        } catch (android.content.pm.PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Fixture package disappeared", failure);
        }
    }

    private long requireVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Fixture package disappeared", failure);
        }
    }
}
