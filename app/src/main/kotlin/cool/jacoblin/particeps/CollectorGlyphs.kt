package cool.jacoblin.particeps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icon vocabulary, drawn rather than pulled from an icon set.
 *
 * Each glyph is a few strokes on a unit square, scaled at draw time, which keeps them consistent
 * with the marks the setup rail and the access rows already draw and adds no dependency. They exist
 * so a row can say what it is without a label: the participant screens carry as little text as the
 * meaning survives, and an icon is what replaces the words that were removed.
 */
enum class Glyph {
    MOTION,
    LOCATION,
    CONNECTION,
    DATA_VOLUME,
    SCREEN,
    APP,
    KEYBOARD,
    PERSON,
    CONTACT,
    CLOCK,
    LANGUAGE,
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val line = s * 0.09f
        when (glyph) {
            Glyph.MOTION -> drawMotion(tint, s, line)
            Glyph.LOCATION -> drawLocation(tint, s, line)
            Glyph.CONNECTION -> drawConnection(tint, s, line)
            Glyph.DATA_VOLUME -> drawDataVolume(tint, s, line)
            Glyph.SCREEN -> drawScreen(tint, s, line)
            Glyph.APP -> drawApp(tint, s, line)
            Glyph.KEYBOARD -> drawKeyboard(tint, s, line)
            Glyph.PERSON -> drawPerson(tint, s, line)
            Glyph.CONTACT -> drawContact(tint, s, line)
            Glyph.CLOCK -> drawClock(tint, s, line)
            Glyph.LANGUAGE -> drawLanguage(tint, s, line)
        }
    }
}

private fun DrawScope.stroke(tint: Color, width: Float, build: Path.() -> Unit) {
    drawPath(
        Path().apply(build),
        color = tint,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * An oscillation: the sensor reports movement, not a movement.
 *
 * Quadratics rather than cubics, because a quadratic's extreme is exactly halfway to its control
 * point and so the amplitude is what the numbers say. The cubic this replaced put its controls at
 * the box edges and still only reached a third of the height, which left the one open glyph in the
 * set looking lighter than the six closed ones beside it.
 */
private fun DrawScope.drawMotion(tint: Color, s: Float, line: Float) = stroke(tint, line) {
    moveTo(s * 0.08f, s * 0.5f)
    quadraticTo(s * 0.29f, s * -0.10f, s * 0.5f, s * 0.5f)
    quadraticTo(s * 0.71f, s * 1.10f, s * 0.92f, s * 0.5f)
}

private fun DrawScope.drawLocation(tint: Color, s: Float, line: Float) {
    stroke(tint, line) {
        moveTo(s * 0.5f, s * 0.9f)
        cubicTo(s * 0.5f, s * 0.62f, s * 0.79f, s * 0.58f, s * 0.79f, s * 0.38f)
        // A teardrop closed by an arc, so the pin reads at 20 dp without any fill.
        arcTo(Rect(Offset(s * 0.21f, s * 0.09f), Size(s * 0.58f, s * 0.58f)), 0f, -180f, false)
        cubicTo(s * 0.21f, s * 0.58f, s * 0.5f, s * 0.62f, s * 0.5f, s * 0.9f)
    }
    drawCircle(tint, radius = s * 0.1f, center = Offset(s * 0.5f, s * 0.38f))
}

/**
 * Rising arcs over a point: reach, not a named network.
 *
 * All three arcs share the centre the dot sits on. Deriving each bounding box from that centre and
 * a radius is what keeps them concentric; insetting a square by unequal amounts, as this did
 * before, quietly moves the centre with every ring.
 */
private fun DrawScope.drawConnection(tint: Color, s: Float, line: Float) {
    val cx = s * 0.5f
    val cy = s * 0.80f
    listOf(0.20f, 0.345f, 0.49f).forEach { fraction ->
        val r = s * fraction
        drawArc(
            color = tint,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = line, cap = StrokeCap.Round),
        )
    }
    drawCircle(tint, radius = s * 0.075f, center = Offset(cx, cy))
}

/** Two arrows, one each way: this is a volume, not a destination. */
private fun DrawScope.drawDataVolume(tint: Color, s: Float, line: Float) = stroke(tint, line) {
    moveTo(s * 0.32f, s * 0.86f)
    lineTo(s * 0.32f, s * 0.16f)
    moveTo(s * 0.16f, s * 0.32f)
    lineTo(s * 0.32f, s * 0.16f)
    lineTo(s * 0.48f, s * 0.32f)
    moveTo(s * 0.68f, s * 0.16f)
    lineTo(s * 0.68f, s * 0.86f)
    moveTo(s * 0.52f, s * 0.7f)
    lineTo(s * 0.68f, s * 0.86f)
    lineTo(s * 0.84f, s * 0.7f)
}

private fun DrawScope.drawScreen(tint: Color, s: Float, line: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(s * 0.24f, s * 0.1f),
        size = Size(s * 0.52f, s * 0.8f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
        style = Stroke(width = line),
    )
    stroke(tint, line) {
        moveTo(s * 0.42f, s * 0.76f)
        lineTo(s * 0.58f, s * 0.76f)
    }
}

private fun DrawScope.drawApp(tint: Color, s: Float, line: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(s * 0.14f, s * 0.14f),
        size = Size(s * 0.72f, s * 0.72f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.2f),
        style = Stroke(width = line),
    )
    drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.5f, s * 0.5f))
}

