package com.example.zoomhundred.imaging

import android.graphics.Bitmap
import com.example.zoomhundred.model.ColorProfile
import kotlin.math.pow

/**
 * A single, explicit colour-grade description. Every profile is a set of numbers, not a
 * hidden LUT, so results are reproducible and debuggable.
 */
data class ColorGrade(
    val exposureEv: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,   // -1 cool … +1 warm
    val tint: Float = 0f,          // -1 green … +1 magenta
    val shadows: Float = 0f,       // -1 crush … +1 lift
    val highlights: Float = 0f,    // -1 recover … +1 roll off
    val fade: Float = 0f,          // 0..1 lifted blacks (print look)
    val grain: Float = 0f,         // 0..1
    val vignette: Float = 0f,      // 0..1
    val mono: Boolean = false,
    val monoMixR: Float = 0.299f,
    val monoMixG: Float = 0.587f,
    val monoMixB: Float = 0.114f
) {
    val isIdentity: Boolean
        get() = exposureEv == 0f && contrast == 1f && saturation == 1f && temperature == 0f &&
            tint == 0f && shadows == 0f && highlights == 0f && fade == 0f && grain == 0f &&
            vignette == 0f && !mono
}

/**
 * CPU colour pipeline used for every captured image that gets a profile, frame or LUT.
 *
 * Works on ARGB_8888 pixels in one pass, then applies an optional `.cube` LUT with
 * trilinear interpolation and an intensity blend. Runs on a worker executor; callers
 * keep the UI responsive with progress callbacks.
 */
object ColorPipeline {

    fun gradeFor(profile: ColorProfile): ColorGrade = when (profile) {
        ColorProfile.NEUTRAL -> ColorGrade()
        ColorProfile.VIVID -> ColorGrade(contrast = 1.10f, saturation = 1.28f, shadows = 0.06f, highlights = -0.05f)
        ColorProfile.CINEMA -> ColorGrade(contrast = 1.12f, saturation = 0.86f, temperature = -0.10f,
            shadows = -0.10f, highlights = -0.18f, fade = 0.10f)
        ColorProfile.WARM_FILM -> ColorGrade(contrast = 1.05f, saturation = 0.92f, temperature = 0.22f,
            shadows = 0.08f, highlights = -0.10f, grain = 0.14f, fade = 0.06f)
        ColorProfile.COOL_CHROME -> ColorGrade(contrast = 1.08f, saturation = 0.95f, temperature = -0.18f,
            tint = 0.04f, shadows = 0.04f, highlights = -0.06f)
        ColorProfile.FADED -> ColorGrade(contrast = 0.92f, saturation = 0.78f, shadows = 0.20f,
            highlights = -0.04f, fade = 0.26f, grain = 0.10f)
        ColorProfile.MONO -> ColorGrade(contrast = 1.14f, mono = true, grain = 0.08f, vignette = 0.08f)
        ColorProfile.HIGH_CONTRAST -> ColorGrade(contrast = 1.32f, saturation = 1.06f, shadows = -0.16f,
            highlights = -0.10f)
        ColorProfile.INFRARED -> ColorGrade(contrast = 1.20f, mono = true,
            monoMixR = 0.35f, monoMixG = 0.85f, monoMixB = -0.20f, grain = 0.12f)
    }

