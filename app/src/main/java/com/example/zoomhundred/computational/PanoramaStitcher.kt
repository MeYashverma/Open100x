package com.example.zoomhundred.computational

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.example.zoomhundred.imaging.BitmapOps
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local panorama stitcher.
 *
 * Honest scope: consecutive frames are aligned by **horizontal translation only** using
 * a normalised luma correlation over the overlap, then composited with linear feathering.
 * That handles a hand-held horizontal sweep well; it does not estimate rotation, and it
 * does not correct for large vertical drift (documented in FEATURE_COMPATIBILITY.md).
 *
 * Frames must be supplied in sweep order.
 */
object PanoramaStitcher {

    data class Result(val bitmap: Bitmap, val framesUsed: Int, val offsets: List<Int>)

    private const val MAX_FRAME_EDGE = 1600

    fun stitch(
        frameCount: Int,
        provider: FrameProvider,
        minOverlapFraction: Float = 0.12f,
        progress: ((Float, String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Result? {
        val frames = ArrayList<Bitmap>(frameCount)
        try {
            for (i in 0 until frameCount) {
                if (isCancelled()) return null
                progress?.invoke(i.toFloat() / (frameCount * 2f), "Loading ${i + 1}/$frameCount")
                val frame = provider.get(i) ?: continue
                val limited = BitmapOps.limitEdge(frame, MAX_FRAME_EDGE)
                if (limited !== frame) frame.recycle()
                frames.add(limited)
            }
            if (frames.size < 2) return null

            val height = frames.minOf { it.height }
            val offsets = ArrayList<Int>(frames.size)
            var totalWidth = 0
            var previousLuma: IntArray? = null
            var previousSamples = 0

            frames.forEachIndexed { index, frame ->
                // Normalise frame height so the sweep stays on one canvas.
                val scaled = if (frame.height != height) {
                    Bitmap.createScaledBitmap(frame, (frame.width * height / frame.height.toFloat()).toInt(), height, true)
                } else frame
                if (scaled !== frame) frame.recycle()
                frames[index] = scaled

                val luma = lumaColumns(scaled)
                if (index == 0) {
                    offsets.add(0)
                    totalWidth = scaled.width
                } else {
                    val overlap = estimateOverlap(
                        previousLuma ?: IntArray(0), previousSamples, luma.first, scaled.width, height
                    )
                    val advance = (scaled.width - overlap).coerceAtLeast(
                        (scaled.width * (1f - 0.75f)).toInt()
                    )
                    offsets.add(totalWidth - overlap)
                    totalWidth += advance
                }
                previousLuma = luma.first
                previousSamples = luma.second
                progress?.invoke(0.5f + index.toFloat() * 0.2f / frames.size, "Aligning")
            }

            if (abs(totalWidth) < height) return null
            val safeWidth = min(totalWidth, 20_000)
            val output = Bitmap.createBitmap(max(1, safeWidth), height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            frames.forEachIndexed { index, frame ->
                if (isCancelled()) return null
                progress?.invoke(0.75f + index.toFloat() * 0.25f / frames.size, "Blending")
                val left = offsets[index]
                val overlapLeft = if (index == 0) 0 else {
                    // Overlap width with the previous frame.
                    (offsets[index - 1] + frames[index - 1].width - left).coerceAtLeast(0)
                }
                if (index == 0) {
                    canvas.drawBitmap(frame, left.toFloat(), 0f, paint)
                } else if (overlapLeft <= 0) {
                    canvas.drawBitmap(frame, left.toFloat(), 0f, paint)
                } else {
                    val feather = max(1, overlapLeft)
                    val shader = LinearGradient(
                        0f, 0f, feather.toFloat(), 0f,
                        intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt()),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = shader
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                    // Draw only the fade-in part; the rest is opaque.
                    val faded = Bitmap.createBitmap(frame, 0, 0, min(feather, frame.width), frame.height)
                    canvas.drawBitmap(faded, left.toFloat(), 0f, paint)
                    faded.recycle()
                    paint.shader = null
                    if (feather < frame.width) {
                        val remainder = Bitmap.createBitmap(
                            frame, feather, 0, frame.width - feather, frame.height
                        )
                        canvas.drawBitmap(remainder, (left + feather).toFloat(), 0f, paint)
                        remainder.recycle()
                    }
                }
            }

            // Trim the ragged top/bottom edges caused by vertical drift between frames.
            val trim = (height * 0.02f).toInt()
            val cropped = if (trim > 0 && height - trim * 2 > 8) {
                Bitmap.createBitmap(output, 0, trim, output.width, height - trim * 2)
            } else output
            if (cropped !== output) output.recycle()
            progress?.invoke(1f, "Done")
            return Result(cropped, frames.size, offsets)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /** Column-wise luma samples used for overlap estimation. */
    private fun lumaColumns(bitmap: Bitmap): Pair<IntArray, Int> {
        val samples = 96
        val rows = 32
        val out = IntArray(samples * rows)
        val rowPixels = IntArray(bitmap.width)
        for (r in 0 until rows) {
            val y = (r * bitmap.height / rows).coerceIn(0, bitmap.height - 1)
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (s in 0 until samples) {
                val x = (s * bitmap.width / samples).coerceIn(0, bitmap.width - 1)
                val p = rowPixels[x]
                out[r * samples + s] = BitmapOps.luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        return out to samples
    }

    /**
     * Estimates how many (full-resolution) columns the right edge of [prevLuma] shares
     * with the left edge of [nextLuma]. Returns the best-scoring overlap in pixels.
     */
    private fun estimateOverlap(prevLuma: IntArray, prevSamples: Int, nextLuma: IntArray, nextWidth: Int, height: Int): Int {
        val samples = prevSamples
        val rows = prevLuma.size / samples
        val minOverlapSamples = max(4, samples / 8)
        val maxOverlapSamples = max(minOverlapSamples + 1, samples * 3 / 4)
        var bestSamples = minOverlapSamples
        var bestScore = Double.MAX_VALUE
        var bestNormalised = Double.MAX_VALUE

        for (overlap in minOverlapSamples until maxOverlapSamples) {
            var sum = 0.0
            var count = 0
            for (r in 0 until rows) {
                val prevBase = r * samples + (samples - overlap)
                val nextBase = r * samples
                for (i in 0 until overlap) {
                    val a = prevLuma[prevBase + i]
                    val b = nextLuma[nextBase + i]
                    sum += abs(a - b)
                    count++
                }
            }
            if (count == 0) continue
            val score = sum / count
            // Prefer larger overlaps when scores are close: fewer visible seams.
            val normalised = score - overlap * 0.05
            if (normalised < bestNormalised) {
                bestNormalised = normalised
                bestScore = score
                bestSamples = overlap
            }
        }
        if (bestScore > 40.0) {
            // No convincing match: fall back to a conservative 1/3 overlap.
            bestSamples = samples / 3
        }
        val fraction = bestSamples.toFloat() / samples
        return (nextWidth * fraction).toInt().coerceIn(1, nextWidth - 1)
    }
}
