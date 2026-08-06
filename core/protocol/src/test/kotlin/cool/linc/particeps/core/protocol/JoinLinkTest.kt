package cool.linc.particeps.core.protocol

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JoinLinkTest {
    @Test
    fun canonicalJoinLinkRoundTripsAnOpaqueHttpsArtifact() {
        val link = JoinLink(
            URI("https://artifacts.example.invalid/join/dGhpcy1pcy1hLTEyOC1iaXQtdG9rZW4"),
            "0".repeat(64),
            "0123456789ABCDEFFEDCBA9876543210",
        )

        assertEquals(link, JoinLink.parse(link.encode()))
        assertEquals("0123 4567 89AB CDEF FEDC BA98 7654 3210", link.displayFingerprint())
    }

    @Test
    fun artifactUrlProfileRejectsValuesThatPlatformParsersWouldNormalizeDifferently() {
        listOf(
            "https://EXAMPLE.invalid:443/a/../config.partcfg",
            "https://artifacts.example.invalid/config.partcfg?download=1",
            "https://artifacts.example.invalid/a//config.partcfg",
            "https://artifacts.example.invalid/a/%63onfig.partcfg",
            "https://127.0.0.1/config.partcfg",
        ).forEach { artifact ->
            assertThrows(artifact, IllegalArgumentException::class.java) {
                JoinLink(URI(artifact), "0".repeat(64), "A".repeat(32))
            }
        }
    }

    @Test
    fun parserRejectsMutableOrAmbiguousJoinLinks() {
        val valid = JoinLink(
            URI("https://artifacts.example.invalid/config.partcfg"),
            "0".repeat(64),
            "A".repeat(32),
        ).encode()
        val encodedArtifact = "https%3A%2F%2Fartifacts.example.invalid%2Fconfig.partcfg"

        listOf(
            valid.replace("particeps://", "https://"),
            valid.replace("artifact=", "unknown=x&artifact="),
            valid.replace("&sha256=", "&sha256=${"0".repeat(64)}&sha256="),
            valid.replace("https%3A", "http%3A"),
            valid.replace(
                encodedArtifact,
                "https%3A%2F%2Fuser%40artifacts.example.invalid%2Fconfig.partcfg",
            ),
            valid.replace(encodedArtifact, "$encodedArtifact%23mutable"),
            valid.replace("%2F", "%2f"),
            "$valid&extra=1",
        ).forEach { hostile ->
            assertThrows(hostile, IllegalArgumentException::class.java) { JoinLink.parse(hostile) }
        }
    }

    @Test
    fun encoderRejectsAnArtifactWhoseEscapedJoinLinkExceedsTheWireBound() {
        val link = JoinLink(
            URI("https://artifacts.example.invalid/${"a/".repeat(1_000)}config.partcfg"),
            "0".repeat(64),
            "A".repeat(32),
        )

        assertThrows(IllegalArgumentException::class.java) { link.encode() }
    }
}