    /**
     * @param src mutated-free input; the returned bitmap is new.
     * @param lut optional 3D/1D LUT applied after the grade.
     * @param lutIntensity 0..1 blend between graded and LUT result.
     */
    fun apply(
        src: Bitmap,
        grade: ColorGrade,
        lut: CubeLut? = null,
        lutIntensity: Float = 1f,
        progress: ((Float) -> Unit)? = null
    ): Bitmap {
        if (grade.isIdentity && (lut == null || lutIntensity <= 0f)) {
            return src.copy(Bitmap.Config.ARGB_8888, true)
        }
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val gain = 2f.pow(grade.exposureEv)
        val rGain = gain * (1f + 0.30f * grade.temperature + 0.06f * grade.tint)
        val gGain = gain * (1f - 0.10f * grade.tint)
        val bGain = gain * (1f - 0.30f * grade.temperature + 0.06f * grade.tint)
        val useLut = lut != null && lutIntensity > 0f
        val lutOut = FloatArray(3)
        val lutBlend = lutIntensity.coerceIn(0f, 1f)
        val grainAmount = (grade.grain * 26f)
        val contrast = grade.contrast
        val saturation = grade.saturation
        val shadowLift = grade.shadows * 0.22f
        val highlightPull = grade.highlights * 0.22f
        val fade = grade.fade
        val vignette = grade.vignette
        val cx = width / 2f
        val cy = height / 2f
        val maxDist = kotlin.math.sqrt(cx * cx + cy * cy)
        var seed = 0x9E3779B9.toInt()

        for (y in 0 until height) {
            val row = y * width
            val dy = (y - cy)
            for (x in 0 until width) {
                val p = pixels[row + x]
                var r = ((p shr 16) and 0xFF) / 255f * rGain
                var g = ((p shr 8) and 0xFF) / 255f * gGain
                var b = (p and 0xFF) / 255f * bGain

                // Contrast around middle grey.
                r = (r - 0.5f) * contrast + 0.5f
                g = (g - 0.5f) * contrast + 0.5f
                b = (b - 0.5f) * contrast + 0.5f

                // Shadow lift / highlight roll-off (smooth, luminance weighted).
                val luma0 = 0.299f * r + 0.587f * g + 0.114f * b
                if (shadowLift != 0f) {
                    val w = ((0.55f - luma0).coerceIn(0f, 1f)) / 0.55f
                    val add = shadowLift * w * w
                    r += add; g += add; b += add
                }
                if (highlightPull != 0f) {
                    val w = ((luma0 - 0.55f).coerceIn(0f, 1f)) / 0.45f
                    val add = highlightPull * w * w
                    r += add; g += add; b += add
                }

                // Saturation around luminance.
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                r = luma + (r - luma) * saturation
                g = luma + (g - luma) * saturation
                b = luma + (b - luma) * saturation

                if (grade.mono) {
                    val m = grade.monoMixR * r + grade.monoMixG * g + grade.monoMixB * b
                    r = m; g = m; b = m
                }

                if (fade > 0f) {
                    r = r * (1f - fade * 0.35f) + fade * 0.16f
                    g = g * (1f - fade * 0.35f) + fade * 0.15f
                    b = b * (1f - fade * 0.33f) + fade * 0.18f
                }

                if (useLut) {
                    lut!!.map(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), lutOut)
                    r += (lutOut[0] - r) * lutBlend
                    g += (lutOut[1] - g) * lutBlend
                    b += (lutOut[2] - b) * lutBlend
                }

                if (vignette > 0f) {
                    val dx = x - cx
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy) / maxDist
                    val v = (1f - vignette * 0.55f * dist * dist).coerceIn(0f, 1f)
                    r *= v; g *= v; b *= v
                }

                if (grainAmount > 0f) {
                    // Deterministic hash noise keeps the result reproducible.
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val n = ((seed and 0xFF) - 128) / 128f * grainAmount / 255f
                    r += n; g += n; b += n
                }

                pixels[row + x] = (0xFF shl 24) or
                    ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
                    ((b * 255f).toInt().coerceIn(0, 255))
            }
            if (y % 256 == 0) progress?.invoke(y.toFloat() / height)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        progress?.invoke(1f)
        return out
    }

    /** Convenience for the capture pipeline: profile + optional user LUT. */
    fun applyProfile(
        src: Bitmap,
        profile: ColorProfile,
        lut: CubeLut?,
        lutIntensityPercent: Int,
        progress: ((Float) -> Unit)? = null
    ): Bitmap = apply(
        src = src,
        grade = gradeFor(profile),
        lut = lut,
        lutIntensity = lutIntensityPercent.coerceIn(0, 100) / 100f,
        progress = progress
    )
}
