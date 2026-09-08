package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.net.URI
import java.security.MessageDigest

/** The shipped signed demo, transported through the same canonical pointer as a study QR. */
internal data class QrStudyImportFixture(val envelope: ByteArray, val join: JoinLink)

internal fun qrStudyImportFixture(application: CollectorApplication): QrStudyImportFixture {
    val envelope = requireNotNull(DemoStudy.load)(application.resources)
    val configuration = StudyConfigurationCodec.decode(
        SignedConfigurationCodec.decode(envelope).configurationBytes,
    )
    val join = JoinLink(
        artifactUrl = URI("https://studies.example.invalid/qr-study.partcfg"),
        artifactSha256 = envelope.qrArtifactSha256(),
        signerFingerprint = configuration.signer.fingerprint.replace(" ", ""),
    )
    return QrStudyImportFixture(envelope, JoinLink.parse(join.encode()))
}

internal fun ByteArray.qrArtifactSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
