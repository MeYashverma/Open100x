package com.example.zoomhundred.model

import android.graphics.Rect

/**
 * Photographic aspect ratios.
 *
 * The sensor is 4:3 on most phones, so any other ratio is produced by cropping the
 * captured frame (documented in [FEATURE_COMPATIBILITY.md]). [FULL] keeps the whole
 * sensor output. The preview draws a masked guide for the same rectangle, so what you
 * frame is what you get.
 */
enum class AspectRatio(
    val label: String,
    val value: Float,
    val isPortraitRatio: Boolean = false
) {
    FULL("Full", 0f),
    R4_3("4:3", 4f / 3f),
    R3_2("3:2", 3f / 2f),
    R16_9("16:9", 16f / 9f),
    R1_1("1:1", 1f),
    R5_4("5:4", 5f / 4f),
    R4_5("4:5", 4f / 5f, isPortraitRatio = true),
    R3_4("3:4", 3f / 4f, isPortraitRatio = true),
    R9_16("9:16", 9f / 16f, isPortraitRatio = true),
    R239("2.39:1", 2.39f);

    /**
     * Largest centred rectangle of this ratio that fits inside [width] x [height].
     * Returns a full-frame rect for [FULL] or invalid geometry.
     */
    fun cropRect(width: Int, height: Int): Rect {
        if (this == FULL || width <= 0 || height <= 0) return Rect(0, 0, width, height)
        val target = value
        val source = width.toFloat() / height.toFloat()
        return if (source > target) {
            // Source is wider: crop horizontally.
            val w = (height * target).toInt().coerceAtLeast(1)
            val left = (width - w) / 2
            Rect(left, 0, left + w, height)
        } else {
            val h = (width / target).toInt().coerceAtLeast(1)
            val top = (height - h) / 2
            Rect(0, top, width, top + h)
        }
    }

    /** Framing window inside the preview, in 0..1 view coordinates. */
    fun guideFraction(previewAspect: Float): FloatArray {
        if (this == FULL || previewAspect <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        return if (previewAspect > value) {
            val w = (value / previewAspect).coerceIn(0f, 1f)
            floatArrayOf((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = (previewAspect / value).coerceIn(0f, 1f)
            floatArrayOf(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
    }

    companion object {
        fun fromLabel(label: String?): AspectRatio =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: FULL

        /** Ratios worth offering as quick chips; the rest live in the sheet. */
        val QUICK = listOf(FULL, R4_3, R3_2, R16_9, R1_1)
    }
}

/** Photo file format actually written by the capture pipeline. */
enum class PhotoFormat(val label: String, val mime: String) {
    JPEG("JPEG", "image/jpeg"),
    WEBP("WebP", "image/webp"),
    RAW("RAW / DNG", "image/x-adobe-dng"),
    RAW_JPEG("RAW + JPEG", "image/jpeg")
}

enum class FlashMode { OFF, AUTO, ON, TORCH }

/** Legacy persist format for the zoom post-processor (kept for compatibility). */
enum class OutputFormat { JPEG, WEBP }

enum class VolumeKeyAction { ZOOM, SHUTTER, OFF }

enum class StabilizationMode { OFF, OPTICAL, ELECTRONIC, AUTO }

/** Composition guides. Drawn by overlay/GridOverlayView. */
enum class GridMode {
    NONE, THIRDS, GOLDEN, SPIRAL, CROSS, DIAGONAL, SQUARE
}

/** Software colour profiles applied by imaging/ColorPipeline. */
enum class ColorProfile(val label: String) {
    NEUTRAL("Neutral"),
    VIVID("Vivid"),
    CINEMA("Cinema"),
    WARM_FILM("Warm film"),
    COOL_CHROME("Cool chrome"),
    FADED("Faded print"),
    MONO("Mono"),
    HIGH_CONTRAST("High contrast"),
    INFRARED("Mono IR")
}

/** Decorative frames rendered by imaging/FrameRenderer. */
enum class FrameStyle(val label: String) {
    NONE("None"),
    THIN_WHITE("Thin white"),
    THIN_BLACK("Thin black"),
    WIDE_WHITE("Wide white"),
    FILM_35MM("35mm film"),
    INSTANT("Instant film"),
    CINEMA("Cinematic bars"),
    DATE_STAMP("Date stamp"),
    CAPTION("Camera info")
}

/** Recording container/codec preference. Both are H.264 today; HEVC is gated. */
enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", "video/avc"),
    HEVC("HEVC", "video/hevc")
}

/** Named video resolution buckets, mapped to CameraX Quality values. */
enum class VideoResolution(val label: String, val longEdge: Int) {
    HD("720p", 1280),
    FHD("1080p", 1920),
    UHD("4K", 3840)
}

enum class FocusMode { AUTO, MANUAL }

enum class WbMode { AUTO, DAYLIGHT, CLOUDY, SHADE, TUNGSTEN, FLUORESCENT, CUSTOM }

internal fun clampRatio(r: Float, minRatio: Float, maxRatio: Float) = r.coerceIn(minRatio, maxRatio)
