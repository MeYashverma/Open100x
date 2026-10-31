package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.zoomhundred.camera.LumaFrame
import kotlin.math.abs
import kotlin.math.max

/** Horizon level driven by the device's rotation vector / accelerometer. */
class LevelIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var rollDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var pitchDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = Color.argb(120, 243, 244, 246)
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = Color.argb(190, 243, 244, 246)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((180 * density).toInt(), widthMeasureSpec),
            resolveSize((28 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f + 3f * density
        val level = abs(rollDegrees) < 1.5f
        linePaint.color = if (level) Color.parseColor("#7FD48B") else Color.argb(215, 243, 244, 246)
        val half = w * 0.36f
        val rotationRad = Math.toRadians(rollDegrees.toDouble().coerceIn(-45.0, 45.0))
        val dx = (Math.cos(rotationRad) * half).toFloat()
        val dy = (Math.sin(rotationRad) * half).toFloat()
        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, linePaint)
        // centre mark
        canvas.drawLine(cx, cy - 4f * density, cx, cy + 4f * density, tickPaint)
        // fixed reference ticks
        val tickGap = half * 0.16f
        canvas.drawLine(cx - half - tickGap, cy, cx - half, cy, tickPaint)
        canvas.drawLine(cx + half, cy, cx + half + tickGap, cy, tickPaint)
        if (!level) {
            readoutPaint.color = Color.argb(190, 243, 244, 246)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f°", rollDegrees), cx, h - 2 * density, readoutPaint)
        } else {
            readoutPaint.color = Color.parseColor("#7FD48B")
            canvas.drawText("LEVEL", cx, h - 2 * density, readoutPaint)
        }
    }
}

/**
 * Focus peaking: highlights high-frequency edges so manual focus can be judged on a
 * phone screen. Uses the live analysis stream; no extra camera stream is opened.
 */
class FocusPeakingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lock = Any()
    private var mask: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val accent = Color.parseColor("#FF6E9BFF")
    var monitorEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    fun submitFrame(frame: LumaFrame) {
        val stepX = max(1, frame.width / 200)
        val stepY = max(1, frame.height / 140)
        val w = max(1, frame.width / stepX)
        val h = max(1, frame.height / stepY)
        if (w < 4 || h < 4) return
        val luma = IntArray(w * h)
        try {
            for (y in 0 until h) {
                val row = (y * stepY) * frame.rowStride
                for (x in 0 until w) {
                    luma[y * w + x] = frame.buffer.get(row + (x * stepX) * frame.pixelStride).toInt() and 0xFF
                }
            }
        } catch (_: Exception) {
            return
        }
        val pixels = IntArray(w * h)
        val threshold = 34
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1]) -
                    (luma[i - w - 1] + 2 * luma[i - 1] + luma[i + w - 1])
                val gy = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) -
                    (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1])
                val magnitude = (abs(gx) + abs(gy)) / 4
                pixels[i] = if (magnitude > threshold) accent else 0x00000000
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        synchronized(lock) {
            mask?.recycle()
            mask = bitmap
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(lock) {
            mask?.recycle()
            mask = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = synchronized(lock) { mask } ?: return
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), paint)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}

/**
 * Zebra stripes over clipped highlights. Threshold is fixed at the sensor's practical
 * white point and reported in the UI as "zebra 95%".
 */
class ZebraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lock = Any()
    private var mask: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val stripeColor = Color.argb(190, 242, 180, 87)
    var monitorEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    fun submitFrame(frame: LumaFrame, threshold: Int = 242) {
        val stepX = max(1, frame.width / 200)
        val stepY = max(1, frame.height / 140)
        val w = max(1, frame.width / stepX)
        val h = max(1, frame.height / stepY)
        if (w < 2 || h < 2) return
        val pixels = IntArray(w * h)
        try {
            for (y in 0 until h) {
                val row = (y * stepY) * frame.rowStride
                for (x in 0 until w) {
                    val luma = frame.buffer.get(row + (x * stepX) * frame.pixelStride).toInt() and 0xFF
                    pixels[y * w + x] = if (luma >= threshold) {
                        // 45° stripe pattern.
                        if (((x + y) / 2) % 3 == 0) stripeColor else 0x00000000
                    } else {
                        0x00000000
                    }
                }
            }
        } catch (_: Exception) {
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        synchronized(lock) {
            mask?.recycle()
            mask = bitmap
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(lock) {
            mask?.recycle()
            mask = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = synchronized(lock) { mask } ?: return
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), paint)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}

/**
 * IRE false-colour exposure map.
 *
 * Luma is bucketed into the usual broadcast bands so you can expose by eye:
 * crushed blacks, legal shadow, mid-grey (green), skin range (yellow/orange),
 * near-clip (red) and clipped (white). This is a *display* overlay only; it is never
 * burned into a photograph.
 */
class FalseColorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lock = Any()
    private var mask: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    var monitorEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    fun submitFrame(frame: LumaFrame) {
        val stepX = max(1, frame.width / 200)
        val stepY = max(1, frame.height / 140)
        val w = max(1, frame.width / stepX)
        val h = max(1, frame.height / stepY)
        if (w < 2 || h < 2) return
        val pixels = IntArray(w * h)
        try {
            for (y in 0 until h) {
                val row = (y * stepY) * frame.rowStride
                for (x in 0 until w) {
                    val luma = frame.buffer.get(row + (x * stepX) * frame.pixelStride).toInt() and 0xFF
                    pixels[y * w + x] = band(luma)
                }
            }
        } catch (_: Exception) {
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        synchronized(lock) {
            mask?.recycle()
            mask = bitmap
        }
        postInvalidateOnAnimation()
    }

    private fun band(luma: Int): Int = when {
        luma < 6 -> 0xFF3B0764.toInt()      // crushed black
        luma < 24 -> 0xFF1E3A8A.toInt()     // deep shadow
        luma < 58 -> 0xFF0E7490.toInt()     // shadow detail
        luma < 112 -> 0xFF15803D.toInt()    // mid grey
        luma < 168 -> 0xFFCA8A04.toInt()    // skin / highlight range
        luma < 214 -> 0xFFEA580C.toInt()    // hot
        luma < 244 -> 0xFFDC2626.toInt()    // near clip
        else -> 0xFFFFFFFF.toInt()          // clipped
    }

    fun clear() {
        synchronized(lock) {
            mask?.recycle()
            mask = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = synchronized(lock) { mask } ?: return
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), paint)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}
