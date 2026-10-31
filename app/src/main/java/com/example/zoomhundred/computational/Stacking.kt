package com.example.zoomhundred.computational

import android.graphics.Bitmap
import com.example.zoomhundred.imaging.BitmapOps
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-frame computational photography on the CPU.
 *
 * Frames are supplied lazily by a [FrameProvider] so only one decoded frame is resident
 * at a time — a burst of eight 12 MP frames would otherwise exceed the heap on many
 * phones. Everything is aligned to the first frame with an integer translation search on
 * a downsampled luma grid, then merged:
 *
 *  * [NightStacker] — robust weighted temporal average (motion rejection) + tone lift.
 *  * [HdrFusion]    — exposure fusion with a well-exposedness weight (Mertens-style).
 *  * [LightTrailStacker] — per-pixel maximum (trails) or mean (smooth water).
 *  * [FocusStacker] — per-pixel sharpest-source selection using a smoothed Laplacian map.
 *
 * Known limits (documented in docs/FEATURE_COMPATIBILITY.md): translation-only
 * alignment, no deghosting for large moving subjects, no hot-pixel correction.
 */
fun interface FrameProvider {
    /** Returns a decoded frame or null when it cannot be loaded. Caller owns recycling. */
    fun get(index: Int): Bitmap?
}

data class StackOptions(
    val maxPixelsPerFrame: Int = 2_600_000,
    val alignmentRadiusPercent: Int = 4,
    val noiseSigma: Float = 0.035f,
    val progress: ((Float, String) -> Unit)? = null,
    val isCancelled: () -> Boolean = { false }
)

object FrameAligner {

    /** Downsampled luma grid used for shift estimation. */
    class Grid(val width: Int, val height: Int, val luma: IntArray, val scale: Int)

    fun grid(bitmap: Bitmap, targetWidth: Int = 128): Grid {
        val scale = max(1, bitmap.width / max(1, targetWidth))
        val w = max(1, bitmap.width / scale)
        val h = max(1, bitmap.height / scale)
        val luma = IntArray(w * h)
        val row = IntArray(bitmap.width)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, bitmap.width, 0, min(y * scale, bitmap.height - 1), bitmap.width, 1)
            for (x in 0 until w) {
                val p = row[min(x * scale, bitmap.width - 1)]
                luma[y * w + x] = BitmapOps.luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        return Grid(w, h, luma, scale)
    }

    /**
     * Coarse-to-fine SAD search around the origin. Returns the shift in full-resolution
     * pixels that best maps [target] onto [reference].
     */
    fun estimateShift(reference: Grid, target: Grid, radiusPercent: Int = 4): Pair<Int, Int> {
        if (reference.width != target.width || reference.height != target.height) return 0 to 0
        val radius = max(2, reference.width * radiusPercent / 100)
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        // Coarse pass on a 2x-decimated grid, then refine ±2 cells.
        val step = 2
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                val score = sad(reference, target, dx, dy, step)
                if (score < bestScore) {
                    bestScore = score; bestDx = dx; bestDy = dy
                }
            }
        }
        val fineBestX = bestDx
        val fineBestY = bestDy
        bestScore = Long.MAX_VALUE
        for (dy in fineBestY - step..fineBestY + step) {
            for (dx in fineBestX - step..fineBestX + step) {
                val score = sad(reference, target, dx, dy, 1)
                if (score < bestScore) {
                    bestScore = score; bestDx = dx; bestDy = dy
                }
            }
        }
        return (bestDx * reference.scale) to (bestDy * reference.scale)
    }

    private fun sad(ref: Grid, tgt: Grid, dx: Int, dy: Int, step: Int): Long {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < ref.height) {
            val ty = y + dy
            if (ty !in 0 until tgt.height) { y += step; continue }
            var x = 0
            while (x < ref.width) {
                val tx = x + dx
                if (tx in 0 until tgt.width) {
                    sum += abs(ref.luma[y * ref.width + x] - tgt.luma[ty * tgt.width + tx])
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count == 0) Long.MAX_VALUE else sum * 1000 / count
    }
}

private class WeightedAccumulator(private val width: Int, private val height: Int) {
    val sumR = FloatArray(width * height)
    val sumG = FloatArray(width * height)
    val sumB = FloatArray(width * height)
    val weight = FloatArray(width * height)
    val count = width * height
    var frames = 0

