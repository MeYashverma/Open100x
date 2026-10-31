package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The photo-sphere aiming grid, in the spirit of a G-cam sphere capture.
 *
 * A fixed set of world directions is drawn at the screen position they occupy *given the
 * phone's current attitude*, so the dots behave like markers painted on the scene: sweep the
 * phone and they travel across the screen. Hollow dots are still missing, filled dots are
 * already captured. Align an empty dot with the centre reticle and the capture loop fires.
 */
class SphereOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val rotation = FloatArray(9).also {
        it[0] = 1f; it[4] = 1f; it[8] = 1f
    }
    private var hasRotation = false
    private var cells: Array<FloatArray> = emptyArray()
    private var covered: BooleanArray = BooleanArray(0)

    var hFovDegrees: Float = 66f
        set(value) {
            field = value
            invalidate()
        }

    private val dotRadius = 4.5f * density
    private val coveredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 235
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.WHITE
        alpha = 170
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 90
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.WHITE
        alpha = 210
    }

    fun setGrid(worldCells: Array<FloatArray>, coverage: BooleanArray) {
        cells = worldCells
        covered = coverage
        invalidate()
    }

    fun setOrientation(matrix: FloatArray) {
        if (matrix.size < 9) return
        System.arraycopy(matrix, 0, rotation, 0, 9)
        hasRotation = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cells.isEmpty()) {
            drawReticle(canvas, cx, cy)
            return
        }
        if (!hasRotation) {
            // No gyro: show the grid in a plain ring so the capture is still understandable.
            drawFlatFallback(canvas, cx, cy)
            drawReticle(canvas, cx, cy)
            return
        }

        val tanH = tan(Math.toRadians((hFovDegrees.coerceIn(25f, 120f) / 2f).toDouble())).toFloat()
        val focal = cx / tanH
        val margin = min(width, height) * 0.10f

        for (index in cells.indices) {
            val cell = cells[index]
            // device = Rᵀ · world
            val vx = rotation[0] * cell[0] + rotation[3] * cell[1] + rotation[6] * cell[2]
            val vy = rotation[1] * cell[0] + rotation[4] * cell[1] + rotation[7] * cell[2]
            val vz = rotation[2] * cell[0] + rotation[5] * cell[1] + rotation[8] * cell[2]

            var sx: Float
            var sy: Float
            var onScreen = true
            if (vz < -0.05f) {
                sx = cx + (vx / -vz) * focal
                sy = cy - (vy / -vz) * focal
            } else {
                // Behind the camera: park it on the edge as a direction hint.
                val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-4f)
                sx = cx + (vx / len) * (cx - margin)
                sy = cy - (vy / len) * (cy - margin)
                onScreen = false
            }
            sx = sx.coerceIn(margin, width - margin)
            sy = sy.coerceIn(margin + 8f * density, height - margin)
            val isCovered = index < covered.size && covered[index]
            when {
                isCovered -> canvas.drawCircle(sx, sy, dotRadius, coveredPaint)
                onScreen -> canvas.drawCircle(sx, sy, dotRadius, emptyPaint)
                else -> canvas.drawCircle(sx, sy, dotRadius * 0.7f, edgePaint)
            }
        }
        drawReticle(canvas, cx, cy)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        val radius = 11f * density
        canvas.drawCircle(cx, cy, radius, reticlePaint)
        val tick = 5f * density
        canvas.drawLine(cx, cy - radius - tick, cx, cy - radius, reticlePaint)
        canvas.drawLine(cx, cy + radius, cx, cy + radius + tick, reticlePaint)
        canvas.drawLine(cx - radius - tick, cy, cx - radius, cy, reticlePaint)
        canvas.drawLine(cx + radius, cy, cx + radius + tick, cy, reticlePaint)
    }

    private fun drawFlatFallback(canvas: Canvas, cx: Float, cy: Float) {
        val radius = min(cx, cy) * 0.55f
        for (index in cells.indices) {
            val angle = (index.toFloat() / cells.size) * Math.PI * 2
            val sx = cx + (Math.cos(angle) * radius).toFloat()
            val sy = cy + (Math.sin(angle) * radius).toFloat()
            val isCovered = index < covered.size && covered[index]
            if (isCovered) canvas.drawCircle(sx, sy, dotRadius, coveredPaint)
            else canvas.drawCircle(sx, sy, dotRadius, emptyPaint)
        }
    }
}
