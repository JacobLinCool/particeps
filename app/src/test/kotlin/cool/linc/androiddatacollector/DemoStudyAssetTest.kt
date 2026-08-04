package cool.linc.androiddatacollector

import cool.linc.androiddatacollector.core.protocol.ConfigurationVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DemoStudyAssetTest {
    @Test
    fun debugDemoIsAValidCurrentProtocolV1Artifact() {
        val projectDirectory = Path.of(requireNotNull(System.getProperty("adc.appProjectDir")))
        val encoded = Files.readString(
            projectDirectory.resolve("src/debug/res/raw/demo_study_envelope.txt"),
        )
        val verified = ConfigurationVerifier(
            trustedSigningKeys = emptyMap(),
            clientVersion = 1,
            now = { Instant.parse("2026-08-04T00:00:00Z") },
        ).verify(Base64.getDecoder().decode(encoded.trim()))

        assertEquals("modular-sensing-demo", verified.configuration.experimentId)
        assertEquals("demo-config-2026", verified.configuration.configurationId)
        assertFalse(verified.signerAnchored)
    }
}