    fun toBitmap(): Bitmap {
        val out = IntArray(count)
        for (i in 0 until count) {
            val w = weight[i]
            if (w <= 0f) {
                out[i] = 0xFF000000.toInt()
            } else {
                val r = (sumR[i] / w).toInt().coerceIn(0, 255)
                val g = (sumG[i] / w).toInt().coerceIn(0, 255)
                val b = (sumB[i] / w).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, width, 0, 0, width, height)
        return bmp
    }
}

/** Shared alignment wired into each merge strategy. */
private fun forEachAlignedFrame(
    frameCount: Int,
    provider: FrameProvider,
    options: StackOptions,
    onFrame: (pixels: IntArray, width: Int, height: Int, index: Int) -> Unit
): Int {
    var reference: FrameAligner.Grid? = null
    var width = 0
    var height = 0
    var used = 0

    for (index in 0 until frameCount) {
        if (options.isCancelled()) break
        options.progress?.invoke(index.toFloat() / frameCount, "Frame ${index + 1}/$frameCount")
        val frame = provider.get(index) ?: continue
        val limited = BitmapOps.limitPixels(frame, options.maxPixelsPerFrame)
        if (index == 0 || width == 0) {
            width = limited.width
            height = limited.height
            reference = FrameAligner.grid(limited)
        }
        val grid = FrameAligner.grid(limited)
        val referenceGrid = reference
        val shift = if (index == 0 || referenceGrid == null) 0 to 0
        else FrameAligner.estimateShift(referenceGrid, grid, options.alignmentRadiusPercent)
        val dx = shift.first
        val dy = shift.second
        val pixels = IntArray(limited.width * limited.height)
        limited.getPixels(pixels, 0, limited.width, 0, 0, limited.width, limited.height)

        // Re-index into the reference canvas when the frame is shifted.
        val shifted = if (dx == 0 && dy == 0) pixels else shiftPixels(pixels, limited.width, limited.height, dx, dy, width, height)
        onFrame(shifted, width, height, index)
        used++
        if (limited !== frame) limited.recycle()
        frame.recycle()
    }
    return used
}

private fun shiftPixels(src: IntArray, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int): IntArray {
    val out = IntArray(dw * dh)
    for (y in 0 until dh) {
        val sy = y + dy
        if (sy !in 0 until sh) continue
        val srcRow = sy * sw
        val dstRow = y * dw
        val xStart = max(0, -dx)
        val xEnd = min(dw, sw - dx)
        for (x in xStart until xEnd) {
            out[dstRow + x] = src[srcRow + x + dx]
        }
    }
    return out
}

object NightStacker {

    /**
     * Robust temporal average. Each incoming pixel is weighted against the running mean,
     * so a passing car or a hand wobble contributes little instead of ghosting.
     */
    fun stack(frameCount: Int, provider: FrameProvider, options: StackOptions = StackOptions()): Bitmap? {
        var acc: WeightedAccumulator? = null
        val used = forEachAlignedFrame(frameCount, provider, options) { pixels, w, h, index ->
            val accumulator = acc ?: WeightedAccumulator(w, h).also { acc = it }
            val sigma255 = (options.noiseSigma * 255f).coerceAtLeast(1f)
            for (i in 0 until accumulator.count) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (index == 0) {
                    accumulator.sumR[i] = r.toFloat()
                    accumulator.sumG[i] = g.toFloat()
                    accumulator.sumB[i] = b.toFloat()
                    accumulator.weight[i] = 1f
                } else {
                    val wr = accumulator.weight[i]
                    if (wr <= 0f) {
                        accumulator.sumR[i] = r.toFloat(); accumulator.sumG[i] = g.toFloat()
                        accumulator.sumB[i] = b.toFloat(); accumulator.weight[i] = 1f
                    } else {
                        val meanR = accumulator.sumR[i] / wr
                        val meanG = accumulator.sumG[i] / wr
                        val meanB = accumulator.sumB[i] / wr
                        val dev = (abs(r - meanR) + abs(g - meanG) + abs(b - meanB)) / 3f
                        val weight = 1f / (1f + (dev / sigma255).pow(2))
                        accumulator.sumR[i] += r * weight
                        accumulator.sumG[i] += g * weight
                        accumulator.sumB[i] += b * weight
                        accumulator.weight[i] = wr + weight
                    }
                }
            }
            accumulator.frames = index + 1
        }
        val result = acc?.toBitmap() ?: return null
        options.progress?.invoke(0.95f, "Tone mapping")
        return toneMapNight(result, used)
    }

