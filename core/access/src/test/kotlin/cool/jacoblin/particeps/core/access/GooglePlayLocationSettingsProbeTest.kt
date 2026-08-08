package cool.jacoblin.particeps.core.access

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.definition.LocationPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GooglePlayLocationSettingsProbeTest {
    @Test
    fun prioritiesMatchTheCollectorLocationRequestContract() {
        assertEquals(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LocationPriority.BALANCED.toGooglePlayPriority(),
        )
        assertEquals(
            Priority.PRIORITY_HIGH_ACCURACY,
            LocationPriority.HIGH_ACCURACY.toGooglePlayPriority(),
        )
        assertEquals(1.25f, profile().minimumDisplacementMeters(), 0f)
    }

    @Test
    fun successfulCheckIsReadyAndReceivesTheExactProfile() = runTest {
        val expected = profile()
        var received: LocationAccessProfile? = null
        val probe = GooglePlayLocationSettingsProbe { actual -> received = actual }

        assertEquals(LocationSettingsProbeResult.READY, probe.inspect(expected))
        assertEquals(expected, received)
    }

    @Test
    fun onlyResolvableApiExceptionIsActionable() = runTest {
        val resolvable = ResolvableApiException(Status(CommonStatusCodes.RESOLUTION_REQUIRED))
        assertEquals(
            LocationSettingsProbeResult.RESOLUTION_REQUIRED,
            failingProbe(resolvable).inspect(profile()),
        )

        val statusCodeWithoutResolution = ApiException(Status(CommonStatusCodes.RESOLUTION_REQUIRED))
        assertEquals(
            LocationSettingsProbeResult.CHECK_FAILED,
            failingProbe(statusCodeWithoutResolution).inspect(profile()),
        )
    }

    @Test
    fun unchangeableAndUnexpectedFailuresRemainDistinctAndFailClosed() = runTest {
        assertEquals(
            LocationSettingsProbeResult.CHANGE_UNAVAILABLE,
            failingProbe(
                ApiException(Status(LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE)),
            ).inspect(profile()),
        )
        assertEquals(
            LocationSettingsProbeResult.CHECK_FAILED,
            failingProbe(ApiException(Status.RESULT_INTERNAL_ERROR)).inspect(profile()),
        )
        assertEquals(
            LocationSettingsProbeResult.CHECK_FAILED,
            failingProbe(IllegalStateException("Play services check failed")).inspect(profile()),
        )
    }

    @Test
    fun coroutineCancellationIsNeverConvertedIntoCheckFailure() = runTest {
        val cancellation = CancellationException("test cancellation")
        try {
            failingProbe(cancellation).inspect(profile())
            fail("CancellationException must be rethrown")
        } catch (caught: CancellationException) {
            // Coroutine stack-trace recovery may copy a CancellationException; the contract is
            // propagation rather than object identity.
            assertEquals(cancellation.message, caught.message)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun aCheckThatNeverCompletesFailsClosedAtTheBoundedDeadline() = runTest {
        val probe = GooglePlayLocationSettingsProbe { awaitCancellation() }

        assertEquals(LocationSettingsProbeResult.CHECK_FAILED, probe.inspect(profile()))
        assertEquals(LOCATION_SETTINGS_CHECK_TIMEOUT_MILLIS, currentTime)
    }

    private fun failingProbe(failure: Exception) = GooglePlayLocationSettingsProbe {
        throw failure
    }

    private fun profile() = LocationAccessProfile(
        intervalMillis = 10_000,
        minimumIntervalMillis = 2_000,
        maximumBatchDelayMillis = 60_000,
        minimumDisplacementMillimeters = 1_250,
        priority = LocationPriority.BALANCED,
    )
}
