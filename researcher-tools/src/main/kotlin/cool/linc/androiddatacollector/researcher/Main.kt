package cool.linc.androiddatacollector.researcher

import cool.linc.androiddatacollector.core.crypto.HpkeCrypto
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.protocol.ConfigurationVerifier
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationCodec
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationEnvelope
import cool.linc.androiddatacollector.core.definition.StudyConfigurationCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

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
    writeNew(args.path("--private"), Base64.getEncoder().encode(pair.private.encoded))
    writeNew(args.path("--public"), Base64.getEncoder().encode(pair.public.encoded))
}

private fun hpkeKeygen(args: Arguments) {
    val pair = HpkeCrypto.generateKeyset()
    writeNew(args.path("--private"), pair.privateKeysetJson.toByteArray(Charsets.UTF_8))
    writeNew(args.path("--public"), pair.publicKeysetJson.toByteArray(Charsets.UTF_8))
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
    val staging = Files.createTempDirectory(output.parent, ".adc-personalize-")
    try {
        assignments.forEach { configuration ->
            val canonical = StudyConfigurationCodec.encode(configuration)
            val envelope = signEnvelope(canonical, configuration.signer.publicKey, keyId, privateKey)
            writeNew(staging.resolve("${configuration.configurationId}.json"), canonical)
            writeNew(staging.resolve("${configuration.configurationId}.adccfg"), envelope)
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
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())),
    )
    val signature = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(configurationBytes)
        sign()
    }
    val declaredKey = KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(declaredPublicKey)),
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
        appVersionCode = args.optionalValue("--app-version")?.toInt() ?: Int.MAX_VALUE,
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
    // Staged, because the AES-GCM tag is only checked once the last byte has been read: a tampered
    // bundle writes plausible-looking plaintext right up until it fails. Nothing appears at the
    // destination unless verification succeeded.
    val staging = Files.createTempFile(output.toAbsolutePath().parent, ".adc-decrypt", ".tmp")
    try {
        Files.newInputStream(args.path("--bundle")).use { input ->
            Files.newOutputStream(staging).use { plaintext ->
                ResearchExport.decrypt(input, plaintext, Files.readString(args.path("--private")), configuration)
            }
        }
        Files.move(staging, output)
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
