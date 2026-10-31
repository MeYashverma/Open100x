package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.zoomhundred.model.GridMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Composition guides plus the aspect-ratio framing mask.
 *
 * The mask dims everything the sensor will crop away, so a 16:9 or 1:1 choice is visible
 * before the shutter is pressed instead of being a surprise in the gallery.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var gridMode: GridMode = GridMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** left, top, right, bottom in 0..1 of the preview. Null = full frame. */
    var aspectGuide: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    var showCenterCross: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val strongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(7f, 6f), 0f)
    }
    private val maskPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val guideBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val spiralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 205, 187, 255)
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    private val spiralPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        aspectGuide?.let { guide ->
            if (guide.size == 4) {
                val left = guide[0] * w
                val top = guide[1] * h
                val right = guide[2] * w
                val bottom = guide[3] * h
                canvas.drawRect(0f, 0f, w, top, maskPaint)
                canvas.drawRect(0f, bottom, w, h, maskPaint)
                canvas.drawRect(0f, top, left, bottom, maskPaint)
                canvas.drawRect(right, top, w, bottom, maskPaint)
                canvas.drawRect(left, top, right, bottom, guideBorderPaint)
            }
        }

        when (gridMode) {
            GridMode.NONE -> Unit
            GridMode.THIRDS -> drawThirds(canvas, w, h)
            GridMode.GOLDEN -> drawGolden(canvas, w, h)
            GridMode.SPIRAL -> {
                drawGolden(canvas, w, h)
                drawSpiral(canvas, w, h)
            }
            GridMode.CROSS -> drawCross(canvas, w, h)
            GridMode.DIAGONAL -> drawDiagonal(canvas, w, h)
            GridMode.SQUARE -> drawSquare(canvas, w, h)
        }

        if (showCenterCross) drawCross(canvas, w, h)
    }

    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(w / 3, 0f, w / 3, h, solidPaint)
        canvas.drawLine(w * 2 / 3, 0f, w * 2 / 3, h, solidPaint)
        canvas.drawLine(0f, h / 3, w, h / 3, solidPaint)
        canvas.drawLine(0f, h * 2 / 3, w, h * 2 / 3, solidPaint)
    }

    private fun drawGolden(canvas: Canvas, w: Float, h: Float) {
        val phi = 0.618034f
        canvas.drawLine(w * phi, 0f, w * phi, h, solidPaint)
        canvas.drawLine(w * (1f - phi), 0f, w * (1f - phi), h, solidPaint)
        canvas.drawLine(0f, h * phi, w, h * phi, solidPaint)
        canvas.drawLine(0f, h * (1f - phi), w, h * (1f - phi), solidPaint)
    }

    private fun drawSpiral(canvas: Canvas, w: Float, h: Float) {
        // Logarithmic golden spiral: r = r0 * e^(b·θ), θ across ~1.6 turns.
        spiralPath.reset()
        val centerX = w * 0.618f
        val centerY = h * 0.382f
        val maxRadius = minOf(w, h) * 0.62f
        val b = 0.3063489f
        val totalAngle = 3.2f * Math.PI.toFloat()
        val r0 = maxRadius / kotlin.math.exp(b * totalAngle)
        val steps = 160
        for (i in 0..steps) {
            val theta = i.toFloat() / steps * totalAngle
            val r = r0 * kotlin.math.exp(b * theta)
            val x = centerX + r * cos(theta) * 1.4f
            val y = centerY + r * sin(theta) * 1.4f
            if (i == 0) spiralPath.moveTo(x, y) else spiralPath.lineTo(x, y)
        }
        canvas.drawPath(spiralPath, spiralPaint)

    }

    private fun drawCross(canvas: Canvas, w: Float, h: Float) {
        val arm = 16f * density
        canvas.drawLine(w / 2 - arm, h / 2, w / 2 + arm, h / 2, strongPaint)
        canvas.drawLine(w / 2, h / 2 - arm, w / 2, h / 2 + arm, strongPaint)
    }

    private fun drawDiagonal(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(0f, 0f, w, h, solidPaint)
        canvas.drawLine(w, 0f, 0f, h, solidPaint)
        canvas.drawLine(0f, h / 2, w, h / 2, dashedPaint)
        canvas.drawLine(w / 2, 0f, w / 2, h, dashedPaint)
    }

    private fun drawSquare(canvas: Canvas, w: Float, h: Float) {
        val size = minOf(w, h)
        val left = (w - size) / 2
        val top = (h - size) / 2
        canvas.drawRect(left, top, left + size, top + size, dashedPaint)
        drawThirds(canvas, w, h)
    }
}
