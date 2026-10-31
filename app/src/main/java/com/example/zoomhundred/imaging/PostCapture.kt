package com.example.zoomhundred.imaging

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.example.zoomhundred.model.AspectRatio
import com.example.zoomhundred.model.ColorProfile
import com.example.zoomhundred.model.FrameStyle

/**
 * The single post-capture pipeline. Every still (except RAW) goes through here, so the
 * order of operations is predictable:
 *
 * 1. decode + rotate by EXIF
 * 2. keep an untouched original (optional, for gallery before/after)
 * 3. mode-specific render (portrait bokeh, fisheye)
 * 4. aspect-ratio crop (only when a non-native ratio is selected)
 * 5. mirror (front camera preferences)
 * 6. colour profile + LUT
 * 7. decorative frame
 * 8. extreme-zoom enhancement (existing engine, preserved)
 * 9. re-encode in place + write EXIF (zoom, ISO, shutter, software tag, GPS strip)
 */
object PostCapture {

    data class Params(
        val zoomRatio: Float = 1f,
        val nativeZoom: Float = 1f,
        val iso: Int? = null,
        val shutterNs: Long? = null,
        val aspect: AspectRatio = AspectRatio.FULL,
        val profile: ColorProfile = ColorProfile.NEUTRAL,
        val lut: CubeLut? = null,
        val lutIntensity: Int = 100,
        val frame: FrameStyle = FrameStyle.NONE,
        val portraitBlur: Int = 0,
        val fisheye: Boolean = false,
        val fisheyeStrength: Int = 60,
        val mirror: Boolean = false,
        val enhanceZoom: Boolean = false,
        val enhanceStrength: Int = 55,
        val stripGps: Boolean = false,
        val keepOriginal: Boolean = true,
        val digitalCrop: Float = 1f,
        val cropOffsetX: Float = 0f,
        val cropOffsetY: Float = 0f,
        val lensLabel: String = "",
        val modeLabel: String = ""
    )

    fun process(context: Context, uri: Uri, params: Params): Boolean {
        val resolver = context.contentResolver
        val format = resolveFormat(resolver, uri) ?: return false
        if (format == OutputKind.RAW) {
            // Never re-encode a DNG: just stamp the software tag.
            ExifUtils.writeProMetadata(
                resolver, uri, params.zoomRatio, params.nativeZoom,
                params.iso, params.shutterNs, params.stripGps
            )
            return true
        }

        val decoded = BitmapOps.decodeSampled(resolver, uri) ?: return false
        val orientation = BitmapOps.readExifOrientation(resolver, uri)
        val oriented = BitmapOps.applyExifOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()

        if (params.keepOriginal) {
            ImageStore.keepOriginal(context, resolver, uri, displayName(resolver, uri))
        }

        val rendered = renderBitmap(oriented, params)
        if (rendered !== oriented) oriented.recycle()

        val ok = writeBitmap(resolver, uri, rendered, format)
        rendered.recycle()
        if (ok) {
            ExifUtils.writeProMetadata(
                resolver, uri, params.zoomRatio, params.nativeZoom,
                params.iso, params.shutterNs, params.stripGps
            )
        }
        return ok
    }

