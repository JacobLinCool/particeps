package cool.jacoblin.particeps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.storage.EncryptedActiveStudyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Protocol v1 configuration import on Android's own provider set: the raw-key signature
 * verification the app accepts, and the retired envelope identity it must refuse.
 */
@RunWith(AndroidJUnit4::class)
class AndroidConfigurationImportTest {
    @Test
    fun debugDemoImportsIntoTheAndroidSession() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<CollectorApplication>()
        val session = application.session
        session.clearStudyDataForTest()
        assertNull("test requires a clean study session", session.snapshot.value.configuration)

        try {
            val loadDemo = requireNotNull(DemoStudy.load)
            session.importSignedConfiguration(loadDemo(application.resources))

            assertEquals(ExperimentState.IMPORTED, session.snapshot.value.runtime.metadata?.state)
        } finally {
            session.clearStudyDataForTest()
        }
    }

    /**
     * The import picker asks for `application/octet-stream` and the wildcard MIME type, so a file
     * left over from the retired identity stays selectable however it is named; the picker is not
     * and cannot be the gate. The parser is. An envelope that is well formed apart from its magic
     * must fail the import outright, leaving no study in the session and nothing on disk.
     */
    @Test
    fun theRetiredConfigurationMagicFailsClosedInTheParser() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<CollectorApplication>()
        val session = application.session
        session.clearStudyDataForTest()
        assertNull("test requires a clean study session", session.snapshot.value.configuration)

        try {
            val loadDemo = requireNotNull(DemoStudy.load)
            val envelope = loadDemo(application.resources).also { RETIRED_CONFIGURATION_MAGIC.copyInto(it) }

            val failure = runCatching { session.importSignedConfiguration(envelope) }.exceptionOrNull()

            assertNotNull("the retired magic must not import", failure)
            assertNull(session.snapshot.value.configuration)
            assertNull(session.snapshot.value.runtime.metadata)
            assertNull(
                "a refused import must persist no active study",
                EncryptedActiveStudyStore(application).load(),
            )
        } finally {
            session.clearStudyDataForTest()
        }
    }

    private companion object {
        /**
         * Retired-identity rejection fixture: the pre-Particeps signed-configuration magic, kept
         * here only as bytes the import path has to refuse. Nothing in this repository writes it.
         */
        val RETIRED_CONFIGURATION_MAGIC = "ADCCFG01".toByteArray(Charsets.US_ASCII)
    }
}
