package com.example.zoomhundred.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size
import com.example.zoomhundred.model.VideoResolution

/** How honest the app can be about a feature on this device. */
enum class FeatureState(val label: String) {
    FULL("Supported"),
    LIMITED("Limited"),
    SIMULATED("Simulated"),
    EXPERIMENTAL("Experimental"),
    UNAVAILABLE("Unavailable");

    val usable: Boolean get() = this != UNAVAILABLE
}

/**
 * Everything we can learn about one physical/logical camera *before* binding it.
 * Populated by [CapabilityDetector] from `CameraCharacteristics` only, so the UI can be
 * built correctly without opening a camera session.
 */
data class LensCapabilities(
    val cameraId: String,
    val facing: Int,
    val label: String,
    val isMainWide: Boolean,
    val isUltraWide: Boolean,
    val isTelephoto: Boolean,
    val focalLengthsMm: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val fovHorizontalDeg: Float,
    val fovVerticalDeg: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val sensorOrientation: Int,
    val hardwareLevel: Int,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsDepthOutput: Boolean,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val minFocusDistanceDiopters: Float,
    val hasFlash: Boolean,
    val oisSupported: Boolean,
    val videoStabilizationSupported: Boolean,
    val previewStabilizationSupported: Boolean,
    val photoOutputSizes: List<Size>,
    val videoOutputSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val physicalIds: Set<String>
) {
    val maxPhotoMegapixels: Float
        get() = photoOutputSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { it / 1_000_000f } ?: 0f

    /** Widest frame rate the sensor advertises in any range (not a guarantee for a resolution). */
    val maxAdvertisedFps: Int get() = fpsRanges.maxOfOrNull { it.upper } ?: 30

    val supportedVideoResolutions: List<VideoResolution>
        get() = VideoResolution.entries.filter { res ->
            videoOutputSizes.any { it.width >= res.longEdge || it.height >= res.longEdge }
        }

    fun supportsVideo(resolution: VideoResolution, fps: Int): Boolean {
        val sizeOk = videoOutputSizes.any {
            (it.width >= resolution.longEdge && it.height >= resolution.longEdge / 2) ||
                (it.height >= resolution.longEdge)
        }
        val fpsOk = fpsRanges.any { fps >= it.lower && fps <= it.upper }
        return sizeOk && fpsOk
    }
}

data class EncoderCapabilities(
    val h264: Boolean,
    val hevc: Boolean,
    val supports4k30: Boolean,
    val supports4k60: Boolean,
    val supports1080p60: Boolean
)

/** Session-wide capabilities, independent of the selected lens. */
data class DeviceCapabilities(
    val lenses: List<LensCapabilities> = emptyList(),
    val encoders: EncoderCapabilities = EncoderCapabilities(false, false, false, false, false),
    val hasGyroscope: Boolean = false,
    val hasAccelerometer: Boolean = false,
    val sdkInt: Int = 0,
    val detectedAtMs: Long = 0L
) {
    val backLenses: List<LensCapabilities> get() = lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    val frontLenses: List<LensCapabilities> get() = lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
    val hasUltraWide: Boolean get() = backLenses.any { it.isUltraWide }
    val hasTelephoto: Boolean get() = backLenses.any { it.isTelephoto }
    val mainBackLens: LensCapabilities?
        get() = backLenses.firstOrNull { it.isMainWide } ?: backLenses.firstOrNull()

    fun lens(cameraId: String?): LensCapabilities? = lenses.firstOrNull { it.cameraId == cameraId }

    /** Prefer the widest back lens for 0.5x-style framing when one exists. */
    fun widestBackLens(): LensCapabilities? = backLenses.maxByOrNull { it.fovHorizontalDeg }

    fun supportsVideo(resolution: VideoResolution, fps: Int, lens: LensCapabilities?): FeatureState {
        val lensOk = lens?.supportsVideo(resolution, fps) == true
        val encoderOk = when {
            resolution == VideoResolution.UHD && fps >= 50 -> encoders.supports4k60
            resolution == VideoResolution.UHD -> encoders.supports4k30
            resolution == VideoResolution.FHD && fps >= 50 -> encoders.supports1080p60
            else -> true
        }
        return when {
            lensOk && encoderOk -> FeatureState.FULL
            resolution != VideoResolution.UHD && lensOk -> FeatureState.LIMITED
            else -> FeatureState.UNAVAILABLE
        }
    }
}

/**
 * Single place that turns hardware facts into UI truth. Every feature the camera screen
 * renders asks this object — never a hard-coded list — so nothing is advertised that the
 * device cannot actually do.
 */
/**
 * CameraX exposes RAW output through `ImageCapture.Builder.setOutputFormat(int)`. The
 * constant is not present in every artifact this project can be built against, so the
 * call is resolved reflectively and the capability layer asks the same question. If the
 * API is missing, RAW is genuinely unavailable and the UI says so.
 */
