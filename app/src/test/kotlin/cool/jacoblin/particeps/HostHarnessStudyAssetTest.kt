package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HostHarnessStudyAssetTest {
    @Test
    fun everyHostStudyHasAValidProductionSignatureAndDistinctConfiguration() {
        val assets = Path.of(requireNotNull(System.getProperty("particeps.appProjectDir")))
            .resolve("src/androidTest/assets")
        val expected = mapOf(
            "host_harness_study_envelope.txt" to "android-host-vpn-fixture-2026",
            "host_fixed_64_study_envelope.txt" to "android-host-vpn-fixed-64-2026",
            "host_fixed_512_study_envelope.txt" to "android-host-vpn-fixed-512-2026",
            "host_fixed_4096_study_envelope.txt" to "android-host-vpn-fixed-4096-2026",
        )
        val verifier = ConfigurationVerifier(
            trustedSigningKeys = emptyMap(),
            clientVersion = 1,
            now = { Instant.parse("2026-10-05T00:00:00Z") },
        )
        expected.forEach { (file, configurationId) ->
            val verified = verifier.verify(Base64.getDecoder().decode(Files.readString(assets.resolve(file)).trim()))
            assertEquals(configurationId, verified.configuration.configurationId)
            assertEquals("android-host-vpn-fixture", verified.configuration.experimentId)
            assertFalse(verified.signerAnchored)
        }
    }
}
