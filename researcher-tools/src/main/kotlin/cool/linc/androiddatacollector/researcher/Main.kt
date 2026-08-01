package cool.linc.androiddatacollector.researcher

import cool.linc.androiddatacollector.core.crypto.HpkeCrypto
import cool.linc.androiddatacollector.core.export.ResearchExport
import cool.linc.androiddatacollector.core.protocol.ConfigurationVerifier
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationCodec
import cool.linc.androiddatacollector.core.protocol.SignedConfigurationEnvelope
import cool.linc.androiddatacollector.core.definition.StudyConfigurationCodec
import java.nio.file.Files
import java.nio.file.Path
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
    val canonical = StudyConfigurationCodec.canonicalize(Files.readAllBytes(args.path("--input")))
    writeNew(args.path("--output"), canonical)
}

private fun sign(args: Arguments) {
    val configurationBytes = Files.readAllBytes(args.path("--config"))
    val configuration = StudyConfigurationCodec.decode(configurationBytes)
    val keyId = args.value("--key-id")
    require(configuration.signer.keyId == keyId) {
        "Configuration declares signer '${configuration.signer.keyId}' but --key-id is '$keyId'"
    }
    val privateKeyBytes = Base64.getDecoder().decode(Files.readString(args.path("--private")).trim())
    val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
    val signature = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(configurationBytes)
        sign()
    }
    // The configuration carries the public key participants will verify with, so a mismatch here
    // would produce a file that signs cleanly and then fails on every device. Catch it now.
    val declaredKey = KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(configuration.signer.publicKey)),
    )
    val selfCheck = Signature.getInstance("Ed25519").run {
        initVerify(declaredKey)
        update(configurationBytes)
        verify(signature)
    }
    require(selfCheck) { "signer.public_key in the configuration does not match --private" }
    val envelope = SignedConfigurationCodec.encode(
        SignedConfigurationEnvelope(
            signerKeyId = keyId,
            configurationBytes = configurationBytes,
            signature = signature,
        ),
    )
    writeNew(args.path("--output"), envelope)
    println("signed ${configuration.experimentId} ${configuration.configurationId}")
    println("fingerprint ${configuration.signer.fingerprint}")
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
      canonicalize --input FILE --output FILE
      sign --config FILE --private FILE --key-id ID --output FILE
      check-config --envelope FILE [--public FILE --key-id ID] [--app-version N] [--now ISO_INSTANT]
      decrypt --bundle FILE --private FILE --config FILE --output FILE
""".trimIndent()
