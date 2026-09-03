package cool.jacoblin.particeps.researcher

import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.export.ResearchBundleVerifier
import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import cool.jacoblin.particeps.core.protocol.SignedConfigurationEnvelope
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant

fun main(arguments: Array<String>) {
    require(arguments.isNotEmpty()) { usage() }
    val args = Arguments(arguments.drop(1))
    when (arguments.first()) {
        "signing-keygen" -> signingKeygen(args)
        "hpke-keygen" -> hpkeKeygen(args)
        "canonicalize" -> canonicalize(args)
        "sign" -> sign(args)
        "personalize" -> personalize(args)
        "check-config" -> checkConfig(args)
        "decrypt" -> decrypt(args)
        else -> throw IllegalArgumentException(usage())
    }
}

private fun signingKeygen(args: Arguments) {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    writeNew(args.path("--private"), ProtocolBase64Url.encode(pair.private.encoded.requirePrefix(ED25519_PKCS8_PREFIX)).toByteArray())
    writeNew(args.path("--public"), ProtocolBase64Url.encode(pair.public.encoded.requirePrefix(ED25519_X509_PREFIX)).toByteArray())
}

private fun hpkeKeygen(args: Arguments) {
    val pair = HpkeCrypto.generateKeyPair()
    writeNew(args.path("--private"), ProtocolBase64Url.encode(pair.privateKey).toByteArray())
    writeNew(args.path("--public"), ProtocolBase64Url.encode(pair.publicKey).toByteArray())
}

private fun canonicalize(args: Arguments) {
    var canonical = StudyConfigurationCodec.canonicalize(Files.readAllBytes(args.path("--input")))
    args.optionalValue("--assigned-participant-id")?.let { assignedId ->
        canonical = StudyConfigurationCodec.encode(
            StudyConfigurationCodec.decode(canonical).copy(assignedParticipantId = assignedId),
        )
    }
    writeNew(args.path("--output"), canonical)
}

private fun sign(args: Arguments) {
    var configurationBytes = Files.readAllBytes(args.path("--config"))
    var configuration = StudyConfigurationCodec.decode(configurationBytes)
    args.optionalValue("--assigned-participant-id")?.let { assignedId ->
        configuration = configuration.copy(assignedParticipantId = assignedId)
        configurationBytes = StudyConfigurationCodec.encode(configuration)
    }
    val keyId = args.value("--key-id")
    require(configuration.signer.keyId == keyId) {
        "Configuration declares signer '${configuration.signer.keyId}' but --key-id is '$keyId'"
    }
    val envelope = signEnvelope(
        configurationBytes,
        configuration.signer.publicKey,
        keyId,
        Files.readString(args.path("--private")),
    )
    writeNew(args.path("--output"), envelope)
    println("signed ${configuration.experimentId} ${configuration.configurationId}")
    println("fingerprint ${configuration.signer.fingerprint}")
}

/**
 * Produces one canonical JSON and signed envelope per tab-separated
 * `configuration_id<TAB>assigned_participant_id` row. The assigned code never appears in a
 * filename or command output; researchers keep the supplied mapping as the join table.
 */
private fun personalize(args: Arguments) {
    val baseBytes = StudyConfigurationCodec.canonicalize(Files.readAllBytes(args.path("--config")))
    val base = StudyConfigurationCodec.decode(baseBytes)
    val keyId = args.value("--key-id")
    require(base.signer.keyId == keyId) { "Configuration signer does not match --key-id" }
    val privateKey = Files.readString(args.path("--private"))
    val assignments = Files.readAllLines(args.path("--mapping")).mapIndexed { index, line ->
        require(line.isNotBlank()) { "Blank mapping row ${index + 1}" }
        val fields = line.split('\t')
        require(fields.size == 2) { "Mapping row ${index + 1} must contain exactly two tab-separated fields" }
        base.copy(configurationId = fields[0], assignedParticipantId = fields[1])
    }
    require(assignments.isNotEmpty()) { "Mapping is empty" }
    require(assignments.map { it.configurationId }.distinct().size == assignments.size) {
        "Duplicate configuration ID in mapping"
    }

    val output = args.path("--output-dir")
    require(!Files.exists(output)) { "Refusing to overwrite ${output.toAbsolutePath()}" }
    output.parent?.let(Files::createDirectories)
    val staging = Files.createTempDirectory(output.parent, ".particeps-personalize-")
    try {
        assignments.forEach { configuration ->
            val canonical = StudyConfigurationCodec.encode(configuration)
            val envelope = signEnvelope(canonical, configuration.signer.publicKey, keyId, privateKey)
            writeNew(staging.resolve("${configuration.configurationId}.json"), canonical)
            writeNew(staging.resolve("${configuration.configurationId}.partcfg"), envelope)
        }
        Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: Throwable) {
        deleteTree(staging)
        throw failure
    }
    println("personalized ${assignments.size} configurations")
}

