package com.example.zoomhundred.computational

import android.graphics.Bitmap
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Equirectangular (photo-sphere) stitcher.
 *
 * This is a real spherical projection rather than a strip of frames: every capture carries
 * the device rotation that was live when the shutter fired, each frame is projected onto a
 * virtual sphere and the sphere is rasterised into a 2:1 equirectangular image. One code
 * path therefore produces a **panorama** (horizontal sweep), a **360** (sweep plus tilt) or
 * a full **sphere** (up, down and around) — the coverage is simply whatever the user shot.
 *
 * Matrix convention: Android's rotation-vector matrix is row-major and maps
 * **device → world** (world = East/North/Up), so `world = R · device` and `device = Rᵀ · world`.
 * The camera looks along **-Z** of the device, and frames arrive already upright (EXIF baked
 * in by the capture pipeline).
 *
 * Honest limits: no bundle adjustment, no de-ghosting of moving subjects, and only a mild
 * per-frame exposure equalisation — a seam can still show if the exposure drifted a lot
 * between passes.
 */
object SphericalStitcher {

    const val DEFAULT_OUT_WIDTH = 2048
    private const val DEFAULT_H_FOV = 66f
    private const val MAX_OUTPUT_PIXELS = 3_200_000L

    /** One captured frame plus the orientation matrix that was live at capture time. */
    class Frame(val rotation: FloatArray)

