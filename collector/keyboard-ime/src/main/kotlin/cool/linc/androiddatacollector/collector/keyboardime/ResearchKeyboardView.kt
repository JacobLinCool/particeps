package cool.linc.androiddatacollector.collector.keyboardime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import kotlin.math.max

internal enum class KeyCategory { LETTER, SPACE, BACKSPACE, ENTER }

internal data class KeyboardKey(
    val text: String,
    val category: KeyCategory,
    val bounds: RectF = RectF(),
)

internal class ResearchKeyboardView(
    context: Context,
) : View(context) {
    var collectionAllowed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var onKeyCommitted: (KeyboardKey) -> Unit = {}

    private val keys = listOf(
        "QWERTYUIOP".map { KeyboardKey(it.toString().lowercase(), KeyCategory.LETTER) },
        "ASDFGHJKL".map { KeyboardKey(it.toString().lowercase(), KeyCategory.LETTER) },
        "ZXCVBNM".map { KeyboardKey(it.toString().lowercase(), KeyCategory.LETTER) },
        listOf(
            KeyboardKey("⌫", KeyCategory.BACKSPACE),
            KeyboardKey("space", KeyCategory.SPACE),
            KeyboardKey("↵", KeyCategory.ENTER),
        ),
    ).flatten()
    private val rows = listOf(
        keys.subList(0, 10),
        keys.subList(10, 19),
        keys.subList(19, 26),
        keys.subList(26, 29),
    )
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(238, 242, 247) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 37, 51)
        textAlign = Paint.Align.CENTER
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 80, 96)
        textAlign = Paint.Align.CENTER
    }
    private var activeKey: KeyboardKey? = null

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (resources.displayMetrics.density * DESIRED_HEIGHT_DP).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val density = resources.displayMetrics.density
        val statusHeight = STATUS_HEIGHT_DP * density
        val gap = KEY_GAP_DP * density
        val rowHeight = (height - statusHeight - gap * (rows.size + 1)) / rows.size
        rows.forEachIndexed { rowIndex, row ->
            val unitCount = if (rowIndex == rows.lastIndex) BOTTOM_ROW_UNITS else row.size.toFloat()
            val unitWidth = (width - gap * (unitCount + 1)) / unitCount
            var left = gap
            row.forEach { key ->
                val units = when (key.category) {
                    KeyCategory.SPACE -> SPACE_KEY_UNITS
                    KeyCategory.BACKSPACE, KeyCategory.ENTER -> SIDE_KEY_UNITS
                    KeyCategory.LETTER -> 1f
                }
                val keyWidth = unitWidth * units + gap * (units - 1f)
                val top = statusHeight + gap + rowIndex * (rowHeight + gap)
                key.bounds.set(left, top, left + keyWidth, top + rowHeight)
                left += keyWidth + gap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(211, 219, 228))
        val density = resources.displayMetrics.density
        statusPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            STATUS_TEXT_SP,
            resources.displayMetrics,
        )
        canvas.drawText(
            if (collectionAllowed) "Research touch capture active" else "Touch capture disabled for this field",
            width / 2f,
            STATUS_BASELINE_DP * density,
            statusPaint,
        )
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            KEY_TEXT_SP,
            resources.displayMetrics,
        )
        keys.forEach { key ->
            canvas.drawRoundRect(key.bounds, CORNER_DP * density, CORNER_DP * density, keyPaint)
            val baseline = key.bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(key.text, key.bounds.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerIndex = max(0, event.actionIndex.coerceAtMost(event.pointerCount - 1))
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> activeKey = keyAt(x, y)
            MotionEvent.ACTION_UP -> {
                val key = activeKey
                if (key != null && key === keyAt(x, y)) {
                    onKeyCommitted(key)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        activeKey?.let { key ->
            if (collectionAllowed) {
                ImeObservationBridge.publish(
                    event,
                    pointerIndex,
                    (x - key.bounds.left) / key.bounds.width(),
                    (y - key.bounds.top) / key.bounds.height(),
                    key.category.name,
                )
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeKey = null
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun keyAt(
        x: Float,
        y: Float,
    ): KeyboardKey? = keys.firstOrNull { it.bounds.contains(x, y) }

    private companion object {
        const val DESIRED_HEIGHT_DP = 280f
        const val STATUS_HEIGHT_DP = 32f
        const val STATUS_BASELINE_DP = 21f
        const val STATUS_TEXT_SP = 12f
        const val KEY_TEXT_SP = 18f
        const val KEY_GAP_DP = 4f
        const val CORNER_DP = 6f
        const val BOTTOM_ROW_UNITS = 8f
        const val SPACE_KEY_UNITS = 4f
        const val SIDE_KEY_UNITS = 2f
    }
}
