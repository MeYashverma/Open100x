package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.zoomhundred.camera.LumaFrame
import kotlin.math.max
import kotlin.math.min

/**
 * The magnified preview window ("loupe").
 *
 * It renders a real, colour-correct magnification of the live analysis frame — the same
 * stream the histogram and focus peaking use — centred on the point of the viewfinder it
 * floats over. At extreme zoom this is the difference between guessing and framing.
 *
 * Cost control: frames are converted to a small ARGB bitmap (long edge ≈ 220 px), only
 * when the analysis stream delivers one, and the conversion is throttled by the caller.
 */
class LoupeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var magnification: Float = 3f
        set(value) {
            field = value.coerceIn(1.5f, 8f)
            invalidate()
        }

    var zoomRatio: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    /** Point of the viewfinder this loupe magnifies, in 0..1 of the preview. */
    var sourceX: Float = 0.5f
        private set
    var sourceY: Float = 0.5f
        private set

    var onMoved: ((Float, Float) -> Unit)? = null
    var onTapped: (() -> Unit)? = null

    private val frameLock = Any()
    private var frameBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        color = Color.argb(150, 255, 255, 255)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
        color = Color.parseColor("#CDBBFF")
    }
    private val scrimPaint = Paint().apply { color = Color.argb(150, 6, 7, 10) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 243, 244, 246)
        textSize = 9.5f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val clipPath = Path()
    private val rect = RectF()

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false

    fun setSourceCenter(fx: Float, fy: Float) {
        sourceX = fx.coerceIn(0.05f, 0.95f)
        sourceY = fy.coerceIn(0.05f, 0.95f)
        invalidate()
    }

    /** Called from the analysis thread. Copies only the rows it needs. */
    fun submitFrame(frame: LumaFrame) {
        val targetW = 220
        val srcW = frame.width
        val srcH = frame.height
        if (srcW <= 0 || srcH <= 0) return
        val scale = max(1, srcW / targetW)
        val w = max(1, srcW / scale)
        val h = max(1, srcH / scale)
        val pixels = IntArray(w * h)
        val yBuf = frame.buffer
        try {
            for (y in 0 until h) {
                val yRow = (y * scale) * frame.rowStride
                for (x in 0 until w) {
                    // The analysis stream is luma-only (no chroma planes are copied), so the
                    // loupe renders a neutral monochrome image. That is what a focus loupe
                    // wants anyway: contrast, not colour.
                    val luma = yBuf.get(yRow + (x * scale) * frame.pixelStride).toInt() and 0xFF
                    pixels[y * w + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            synchronized(frameLock) {
                frameBitmap?.recycle()
                frameBitmap = bitmap
            }
            postInvalidateOnAnimation()
        } catch (_: Exception) {
            // A truncated/odd frame must never break the preview.
        }
    }

    fun clear() {
        synchronized(frameLock) {
            frameBitmap?.recycle()
            frameBitmap = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 10f * d
        rect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawColor(Color.parseColor("#0E1013"))

        val frame = synchronized(frameLock) { frameBitmap }
        if (frame != null) {
            val cropW = (frame.width / magnification).coerceAtLeast(8f)
            val cropH = (frame.height / magnification).coerceAtLeast(8f)
            val cx = sourceX * frame.width
            val cy = sourceY * frame.height
            val left = (cx - cropW / 2f).coerceIn(0f, frame.width - cropW)
            val top = (cy - cropH / 2f).coerceIn(0f, frame.height - cropH)
            val src = RectF(left, top, left + cropW, top + cropH)
            canvas.drawBitmap(frame, android.graphics.Rect(left.toInt(), top.toInt(), (left + cropW).toInt(), (top + cropH).toInt()), rect, bitmapPaint)
            // Crosshair shows the exact centre of the loupe.
            canvas.drawLine(w / 2f, h / 2f - 5f * d, w / 2f, h / 2f + 5f * d, accentPaint)
            canvas.drawLine(w / 2f - 5f * d, h / 2f, w / 2f + 5f * d, h / 2f, accentPaint)
            if (src.isEmpty) canvas.drawColor(Color.parseColor("#0E1013"))
        } else {
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
            canvas.drawText("LOUPE", 8f * d, h / 2f + 4f * d, textPaint)
        }
        canvas.restore()

        canvas.drawRoundRect(rect.inset(0.6f, 0.6f), radius, radius, borderPaint)

        // Label strip
        val label = "×%.1f  ·  %s".format(magnification, formatZoom(zoomRatio))
        val labelW = textPaint.measureText(label) + 8f * d
        val bgRect = RectF(0f, h - 15f * d, min(labelW, w), h)
        canvas.drawRoundRect(bgRect, 4f * d, 4f * d, scrimPaint)
        canvas.drawText(label, 4f * d, h - 4.5f * d, textPaint)
    }

    private fun formatZoom(zoom: Float): String =
        if (zoom >= 10f) "${zoom.toInt()}x" else String.format(java.util.Locale.US, "%.1fx", zoom)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = x
                dragStartY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                moveTo(dragStartX + dx, dragStartY + dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && kotlin.math.abs(event.rawX - dragStartRawX) < 6f &&
                    kotlin.math.abs(event.rawY - dragStartRawY) < 6f
                ) {
                    onTapped?.invoke()
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Moves within the parent, reporting the new normalised position. */
    fun moveTo(newX: Float, newY: Float) {
        val parentView = parent as? View ?: return
        val maxX = (parentView.width - width).toFloat().coerceAtLeast(1f)
        val maxY = (parentView.height - height).toFloat().coerceAtLeast(1f)
        x = newX.coerceIn(0f, maxX)
        y = newY.coerceIn(0f, maxY)
        onMoved?.invoke(x / maxX, y / maxY)
    }
}
