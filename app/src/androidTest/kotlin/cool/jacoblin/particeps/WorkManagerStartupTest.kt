package cool.jacoblin.particeps

import androidx.startup.AppInitializer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerStartupTest {
    @Test
    fun workManagerRemainsAvailableWithoutEagerProviderInitialization() {
        val application = ApplicationProvider.getApplicationContext<CollectorApplication>()
        assertFalse(
            "WorkManager must not open its database during content-provider startup",
            AppInitializer.getInstance(application)
                .isEagerlyInitialized(WorkManagerInitializer::class.java),
        )
        val manager = WorkManager.getInstance(application)
        assertSame(manager, WorkManager.getInstance(application.applicationContext))
    }
}