object CameraXRawSupport {

    val available: Boolean by lazy {
        runCatching {
            androidx.camera.core.ImageCapture.Builder::class.java
                .getMethod("setOutputFormat", Int::class.javaPrimitiveType)
            Class.forName("androidx.camera.core.ImageCapture").getField("OUTPUT_FORMAT_RAW")
        }.isSuccess
    }

    fun enableRawOutput(builder: androidx.camera.core.ImageCapture.Builder): Boolean = runCatching {
        if (!available) return false
        val method = builder.javaClass.getMethod("setOutputFormat", Int::class.javaPrimitiveType)
        val constant = Class.forName("androidx.camera.core.ImageCapture")
            .getField("OUTPUT_FORMAT_RAW").getInt(null)
        method.invoke(builder, constant)
        true
    }.getOrDefault(false)
}

object FeatureGate {

    fun raw(lens: LensCapabilities?): FeatureState = when {
        lens == null -> FeatureState.UNAVAILABLE
        lens.supportsRaw && CameraXRawSupport.available -> FeatureState.FULL
        else -> FeatureState.UNAVAILABLE
    }

    fun manualExposure(lens: LensCapabilities?): FeatureState = when {
        lens == null -> FeatureState.UNAVAILABLE
        lens.supportsManualSensor && lens.exposureTimeRange != null && lens.isoRange != null -> FeatureState.FULL
        else -> FeatureState.UNAVAILABLE
    }

    fun manualFocus(lens: LensCapabilities?): FeatureState = when {
        lens == null -> FeatureState.UNAVAILABLE
        lens.minFocusDistanceDiopters > 0f -> FeatureState.FULL
        else -> FeatureState.UNAVAILABLE
    }

    /** Multi-frame HDR only needs AE compensation, which is effectively universal. */
    fun hdr(lens: LensCapabilities?): FeatureState = if (lens == null) FeatureState.UNAVAILABLE else FeatureState.FULL

    fun night(lens: LensCapabilities?): FeatureState = if (lens == null) FeatureState.UNAVAILABLE else FeatureState.FULL

    fun astro(lens: LensCapabilities?): FeatureState = when {
        lens == null -> FeatureState.UNAVAILABLE
        lens.supportsManualSensor &&
            (lens.exposureTimeRange?.upper ?: 0L) >= 500_000_000L -> FeatureState.FULL
        else -> FeatureState.LIMITED // stacking still works, exposure cannot be extended
    }

    fun portrait(): FeatureState = FeatureState.SIMULATED // software bokeh, no depth input

    fun panorama(): FeatureState = FeatureState.EXPERIMENTAL

    fun lightTrails(): FeatureState = FeatureState.FULL

    fun fisheye(): FeatureState = FeatureState.SIMULATED

    fun slowMotion(): FeatureState = FeatureState.UNAVAILABLE // no high-speed session in CameraX

    fun timeLapse(): FeatureState = FeatureState.FULL

    fun timeLapseVideo(): FeatureState = FeatureState.EXPERIMENTAL

    fun ois(lens: LensCapabilities?): FeatureState = when {
        lens?.oisSupported == true -> FeatureState.FULL
        lens == null -> FeatureState.UNAVAILABLE
        else -> FeatureState.UNAVAILABLE
    }

    fun eis(lens: LensCapabilities?): FeatureState = when {
        lens == null -> FeatureState.UNAVAILABLE
        lens.previewStabilizationSupported -> FeatureState.FULL
        lens.videoStabilizationSupported -> FeatureState.LIMITED // video-only, crops
        else -> FeatureState.UNAVAILABLE
    }

    fun stabilization(lens: LensCapabilities?, mode: com.example.zoomhundred.model.StabilizationMode): FeatureState =
        when (mode) {
            com.example.zoomhundred.model.StabilizationMode.OFF -> FeatureState.FULL
            com.example.zoomhundred.model.StabilizationMode.OPTICAL -> ois(lens)
            com.example.zoomhundred.model.StabilizationMode.ELECTRONIC -> eis(lens)
            com.example.zoomhundred.model.StabilizationMode.AUTO -> when {
                ois(lens).usable || eis(lens).usable -> FeatureState.FULL
                else -> FeatureState.UNAVAILABLE
            }
        }

    fun ultraWide(caps: DeviceCapabilities): FeatureState =
        if (caps.hasUltraWide) FeatureState.FULL else FeatureState.UNAVAILABLE

    fun telephoto(caps: DeviceCapabilities): FeatureState =
        if (caps.hasTelephoto) FeatureState.FULL else FeatureState.SIMULATED // digital only

    fun gyroStability(caps: DeviceCapabilities): FeatureState =
        if (caps.hasGyroscope) FeatureState.FULL else FeatureState.UNAVAILABLE
}