    /**
     * In-memory variant used by the computational pipelines (night, HDR, panorama…).
     * Returns a new bitmap; [src] is left untouched and remains owned by the caller.
     */
    fun renderBitmap(src: Bitmap, params: Params): Bitmap {
        var current: Bitmap = src.copy(Bitmap.Config.ARGB_8888, true) ?: src
        if (params.portraitBlur > 0) {
            current = replace(current, PortraitRenderer.render(current, params.portraitBlur))
        }
        if (params.fisheye) {
            current = replace(current, DistortionWarp.fisheye(current, params.fisheyeStrength, circular = false))
        }
        if (params.digitalCrop > 1.01f) {
            current = replace(current, cropDigital(current, params.digitalCrop, params.cropOffsetX, params.cropOffsetY))
        }
        if (params.aspect != AspectRatio.FULL) {
            val rect = params.aspect.cropRect(current.width, current.height)
            if (rect.width() > 8 && rect.height() > 8 &&
                (rect.width() != current.width || rect.height() != current.height)
            ) {
                current = replace(current, Bitmap.createBitmap(current, rect.left, rect.top, rect.width(), rect.height()))
            }
        }
        if (params.mirror) {
            current = replace(current, BitmapOps.mirror(current))
        }
        if (params.profile != ColorProfile.NEUTRAL || params.lut != null) {
            current = replace(current, ColorPipeline.applyProfile(current, params.profile, params.lut, params.lutIntensity))
        }
        if (params.frame != FrameStyle.NONE) {
            current = replace(current, FrameRenderer.render(current, params.frame, buildCaption(params)))
        }
        if (params.enhanceZoom && params.zoomRatio >= 12f) {
            current = replace(current, ExtremeZoomPostProcessor.enhanceBitmap(current, params.enhanceStrength))
        }
        return current
    }

    /**
     * Applies the digital part of the hybrid zoom to the captured file.
     *
     * The sensor only delivered the native optical/native-crop field of view; this crops
     * to the framed field of view. Resolution is *not* faked by upscaling — a 10× digital
     * step really does reduce the pixel count, which is documented behaviour.
     */
    private fun cropDigital(src: Bitmap, factor: Float, offsetX: Float, offsetY: Float): Bitmap {
        val w = (src.width / factor).toInt().coerceAtLeast(1)
        val h = (src.height / factor).toInt().coerceAtLeast(1)
        val maxLeft = (src.width - w).coerceAtLeast(0)
        val maxTop = (src.height - h).coerceAtLeast(0)
        val left = ((maxLeft / 2f) + offsetX.coerceIn(-1f, 1f) * (maxLeft / 2f)).toInt().coerceIn(0, maxLeft)
        val top = ((maxTop / 2f) + offsetY.coerceIn(-1f, 1f) * (maxTop / 2f)).toInt().coerceIn(0, maxTop)
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (old !== new) old.recycle()
        return new
    }

    private fun buildCaption(params: Params): String {
        val parts = ArrayList<String>(4)
        parts.add("OPEN 100x")
        if (params.zoomRatio > 1.01f) parts.add(formatZoom(params.zoomRatio))
        if (params.lensLabel.isNotBlank()) parts.add(params.lensLabel)
        if (params.iso != null) parts.add("ISO ${params.iso}")
        if (params.shutterNs != null) parts.add(formatShutter(params.shutterNs))
        parts.add(java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date()))
        return parts.joinToString("  ·  ")
    }

    private fun formatZoom(zoom: Float): String =
        if (zoom >= 10f) "${zoom.toInt()}×" else String.format(java.util.Locale.US, "%.1f×", zoom)

    private fun formatShutter(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 0.4) String.format(java.util.Locale.US, "%.1fs", seconds)
        else "1/${Math.round(1.0 / seconds)}"
    }

    private fun writeBitmap(
        resolver: android.content.ContentResolver,
        uri: Uri,
        bitmap: Bitmap,
        kind: OutputKind
    ): Boolean {
        val format = when (kind) {
            OutputKind.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (kind == OutputKind.WEBP) 92 else 95
        return try {
            resolver.openOutputStream(uri, "wt")?.use { out -> bitmap.compress(format, quality, out) } ?: return false
            resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    saveAttributes()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveFormat(resolver: android.content.ContentResolver, uri: Uri): OutputKind? {
        val mime = resolver.getType(uri) ?: return OutputKind.JPEG
        return when {
            mime.contains("dng", true) || mime.contains("raw", true) -> OutputKind.RAW
            mime.contains("webp", true) -> OutputKind.WEBP
            else -> OutputKind.JPEG
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        return name ?: "orig_${System.currentTimeMillis()}.jpg"
    }

    private enum class OutputKind { JPEG, WEBP, RAW }
}
