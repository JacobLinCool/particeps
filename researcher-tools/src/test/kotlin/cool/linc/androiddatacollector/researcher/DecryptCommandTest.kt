package cool.linc.androiddatacollector.researcher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecryptCommandTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val corpus: JsonObject by lazy {
        val repository = Path.of(requireNotNull(System.getProperty("adc.repository.root")))
        JsonParser.parseString(
            Files.readString(repository.resolve("protocol/v1/conformance-vectors.json")),
        ).asJsonObject
    }

    @Test
    fun validBundleIsPrivateAndPublishedOnlyAfterCompleteVerification() {
        val files = inputs(corpus.getAsJsonObject("valid").getAsJsonObject("bundle").get("container_hex").asString)

        main(files.arguments())

        assertArrayEquals(
            corpus.getAsJsonObject("valid").getAsJsonObject("bundle")
                .get("document_jcs_utf8_hex").asString.hex(),
            Files.readAllBytes(files.output),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(files.output),
        )
    }

    @Test
    fun authenticatedButInvalidDocumentPublishesNothingAndDeletesStaging() {
        val vector = corpus.getAsJsonArray("hostile")
            .map { it.asJsonObject }
            .single { it.get("id").asString == "bundle-unknown-payload" }
        val files = inputs(vector.get("input_hex").asString)

        assertThrows(Exception::class.java) { main(files.arguments()) }

        assertFalse(Files.exists(files.output))
        Files.list(files.output.parent).use { entries ->
            assertFalse(entries.anyMatch { it.fileName.toString().startsWith(".adc-decrypt") })
        }
    }

    private fun inputs(bundleHex: String): CommandFiles {
        val directory = temporary.newFolder().toPath()
        val valid = corpus.getAsJsonObject("valid")
        val signed = valid.getAsJsonObject("signed_configuration")
        val bundle = valid.getAsJsonObject("bundle")
        return CommandFiles(
            bundle = directory.resolve("input.adcexp").also { Files.write(it, bundleHex.hex()) },
            privateKey = directory.resolve("private.key").also {
                Files.writeString(it, bundle.get("researcher_private_key_base64url").asString)
            },
            configuration = directory.resolve("configuration.json").also {
                Files.write(it, signed.get("canonical_jcs_utf8_hex").asString.hex())
            },
            output = directory.resolve("output.json"),
        )
    }

    private fun String.hex(): ByteArray {
        require(length % 2 == 0 && matches(Regex("[0-9a-f]*")))
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private data class CommandFiles(
        val bundle: Path,
        val privateKey: Path,
        val configuration: Path,
        val output: Path,
    ) {
        fun arguments() = arrayOf(
            "decrypt",
            "--bundle", bundle.toString(),
            "--private", privateKey.toString(),
            "--config", configuration.toString(),
            "--output", output.toString(),
        )
    }
}
