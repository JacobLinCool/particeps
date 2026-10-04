package cool.jacoblin.particeps.collector.usageevents

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real framework query and AppOps. No simulated UsageStats result is substituted. */
@RunWith(AndroidJUnit4::class)
class UsageEventsAccessAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun realActivityEventsAreQueryableAndLegitimateEmptyQueriesRemainEmpty() = withUsageAccess {
        val query = AndroidUsageEventsQuery(context)
        assertNull(query.unavailableReason())
        val startedAt = System.currentTimeMillis()
        assertTrue(query.events(startedAt, startedAt).isEmpty())
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.context, UsageProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var types = emptySet<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            types = query.events(startedAt, System.currentTimeMillis()).filter {
                it.activityComponent == UsageProbeActivity::class.java.name
            }.map { it.type }.toSet()
            if ("ACTIVITY_RESUMED" in types && "ACTIVITY_PAUSED" in types) break
            SystemClock.sleep(50)
        }
        assertTrue("Missing real activity resumed event: $types", "ACTIVITY_RESUMED" in types)
        assertTrue("Missing real activity paused event: $types", "ACTIVITY_PAUSED" in types)
    }

    @Test
    fun revokingRealAppOpsFailsTheCollectorWithoutClaimingEmptyCoverage() = withUsageAccess {
        runBlocking {
            val query = AndroidUsageEventsQuery(context)
            val scope = CoroutineScope(SupervisorJob())
            val sink = CountingSink()
            val clocks = object : ResearchClocks {
                override fun now() = ResearchTime(
                    System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), "usage-android-test",
                )
            }
            val collector = UsageEventsCollector(
                query, UsageEventsV1ProfileConfiguration(15),
                CollectorContext(
                    scope, sink, clocks,
                    requireNotNull(ProtocolEventSourceRegistry[UsageEventsV1ProfileConfiguration.SOURCE_ID]),
                    1, StudyScopedTokenEncoder { _, _ -> "a".repeat(64) }, false,
                ),
            )
            try {
                collector.start()
                collector.pause()
                setUsageAccess("ignore")
                assertEquals(UsageEventsUnavailableReason.ACCESS_DENIED, query.unavailableReason())
                assertTrue(collector.flushThrough(clocks.now(), null) is CollectorFlushResult.Failed)
                assertEquals(CollectorStatus.FAILED, collector.health.value.status)
                assertEquals("USAGE_ACCESS_REVOKED", collector.health.value.reasonCode)
                assertEquals(0, sink.observations)
                setUsageAccess("allow")
                assertNull(query.unavailableReason())
                assertTrue(collector.flushThrough(clocks.now(), null) is CollectorFlushResult.Failed)
                assertEquals(0, sink.observations)
                assertFalse(runCatching { collector.resume() }.isSuccess)
            } finally {
                collector.stop()
                scope.cancel()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun withUsageAccess(block: () -> Unit) {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val previous = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName,
        )
        val previousMode = when (previous) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> error("Unknown AppOps mode: $previous")
        }
        try {
            setUsageAccess("allow")
            block()
        } finally {
            setUsageAccess(previousMode)
        }
    }

    private fun setUsageAccess(mode: String) {
        val command = "cmd appops set ${context.packageName} GET_USAGE_STATS $mode"
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        val text = ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
        check(text.isBlank()) { "AppOps command failed: $text" }
    }

    private class CountingSink : EventSink {
        private val token = object : AdmissionToken {}
        var observations = 0
        override fun captureToken() = token
        override fun captureBarrierFlushToken(boundary: ResearchTime) = token
        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult {
            observations++
            return EmitBatchResult.Accepted(observations.toLong(), batch.events.size)
        }
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult {
            observations++
            return EmitBatchResult.Accepted(observations.toLong(), 0)
        }
    }
}

class UsageProbeActivity : Activity()
