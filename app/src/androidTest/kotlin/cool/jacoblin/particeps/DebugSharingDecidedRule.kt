package cool.jacoblin.particeps

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Records a debug-sharing choice before the activity starts, so a flow test begins at the study
 * screens instead of the first-launch debug-information question. Order it before the compose rule.
 */
class DebugSharingDecidedRule(private val shared: Boolean = false) : ExternalResource() {
    override fun before() {
        DebugTelemetry.setSharing(InstrumentationRegistry.getInstrumentation().targetContext, shared)
    }
}
