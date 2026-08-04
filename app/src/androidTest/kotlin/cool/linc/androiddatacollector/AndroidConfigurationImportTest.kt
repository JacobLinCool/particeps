package cool.linc.androiddatacollector

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.linc.androiddatacollector.core.model.ExperimentState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for Protocol v1 raw-key signature verification on Android's provider set. */
@RunWith(AndroidJUnit4::class)
class AndroidConfigurationImportTest {
    @Test
    fun debugDemoImportsIntoTheAndroidSession() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<CollectorApplication>()
        val session = application.session
        withTimeout(TIMEOUT_MILLIS) { session.snapshot.first { it.initialized } }
        assertNull("test requires a clean study session", session.snapshot.value.configuration)

        try {
            val loadDemo = requireNotNull(DemoStudy.load)
            session.importSignedConfiguration(loadDemo(application.resources))

            assertEquals(ExperimentState.IMPORTED, session.snapshot.value.runtime.metadata?.state)
        } finally {
            if (session.snapshot.value.configuration != null) {
                session.withdraw()
                session.deleteLocalData()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
