package com.example.zoomhundred.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.FaceDetector
import com.example.zoomhundred.model.FrameStyle
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Creative post-capture effects: decorative frames, software portrait bokeh and the
 * fisheye/barrel warp.
 *
 * All three are honest software transforms — they are labelled "Software" in the UI and
 * never claimed to be optics or sensor depth.
 */
object FrameRenderer {

    fun render(src: Bitmap, style: FrameStyle, caption: String): Bitmap {
        if (style == FrameStyle.NONE) return src
        return when (style) {
            FrameStyle.THIN_WHITE -> bordered(src, 0.022f, Color.WHITE, Color.parseColor("#22000000"), caption = null)
            FrameStyle.THIN_BLACK -> bordered(src, 0.022f, Color.parseColor("#0B0B0C"), Color.parseColor("#33FFFFFF"), caption = null)
            FrameStyle.WIDE_WHITE -> bordered(src, 0.075f, Color.WHITE, Color.parseColor("#1A000000"), caption)
            FrameStyle.FILM_35MM -> filmStrip(src, caption)
            FrameStyle.INSTANT -> instant(src, caption)
            FrameStyle.CINEMA -> cinema(src, caption)
            FrameStyle.DATE_STAMP -> dateStamp(src)
            FrameStyle.CAPTION -> captionBand(src, caption)
            FrameStyle.NONE -> src
        }
    }

