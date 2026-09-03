package cool.jacoblin.particeps.fixtures.competingvpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

/** Launch point used only after the host has pre-authorized this fixture's VPN app-op. */
public final class CompetingVpnActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (VpnService.prepare(this) == null) {
            startForegroundService(new Intent(this, CompetingVpnService.class));
        } else {
            CompetingVpnService.writeState(this, false);
        }
        finish();
    }
}
