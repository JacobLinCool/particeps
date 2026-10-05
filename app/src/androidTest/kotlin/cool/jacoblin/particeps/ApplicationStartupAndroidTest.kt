package cool.jacoblin.particeps

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationStartupAndroidTest {
    @Test
    fun blockedGraphConstructionLeavesAndroidMainLooperResponsive() {
        val owner = SupervisorJob()
        val startup = ApplicationStartup(CoroutineScope(owner))
        val constructing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainResponded = CountDownLatch(1)
        val factoryThread = AtomicReference<Thread>()
        val main = Handler(Looper.getMainLooper())
        try {
            assertTrue(main.post {
                startup.start {
                    factoryThread.set(Thread.currentThread())
                    constructing.countDown()
                    // A real blocking operation, not suspension that would also pass on Main.
                    release.await()
                    error("Synthetic construction failure after releasing the worker")
                }
            })
            assertTrue("The background factory must start", constructing.await(5, TimeUnit.SECONDS))
            assertTrue(main.post { mainResponded.countDown() })
            assertTrue(
                "Main must process another callback while graph construction is still blocked",
                mainResponded.await(5, TimeUnit.SECONDS),
            )
            assertNotSame(Looper.getMainLooper().thread, factoryThread.get())
            assertTrue(startup.state.value is ApplicationStartupState.Starting)
            release.countDown()
            val failure = runBlocking {
                withTimeout(5_000L) { runCatching { startup.awaitReady() }.exceptionOrNull() }
            }
            assertTrue(failure is ApplicationStartupException)
            assertSame(ApplicationStartupState.Failed, startup.state.value)
        } finally {
            release.countDown()
            runBlocking { withTimeout(5_000L) { owner.cancelAndJoin() } }
        }
    }
}