    private fun bordered(
        src: Bitmap, fraction: Float, borderColor: Int, lineColor: Int, caption: String?
    ): Bitmap {
        val margin = max(6, (min(src.width, src.height) * fraction).toInt())
        val bottomExtra = if (caption != null) (margin * 2.4f).toInt() else margin
        val out = Bitmap.createBitmap(src.width + margin * 2, src.height + margin + bottomExtra, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(borderColor)
        canvas.drawBitmap(src, margin.toFloat(), margin.toFloat(), null)
        val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, margin * 0.03f)
            color = lineColor
        }
        canvas.drawRect(
            margin - hairline.strokeWidth, margin - hairline.strokeWidth,
            (margin + src.width + hairline.strokeWidth), (margin + src.height + hairline.strokeWidth),
            hairline
        )
        if (caption != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5A5A5C")
                textSize = max(11f, margin * 0.34f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                letterSpacing = 0.08f
            }
            canvas.drawText(
                caption,
                margin.toFloat(),
                (margin + src.height + bottomExtra * 0.62f),
                text
            )
        }
        return out
    }

    private fun filmStrip(src: Bitmap, caption: String): Bitmap {
        val holeSize = max(5, (src.height * 0.012f).toInt())
        val strip = max(holeSize * 4, (src.height * 0.055f).toInt())
        val out = Bitmap.createBitmap(src.width + strip * 2, src.height + strip * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#0A0A0B"))
        canvas.drawBitmap(src, strip.toFloat(), strip.toFloat(), null)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C1E") }
        val holeFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#3A3A3C")
        }
        val step = holeSize * 2
        var x = (strip * 0.5f).toInt()
        while (x < out.width - strip) {
            val rect = RectF(x.toFloat(), strip * 0.28f, (x + holeSize).toFloat(), strip * 0.72f)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, hole)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, holeFrame)
            val bottom = RectF(x.toFloat(), (out.height - strip * 0.72f), (x + holeSize).toFloat(), (out.height - strip * 0.28f))
            canvas.drawRoundRect(bottom, 1.5f, 1.5f, hole)
            canvas.drawRoundRect(bottom, 1.5f, 1.5f, holeFrame)
            x += step
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C98B3A")
            textSize = max(9f, strip * 0.34f)
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("OPEN 100x  ${caption.take(28)}", strip.toFloat(), (strip * 0.95f), label)
        return out
    }

    private fun instant(src: Bitmap, caption: String): Bitmap {
        val margin = (min(src.width, src.height) * 0.05f).toInt()
        val bottom = (min(src.width, src.height) * 0.16f).toInt()
        val out = Bitmap.createBitmap(src.width + margin * 2, src.height + margin + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#F4F2EC"))
        val inner = Paint()
        canvas.drawBitmap(src, margin.toFloat(), margin.toFloat(), inner)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A4A48")
            textSize = max(12f, bottom * 0.22f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        }
        canvas.drawText(caption.take(48), margin.toFloat(), (margin + src.height + bottom * 0.55f), text)
        return out
    }

    private fun cinema(src: Bitmap, caption: String): Bitmap {
        val targetRatio = 2.39f
        val barHeight = max(0, ((src.height - src.width / targetRatio) / 2f).toInt())
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, 0f, 0f, null)
        val bar = Paint().apply { color = Color.BLACK }
        canvas.drawRect(0f, 0f, src.width.toFloat(), barHeight.toFloat(), bar)
        canvas.drawRect(0f, (src.height - barHeight).toFloat(), src.width.toFloat(), src.height.toFloat(), bar)
        if (caption.isNotBlank() && barHeight > 20) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#886A6A6A")
                textSize = max(9f, barHeight * 0.16f)
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(caption.take(52), src.width / 2f, src.height - barHeight * 0.35f, text)
        }
        return out
    }

    private fun dateStamp(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5A623")
            textSize = max(14f, src.width * 0.028f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.argb(180, 0, 0, 0))
            textAlign = Paint.Align.RIGHT
        }
        val format = java.text.SimpleDateFormat("yyyy.MM.dd  HH:mm", java.util.Locale.US)
        canvas.drawText(
            format.format(java.util.Date()),
            src.width - src.width * 0.035f,
            src.height - src.height * 0.035f,
            stamp
        )
        return out
    }

    private fun captionBand(src: Bitmap, caption: String): Bitmap {
        val band = max(28, (src.height * 0.075f).toInt())
        val out = Bitmap.createBitmap(src.width, src.height + band, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1C")
            textSize = max(11f, band * 0.3f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText(caption.take(64), band * 0.35f, src.height + band * 0.62f, text)
        return out
    }
}

object PortraitRenderer {

    private const val MAX_EDGE = 3600

    /**
     * Software depth-of-field. Subject mask comes from framework face detection when it
     * finds faces, otherwise from a centred subject ellipse. Blur strength and feather
     * are user-controlled; edges are feathered to avoid cut-out halos.
     */
    fun render(src: Bitmap, strengthPercent: Int): Bitmap {
        val strength = strengthPercent.coerceIn(0, 100) / 100f
        if (strength <= 0.01f) return src.copy(Bitmap.Config.ARGB_8888, true)
        val base = BitmapOps.limitEdge(src, MAX_EDGE)
        val working = if (base === src) src.copy(Bitmap.Config.ARGB_8888, true) else base

        val radius = max(2, (min(working.width, working.height) * (0.004f + 0.022f * strength)).toInt())
        val blurred = BitmapOps.boxBlur(working, radius, passes = 2)

        val faces = detectFaces(working)
        val subject = if (faces.isEmpty()) {
            val cx = working.width * 0.5f
            val cy = working.height * 0.52f
            val halfW = working.width * 0.30f
            val halfH = working.height * 0.36f
            listOf(RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH))
        } else faces

        val w = working.width
        val h = working.height
        val sharp = IntArray(w * h)
        val soft = IntArray(w * h)
        working.getPixels(sharp, 0, w, 0, 0, w, h)
        blurred.getPixels(soft, 0, w, 0, 0, w, h)
        blurred.recycle()

        val out = IntArray(w * h)
        val feather = 0.55f + 0.35f * strength
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var best = 0f
                for (f in subject) {
                    val dx = (x - f.centerX()) / (f.width() * 1.15f)
                    val dy = (y - f.centerY()) / (f.height() * 1.25f)
                    val d = hypot(dx, dy)
                    val inside = (1f - (d - feather) / max(0.0001f, 1f - feather)).coerceIn(0f, 1f)
                    val m = inside * inside * (3f - 2f * inside) // smoothstep
                    if (m > best) best = m
                }
                val keep = (1f - strength * (1f - best)).coerceIn(0f, 1f)
                val s = sharp[row + x]
                val b = soft[row + x]
                val r = blend((s shr 16) and 0xFF, (b shr 16) and 0xFF, keep)
                val g = blend((s shr 8) and 0xFF, (b shr 8) and 0xFF, keep)
                val bl = blend(s and 0xFF, b and 0xFF, keep)
                out[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        if (working !== src) working.recycle()
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun blend(sharp: Int, soft: Int, keep: Float): Int =
        ((1f - keep) * soft + keep * sharp).toInt().coerceIn(0, 255)

    private fun detectFaces(src: Bitmap): List<RectF> {
        return try {
            val scale = min(1f, 640f / max(1, src.width))
            val small = Bitmap.createScaledBitmap(src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()), true)
            val rgb565 = small.copy(Bitmap.Config.RGB_565, false)
            val detector = FaceDetector(rgb565.width, rgb565.height, 4)
            val faces = arrayOfNulls<FaceDetector.Face>(4)
            val count = detector.findFaces(rgb565, faces)
            val result = ArrayList<RectF>(count)
            for (i in 0 until count) {
                val face = faces[i] ?: continue
                val mid = android.graphics.PointF()
                face.getMidPoint(mid)
                val eyes = face.eyesDistance()
                // Expand the face box into a plausible head-and-shoulders subject zone.
                val w = eyes * 2.6f / scale
                val h = eyes * 3.6f / scale
                val cx = mid.x / scale
                val cy = mid.y / scale + h * 0.35f
                result.add(RectF(cx - w, cy - h * 0.75f, cx + w, cy + h * 0.75f))
            }
            if (rgb565 !== small) rgb565.recycle()
            if (small !== src) small.recycle()
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object DistortionWarp {

    /**
     * Fisheye-style polar remap. [strength] 0..1 controls curvature; [circular] keeps the
     * classic round image with black corners.
     *
     * This is a lens simulation applied after capture, not optical fisheye.
     */
    fun fisheye(src: Bitmap, strengthPercent: Int, circular: Boolean): Bitmap {
        val strength = strengthPercent.coerceIn(0, 100) / 100f
        if (strength <= 0.01f) return src.copy(Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        // Corner radius keeps the whole frame; the shorter half-axis gives a round image.
        val maxR = if (circular) min(cx, cy) else hypot(cx, cy)
        val k = 0.35f + 0.95f * strength
        val tanK = tan(k)
        for (y in 0 until h) {
            val row = y * w
            val dv = (y - cy) / maxR
            for (x in 0 until w) {
                val du = (x - cx) / maxR
                val r = hypot(du, dv)
                if (r < 1e-6f) {
                    out[row + x] = pixels[(cy.toInt() * w) + cx.toInt()]
                    continue
                }
                // Fisheye: sample closer to the centre as r grows (tan curve).
                val mappedR = tan(r * k) / tanK
                val su = du / r * mappedR
                val sv = dv / r * mappedR
                val sx = ((su + 1f) * 0.5f * (w - 1))
                val sy = ((sv + 1f) * 0.5f * (h - 1))
                out[row + x] = if (sx < 0f || sy < 0f || sx > w - 1 || sy > h - 1) {
                    0xFF000000.toInt()
                } else {
                    bilinear(pixels, w, h, sx, sy)
                }
            }
        }
        src.recycle()
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun bilinear(pixels: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]
        fun channel(shift: Int): Int {
            val a = ((p00 shr shift) and 0xFF) * (1 - tx) + ((p10 shr shift) and 0xFF) * tx
            val b = ((p01 shr shift) and 0xFF) * (1 - tx) + ((p11 shr shift) and 0xFF) * tx
            return (a * (1 - ty) + b * ty).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * Corrects marginal barrel distortion (common on ultra-wide lenses) using the
     * standard Brown-Conrady k1 term. Negative [k1] expands the centre.
     */
    fun correctBarrel(src: Bitmap, k1: Float): Bitmap {
        if (abs(k1) < 0.001f) return src.copy(Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxR = hypot(cx, cy)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val du = (x - cx) / maxR
                val dv = (y - cy) / maxR
                val r2 = du * du + dv * dv
                val scale = 1f + k1 * r2
                val sx = (cx + du * scale * maxR).toInt()
                val sy = (cy + dv * scale * maxR).toInt()
                out[row + x] = if (sx in 0 until w && sy in 0 until h) pixels[sy * w + sx] else 0xFF000000.toInt()
            }
        }
        src.recycle()
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
