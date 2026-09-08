package cool.jacoblin.particeps

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Api37CompatibilityInitializationTest {
    @Test
    fun compatibilityWaitsUntilApplicationOnCreateReturnsSuccessfully() {
        val instrumentation = Api37CompatibilityInstrumentation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val application = object : Application() {
            override fun onCreate() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val creation = executor.submit { instrumentation.callApplicationOnCreate(application) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val observation = executor.submit<Application> {
                observing.countDown()
                instrumentation.awaitApplicationCreation()
            }

            assertTrue(observing.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { observation.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            creation.get(5, TimeUnit.SECONDS)
            assertSame(application, observation.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedApplicationOnCreateNeverReleasesCompatibilityVerification() {
        val instrumentation = Api37CompatibilityInstrumentation()
        val failure = IllegalStateException("application initialization failed")
        val application = object : Application() {
            override fun onCreate(): Unit = throw failure
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            instrumentation.callApplicationOnCreate(application)
        })
        assertThrows(TimeoutException::class.java) { instrumentation.awaitApplicationCreation(0) }
    }
}
