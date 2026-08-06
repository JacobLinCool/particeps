package cool.linc.particeps.core.protocol

import cool.linc.particeps.core.definition.AppLifecycleConfiguration
import cool.linc.particeps.core.definition.ExportConfiguration
import cool.linc.particeps.core.definition.LocationConfiguration
import cool.linc.particeps.core.definition.LocationPriority
import cool.linc.particeps.core.definition.ProtocolBase64Url
import cool.linc.particeps.core.definition.SignerIdentity
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.StudyConfigurationCodec
import cool.linc.particeps.core.definition.UploadConfiguration
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationProtocolTest {
    @Test
    fun canonicalCodecUsesClosedWorldJcsAndIntegerPhysicalUnits() {
        val configuration = configuration()
        val canonical = StudyConfigurationCodec.encode(configuration)
        val text = canonical.toString(Charsets.UTF_8)

        assertEquals(configuration, StudyConfigurationCodec.decode(canonical))
        assertArrayEquals(canonical, StudyConfigurationCodec.canonicalize(pretty(canonical)))
        assertTrue(text.startsWith("{\"assigned_participant_id\":"))
        assertTrue(text.contains("\"minimum_client_version\":\"7\""))
        assertTrue(text.contains("\"minimum_displacement_millimeters\":5000"))
        assertFalse(text.contains("minimum_app_version"))
        assertFalse(text.contains("tink"))
    }

    @Test
    fun canonicalizerNormalizesIntegralSpellingsButDecoderRequiresCanonicalBytes() {
        val canonical = StudyConfigurationCodec.encode(configuration())
        val exponent = canonical.toString(Charsets.UTF_8)
            .replace("\"duration_hours\":24", "\"duration_hours\":2.4e1")
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) { StudyConfigurationCodec.decode(exponent) }
        assertArrayEquals(canonical, StudyConfigurationCodec.canonicalize(exponent))
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.canonicalize(
                exponent.toString(Charsets.UTF_8).replace("2.4e1", "24.5").toByteArray(),
            )
        }
    }

    @Test
    fun hostileJsonAndLegacyV1AreRejected() {
        val canonical = StudyConfigurationCodec.encode(configuration())
        val text = canonical.toString(Charsets.UTF_8)
        val duplicate = text.replaceFirst("{", "{\"assigned_participant_id\":null,").toByteArray()
        val unknown = text.replaceFirst("{", "{\"unknown\":true,").toByteArray()
        val legacy = text
            .replace("\"minimum_client_version\":\"7\",", "\"minimum_app_version\":7,")
            .replace("\"platform\":\"android\",", "")
            .toByteArray()
        val leadingZero = text.replace("\"minimum_client_version\":\"7\"", "\"minimum_client_version\":\"07\"")
            .toByteArray()
        val loneSurrogate = text.replace("Protocol test", "\\ud800").toByteArray()
        val malformedUtf8 = canonical.copyOf().also { it[text.indexOf("Protocol test")] = 0x80.toByte() }
        val extremeExponent = text.replace("\"duration_hours\":24", "\"duration_hours\":1e999999999")
            .toByteArray()

        listOf(duplicate, unknown, legacy, leadingZero, loneSurrogate, malformedUtf8, extremeExponent).forEach { hostile ->
            assertThrows(IllegalArgumentException::class.java) { StudyConfigurationCodec.canonicalize(hostile) }
        }
    }

    @Test
    fun partcfg01HasFixedEd25519TailAndRejectsOldSignatureLengthFraming() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val configurationBytes = StudyConfigurationCodec.encode(configuration(publicKey(pair)))
        val signature = sign(pair, configurationBytes)
        val envelope = SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope("test-signer", configurationBytes, signature),
        )
        val decoded = SignedConfigurationCodec.decode(envelope)

        assertEquals("PTCCFG01", envelope.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals("test-signer".length, ByteBuffer.wrap(envelope, 8, 2).short.toInt())
        assertEquals(configurationBytes.size, ByteBuffer.wrap(envelope, 10, 4).int)
        assertArrayEquals(signature, envelope.copyOfRange(envelope.size - 64, envelope.size))
        assertArrayEquals(configurationBytes, decoded.configurationBytes)

        val legacy = ByteBuffer.allocate(envelope.size + 2)
            .put(envelope, 0, 14)
            .putShort(64)
            .put(envelope, 14, envelope.size - 14)
            .array()
        assertThrows(IllegalArgumentException::class.java) { SignedConfigurationCodec.decode(legacy) }
    }

    @Test
    fun verifierReturnsAuthenticatedConfigurationProvenance() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val configurationBytes = StudyConfigurationCodec.encode(configuration(publicKey(pair)))
        val signature = sign(pair, configurationBytes)
        val envelope = SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope("test-signer", configurationBytes, signature),
        )
        val verified = verifier(mapOf("test-signer" to publicKey(pair))).verify(envelope)

        assertTrue(verified.signerAnchored)
        assertEquals("test-signer", verified.signerKeyId)
        assertArrayEquals(configurationBytes, verified.canonicalConfigurationBytes)
        assertArrayEquals(signature, verified.signature)
        assertTrue(Regex("[0-9a-f]{64}").matches(verified.configurationSha256))

        val tampered = envelope.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { verifier(emptyMap()).verify(tampered) }
        assertThrows(IllegalArgumentException::class.java) {
            verifier(mapOf("other-signer" to publicKey(pair))).verify(envelope)
        }
    }

    @Test
    fun rawKeyEncodingRejectsPaddingAndDerWireKeys() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            configuration(publicKey(pair) + "=")
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration(java.util.Base64.getEncoder().encodeToString(pair.public.encoded))
        }
    }

    @Test
    fun verifierEnforcesActivationAndDecimalClientVersion() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val bytes = StudyConfigurationCodec.encode(configuration(publicKey(pair)))
        val envelope = SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope("test-signer", bytes, sign(pair, bytes)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationVerifier(emptyMap(), 6, now = { Instant.parse("2027-01-01T00:00:00Z") }).verify(envelope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationVerifier(emptyMap(), 7, now = { Instant.parse("2031-01-01T00:00:00Z") }).verify(envelope)
        }
    }

    private fun verifier(pinned: Map<String, String>) = ConfigurationVerifier(
        pinned,
        clientVersion = 7,
        now = { Instant.parse("2027-01-01T00:00:00Z") },
    )

    private fun sign(pair: KeyPair, bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(bytes)
        sign()
    }

    private fun publicKey(pair: KeyPair): String = ProtocolBase64Url.encode(pair.public.encoded.copyOfRange(12, 44))

    private fun configuration(signerPublicKey: String = ProtocolBase64Url.encode(ByteArray(32) { 1 })) =
        StudyConfiguration(
            schemaVersion = 1,
            experimentId = "protocol-test",
            configurationId = "protocol-config",
            assignedParticipantId = null,
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = "android",
            minimumClientVersion = 7,
            title = "Protocol test",
            researcherName = "Protocol researcher",
            researcherContact = "protocol@example.invalid",
            purpose = "Exercise the destructive Protocol v1 contract.",
            durationHours = 24,
            consentDocumentVersion = "v1",
            consentSummary = "Protocol test consent summary.",
            collectors = listOf(
                AppLifecycleConfiguration(true),
                LocationConfiguration(false, 10_000, 5_000, 30_000, 5_000, LocationPriority.BALANCED),
            ),
            surveys = emptyList(),
            interventions = emptyList(),
            maximumLocalBytes = 16_777_216,
            signer = SignerIdentity("test-signer", signerPublicKey),
            export = ExportConfiguration("test-hpke", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
            upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false),
        )

    private fun pretty(canonical: ByteArray): ByteArray = canonical.toString(Charsets.UTF_8)
        .replace("{", "{\n")
        .replace(",", ",\n")
        .toByteArray()
}