    /** Lifts shadows and rolls off highlights without crushing black point. */
    private fun toneMapNight(src: Bitmap, frames: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val gamma = if (frames >= 6) 1.18f else 1.10f
        val invGamma = 1f / gamma
        for (i in pixels.indices) {
            val p = pixels[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            val luma = BitmapOps.luma(r, g, b) / 255f
            // Shadow-weighted gamma: dark areas lift, highlights stay put.
            val lift = (1f - luma).coerceIn(0f, 1f)
            val gammaR = 1f + (gamma - 1f) * lift
            r = (255f * (r / 255f).pow(1f / gammaR)).toInt().coerceIn(0, 255)
            g = (255f * (g / 255f).pow(1f / gammaR)).toInt().coerceIn(0, 255)
            b = (255f * (b / 255f).pow(1f / gammaR)).toInt().coerceIn(0, 255)
            if (luma < 0.04f) { // keep true blacks black
                val k = (luma / 0.04f).coerceIn(0f, 1f)
                r = (r * k).toInt(); g = (g * k).toInt(); b = (b * k).toInt()
            } else if (luma > 0.92f) {
                val k = 1f - (luma - 0.92f) * 1.6f
                r = (255f - (255f - r) * k).toInt().coerceIn(0, 255)
                g = (255f - (255f - g) * k).toInt().coerceIn(0, 255)
                b = (255f - (255f - b) * k).toInt().coerceIn(0, 255)
            }
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

object HdrFusion {

    /**
     * Exposure fusion. Frames are bracketed exposures of the same scene; the well-exposed
     * neighbourhood of each contributes most, so highlights recover and shadows open up
     * without an explicit tone curve per frame.
     */
    fun fuse(frameCount: Int, provider: FrameProvider, options: StackOptions = StackOptions()): Bitmap? {
        var acc: WeightedAccumulator? = null
        val sigma = 0.22f
        val denom = 2f * sigma * sigma
        forEachAlignedFrame(frameCount, provider, options) { pixels, w, h, _ ->
            val accumulator = acc ?: WeightedAccumulator(w, h).also { acc = it }
            for (i in 0 until accumulator.count) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = BitmapOps.luma(r, g, b) / 255f
                val d = luma - 0.5f
                var weight = exp(-(d * d) / denom)
                // Very dark frames still contribute in the deepest shadows.
                if (luma < 0.12f) weight *= 0.85f
                weight = weight.coerceAtLeast(0.0025f)
                accumulator.sumR[i] += r * weight
                accumulator.sumG[i] += g * weight
                accumulator.sumB[i] += b * weight
                accumulator.weight[i] += weight
            }
            accumulator.frames++
        }
        val fused = acc?.toBitmap() ?: return null
        options.progress?.invoke(0.95f, "Blending")
        return toneMapFusion(fused)
    }

    private fun toneMapFusion(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            // Gentle S-curve restores contrast lost by averaging.
            fun curve(v: Int): Int {
                val x = v / 255f
                val y = 0.5f + (x - 0.5f) * 1.06f
                val s = y + 0.06f * (y - 0.5f) * (1f - abs(2f * y - 1f))
                return (s * 255f).toInt().coerceIn(0, 255)
            }
            r = curve(r); g = curve(g); b = curve(b)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

enum class AccumulationMode { LIGHTEN, AVERAGE }

object LightTrailStacker {

    /**
     * [AccumulationMode.LIGHTEN] keeps the brightest sample per pixel — traffic trails,
     * star trails, light painting. [AccumulationMode.AVERAGE] blurs moving water/silk.
     */
    fun accumulate(
        frameCount: Int,
        provider: FrameProvider,
        mode: AccumulationMode,
        options: StackOptions = StackOptions()
    ): Bitmap? {
        var acc: WeightedAccumulator? = null
        forEachAlignedFrame(frameCount, provider, options) { pixels, w, h, index ->
            val accumulator = acc ?: WeightedAccumulator(w, h).also { acc = it }
            when (mode) {
                AccumulationMode.LIGHTEN -> {
                    if (index == 0) {
                        for (i in 0 until accumulator.count) {
                            val p = pixels[i]
                            accumulator.sumR[i] = ((p shr 16) and 0xFF).toFloat()
                            accumulator.sumG[i] = ((p shr 8) and 0xFF).toFloat()
                            accumulator.sumB[i] = (p and 0xFF).toFloat()
                            accumulator.weight[i] = 1f
                        }
                    } else {
                        for (i in 0 until accumulator.count) {
                            val p = pixels[i]
                            accumulator.sumR[i] = max(accumulator.sumR[i], ((p shr 16) and 0xFF).toFloat())
                            accumulator.sumG[i] = max(accumulator.sumG[i], ((p shr 8) and 0xFF).toFloat())
                            accumulator.sumB[i] = max(accumulator.sumB[i], (p and 0xFF).toFloat())
                        }
                    }
                }
                AccumulationMode.AVERAGE -> {
                    for (i in 0 until accumulator.count) {
                        val p = pixels[i]
                        accumulator.sumR[i] = accumulator.sumR[i] + ((p shr 16) and 0xFF).toFloat()
                        accumulator.sumG[i] = accumulator.sumG[i] + ((p shr 8) and 0xFF).toFloat()
                        accumulator.sumB[i] = accumulator.sumB[i] + (p and 0xFF).toFloat()
                        accumulator.weight[i] = (index + 1).toFloat()
                    }
                }
            }
            accumulator.frames = index + 1
        }
        return acc?.toBitmap()
    }
}

object FocusStacker {

    /**
     * Selects, per pixel, the frame that is sharpest there. Sharpness is the local
     * Laplacian magnitude on a downsampled luma map, box-smoothed so selection does not
     * flicker at edges.
     */
    fun stack(frameCount: Int, provider: FrameProvider, options: StackOptions = StackOptions()): Bitmap? {
        var out: IntArray? = null
        var bestScore: FloatArray? = null
        var outWidth = 0
        var outHeight = 0
        var gridWidth = 0
        var gridHeight = 0
        var framesUsed = 0

        forEachAlignedFrame(frameCount, provider, options) { pixels, w, h, _ ->
            if (out == null) {
                outWidth = w; outHeight = h
                out = IntArray(w * h)
                bestScore = FloatArray(w * h) { -1f }
                val scale = max(1, w / 160)
                gridWidth = max(1, w / scale)
                gridHeight = max(1, h / scale)
            }
            val scoreGrid = sharpnessGrid(pixels, w, h, gridWidth, gridHeight)
            val output = out!!
            val scores = bestScore!!
            for (y in 0 until h) {
                val gy = (y * gridHeight / h).coerceIn(0, gridHeight - 1)
                for (x in 0 until w) {
                    val gx = (x * gridWidth / w).coerceIn(0, gridWidth - 1)
                    val score = scoreGrid[gy * gridWidth + gx]
                    val i = y * w + x
                    if (score > scores[i]) {
                        scores[i] = score
                        output[i] = pixels[i]
                    }
                }
            }
            framesUsed++
        }
        if (framesUsed == 0) return null
        val output = out ?: return null
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(output, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }

    private fun sharpnessGrid(pixels: IntArray, w: Int, h: Int, gw: Int, gh: Int): FloatArray {
        // Downsample luma first.
        val luma = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val sy = gy * h / gh
            for (gx in 0 until gw) {
                val sx = gx * w / gw
                val p = pixels[sy * w + sx]
                luma[gy * gw + gx] = BitmapOps.luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toFloat()
            }
        }
        val lap = FloatArray(gw * gh)
        for (y in 1 until gh - 1) {
            for (x in 1 until gw - 1) {
                val c = luma[y * gw + x] * 4f
                val n = luma[(y - 1) * gw + x] + luma[(y + 1) * gw + x] +
                    luma[y * gw + x - 1] + luma[y * gw + x + 1]
                lap[y * gw + x] = abs(c - n)
            }
        }
        // 3x3 box smoothing.
        val smoothed = FloatArray(gw * gh)
        for (y in 1 until gh - 1) {
            for (x in 1 until gw - 1) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += lap[(y + dy) * gw + x + dx]
                smoothed[y * gw + x] = sum / 9f
            }
        }
        return smoothed
    }
}