    /**
     * @param frames       capture order, each with its device→world rotation
     * @param outWidth     equirectangular width; the height is half of it
     * @param hFovDegrees  horizontal field of view of the lens that produced the frames
     * @param provider     lazily decodes frame [index]; only one decoded frame is resident
     *                     at a time, which keeps 12 MP captures off the heap
     */
    fun stitch(
        frames: List<Frame>,
        outWidth: Int = DEFAULT_OUT_WIDTH,
        hFovDegrees: Float = DEFAULT_H_FOV,
        provider: FrameProvider,
        progress: ((Float, String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Bitmap? {
        if (frames.size < 2) return null
        val width = outWidth.coerceIn(512, 8192)
        val height = (width / 2).coerceAtLeast(256)
        if (width.toLong() * height > MAX_OUTPUT_PIXELS) {
            return stitch(frames, width / 2, hFovDegrees, provider, progress, isCancelled)
        }
        val total = width * height

        val sumR = IntArray(total)
        val sumG = IntArray(total)
        val sumB = IntArray(total)
        val weight = IntArray(total)
        val accumulatorScale = 64

        val tanH = tan(Math.toRadians((hFovDegrees.coerceIn(25f, 120f) / 2f).toDouble())).toFloat()
        val sinLon = FloatArray(width)
        val cosLon = FloatArray(width)
        for (x in 0 until width) {
            val lon = Math.toRadians((x + 0.5) / width * 360.0 - 180.0)
            sinLon[x] = sin(lon).toFloat()
            cosLon[x] = cos(lon).toFloat()
        }
        val sinLat = FloatArray(height)
        val cosLat = FloatArray(height)
        for (y in 0 until height) {
            val lat = Math.toRadians(90.0 - (y + 0.5) / height * 180.0)
            sinLat[y] = sin(lat).toFloat()
            cosLat[y] = cos(lat).toFloat()
        }

        var used = 0
        for (index in frames.indices) {
            if (isCancelled()) return null
            progress?.invoke(index.toFloat() / frames.size, "Projecting ${index + 1}/${frames.size}")
            val bitmap = try {
                provider.get(index)
            } catch (_: OutOfMemoryError) {
                null
            } ?: continue
            val frameWidth = bitmap.width
            val frameHeight = bitmap.height
            if (frameWidth < 32 || frameHeight < 32) {
                bitmap.recycle()
                continue
            }
            val tanV = tanH * frameHeight.toFloat() / frameWidth.toFloat()
            val source = IntArray(frameWidth * frameHeight)
            bitmap.getPixels(source, 0, frameWidth, 0, 0, frameWidth, frameHeight)
            bitmap.recycle()

            // Very mild gain matching: neighbouring frames of one sweep should already be
            // within a few percent of each other, so this only trims visible banding.
            val gain = meanLuma(source)
            val exposureScale = (128f / gain.coerceAtLeast(1f)).coerceIn(0.9f, 1.12f)
            val gainFixed = (exposureScale * 1024f).toInt()

            val r = frames[index].rotation
            val bounds = footprint(r, tanH, tanV, width, height)
            if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) continue

            for (y in bounds.top until bounds.bottom) {
                val latSin = sinLat[y]
                val latCos = cosLat[y]
                val rowBase = y * width
                for (x in bounds.left until bounds.right) {
                    val dx = latCos * sinLon[x]
                    val dy = latSin
                    val dz = latCos * cosLon[x]

                    // device = Rᵀ · world  (column-wise dot products of R)
                    val vx = r[0] * dx + r[3] * dy + r[6] * dz
                    val vy = r[1] * dx + r[4] * dy + r[7] * dz
                    val vz = r[2] * dx + r[5] * dy + r[8] * dz
                    if (vz > -1e-4f) continue
                    val invZ = 1f / -vz
                    val u = 0.5f + (vx * invZ) / (2f * tanH)
                    val v = 0.5f - (vy * invZ) / (2f * tanV)
                    if (u <= 0f || u >= 1f || v <= 0f || v >= 1f) continue

                    // Feather the frame edges so overlapping shots blend instead of seam.
                    val edge = min(min(u, 1f - u), min(v, 1f - v)) * 4f
                    val w = (edge.coerceIn(0f, 1f) * 255f).toInt()
                    if (w <= 2) continue

                    val color = sampleBilinear(source, frameWidth, frameHeight, u, v)
                    val cr = ((color shr 16) and 0xFF) * gainFixed / 1024
                    val cg = ((color shr 8) and 0xFF) * gainFixed / 1024
                    val cb = (color and 0xFF) * gainFixed / 1024
                    val idx = rowBase + x
                    sumR[idx] += cr.coerceIn(0, 255) * w * accumulatorScale
                    sumG[idx] += cg.coerceIn(0, 255) * w * accumulatorScale
                    sumB[idx] += cb.coerceIn(0, 255) * w * accumulatorScale
                    weight[idx] += w * accumulatorScale
                }
            }
            used++
        }
        progress?.invoke(0.9f, "Compositing")
        if (used < 2) return null

        // Trim to the covered window so a 200° sweep does not ship as a 360 with black gaps.
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                if (weight[rowBase + x] > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX > maxX || minY > maxY) return null

        val padX = (width / 64).coerceAtLeast(2)
        val padY = (height / 64).coerceAtLeast(2)
        val cropLeft = (minX - padX).coerceAtLeast(0)
        val cropTop = (minY - padY).coerceAtLeast(0)
        val cropRight = (maxX + padX).coerceAtMost(width - 1)
        val cropBottom = (maxY + padY).coerceAtMost(height - 1)
        val cropW = cropRight - cropLeft + 1
        val cropH = cropBottom - cropTop + 1

        val buffer = IntArray(cropW * cropH)
        for (y in 0 until cropH) {
            val srcRow = (cropTop + y) * width + cropLeft
            val dstRow = y * cropW
            for (x in 0 until cropW) {
                val idx = srcRow + x
                val w = weight[idx]
                buffer[dstRow + x] = if (w <= 0) {
                    // A gap the user never covered: neutral grey reads as "missing", not as
                    // a photographic black band.
                    (0xFF shl 24) or 0x1A1A1A
                } else {
                    val cr = (sumR[idx] / w).coerceIn(0, 255)
                    val cg = (sumG[idx] / w).coerceIn(0, 255)
                    val cb = (sumB[idx] / w).coerceIn(0, 255)
                    (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                }
            }
        }
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        out.setPixels(buffer, 0, cropW, 0, 0, cropW, cropH)
        progress?.invoke(1f, "Done")
        return out
    }

    private class Bounds(val left: Int, val right: Int, val top: Int, val bottom: Int)

    /**
     * Longitude/latitude window a frame can possibly cover, in output pixels.
     *
     * Iterating the whole equirect for every frame would be tens of millions of trig
     * evaluations for a sphere; this restricts each frame to its footprint plus a margin.
     */
    private fun footprint(r: FloatArray, tanH: Float, tanV: Float, width: Int, height: Int): Bounds {
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f, 0f, 0f)
        var i = 0
        while (i < corners.size) {
            val u = corners[i]
            val v = corners[i + 1]
            val dx = u * tanH
            val dy = -v * tanV
            val dz = -1f
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            // world = R · device
            val wx = (r[0] * dx + r[1] * dy + r[2] * dz) / len
            val wy = (r[3] * dx + r[4] * dy + r[5] * dz) / len
            val wz = (r[6] * dx + r[7] * dy + r[8] * dz) / len
            val lon = Math.toDegrees(atan2(wx.toDouble(), wz.toDouble()))
            val lat = Math.toDegrees(asin(wy.coerceIn(-1f, 1f).toDouble()))
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            i += 2
        }
        val spansAllLongitudes = (maxLon - minLon) > 180.0 || maxLat > 78.0 || minLat < -78.0
        val marginX = width / 24
        val marginY = height / 24
        val left = if (spansAllLongitudes) 0
        else (((minLon + 180.0) / 360.0 * width).toInt() - marginX).coerceIn(0, width)
        val right = if (spansAllLongitudes) width
        else (((maxLon + 180.0) / 360.0 * width).toInt() + marginX).coerceIn(0, width)
        val top = (((90.0 - maxLat) / 180.0 * height).toInt() - marginY).coerceIn(0, height)
        val bottom = (((90.0 - minLat) / 180.0 * height).toInt() + marginY).coerceIn(0, height)
        return Bounds(left, max(left + 1, right), top, max(top + 1, bottom))
    }

    private fun sampleBilinear(src: IntArray, w: Int, h: Int, u: Float, v: Float): Int {
        val fx = (u * (w - 1)).coerceIn(0f, (w - 1).toFloat())
        val fy = (v * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val p00 = src[y0 * w + x0]
        val p10 = src[y0 * w + x1]
        val p01 = src[y1 * w + x0]
        val p11 = src[y1 * w + x1]
        fun mix(shift: Int): Int {
            val a = (p00 shr shift) and 0xFF
            val b = (p10 shr shift) and 0xFF
            val c = (p01 shr shift) and 0xFF
            val d = (p11 shr shift) and 0xFF
            val top = a + (b - a) * tx
            val bottom = c + (d - c) * tx
            return (top + (bottom - top) * ty).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun meanLuma(src: IntArray): Float {
        if (src.isEmpty()) return 128f
        val step = max(1, src.size / 8192)
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < src.size) {
            val p = src[i]
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            count++
            i += step
        }
        return if (count == 0) 128f else (sum / count).toFloat()
    }

    /**
     * Angle, in degrees, between the forward directions of two rotation matrices.
     * The guided capture loop uses this to decide when the next frame is worth taking.
     */
    fun angularDistance(a: FloatArray, b: FloatArray): Float {
        // Forward (world) = R · (0,0,-1) = -(third column of R).
        val ax = -a[2]
        val ay = -a[5]
        val az = -a[8]
        val bx = -b[2]
        val by = -b[5]
        val bz = -b[8]
        val magA = sqrt(ax * ax + ay * ay + az * az)
        val magB = sqrt(bx * bx + by * by + bz * bz)
        if (magA < 1e-6f || magB < 1e-6f) return 180f
        val c = ((ax * bx + ay * by + az * bz) / (magA * magB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }
}
