package cool.jacoblin.particeps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.storage.EncryptedActiveStudyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A QR is transport only: Android still verifies the signed artifact before persisting a study. */
@RunWith(AndroidJUnit4::class)
class QrConfigurationImportAndroidTest {
    private val application: CollectorApplication
        get() = ApplicationProvider.getApplicationContext()
    private val session: StudySessionManager
        get() = application.session

    @Before
    fun clearStudyBeforeTest() = runBlocking { session.clearStudyDataForTest() }

    @After
    fun clearStudyAfterTest() = runBlocking { session.clearStudyDataForTest() }

    @Test
    fun canonicalJoinImportsTheBoundArtifactWithoutStartingCollection() = runBlocking {
        val fixture = qrStudyImportFixture(application)

        session.importSignedConfiguration(fixture.envelope, fixture.join)

        assertEquals("modular-sensing-demo", session.snapshot.value.study?.experimentId)
        assertEquals(ExperimentState.CONFIG_VERIFIED, session.snapshot.value.runtime.state)
        assertNull(session.snapshot.value.runtime.startedAtUtcMillis)
        assertEquals(0L, session.snapshot.value.runtime.lifetimeDataEventCount)
        assertArrayEquals(fixture.envelope, persistedEnvelope())
    }

    @Test
    fun artifactDigestMismatchLeavesNoStudyOnDisk() = runBlocking {
        val fixture = qrStudyImportFixture(application)
        val wrongDigest = fixture.join.artifactSha256.replaceFirstCharacter()

        assertImportRejected(fixture.envelope, fixture.join.copy(artifactSha256 = wrongDigest))
    }

    @Test
    fun signerFingerprintMismatchLeavesNoStudyOnDisk() = runBlocking {
        val fixture = qrStudyImportFixture(application)
        val wrongFingerprint = fixture.join.signerFingerprint.replaceFirstCharacter()

        assertImportRejected(fixture.envelope, fixture.join.copy(signerFingerprint = wrongFingerprint))
    }

    @Test
    fun matchingQrDigestCannotAuthorizeAnArtifactWithATamperedSignature() = runBlocking {
        val fixture = qrStudyImportFixture(application)
        val tampered = fixture.envelope.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        val matchingDigest = fixture.join.copy(artifactSha256 = tampered.qrArtifactSha256())

        assertImportRejected(tampered, matchingDigest)
    }

    @Test
    fun scanningAnAlreadyImportedStudyCannotReplaceItsParticipantSession() = runBlocking {
        val fixture = qrStudyImportFixture(application)
        session.importSignedConfiguration(fixture.envelope, fixture.join)
        assertEquals(StudyCommandResult.Success, session.reviewStudy())
        // The durable command completes before the session's runtime observer publishes its
        // participant projection. Capture that projection only after the review reaches it.
        val before = withTimeout(40_000L) {
            session.snapshot.first { it.runtime.state == ExperimentState.CONSENT_PENDING }
        }
        assertEquals(ExperimentState.CONSENT_PENDING, before.runtime.state)
        assertNotNull(before.runtime.participantInstanceId)

        val failure = runCatching {
            session.importSignedConfiguration(fixture.envelope, fixture.join)
        }.exceptionOrNull()

        assertNotNull("a second scan must not replace or reset the current study", failure)
        assertEquals(before.study, session.snapshot.value.study)
        assertEquals(before.runtime, session.snapshot.value.runtime)
        assertArrayEquals(fixture.envelope, persistedEnvelope())
    }

    private suspend fun assertImportRejected(envelope: ByteArray, join: JoinLink) {
        val canonicalJoin = JoinLink.parse(join.encode())
        val failure = runCatching {
            session.importSignedConfiguration(envelope, canonicalJoin)
        }.exceptionOrNull()

        assertNotNull("a mismatched or unsigned QR artifact must fail closed", failure)
        assertNull(session.snapshot.value.study)
        assertNull(session.snapshot.value.runtime.state)
        assertNull("a refused QR import must persist no active study", EncryptedActiveStudyStore(application).load())
    }

    private suspend fun persistedEnvelope(): ByteArray {
        val active = EncryptedActiveStudyStore(application).load()
        assertTrue("verified study must be durably stored", active is ActiveStudyRecord.Active)
        return (active as ActiveStudyRecord.Active).envelopeBytes
    }

    private fun String.replaceFirstCharacter(): String =
        (if (first() == '0') "1" else "0") + drop(1)
}
