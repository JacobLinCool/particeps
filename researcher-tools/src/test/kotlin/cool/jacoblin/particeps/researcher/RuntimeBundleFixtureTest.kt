package cool.jacoblin.particeps.researcher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.export.AuthenticatedBundleHeader
import cool.jacoblin.particeps.core.export.ResearchBundleVerifier
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every bundle a real runtime exported, RC13 and later, decrypts and verifies with the INSECURE
 * demo researcher key, through the library and through `researcher-tools decrypt`.
 *
 * Fixtures are the `*.partexp` files under `particeps-analysis/tests/fixtures/runtime-bundles`,
 * or under the directories named by the `particeps.runtime.bundle.fixtures` system property
 * (Gradle property `particeps.runtimeBundleFixtures`, path-separator separated). Each bundle's
 * configuration is the canonical JSON or signed `.partcfg`, beside the fixtures or in this
 * module's `runtime-bundle-configurations` test resources, whose SHA-256 equals the digest in the
 * bundle's outer header. Those configurations are signed by the public demo signer and export to
 * the public demo HPKE key; every fixture already embeds its own.
 *
 * When a fixture root carries the fixture `manifest.json`, the verified bundle ID, commit and
 * event counts, final state and ciphertext SHA-256 must equal the manifest's.
 *
 * Each bundle is also verified as a retained partial range starting at every commit where a
 * reader without history must still prove a rule: a recovery, a resource-barrier flush, and a
 * condition-epoch transition.
 */
class RuntimeBundleFixtureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val repository = Path.of(requireNotNull(System.getProperty("particeps.repository.root")))

    private val roots: List<Path> = System.getProperty(FIXTURES_PROPERTY)
        ?.takeIf(String::isNotBlank)
        ?.split(File.pathSeparator)
        ?.map { Path.of(it).toAbsolutePath().normalize() }
        ?: listOf(repository.resolve(DEFAULT_FIXTURES))

    private val privateKeyFile = repository.resolve("researcher-tools/examples/INSECURE-demo-hpke-private.key")

    @Test
    fun everyRuntimeBundleDecryptsAndVerifies() {
        val bundles = roots.flatMap { root -> files(root) { it.extension == "partexp" } }.sorted()
        assertTrue("No runtime bundle fixtures under $roots", bundles.isNotEmpty())
        val configurations = configurationsByDigest()
        val privateKey = ProtocolBase64Url.decodeExact(
            Files.readString(privateKeyFile).trim(),
            HpkeCrypto.RAW_KEY_BYTES,
            "X25519 private key",
        )
        val manifests = manifestEntries()
        val failures = bundles.mapNotNull { bundle ->
            runCatching { verifyFixture(bundle, configurations, privateKey, manifests) }
                .exceptionOrNull()
                ?.let { "$bundle: ${it.message}" }
        }
        assertEquals("Rejected runtime bundles", emptyList<String>(), failures)
    }

    /**
     * Entries of each root's `manifest.json` (format `particeps-runtime-bundle-fixtures-v1`), keyed
     * by bundle path. A root with a manifest lists every bundle it holds.
     */
    private fun manifestEntries(): Map<Path, JsonObject> = roots.flatMap { root ->
        val manifest = root.resolve("manifest.json")
        if (!Files.isRegularFile(manifest)) return@flatMap emptyList()
        val document = JsonParser.parseString(Files.readString(manifest)).asJsonObject
        require(document.get("format").asString == MANIFEST_FORMAT) { "Unknown fixture manifest format in $manifest" }
        val entries = document.getAsJsonArray("fixtures").map { it.asJsonObject }
            .associateBy { root.resolve(it.get("file").asString) }
        val listed = files(root) { it.extension == "partexp" }.toSet()
        require(entries.keys == listed) { "$manifest does not list exactly the bundles in $root" }
        entries.toList()
    }.toMap()

    private fun verifyFixture(
        bundle: Path,
        configurations: Map<String, ByteArray>,
        privateKey: ByteArray,
        manifests: Map<Path, JsonObject>,
    ) {
        val encoded = Files.readAllBytes(bundle)
        val digest = headerConfigurationDigest(encoded)
        val configurationBytes = requireNotNull(configurations[digest]) {
            "No configuration with SHA-256 $digest beside the fixtures or in $CONFIGURATIONS"
        }
        val configuration = StudyConfigurationCodec.decode(configurationBytes)
        val (header, document) = ByteArrayOutputStream().let { plaintext ->
            ResearchExport.decrypt(encoded.inputStream(), plaintext, privateKey, configuration) to plaintext.toByteArray()
        }
        val verified = ResearchBundleVerifier.verify(document, header, configuration)
        assertEquals(1L, verified.experiment.firstCommitSequence)
        assertTrue(verified.experiment.commitCount > 0)
        manifests[bundle]?.let { entry ->
            assertEquals(entry.get("sha256").asString, encoded.sha256Hex())
            assertEquals(entry.get("bundle_id").asString, verified.header.bundleId.toString())
            assertEquals(entry.get("commit_count").asLong, verified.experiment.commitCount)
            assertEquals(entry.get("event_count").asLong, verified.experiment.eventCount)
            assertEquals(entry.get("state").asString, verified.experiment.state.name)
        }

        verifyRetainedRanges(document, header, configuration)

        val directory = temporary.newFolder().toPath()
        val configurationFile = directory.resolve("configuration.json").also { Files.write(it, configurationBytes) }
        val output = directory.resolve("bundle.json")
        main(
            arrayOf(
                "decrypt",
                "--bundle", bundle.toString(),
                "--private", privateKeyFile.toString(),
                "--config", configurationFile.toString(),
                "--output", output.toString(),
            ),
        )
        assertArrayEquals(document, Files.readAllBytes(output))
    }

    private fun verifyRetainedRanges(
        document: ByteArray,
        header: AuthenticatedBundleHeader,
        configuration: StudyConfiguration,
    ) {
        val spans = commitSpans(document)
        val commits = spans.map { span ->
            val commit = JsonParser.parseString(String(document, span.first, span.last - span.first + 1, Charsets.UTF_8))
                .asJsonObject
            CommitFacts(
                eventCount = commit.getAsJsonArray("events").size(),
                recovery = commit.get("input_kind").asString == "RECOVERY",
                boundary = commit.getAsJsonArray("source_observations").any {
                    it.asJsonObject.get("admission_kind").asString == "BARRIER_FLUSH"
                } || commit.getAsJsonArray("events").any {
                    it.asJsonObject.get("source_id").asString == "study_condition.v1"
                },
            )
        }
        val recoveries = commits.indices.filter { it > 0 && commits[it].recovery }
        val boundaries = commits.indices.filter { it > 0 && !commits[it].recovery && commits[it].boundary }
        // Every recovery start is kept; the more frequent boundaries are sampled to bound the
        // quadratic cost of re-verifying long bundles.
        val stride = boundaries.size / MAXIMUM_BOUNDARY_STARTS + 1
        val starts = (recoveries + boundaries.filterIndexed { position, _ -> position % stride == 0 }).sorted()
        starts.forEach { start ->
            val eventCount = commits.drop(start).sumOf { it.eventCount.toLong() }
            val partial = retainedRange(document, spans, start, eventCount)
            val verified = runCatching { ResearchBundleVerifier.verify(partial, header, configuration) }
                .getOrElse { throw IllegalArgumentException("retained range from commit ${start + 1}: ${it.message}", it) }
            assertEquals(start + 1L, verified.experiment.firstCommitSequence)
        }
    }

    private data class CommitFacts(val eventCount: Int, val recovery: Boolean, val boundary: Boolean)

    /**
     * Rewrites the canonical document as a manual export of the retained range starting at commit
     * index [start]. Only the range members change; every commit stays byte-identical.
     */
    private fun retainedRange(document: ByteArray, commits: List<IntRange>, start: Int, eventCount: Long): ByteArray {
        val arrayStart = commits.first().first - 1
        val arrayEnd = commits.last().last + 1
        val head = String(document, 0, arrayStart + 1, Charsets.UTF_8)
        val countMember = head.lastIndexOf("\"commit_count\":\"")
        require(countMember > 0 && head.endsWith("\",\"commits\":[")) { "Unexpected experiment layout" }
        val tail = String(document, arrayEnd, document.size - arrayEnd, Charsets.UTF_8)
            .replaceFirstMember("event_count", eventCount.toString())
            .replaceFirstMember("first_commit_sequence", (start + 1).toString())
        return ByteArrayOutputStream(document.size).apply {
            write(
                (head.substring(0, countMember) + "\"commit_count\":\"${commits.size - start}\",\"commits\":[")
                    .toByteArray(Charsets.UTF_8),
            )
            commits.drop(start).forEachIndexed { index, span ->
                if (index > 0) write(','.code)
                write(document, span.first, span.last - span.first + 1)
            }
            write(tail.toByteArray(Charsets.UTF_8))
        }.toByteArray()
    }

    private fun String.replaceFirstMember(name: String, value: String): String {
        val pattern = Regex("\"$name\":\"[0-9]+\"")
        val match = requireNotNull(pattern.find(this)) { "Missing experiment member $name" }
        return replaceRange(match.range, "\"$name\":\"$value\"")
    }

    /**
     * Byte ranges of the top-level objects in the experiment's `commits` array. JSON structural
     * characters are ASCII, so they never occur inside a UTF-8 multibyte sequence.
     */
    private fun commitSpans(document: ByteArray): List<IntRange> {
        val marker = "\"commits\":[".toByteArray(Charsets.US_ASCII)
        val arrayStart = (0..document.size - marker.size).first { offset ->
            marker.indices.all { document[offset + it] == marker[it] }
        } + marker.size - 1
        val spans = ArrayList<IntRange>()
        var depth = 0
        var objectStart = -1
        var inString = false
        var index = arrayStart + 1
        while (true) {
            val character = document[index].toInt().toChar()
            if (inString) {
                when (character) {
                    '\\' -> index++
                    '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        if (depth == 0) objectStart = index
                        depth++
                    }
                    '}', ']' -> {
                        if (depth == 0) return spans
                        depth--
                        if (depth == 0) spans += objectStart..index
                    }
                }
            }
            index++
        }
    }

    private fun configurationsByDigest(): Map<String, ByteArray> {
        val resources = Path.of(requireNotNull(javaClass.getResource("/$CONFIGURATIONS")).toURI())
        val candidates = (roots + resources).flatMap { root ->
            files(root) { (it.extension == "json" || it.extension == "partcfg") && Files.size(it) <= MAXIMUM_CONFIGURATION_BYTES }
        }
        return candidates.mapNotNull { path ->
            runCatching {
                val raw = Files.readAllBytes(path)
                val canonical = if (path.extension == "partcfg") {
                    SignedConfigurationCodec.decode(raw).configurationBytes
                } else {
                    StudyConfigurationCodec.canonicalize(raw)
                }
                StudyConfigurationCodec.encode(StudyConfigurationCodec.decode(canonical))
            }.getOrNull()?.let { canonical -> canonical.sha256Hex() to canonical }
        }.toMap()
    }

    private fun files(root: Path, include: (Path) -> Boolean): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths -> paths.filter { it.isRegularFile() && include(it) }.toList() }
    }

    /** The Protocol v1 outer header is magic (8), bundle UUID (16), configuration SHA-256 (32). */
    private fun headerConfigurationDigest(encoded: ByteArray): String {
        require(encoded.size > HEADER_DIGEST_OFFSET + DIGEST_BYTES) { "Truncated bundle" }
        return encoded.copyOfRange(HEADER_DIGEST_OFFSET, HEADER_DIGEST_OFFSET + DIGEST_BYTES).toHex()
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURES_PROPERTY = "particeps.runtime.bundle.fixtures"
        const val DEFAULT_FIXTURES = "particeps-analysis/tests/fixtures/runtime-bundles"
        const val CONFIGURATIONS = "runtime-bundle-configurations"
        const val MANIFEST_FORMAT = "particeps-runtime-bundle-fixtures-v1"
        const val HEADER_DIGEST_OFFSET = 24
        const val DIGEST_BYTES = 32
        const val MAXIMUM_CONFIGURATION_BYTES = 1L shl 20
        const val MAXIMUM_BOUNDARY_STARTS = 24
    }
}
