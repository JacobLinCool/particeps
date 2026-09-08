package cool.jacoblin.particeps

import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cool.jacoblin.particeps.core.protocol.JoinLink
import java.nio.ByteBuffer

internal data class QrLuminanceFrame(val pixels: ByteArray, val width: Int, val height: Int)

/** Copies only the visible Y plane; row padding, pixel stride, and buffer position are not pixels. */
internal fun copyQrLuminance(
    buffer: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    rotationDegrees: Int,
): QrLuminanceFrame {
    require(imageWidth > 0 && imageHeight > 0 && rowStride > 0 && pixelStride > 0)
    require(left >= 0 && top >= 0 && width > 0 && height > 0)
    require(left.toLong() + width <= imageWidth && top.toLong() + height <= imageHeight)
    require(rowStride.toLong() >= (imageWidth - 1L) * pixelStride + 1)
    require(rotationDegrees in setOf(0, 90, 180, 270))
    val lastOffset = (top + height - 1L) * rowStride + (left + width - 1L) * pixelStride
    require(lastOffset < buffer.remaining() && width.toLong() * height <= Int.MAX_VALUE)
    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val outputWidth = if (rotated) height else width
    val outputHeight = if (rotated) width else height
    val pixels = ByteArray(outputWidth * outputHeight)
    val input = buffer.asReadOnlyBuffer()
    val start = input.position()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val outputIndex = when (rotationDegrees) {
                90 -> x * outputWidth + (height - 1 - y)
                180 -> (height - 1 - y) * outputWidth + (width - 1 - x)
                270 -> (width - 1 - x) * outputWidth + y
                else -> y * outputWidth + x
            }
            pixels[outputIndex] = input.get(start + (top + y) * rowStride + (left + x) * pixelStride)
        }
    }
    return QrLuminanceFrame(pixels, outputWidth, outputHeight)
}

/** One instance belongs to one analysis executor. Decoding does not interpret or open QR contents. */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()

    fun decode(frame: QrLuminanceFrame): String? = try {
        val source = PlanarYUVLuminanceSource(
            frame.pixels, frame.width, frame.height, 0, 0, frame.width, frame.height, false,
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

internal fun canonicalStudyQr(text: String): String? = try {
    JoinLink.parse(text).encode()
} catch (_: IllegalArgumentException) {
    null
}
