package com.example.zoomhundred.camera

import com.example.zoomhundred.model.ZoomPreset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure zoom arithmetic for the 100× hybrid system.
 *
 * The total zoom the user asks for is split into
 *   * a **native** ratio handed to `CameraControl.setZoomRatio` (real sensor crop /
 *     optical switch inside the logical camera), and
 *   * a **digital** factor implemented by scaling the preview and cropping the captured
 *     file (the part Open 100x is known for).
 *
 * Keeping this in one place means the UI, the capture pipeline and EXIF all agree on
 * what "37.4×" actually means, and it is unit-tested without a camera.
 */
class ZoomEngine(
    val minNative: Float = 1f,
    val maxNative: Float = 1f,
    val maxTotal: Float = 100f
) {

    data class Mapping(val total: Float, val native: Float, val digital: Float) {
        val isDigital: Boolean get() = digital > 1.001f
    }

    val maxZoom: Float get() = min(maxTotal, max(maxNative, 1f) * MAX_DIGITAL_SPAN)

    fun clamp(total: Float): Float = total.coerceIn(minNative.coerceAtLeast(0.5f), maxZoom)

    fun map(total: Float): Mapping {
        val clamped = clamp(total)
        val native = clamped.coerceIn(minNative, maxNative.coerceAtLeast(minNative))
        val digital = max(1f, clamped / native)
        return Mapping(clamped, native, digital)
    }

    /**
     * Zoom change per vertical drag pixel at the current zoom. Long-range zoom needs
     * finer control near the long end, so sensitivity grows with magnification.
     */
    fun dragSensitivity(total: Float): Float = when {
        total < 2f -> 0.0016f
        total < 10f -> 0.010f
        total < 30f -> 0.030f
        total < 60f -> 0.070f
        else -> 0.130f
    }

    /**
     * Presets that make sense for *this lens*: never wider than the sensor can show and
     * never longer than the engine can reach.
     *
     * An ultra-wide module is a separate physical camera here (the lens button switches to
     * it), so a wide lens must not advertise 0.5× — that would be a dead control. When the
     * current lens really is the ultra-wide, `minNative` is already 0.5 and the wide stop
     * appears naturally.
     */
    fun presets(
        available: List<Float>,
        @Suppress("UNUSED_PARAMETER") hasUltraWide: Boolean,
        @Suppress("UNUSED_PARAMETER") ultraWideMinZoom: Float = 1f,
        telephotoMinZoom: Float? = null
    ): List<ZoomPreset> {
        val floor = minNative.coerceAtLeast(0.5f)
        val cleaned = available
            .filter { it > 0f }
            .map { it.coerceAtMost(maxZoom) }
            .filter { it >= floor - 0.001f }
            .distinctBy { (it * 10).roundToInt() }
            .sorted()

        val list = cleaned.map { ZoomPreset(formatLabel(it), it) }.toMutableList()
        if (list.none { abs(it.ratio - 1f) < 0.01f && floor <= 1f }) list.add(0, ZoomPreset("1×", 1f))
        if (list.none { abs(it.ratio - maxZoom) < 0.01f }) list.add(ZoomPreset(formatLabel(maxZoom), maxZoom))
        if (telephotoMinZoom != null && telephotoMinZoom > 1.2f &&
            list.none { abs(it.ratio - telephotoMinZoom) < 0.05f }
        ) {
            val insertAt = list.indexOfFirst { it.ratio > telephotoMinZoom }.let { if (it < 0) list.size else it }
            list.add(insertAt, ZoomPreset(formatLabel(telephotoMinZoom), telephotoMinZoom))
        }
        return list.filter { it.ratio >= floor - 0.001f }
            .distinctBy { it.label }
            .sortedBy { it.ratio }
    }

    /**
     * Maps a slider position (0..1, logarithmic) to a zoom ratio and back. The log curve
     * makes the 1×–10× region usable while still reaching 100× at the end of the track.
     */
    fun sliderToZoom(fraction: Float): Float {
        val lo = ln(minNative.coerceAtLeast(0.5f))
        val hi = ln(maxZoom.coerceAtLeast(1f))
        val f = fraction.coerceIn(0f, 1f)
        return kotlin.math.exp(lo + (hi - lo) * f).coerceIn(minNative, maxZoom)
    }

    fun zoomToSlider(total: Float): Float {
        val lo = ln(minNative.coerceAtLeast(0.5f))
        val hi = ln(maxZoom.coerceAtLeast(1f))
        if (hi - lo <= 0f) return 0f
        return ((ln(clamp(total)) - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    companion object {

        /** Absolute distance between two zoom stops on the log scale users perceive. */
        fun logDistance(a: Float, b: Float): Float =
            abs(ln(a.coerceAtLeast(0.01f)) - ln(b.coerceAtLeast(0.01f)))

        /**
         * How far the digital crop may extend past the sensor's native ratio. The app's
         * promise is a 100× hybrid range, so this stays wide enough to reach 100× on any
         * lens; the cost is real resolution loss, which is why the crop factor is written
         * into the saved file and shown in the HUD instead of being hidden behind an
         * upscale.
         */
        const val MAX_DIGITAL_SPAN = 100f

        val DEFAULT_STOPS = listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)

        fun formatLabel(ratio: Float): String = when {
            ratio < 1f -> String.format(java.util.Locale.US, "%.1f×", ratio)
            ratio < 10f && ratio % 1f != 0f -> String.format(java.util.Locale.US, "%.1f×", ratio)
            else -> "${ratio.roundToInt()}×"
        }

        fun formatReadout(ratio: Float): String = when {
            ratio < 10f -> String.format(java.util.Locale.US, "%.1f×", ratio)
            ratio < 100f -> String.format(java.util.Locale.US, "%.1f×", ratio)
            else -> String.format(java.util.Locale.US, "%.0f×", ratio)
        }

        /** Approximate 35 mm-equivalent focal length for the HUD. */
        fun focalLength35mm(ratio: Float, baseEquiv: Float = 24f): Float = baseEquiv * ratio

        fun pow(base: Float, exponent: Float): Float = base.toDouble().pow(exponent.toDouble()).toFloat()
    }
}
