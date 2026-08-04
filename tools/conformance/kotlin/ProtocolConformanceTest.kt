package adc.conformance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cool.linc.androiddatacollector.core.crypto.HpkeCrypto
import cool.linc.androiddatacollector.core.definition.ProtocolCanonicalJson
import cool.linc.androiddatacollector.core.definition.StudyConfigurationCodec
import cool.linc.androiddatacollector.core.export.ResearchBundleVerifier
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.export.UploadReceiptCodec
import cool.linc.androiddatacollector.core.protocol.ConfigurationVerifier
import cool.linc.androiddatacollector.core.protocol.JoinLink
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolConformanceTest {
    private val corpus: JsonObject by lazy {
        val root = requireNotNull(System.getProperty("adc.repository.root"))
        JsonParser.parseString(File(root, "protocol/v1/conformance-vectors.json").readText()).asJsonObject
    }
    private val joinCorpus: JsonObject by lazy {
        val root = requireNotNull(System.getProperty("adc.repository.root"))
        JsonParser.parseString(File(root, "protocol/v1/join-link-vectors.json").readText()).asJsonObject
    }

    @Test
    fun validCorpusIsConsumedByKotlinProtocolImplementations() {
        val valid = corpus.objectAt("valid")
        ProtocolCanonicalJson.requireCanonical(
            valid.objectAt("canonical_json").hexAt("canonical_jcs_utf8_hex"),
            MAXIMUM_FIXTURE_BYTES,
        )
        val signed = valid.objectAt("signed_configuration")
        val envelope = SignedConfigurationCodec.decode(signed.hexAt("envelope_hex"))
        assertArrayEquals(signed.hexAt("canonical_jcs_utf8_hex"), envelope.configurationBytes)
        assertEquals(signed.stringAt("signer_key_id"), envelope.signerKeyId)
        val verified = ConfigurationVerifier(
            trustedSigningKeys = emptyMap(),
            clientVersion = 7,
            now = { Instant.parse("2027-01-01T00:00:00Z") },
        ).verify(signed.hexAt("envelope_hex"))

        val bundle = valid.objectAt("bundle")
        val plaintext = ByteArrayOutputStream()
        val header = ResearchExport.decrypt(
            bundle.hexAt("container_hex").inputStream(),
            plaintext,
            bundle.base64UrlAt("researcher_private_key_base64url"),
            verified.configuration,
        )
        ResearchBundleVerifier.verify(plaintext.toByteArray(), header, verified.configuration)
        assertArrayEquals(bundle.hexAt("document_jcs_utf8_hex"), plaintext.toByteArray())

        val receiptVector = valid.objectAt("upload_receipt")
        val receipt = UploadReceiptCodec.decode(receiptVector.hexAt("canonical_jcs_utf8_hex"))
        val expected = receiptVector.objectAt("value")
        assertEquals(expected.stringAt("bundle_id"), receipt.bundleId.toString())
        assertEquals(expected.stringAt("byte_count"), receipt.byteCount.toString())
        assertEquals(expected.stringAt("sha256"), receipt.sha256)
    }

    @Test
    fun everyHostileVectorFailsItsKotlinEntrypoint() {
        val valid = corpus.objectAt("valid")
        val signed = valid.objectAt("signed_configuration")
        val configuration = StudyConfigurationCodec.decode(signed.hexAt("canonical_jcs_utf8_hex"))
        val bundle = valid.objectAt("bundle")
        val privateKey = bundle.base64UrlAt("researcher_private_key_base64url")
        val container = bundle.hexAt("container_hex")
        corpus.getAsJsonArray("hostile").forEach { element ->
            val vector = element.asJsonObject
            val input = vector.hexAt("input_hex")
            when (vector.stringAt("entrypoint")) {
                "canonical_json" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    ProtocolCanonicalJson.requireCanonical(input, MAXIMUM_FIXTURE_BYTES)
                }
                "configuration_jcs" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    StudyConfigurationCodec.decode(input)
                }
                "signed_configuration" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    ConfigurationVerifier(
                        trustedSigningKeys = emptyMap(),
                        clientVersion = 7,
                        now = { Instant.parse("2027-01-01T00:00:00Z") },
                    ).verify(input)
                }
                "bundle" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    val plaintext = ByteArrayOutputStream()
                    val header = ResearchExport.decrypt(input.inputStream(), plaintext, privateKey, configuration)
                    ResearchBundleVerifier.verify(plaintext.toByteArray(), header, configuration)
                }
                "bundle_unwrap_context" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    val keyIdLength = ByteBuffer.wrap(container, 56, 2).short.toInt() and 0xffff
                    val wrapped = container.copyOfRange(70 + keyIdLength, 150 + keyIdLength)
                    HpkeCrypto.decrypt(privateKey, wrapped, input)
                }
                "receipt" -> assertThrows(vector.stringAt("id"), Exception::class.java) {
                    UploadReceiptCodec.decode(input)
                }
                else -> error("Unknown conformance entrypoint")
            }
        }
    }

    @Test
    fun sharedJoinLinkCorpusIsAcceptedAndRejectedByTheKotlinWireParser() {
        val valid = joinCorpus.objectAt("valid")
        val parsed = JoinLink.parse(valid.stringAt("encoded"))
        assertEquals(valid.stringAt("artifact_url"), parsed.artifactUrl.toASCIIString())
        assertEquals(valid.stringAt("artifact_sha256"), parsed.artifactSha256)
        assertEquals(valid.stringAt("signer_fingerprint"), parsed.signerFingerprint)
        assertEquals(valid.stringAt("encoded"), parsed.encode())

        joinCorpus.getAsJsonArray("hostile").forEach { element ->
            val vector = element.asJsonObject
            assertThrows(vector.stringAt("id"), IllegalArgumentException::class.java) {
                JoinLink.parse(vector.stringAt("encoded"))
            }
        }
    }

    private fun JsonObject.objectAt(name: String): JsonObject = getAsJsonObject(name)

    private fun JsonObject.stringAt(name: String): String = get(name).asString

    private fun JsonObject.hexAt(name: String): ByteArray {
        val text = stringAt(name)
        require(text.length % 2 == 0 && text.matches(Regex("[0-9a-f]*"))) { "Invalid vector hex" }
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun JsonObject.base64UrlAt(name: String): ByteArray = Base64.getUrlDecoder().decode(stringAt(name))

    private companion object { const val MAXIMUM_FIXTURE_BYTES = 1_048_576 }
}