/**
 * Keys and a space bar. The marks are filled rather than stroked and sized to survive 18 dp: at the
 * radius they had before they were a pixel across, and the glyph read as a subtitle icon.
 */
private fun DrawScope.drawKeyboard(tint: Color, s: Float, line: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(s * 0.08f, s * 0.24f),
        size = Size(s * 0.84f, s * 0.52f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.1f),
        style = Stroke(width = line),
    )
    listOf(0.26f, 0.5f, 0.74f).forEach { x ->
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * x - s * 0.07f, s * 0.355f),
            size = Size(s * 0.14f, s * 0.11f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.03f),
        )
    }
    drawRoundRect(
        color = tint,
        topLeft = Offset(s * 0.26f, s * 0.575f),
        size = Size(s * 0.48f, s * 0.1f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f),
    )
}

private fun DrawScope.drawPerson(tint: Color, s: Float, line: Float) {
    drawCircle(
        color = tint,
        radius = s * 0.17f,
        center = Offset(s * 0.5f, s * 0.31f),
        style = Stroke(width = line),
    )
    drawArc(
        color = tint,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(s * 0.18f, s * 0.55f),
        size = Size(s * 0.64f, s * 0.62f),
        style = Stroke(width = line, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawContact(tint: Color, s: Float, line: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(s * 0.1f, s * 0.22f),
        size = Size(s * 0.8f, s * 0.56f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
        style = Stroke(width = line),
    )
    stroke(tint, line) {
        moveTo(s * 0.14f, s * 0.28f)
        lineTo(s * 0.5f, s * 0.55f)
        lineTo(s * 0.86f, s * 0.28f)
    }
}

private fun DrawScope.drawClock(tint: Color, s: Float, line: Float) {
    drawCircle(
        color = tint,
        radius = s * 0.38f,
        center = Offset(s * 0.5f, s * 0.5f),
        style = Stroke(width = line),
    )
    stroke(tint, line) {
        moveTo(s * 0.5f, s * 0.28f)
        lineTo(s * 0.5f, s * 0.52f)
        lineTo(s * 0.68f, s * 0.62f)
    }
}

private fun DrawScope.drawLanguage(tint: Color, s: Float, line: Float) {
    drawCircle(
        color = tint,
        radius = s * 0.38f,
        center = Offset(s * 0.5f, s * 0.5f),
        style = Stroke(width = line),
    )
    stroke(tint, line) {
        moveTo(s * 0.12f, s * 0.5f)
        lineTo(s * 0.88f, s * 0.5f)
    }
    // The meridian: two arcs meeting at the poles read as a globe rather than a target.
    stroke(tint, line) {
        moveTo(s * 0.5f, s * 0.12f)
        cubicTo(s * 0.24f, s * 0.34f, s * 0.24f, s * 0.66f, s * 0.5f, s * 0.88f)
        cubicTo(s * 0.76f, s * 0.66f, s * 0.76f, s * 0.34f, s * 0.5f, s * 0.12f)
    }
}
