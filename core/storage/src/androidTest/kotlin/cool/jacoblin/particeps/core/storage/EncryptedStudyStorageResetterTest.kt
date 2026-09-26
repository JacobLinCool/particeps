package cool.jacoblin.particeps.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.RuntimeDocument
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStudyStorageResetterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resetStore = EncryptedStudyResetStore(context)

    @After
    fun tearDown() = runBlocking {
        resetStore.clear()
    }

    @Test
    fun resetDeletesEveryEngineKeyAndKeepsTheResetWitness() = runBlocking {
        val experimentIds = List(2) { "reset-study-${UUID.randomUUID()}" }
        experimentIds.forEach { experimentId ->
            EncryptedExperimentStore(context, experimentId, QUOTA_BYTES).initialize(initialRuntime(experimentId))
        }
        experimentIds.forEach { experimentId ->
            assertTrue("Initialization creates the engine key", engineKeyAlias(experimentId) in aliases())
        }
        resetStore.mark(retainedEnvelopeBytes = byteArrayOf(1, 2, 3))

        EncryptedStudyStorageResetter(context).clearAll()

        assertTrue(aliases().none { it.startsWith(ENGINE_KEY_ALIAS_PREFIX) })
        assertFalse(context.noBackupFilesDir.resolve("experiments").exists())
        experimentIds.forEach { experimentId ->
            assertNull(EncryptedExperimentStore(context, experimentId, QUOTA_BYTES).loadRuntime())
        }
        // The witness must stay readable until the reset that it records has finished.
        assertEquals(listOf<Byte>(1, 2, 3), resetStore.load()?.retainedEnvelopeBytes?.toList())
    }

    private fun aliases(): List<String> =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()

    private fun engineKeyAlias(experimentId: String): String = ENGINE_KEY_ALIAS_PREFIX +
        MessageDigest.getInstance("SHA-256")
            .digest(experimentId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun initialRuntime(experimentId: String) = RuntimeDocument.initial(
        experimentId = experimentId,
        configurationId = "config-001",
        configurationSha256 = "a".repeat(64),
        activityTokenKeyBase64Url = "A".repeat(43),
        participantInstanceId = "018f3ca4-7a82-4f47-8b5c-a4415b9b2290",
    )

    private companion object {
        const val QUOTA_BYTES = 128L * 1024 * 1024
    }
}
