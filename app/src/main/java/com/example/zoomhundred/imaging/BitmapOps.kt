package com.example.zoomhundred.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Small, dependency-free bitmap helpers shared by every processing stage.
 *
 * Everything here is designed for phone-sized images: operations take a max-pixel cap
 * so a 108 MP capture cannot blow the heap, and every function documents whether it
 * mutates its input.
 */
object BitmapOps {

    const val MAX_WORKING_PIXELS = 14_000_000

    fun decodeSampled(resolver: ContentResolver, uri: Uri, maxPixels: Int = MAX_WORKING_PIXELS): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample).toLong() * (h / sample) > maxPixels) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Reads the orientation tag through a **file descriptor**.
     *
     * Content-provider input streams are frequently not markable, and ExifInterface needs
     * to seek: reading through an InputStream silently returns ORIENTATION_NORMAL for a
     * large share of MediaStore URIs, which is exactly how a portrait capture ends up
     * saved 90° on its side. The descriptor path always works.
     */
    fun readExifOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: readExifOrientationFromStream(resolver, uri)
    } catch (_: Exception) {
        readExifOrientationFromStream(resolver, uri)
    }

    private fun readExifOrientationFromStream(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    fun readExifOrientation(file: java.io.File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Decodes at working size **and bakes in the EXIF rotation**, returning pixels that
     * are already upright. Every computational frame must go through this: the stackers
     * save their own bitmap and cannot rely on an orientation tag surviving the merge.
     */
    fun loadOriented(
        resolver: ContentResolver,
        uri: Uri,
        maxPixels: Int = MAX_WORKING_PIXELS
    ): Bitmap? {
        val decoded = decodeSampled(resolver, uri, maxPixels) ?: return null
        return orient(decoded, readExifOrientation(resolver, uri))
    }

    fun loadOriented(file: java.io.File, maxPixels: Int = MAX_WORKING_PIXELS): Bitmap? {
        val decoded = decodeSampledFile(file, maxPixels) ?: return null
        return orient(decoded, readExifOrientation(file))
    }

    private fun orient(decoded: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return decoded
        }
        return try {
            val rotated = applyExifOrientation(decoded, orientation)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } catch (_: Exception) {
            decoded
        } catch (_: OutOfMemoryError) {
            decoded
        }
    }

    fun decodeSampledFile(file: java.io.File, maxPixels: Int = MAX_WORKING_PIXELS): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample).toLong() * (h / sample) > maxPixels) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Returns either [src] itself or a new rotated bitmap. */
    fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return src
        }
        return createBitmapSafe(src, matrix)
    }

    fun createBitmapSafe(src: Bitmap, matrix: Matrix): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)

    /** Downscale so the longest edge is at most [maxEdge]; returns [src] when small enough. */
    fun limitEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun limitPixels(src: Bitmap, maxPixels: Int): Bitmap {
        val pixels = src.width.toLong() * src.height
        if (pixels <= maxPixels) return src
        val scale = sqrt(maxPixels.toFloat() / pixels.toFloat())
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun copy(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, true) ?: src

    fun mirror(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        return createBitmapSafe(src, matrix)
    }

    /** Separable box blur (three passes ≈ Gaussian). Returns a new bitmap. */
    fun boxBlur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        if (radius <= 0) return src.copy(Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        var buffer = IntArray(w * h)
        src.getPixels(buffer, 0, w, 0, 0, w, h)
        var scratch = IntArray(w * h)
        repeat(passes.coerceAtLeast(1)) {
            blurPass(buffer, scratch, w, h, radius, horizontal = true)
            blurPass(scratch, buffer, w, h, radius, horizontal = false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(buffer, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val window = radius * 2 + 1
        for (o in 0 until outer) {
            var sumR = 0; var sumG = 0; var sumB = 0
            fun pixel(i: Int): Int = if (horizontal) src[o * w + i] else src[i * w + o]
            fun index(i: Int): Int = if (horizontal) o * w + i else i * w + o
            for (i in -radius..radius) {
                val idx = i.coerceIn(0, inner - 1)
                val p = pixel(idx)
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (i in 0 until inner) {
                dst[index(i)] = (0xFF shl 24) or
                    ((sumR / window).coerceIn(0, 255) shl 16) or
                    ((sumG / window).coerceIn(0, 255) shl 8) or
                    (sumB / window).coerceIn(0, 255)
                val outIdx = (i - radius).coerceIn(0, inner - 1)
                val inIdx = (i + radius + 1).coerceIn(0, inner - 1)
                val pOut = pixel(outIdx)
                val pIn = pixel(inIdx)
                sumR += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sumG += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sumB += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    fun luma(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000

    /** Simple 2x downsample of a luma plane into a ByteArray, used by the stacker. */
    fun lumaPlane(bmp: Bitmap, targetW: Int): Array<IntArray> {
        val scale = max(1, bmp.width / max(1, targetW))
        val w = max(1, bmp.width / scale)
        val h = max(1, bmp.height / scale)
        val out = Array(h) { IntArray(w) }
        val row = IntArray(bmp.width)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, bmp.width, 0, y * scale, bmp.width, 1)
            for (x in 0 until w) {
                val p = row[x * scale]
                out[y][x] = luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        return out
    }

    fun drawOnCanvas(canvas: Canvas, bitmap: Bitmap, left: Int, top: Int, paint: Paint? = null) {
        canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), paint)
    }

    /** Non-crashing recycle; the bitmap may already be recycled by a failed stage. */
    fun recycleQuietly(bitmap: Bitmap?) {
        try {
            bitmap?.recycle()
        } catch (_: Exception) {
            // ignore
        }
    }

    fun totalPixels(vararg bitmaps: Bitmap?): Long =
        bitmaps.filterNotNull().sumOf { it.width.toLong() * it.height.toLong() }

    fun minEdge(src: Bitmap): Int = min(src.width, src.height)
}
