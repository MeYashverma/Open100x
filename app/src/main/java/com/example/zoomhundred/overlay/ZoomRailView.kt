package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.zoomhundred.camera.ZoomEngine
import com.example.zoomhundred.model.ZoomPreset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * The zoom rail: discrete, hardware-aware presets plus a continuous log-scale scrubber in
 * one control.
 *
 * * Tap a preset → animated jump (handled by the activity).
 * * Drag anywhere on the rail → smooth zoom with per-level sensitivity, snapped to the
 *   nearest preset when released (unless it is far away).
 * * Locked state disables input and dims the control — the 100× engine keeps its lock
 *   feature, it is just visible in the rail now.
 */
class ZoomRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var presets: List<ZoomPreset> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var minZoom: Float = 1f
    var maxZoom: Float = 100f

    var zoom: Float = 1f
        set(value) {
            field = value.coerceIn(minZoom, maxZoom)
            invalidate()
        }

    var locked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onPresetSelected: ((ZoomPreset) -> Unit)? = null
    var onZoomScrubbed: ((Float) -> Unit)? = null
    var onZoomCommit: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val presetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        letterSpacing = 0.02f
    }
    private val activePresetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CDBBFF") }
    private val onPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.parseColor("#131016")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CDBBFF")
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.MONOSPACE
        color = Color.argb(235, 243, 244, 246)
    }
    private val readoutBg = Paint().apply { color = Color.argb(190, 12, 13, 17) }
    private val pillRect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragZoom = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (46 * density).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    private fun trackLeft() = (16 * density)
    private fun trackRight() = width - 16 * density
    private fun trackY() = height - (7 * density)

    private fun zoomToFraction(z: Float): Float {
        val lo = ln(minZoom.coerceAtLeast(0.1f))
        val hi = ln(maxZoom.coerceAtLeast(minZoom + 0.1f))
        if (hi - lo <= 0.0001f) return 0f
        return ((ln(z.coerceAtLeast(0.1f)) - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    private fun fractionToZoom(f: Float): Float {
        val lo = ln(minZoom.coerceAtLeast(0.1f))
        val hi = ln(maxZoom.coerceAtLeast(minZoom + 0.1f))
        return kotlin.math.exp(lo + (hi - lo) * f.coerceIn(0f, 1f)).coerceIn(minZoom, maxZoom)
    }

    override fun onDraw(canvas: Canvas) {
        val left = trackLeft()
        val right = trackRight()
        val y = trackY()
        val progress = zoomToFraction(zoom)
        val dotX = left + (right - left) * progress

        trackPaint.alpha = if (locked) 60 else 90
        canvas.drawLine(left, y, right, y, trackPaint)
        if (!locked) canvas.drawLine(left, y, dotX, y, activeTrackPaint)

        // Preset labels, evenly distributed on the track.
        val slotWidth = (right - left) / (presets.size.coerceAtLeast(1) + 1)
        presets.forEachIndexed { index, preset ->
            val cx = left + slotWidth * (index + 1)
            if (preset.ratio > maxZoom + 0.01f) return@forEachIndexed
            val active = abs(preset.ratio - zoom) <= max(0.02f, preset.ratio * 0.02f)
            if (active) {
                val textW = activePresetPaint.measureText(preset.label) + 16 * density
                val cy = y - 9 * density
                pillRect.set(cx - textW / 2f, cy - 9 * density, cx + textW / 2f, cy + 9 * density)
                canvas.drawRoundRect(pillRect, 9 * density, 9 * density, pillPaint)
                canvas.drawText(preset.label, cx, cy + 4.2f * density, onPillText)
            } else {
                presetPaint.color = if (locked) Color.argb(90, 162, 168, 176) else Color.argb(215, 162, 168, 176)
                val cy = y - 9 * density
                canvas.drawText(preset.label, cx, cy + 4.2f * density, presetPaint)
                canvas.drawCircle(cx, y, 1.6f * density, presetPaint)
            }
        }

        if (dragging) {
            val label = ZoomEngine.formatReadout(zoom)
            val textW = readoutPaint.measureText(label) + 18 * density
            val cx = dotX.coerceIn(left + textW / 2f, right - textW / 2f)
            pillRect.set(cx - textW / 2f, 0f, cx + textW / 2f, 18 * density)
            canvas.drawRoundRect(pillRect, 8 * density, 8 * density, readoutBg)
            canvas.drawText(label, cx, 12.5f * density, readoutPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                dragZoom = zoom
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                }
                if (dragging) {
                    // Direct manipulation: horizontal position on the rail maps to zoom.
                    val fraction = ((event.x - trackLeft()) / (trackRight() - trackLeft()).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    val next = fractionToZoom(fraction)
                    if (abs(next - zoom) > 0.001f) {
                        zoom = next
                        dragZoom = next
                        onZoomScrubbed?.invoke(next)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val snapped = snapToNearest(zoom)
                    onZoomCommit?.invoke(snapped)
                } else {
                    val preset = presetAt(event.x, event.y)
                    if (preset != null) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onPresetSelected?.invoke(preset)
                    } else {
                        performClick()
                    }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToNearest(zoomValue: Float): Float {
        val nearest = presets.minByOrNull { abs(it.ratio - zoomValue) } ?: return zoomValue
        val threshold = nearest.ratio * 0.18f
        return if (abs(nearest.ratio - zoomValue) <= threshold) nearest.ratio else zoomValue
    }

    private fun presetAt(x: Float, y: Float): ZoomPreset? {
        if (y > trackY()) return null
        val left = trackLeft()
        val right = trackRight()
        val slotWidth = (right - left) / (presets.size.coerceAtLeast(1) + 1)
        var best: ZoomPreset? = null
        var bestDistance = Float.MAX_VALUE
        presets.forEachIndexed { index, preset ->
            val cx = left + slotWidth * (index + 1)
            val distance = abs(x - cx)
            val tolerance = min(slotWidth / 2f, 34 * density)
            if (distance <= tolerance && distance < bestDistance) {
                best = preset
                bestDistance = distance
            }
        }
        return best
    }

    private fun max(a: Float, b: Float): Float = if (a > b) a else b
}