private fun signEnvelope(
    configurationBytes: ByteArray,
    declaredPublicKey: String,
    keyId: String,
    privateKeyBase64: String,
): ByteArray {
    val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
        PKCS8EncodedKeySpec(
            ED25519_PKCS8_PREFIX + ProtocolBase64Url.decodeExact(privateKeyBase64.trim(), 32, "Ed25519 private key"),
        ),
    )
    val signature = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(configurationBytes)
        sign()
    }
    val declaredKey = KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(
            ED25519_X509_PREFIX + ProtocolBase64Url.decodeExact(declaredPublicKey, 32, "Ed25519 public key"),
        ),
    )
    require(Signature.getInstance("Ed25519").run {
        initVerify(declaredKey)
        update(configurationBytes)
        verify(signature)
    }) { "signer.public_key in the configuration does not match --private" }
    return SignedConfigurationCodec.encode(
        SignedConfigurationEnvelope(keyId, configurationBytes, signature),
    )
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

private fun checkConfig(args: Arguments) {
    // A configuration carries its own signing key, so --public and --key-id are optional. Supplying
    // them pins the check, reproducing what a build that lists the signer would enforce.
    val pinned = args.optionalValue("--public")?.let { path ->
        val keyId = requireNotNull(args.optionalValue("--key-id")) { "--public also needs --key-id" }
        mapOf(keyId to Files.readString(Path.of(path)).trim())
    } ?: emptyMap()
    val verified = ConfigurationVerifier(
        trustedSigningKeys = pinned,
        clientVersion = args.optionalValue("--app-version")?.toLong() ?: Long.MAX_VALUE,
        now = { args.optionalValue("--now")?.let(Instant::parse) ?: Instant.now() },
    ).verify(Files.readAllBytes(args.path("--envelope")))
    val configuration = verified.configuration
    println("valid ${configuration.experimentId} ${configuration.configurationId}")
    println("signer ${configuration.signer.keyId} ${configuration.signer.fingerprint}")
    println(if (verified.signerAnchored) "pinned yes" else "pinned no (self-certifying)")
}

private fun decrypt(args: Arguments) {
    val configuration = StudyConfigurationCodec.decode(Files.readAllBytes(args.path("--config")))
    val output = args.path("--output")
    require(!Files.exists(output)) { "Refusing to overwrite ${output.toAbsolutePath()}" }
    val outputParent = requireNotNull(output.toAbsolutePath().parent) { "Output needs a parent directory" }
    require(Files.isDirectory(outputParent)) { "Output parent does not exist: $outputParent" }
    // AEAD authenticates only at EOF, and the authenticated plaintext still has a closed-world
    // schema to prove. Keep both phases in a private staging file; publish only their joint result.
    val staging = Files.createTempFile(
        outputParent,
        ".particeps-decrypt",
        ".tmp",
        PosixFilePermissions.asFileAttribute(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        ),
    )
    try {
        val header = Files.newInputStream(args.path("--bundle")).use { input ->
            Files.newOutputStream(
                staging,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { plaintext ->
                ResearchExport.decrypt(
                    input,
                    plaintext,
                    ProtocolBase64Url.decodeExact(
                        Files.readString(args.path("--private")).trim(),
                        HpkeCrypto.RAW_KEY_BYTES,
                        "X25519 private key",
                    ),
                    configuration,
                )
            }
        }
        val verified = Files.newInputStream(staging).use { plaintext ->
            ResearchBundleVerifier.verify(plaintext, header, configuration)
        }
        FileChannel.open(staging, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
        println(
            "verified ${verified.header.bundleId} ${verified.experiment.firstCommitSequence}-" +
                "${verified.experiment.lastCommitSequence}",
        )
    } catch (failure: Throwable) {
        Files.deleteIfExists(staging)
        throw failure
    }
}

private fun writeNew(
    path: Path,
    bytes: ByteArray,
) {
    path.parent?.let(Files::createDirectories)
    Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
}

private class Arguments(raw: List<String>) {
    private val values: Map<String, String>

    init {
        require(raw.size % 2 == 0) { "Every option needs one value" }
        values = raw.chunked(2).associate { pair ->
            require(pair[0].startsWith("--")) { "Invalid option: ${pair[0]}" }
            pair[0] to pair[1]
        }
        require(values.size * 2 == raw.size) { "Duplicate option" }
    }

    fun value(name: String): String = requireNotNull(values[name]) { "Missing option $name" }

    fun optionalValue(name: String): String? = values[name]

    fun path(name: String): Path = Path.of(value(name)).toAbsolutePath().normalize()
}

private fun usage(): String = """
    Commands:
      signing-keygen --private FILE --public FILE
      hpke-keygen --private FILE --public FILE
      canonicalize --input FILE --output FILE [--assigned-participant-id ID]
      sign --config FILE --private FILE --key-id ID --output FILE [--assigned-participant-id ID]
      personalize --config FILE --mapping TSV --private FILE --key-id ID --output-dir DIRECTORY
      check-config --envelope FILE [--public FILE --key-id ID] [--app-version N] [--now ISO_INSTANT]
      decrypt --bundle FILE --private FILE --config FILE --output FILE
""".trimIndent()

private fun ByteArray.requirePrefix(prefix: ByteArray): ByteArray {
    require(size == prefix.size + 32 && copyOfRange(0, prefix.size).contentEquals(prefix)) {
        "Unexpected JCA raw-key encoding"
    }
    return copyOfRange(prefix.size, size)
}

private val ED25519_X509_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)
private val ED25519_PKCS8_PREFIX = byteArrayOf(
    0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
)
