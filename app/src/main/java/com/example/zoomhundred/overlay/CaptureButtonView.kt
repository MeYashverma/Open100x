package com.example.zoomhundred.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator

/**
 * The shutter / record button.
 *
 * States are driven by real capture state: idle, recording (red square), paused, and busy
 * (progress ring while a computational capture is running). Long-press is forwarded so the
 * activity can keep the "hold for settled 100× capture" gesture it always had.
 */
class CaptureButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { PHOTO, VIDEO, RECORDING, PAUSED, BUSY }

    var mode: Mode = Mode.PHOTO
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(220, 243, 244, 246)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F3F4F6") }
    private val recordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4D4F") }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#CDBBFF")
    }
    private val progressTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = Color.argb(50, 255, 255, 255)
    }
    private val arcRect = RectF()

    private var pressedScale = 1f
    private var pressAnimator: ValueAnimator? = null
    private var downTime = 0L
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (isPressed) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (78 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    fun setProgressActive(active: Boolean, value: Float = 0f) {
        progress = value
        if (active) mode = Mode.BUSY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 3f * density
        val scaled = radius * pressedScale

        canvas.drawCircle(cx, cy, scaled, ringPaint)

        when (mode) {
            Mode.PHOTO, Mode.BUSY -> {
                canvas.drawCircle(cx, cy, scaled * 0.78f, innerPaint)
                if (mode == Mode.BUSY) {
                    arcRect.set(cx - scaled, cy - scaled, cx + scaled, cy + scaled)
                    canvas.drawArc(arcRect, -90f, 360f, false, progressTrack)
                    canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
                }
            }
            Mode.VIDEO -> {
                canvas.drawCircle(cx, cy, scaled * 0.78f, recordPaint)
            }
            Mode.RECORDING -> {
                // Record: white ring, red rounded square.
                canvas.drawRoundRect(
                    cx - scaled * 0.42f, cy - scaled * 0.42f,
                    cx + scaled * 0.42f, cy + scaled * 0.42f,
                    8f * density, 8f * density, recordPaint
                )
            }
            Mode.PAUSED -> {
                val barW = scaled * 0.22f
                val barH = scaled * 0.62f
                canvas.drawRoundRect(cx - barW * 1.6f, cy - barH / 2, cx - barW * 0.6f, cy + barH / 2, 3f * density, 3f * density, innerPaint)
                canvas.drawRoundRect(cx + barW * 0.6f, cy - barH / 2, cx + barW * 1.6f, cy + barH / 2, 3f * density, 3f * density, innerPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                downTime = System.currentTimeMillis()
                longPressFired = false
                animateScale(0.92f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - width / 2f) > slop * 4 || kotlin.math.abs(event.y - height / 2f) > slop * 4) {
                    removeCallbacks(longPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                removeCallbacks(longPressRunnable)
                animateScale(1f)
                if (!longPressFired && System.currentTimeMillis() - downTime < longPressTimeout + 200) {
                    onTap?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                removeCallbacks(longPressRunnable)
                animateScale(1f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateScale(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressedScale, target).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressedScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pressAnimator?.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}
