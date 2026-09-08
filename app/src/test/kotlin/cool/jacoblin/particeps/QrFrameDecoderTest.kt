package cool.jacoblin.particeps

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import cool.jacoblin.particeps.core.protocol.JoinLink
import java.net.URI
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QrFrameDecoderTest {
    private val joinLink = JoinLink(
        URI("https://research.example.edu/studies/config.partcfg"),
        "a".repeat(64),
        "B".repeat(32),
    ).encode()

    @Test
    fun decodesGeneratedStudyQrAtEveryQuarterTurnWithPlanePaddingAndCrop() {
        val qr = QRCodeWriter().encode(joinLink, BarcodeFormat.QR_CODE, 320, 320)
        val left = 11
        val top = 9
        val imageWidth = qr.width + left + 7
        val imageHeight = qr.height + top + 5
        for (pixelStride in listOf(1, 2)) {
            val rowStride = imageWidth * pixelStride + 13
            val prefix = 7
            val bytes = ByteArray(prefix + rowStride * imageHeight) { 0xA5.toByte() }
            for (y in 0 until qr.height) {
                for (x in 0 until qr.width) {
                    bytes[prefix + (top + y) * rowStride + (left + x) * pixelStride] =
                        if (qr[x, y]) 0 else 0xff.toByte()
                }
            }
            val input = ByteBuffer.wrap(bytes).apply { position(prefix) }
            for (rotation in listOf(0, 90, 180, 270)) {
                val frame = copyQrLuminance(
                    input, imageWidth, imageHeight, rowStride, pixelStride,
                    left, top, qr.width, qr.height, rotation,
                )
                assertEquals("stride=$pixelStride rotation=$rotation", joinLink, QrFrameDecoder().decode(frame))
                assertEquals(prefix, input.position())
            }
        }
    }

    @Test
    fun rotationPreservesRectangularCropCoordinates() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 4, 5, 6))
        val frames = listOf(0, 90, 180, 270).map { rotation ->
            copyQrLuminance(buffer, 3, 2, 4, 1, 0, 0, 3, 2, rotation)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frames[0].pixels)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), frames[1].pixels)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), frames[2].pixels)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), frames[3].pixels)
        assertEquals(2, frames[1].width)
        assertEquals(3, frames[1].height)
    }

    @Test
    fun blankAndUnreadableFramesHaveNoPayload() {
        val decoder = QrFrameDecoder()
        assertNull(decoder.decode(QrLuminanceFrame(ByteArray(256 * 256) { 0xff.toByte() }, 256, 256)))
        assertNull(decoder.decode(QrLuminanceFrame(ByteArray(256 * 256), 256, 256)))
    }

    @Test
    fun rejectsTruncatedPlaneAndInvalidCropBeforeReadingPixels() {
        val buffer = ByteBuffer.wrap(ByteArray(8))
        assertThrows(IllegalArgumentException::class.java) {
            copyQrLuminance(buffer, 3, 3, 3, 1, 0, 0, 3, 3, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyQrLuminance(buffer, 2, 2, 2, 1, 1, 0, 2, 2, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyQrLuminance(buffer, 2, 2, 2, 1, 0, 0, 2, 2, 45)
        }
    }

    @Test
    fun onlyCanonicalResearchJoinLinksCanLeaveScanner() {
        assertEquals(joinLink, canonicalStudyQr(joinLink))
        assertNull(canonicalStudyQr("https://research.example.edu/"))
        assertNull(canonicalStudyQr("particeps://join/v1?artifact=https://research.example.edu/config.partcfg"))
        assertNull(canonicalStudyQr("$joinLink&extra=value"))
        assertNull(canonicalStudyQr(joinLink.replace("%3A", "%3a")))
    }

    @Test
    fun ordinaryQrIsDecodedButNeverAcceptedAsStudy() {
        val qr = QRCodeWriter().encode("https://example.com/", BarcodeFormat.QR_CODE, 160, 160)
        val pixels = ByteArray(qr.width * qr.height) { index ->
            if (qr[index % qr.width, index / qr.width]) 0 else 0xff.toByte()
        }
        val decoded = QrFrameDecoder().decode(QrLuminanceFrame(pixels, qr.width, qr.height))
        assertEquals("https://example.com/", decoded)
        assertNull(canonicalStudyQr(requireNotNull(decoded)))
    }
}
