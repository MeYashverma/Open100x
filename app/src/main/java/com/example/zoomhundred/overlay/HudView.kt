package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Technical camera HUD (optional).
 *
 * Shows only values that are actually known: focal length, total zoom, native/digital
 * split, ISO, shutter, EV, white balance, focus mode, frame rate, resolution and
 * stabilization. Exposure warnings are driven by the live histogram, so they are real.
 */
data class HudState(
    val focalLengthMm: Float = 0f,
    val focalLength35mm: Float = 0f,
    val zoom: Float = 1f,
    val nativeZoom: Float = 1f,
    val iso: String = "AUTO",
    val shutter: String = "AUTO",
    val ev: String = "0.0",
    val whiteBalance: String = "AWB",
    val focus: String = "AF",
    val fps: Int = 30,
    val resolution: String = "",
    val stabilization: String = "",
    val hint: String? = null,
    val clipWarning: Boolean = false
)

class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: HudState = HudState()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 162, 168, 176)
        textSize = 9.5f * density
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.06f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 243, 244, 246)
        textSize = 11f * density
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2B457")
        textSize = 10f * density
        typeface = Typeface.MONOSPACE
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CDBBFF")
        textSize = 10f * density
        typeface = Typeface.MONOSPACE
    }
    private val divider = Paint().apply { color = Color.argb(26, 255, 255, 255) }

    private val rows: List<Pair<String, () -> String>> = listOf(
        "ZOOM" to { formatZoom(state.zoom) },
        "NAT/DIG" to { formatSplit(state.nativeZoom, state.zoom) },
        "FOCAL" to { if (state.focalLength35mm > 0f) "${state.focalLength35mm.toInt()}mm eq" else "—" },
        "ISO" to { state.iso },
        "SHUTTER" to { state.shutter },
        "EV" to { state.ev },
        "WB" to { state.whiteBalance },
        "FOCUS" to { state.focus },
        "VIDEO" to { if (state.resolution.isNotBlank()) "${state.resolution} ${state.fps}fps" else "—" },
        "STAB" to { state.stabilization.ifBlank { "OFF" } }
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (150 * density).toInt()
        val rowHeight = 15 * density
        val desiredHeight = (rows.size * rowHeight + 14 * density + if (state.hint != null) 14 * density else 0f).toInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val rowHeight = 15 * density
        var y = rowHeight - 4 * density
        rows.forEachIndexed { index, row ->
            canvas.drawText(row.first, 0f, y, labelPaint)
            val value = row.second()
            if (row.first == "ZOOM") {
                canvas.drawText(value, w, y, accentPaint)
            } else {
                canvas.drawText(value, w, y, valuePaint)
            }
            if (index < rows.size - 1) {
                canvas.drawRect(0f, y + 4 * density, w, y + 4.2f * density, divider)
            }
            y += rowHeight
        }
        state.hint?.let { hint ->
            canvas.drawText(hint, 0f, y + 2 * density, if (state.clipWarning) warnPaint else accentPaint)
        }
        if (state.clipWarning) {
            canvas.drawText("CLIP", w, height.toFloat() - 2 * density, warnPaint)
        }
    }

    private fun formatZoom(zoom: Float): String = when {
        zoom < 10f -> String.format(java.util.Locale.US, "%.1f×", zoom)
        zoom < 100f -> String.format(java.util.Locale.US, "%.1f×", zoom)
        else -> String.format(java.util.Locale.US, "%.0f×", zoom)
    }

    private fun formatSplit(native: Float, total: Float): String {
        val digital = (total / native.coerceAtLeast(1f))
        return if (digital > 1.02f) {
            String.format(java.util.Locale.US, "%.1f/%.1f×", native, digital)
        } else {
            String.format(java.util.Locale.US, "%.1f/—", native)
        }
    }
}
