package com.example.zoomhundred.imaging

import android.graphics.Bitmap

/**
 * Resolution-preserving finishing pass applied to every still.
 *
 * The point is the thing phone cameras are judged on: a bare sensor JPEG looks flat and
 * soft next to anything a modern camera app produces. This pass does what those do, at the
 * native resolution, in a single trip over the pixels:
 *
 *  * a gentle S-curve with a lifted toe and rolled-off shoulder (contrast without clipping),
 *  * a small saturation/vibrance push that leaves skin tones alone,
 *  * an unsharp mask applied **to luma only**, with the chroma carried along, so edges get
 *    crisp without colour fringing.
 *
 * It is banded: only a few hundred scan lines are resident at a time, so a 50 MP frame does
 * not need a 200 MB working set.
 */
object PhotoPolish {

    private const val BAND_HEIGHT = 384
    private const val BLUR_RADIUS = 2
    private const val HALO = BLUR_RADIUS + 1

    fun apply(src: Bitmap, strength: Float = 1f, sharpen: Float = 0.62f): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src.copy(Bitmap.Config.ARGB_8888, true) ?: src
        val amount = strength.coerceIn(0f, 1f)
        val sharpenAmount = (sharpen * amount).coerceIn(0f, 1.2f)

        val toneLut = buildToneLut(amount)
        val saturation = 1f + 0.075f * amount
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        var top = 0
        while (top < h) {
            val bandBottom = (top + BAND_HEIGHT).coerceAtMost(h)
            val y0 = (top - HALO).coerceAtLeast(0)
            val y1 = (bandBottom + HALO).coerceAtMost(h)
            val bandRows = y1 - y0
            val bandPixels = IntArray(w * bandRows)
            src.getPixels(bandPixels, 0, w, 0, y0, w, bandRows)

            // Luma plane for the band (cheap: one pass, integer weights).
            val luma = IntArray(w * bandRows)
            for (i in luma.indices) {
                val p = bandPixels[i]
                luma[i] = BitmapOps.luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
            val blurred = boxBlur(luma, w, bandRows, BLUR_RADIUS)

            val writeRows = bandBottom - top
            val outPixels = IntArray(w * writeRows)
            for (row in 0 until writeRows) {
                val srcRow = (top + row - y0) * w
                val dstRow = row * w
                for (x in 0 until w) {
                    val index = srcRow + x
                    val p = bandPixels[index]
                    var r = toneLut[(p shr 16) and 0xFF]
                    var g = toneLut[(p shr 8) and 0xFF]
                    var b = toneLut[p and 0xFF]

                    // Vibrance: pull colour away from its own luma, moreso where it is muted.
                    val l = BitmapOps.luma(r, g, b)
                    val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
                    val sat = 1f + (saturation - 1f) * (1f - chroma * 0.6f)
                    r = (l + (r - l) * sat).toInt().coerceIn(0, 255)
                    g = (l + (g - l) * sat).toInt().coerceIn(0, 255)
                    b = (l + (b - l) * sat).toInt().coerceIn(0, 255)

                    if (sharpenAmount > 0.01f) {
                        val detail = luma[index] - blurred[index]
                        // Dead zone keeps sensor noise from being amplified.
                        val gain = if (detail > 3 || detail < -3) sharpenAmount else 0f
                        if (gain > 0f) {
                            val newL = (BitmapOps.luma(r, g, b) + detail * gain).toInt().coerceIn(1, 255)
                            val oldL = BitmapOps.luma(r, g, b).coerceAtLeast(1)
                            val ratio = newL.toFloat() / oldL
                            r = (r * ratio).toInt().coerceIn(0, 255)
                            g = (g * ratio).toInt().coerceIn(0, 255)
                            b = (b * ratio).toInt().coerceIn(0, 255)
                        }
                    }
                    outPixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(outPixels, 0, w, 0, top, w, writeRows)
            top = bandBottom
        }
        return out
    }

    /**
     * S-curve with a lifted toe and a soft shoulder. Built once per image, applied 8 bits
     * at a time — no per-pixel pow() in the hot loop.
     */
    private fun buildToneLut(strength: Float): IntArray {
        val lut = IntArray(256)
        val contrast = 1f + 0.10f * strength
        for (i in 0..255) {
            var x = i / 255f
            // Gentle S-curve around mid grey.
            val s = if (x < 0.5f) {
                0.5f * Math.pow((2f * x).toDouble(), contrast.toDouble()).toFloat()
            } else {
                1f - 0.5f * Math.pow((2f * (1f - x)).toDouble(), contrast.toDouble()).toFloat()
            }
            x = s
            // Lift the toe so shadows keep detail instead of going flat black.
            x += 0.035f * strength * (1f - x) * (1f - x)
            // Roll the shoulder off rather than clipping.
            if (x > 0.86f) {
                x = 0.86f + (1f - 0.86f) * (1f - (1f - (x - 0.86f) / (1f - 0.86f)).let { it * it })
            }
            lut[i] = (x.coerceIn(0f, 1f) * 255f).toInt()
        }
        return lut
    }

    /** Separable box blur, twice, which is within a pixel of a Gaussian at this radius. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val temp = IntArray(src.size)
        val out = IntArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -radius..radius) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                temp[row + x] = sum / window
                val outX = (x - radius).coerceIn(0, w - 1)
                val inX = (x + radius + 1).coerceIn(0, w - 1)
                sum += src[row + inX] - src[row + outX]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -radius..radius) sum += temp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / window
                val outY = (y - radius).coerceIn(0, h - 1)
                val inY = (y + radius + 1).coerceIn(0, h - 1)
                sum += temp[inY * w + x] - temp[outY * w + x]
            }
        }
        return out
    }
}
