package cool.linc.androiddatacollector.core.protocol

import cool.linc.androiddatacollector.core.definition.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigurationProtocolTest {
    @Test
    fun canonicalCodecRoundTripsEveryCollector() {
        val configuration = configuration()
        val canonical = StudyConfigurationCodec.encode(configuration)

        assertEquals(configuration, StudyConfigurationCodec.decode(canonical))
        assertArrayEquals(canonical, StudyConfigurationCodec.canonicalize(pretty(configuration)))
    }

    @Test
    fun strictCodecRejectsNonCanonicalAndUnknownFields() {
        val canonical = StudyConfigurationCodec.encode(configuration())
        val withWhitespace = canonical.toString(Charsets.UTF_8).replaceFirst("{", "{\n")
        val withUnknown = canonical.toString(Charsets.UTF_8).replaceFirst("{", "{\"unknown\":true,")

        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(withWhitespace.toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(withUnknown.toByteArray())
        }
    }

    @Test
    fun uploadBlockRoundTripsWhenPresentAndAbsent() {
        val enabled = configuration(UploadConfiguration("https://intake.example.invalid/v1", 360, false))
        val canonical = StudyConfigurationCodec.encode(enabled)

        assertEquals(enabled, StudyConfigurationCodec.decode(canonical))
        assertArrayEquals(canonical, StudyConfigurationCodec.canonicalize(pretty(enabled)))

        // An absent upload block is encoded as an empty object, so the root key set stays fixed.
        val disabled = StudyConfigurationCodec.encode(configuration())
        assertEquals(null, StudyConfigurationCodec.decode(disabled).upload)
        assertEquals(true, disabled.toString(Charsets.UTF_8).contains("\"upload\":{}"))
    }

    @Test
    fun uploadBlockRejectsPartialAndInsecureEndpoints() {
        val canonical = StudyConfigurationCodec.encode(
            configuration(UploadConfiguration("https://intake.example.invalid/v1", 360, false)),
        ).toString(Charsets.UTF_8)

        // Cleartext must be refused in the schema, not left to the platform to block later.
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(
                canonical.replace("https://intake", "http://intake").toByteArray(),
            )
        }
        // A half-declared block must not silently inherit a default cadence.
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(
                canonical.replace(",\"interval_minutes\":360", "").toByteArray(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(
                canonical.replace("\"interval_minutes\":360", "\"interval_minutes\":0").toByteArray(),
            )
        }
    }

    @Test
    fun verifierAuthenticatesSignerAndValidityWindow() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val envelope = sign(keyPair)
        val verifier = verifier(mapOf("test-signer" to encoded(keyPair)))

        val verified = verifier.verify(envelope)
        assertEquals("protocol-test", verified.configuration.experimentId)
        assertEquals(true, verified.signerAnchored)
        val tampered = envelope.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(tampered) }
    }

    @Test
    fun aConfigurationCertifiesItselfWhenNoSignerIsPinned() {
        // What lets one published app run any researcher's study: the signing key travels inside
        // the signed bytes, so verification needs nothing but the file.
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val envelope = sign(keyPair)

        val verified = verifier(emptyMap()).verify(envelope)

        assertEquals("protocol-test", verified.configuration.experimentId)
        // The signature proves the file is unchanged, not who wrote it, and the app must say so.
        assertEquals(false, verified.signerAnchored)
        val tampered = envelope.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { verifier(emptyMap()).verify(tampered) }
    }

    @Test
    fun pinningRefusesAnyOtherSignerAndAnySubstitutedKey() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        // A build that pins signers accepts only those, whatever the configuration declares.
        assertThrows(IllegalArgumentException::class.java) {
            verifier(mapOf("someone-else" to encoded(other))).verify(sign(keyPair))
        }

        // And a configuration cannot claim a pinned key ID while carrying a different key: the
        // pinned key wins, so the signature made with the impostor's key fails.
        assertThrows(IllegalArgumentException::class.java) {
            verifier(mapOf("test-signer" to encoded(other))).verify(sign(keyPair))
        }
    }

    @Test
    fun envelopeSignerMustMatchTheConfiguration() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val configurationBytes = StudyConfigurationCodec.encode(configuration(signerPublicKey = encoded(keyPair)))
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(configurationBytes)
            sign()
        }
        // The envelope label sits outside the signature, so a mismatch has to be caught explicitly.
        val relabelled = SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope("other-label", configurationBytes, signature),
        )

        assertThrows(IllegalArgumentException::class.java) { verifier(emptyMap()).verify(relabelled) }
    }

    private fun encoded(keyPair: java.security.KeyPair) =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sign(keyPair: java.security.KeyPair): ByteArray {
        val configurationBytes = StudyConfigurationCodec.encode(
            configuration(signerPublicKey = encoded(keyPair)),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(configurationBytes)
            sign()
        }
        return SignedConfigurationCodec.encode(
            SignedConfigurationEnvelope("test-signer", configurationBytes, signature),
        )
    }

    private fun verifier(pinned: Map<String, String>) = ConfigurationVerifier(
        pinned,
        appVersionCode = 1,
        now = { Instant.parse("2026-07-31T00:00:00Z") },
    )

    private fun configuration(
        upload: UploadConfiguration? = null,
        signerPublicKey: String = TEST_SIGNER_PUBLIC_KEY,
    ) = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = "protocol-test",
        configurationId = "protocol-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        minimumAppVersion = 1,
        title = "Protocol test",
        researcherName = "Protocol researcher",
        researcherContact = "protocol@example.invalid",
        purpose = "Exercise strict signed configuration encoding.",
        durationHours = 24,
        consentDocumentVersion = "v1",
        consentSummary = "Protocol test consent summary.",
        collectors = listOf(
            AppLifecycleConfiguration(true),
            AccelerometerConfiguration(true, 100_000, 1_000_000),
            NetworkStateConfiguration(true, true),
            NetworkUsageConfiguration(false, setOf(NetworkTransport.WIFI, NetworkTransport.MOBILE), 5),
            UsageEventsConfiguration(false, 30),
            LocationConfiguration(false, 10_000, 5_000, 30_000, 5f, LocationPriority.BALANCED),
            KeyboardTouchConfiguration(false, 60),
        ),
        prompts = listOf(PromptConfiguration("daily-check", 60, "Check in.")),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", signerPublicKey),
        export = ExportConfiguration(
            "test-hpke",
            "{\"primaryKeyId\":123456,\"key\":[]}",
        ),
        upload = upload,
    )

    private fun pretty(configuration: StudyConfiguration): ByteArray =
        StudyConfigurationCodec.encode(configuration)
            .toString(Charsets.UTF_8)
            .replace("{", "{\n")
            .replace(",", ",\n")
            .toByteArray()
}

private const val TEST_SIGNER_PUBLIC_KEY =
    "MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0="
