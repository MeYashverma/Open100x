package com.example.zoomhundred.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import com.example.zoomhundred.model.FlashMode
import com.example.zoomhundred.model.FocusMode
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.StabilizationMode
import com.example.zoomhundred.model.WbMode
import kotlin.math.ln
import kotlin.math.pow

/**
 * Translates [ProSettings] + lens characteristics into real Camera2 request options.
 *
 * Two channels are used deliberately:
 *  * **Use-case interop** (`Camera2Interop.Extender`) for values that must also apply to
 *    the still capture request — manual ISO/shutter, AWB mode, OIS.
 *  * **Session options** (`Camera2CameraControl.setCaptureRequestOptions`) for live
 *    values the user drags — focus distance, EV, locks, fps range, custom WB gains.
 *
 * Everything is clamped to the sensor's advertised ranges; nothing is invented.
 */
object CameraControls {

    fun previewBuilder(
        pro: ProSettings,
        lens: LensCapabilities,
        rotation: Int,
        stabilization: StabilizationMode,
        previewStabilizationSupported: Boolean
    ): Preview.Builder {
        val builder = Preview.Builder().setTargetRotation(rotation)
        val ext = Camera2Interop.Extender(builder)
        applyExposureToInterop(ext, pro, lens)
        applyAwbToInterop(ext, pro)
        applyOisToInterop(ext, stabilization, lens)
        if (stabilization != StabilizationMode.OFF && previewStabilizationSupported) {
            // Preview stabilization (API 33+) keeps preview and video FoV consistent.
            runCatching { builder.setPreviewStabilizationEnabled(true) }
        }
        return builder
    }

    fun imageCaptureBuilder(
        pro: ProSettings,
        lens: LensCapabilities,
        rotation: Int,
        flash: FlashMode,
        raw: Boolean,
        stabilization: StabilizationMode
    ): ImageCapture.Builder {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .setFlashMode(
                when (flash) {
                    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    FlashMode.TORCH, FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                }
            )
        if (raw) {
            runCatching { builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW) }
        }
        val ext = Camera2Interop.Extender(builder)
        applyExposureToInterop(ext, pro, lens)
        applyAwbToInterop(ext, pro)
        applyOisToInterop(ext, stabilization, lens)
        applyFocusToInterop(ext, pro, lens)
        return builder
    }

    /**
     * Live session options. [fpsRange] is only meaningful for video/preview and is
     * honoured on a best-effort basis by the HAL (Android treats it as a target range).
     */
    fun sessionOptions(
        pro: ProSettings,
        lens: LensCapabilities,
        fpsRange: Range<Int>? = null,
        stabilization: StabilizationMode = StabilizationMode.AUTO
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()

        // ── Exposure ─────────────────────────────────────────────────────────
        if (pro.manualMode && lens.supportsManualSensor) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampIso(pro.isoValue(), lens))
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(pro.shutterNanos(), lens))
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
        } else if (pro.exposureLocked) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // ── Focus ────────────────────────────────────────────────────────────
        applyFocusToSession(builder, pro, lens)

        // ── White balance ────────────────────────────────────────────────────
        applyAwbToSession(builder, pro, lens)

        // ── Stabilization ────────────────────────────────────────────────────
        if (stabilization == StabilizationMode.OPTICAL && lens.oisSupported) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
        if (stabilization == StabilizationMode.ELECTRONIC) {
            val mode = when {
                lens.previewStabilizationSupported ->
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                lens.videoStabilizationSupported -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
        }

        if (fpsRange != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
        return builder.build()
    }

    private fun applyExposureToInterop(
        ext: Camera2Interop.Extender<*>,
        pro: ProSettings,
        lens: LensCapabilities
    ) {
        if (pro.manualMode && lens.supportsManualSensor) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampIso(pro.isoValue(), lens))
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(pro.shutterNanos(), lens))
        } else {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
    }

    private fun applyAwbToInterop(ext: Camera2Interop.Extender<*>, pro: ProSettings) {
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbModeFor(pro.wbMode))
    }

    private fun applyAwbToSession(
        builder: CaptureRequestOptions.Builder,
        pro: ProSettings,
        lens: LensCapabilities
    ) {
        when {
            pro.wbMode == WbMode.CUSTOM -> {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    kelvinToGains(pro.wbKelvin)
                )
            }
            pro.wbLocked -> {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbModeFor(pro.wbMode))
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            else -> {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbModeFor(pro.wbMode))
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
        }
    }

    private fun applyFocusToInterop(
        ext: Camera2Interop.Extender<*>,
        pro: ProSettings,
        lens: LensCapabilities
    ) {
        if (pro.focusMode == FocusMode.MANUAL && lens.minFocusDistanceDiopters > 0f) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            ext.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDiopters(pro.focusPosition, lens)
            )
        } else {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    private fun applyFocusToSession(
        builder: CaptureRequestOptions.Builder,
        pro: ProSettings,
        lens: LensCapabilities
    ) {
        when {
            pro.focusMode == FocusMode.MANUAL && lens.minFocusDistanceDiopters > 0f -> {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDiopters(pro.focusPosition, lens)
                )
            }
            pro.focusLocked -> {
                // Approximate AF lock: single-shot AF stops hunting and holds.
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
            else -> builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    private fun applyOisToInterop(
        ext: Camera2Interop.Extender<*>,
        stabilization: StabilizationMode,
        lens: LensCapabilities
    ) {
        val mode = when {
            stabilization == StabilizationMode.OPTICAL && lens.oisSupported ->
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            stabilization == StabilizationMode.OFF -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            else -> null // AUTO: leave it to the HAL unless the user asked for OIS
        }
        if (mode != null) ext.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
    }

    private fun awbModeFor(mode: WbMode): Int = when (mode) {
        WbMode.AUTO, WbMode.CUSTOM -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WbMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WbMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WbMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        WbMode.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WbMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    }

    fun clampIso(iso: Int, lens: LensCapabilities): Int {
        val range = lens.isoRange ?: return iso.coerceIn(25, 12800)
        return iso.coerceIn(range.lower, range.upper)
    }

    fun clampExposure(nanos: Long, lens: LensCapabilities): Long {
        val range = lens.exposureTimeRange ?: return nanos.coerceIn(100_000L, 1_000_000_000L)
        return nanos.coerceIn(range.lower, range.upper)
    }

    /** 0 → infinity, 1 → closest focus distance the lens reports. */
    fun focusDiopters(position: Float, lens: LensCapabilities): Float =
        (lens.minFocusDistanceDiopters * position.coerceIn(0f, 1f))

    /**
     * Approximate colour-temperature control. Android has no "set kelvin" key, so we
     * derive relative R/B white-balance gains from the Planckian approximation and feed
     * them through `COLOR_CORRECTION_GAINS` — a genuine manual white balance, not a
     * preview tint.
     */
    fun kelvinToGains(kelvin: Int): RggbChannelVector {
        val t = kelvin.coerceIn(1000, 15000) / 100f
        val r: Float
        val g: Float
        val b: Float
        if (t <= 66f) {
            r = 255f
            g = (99.4708025861f * ln(t) - 161.1195681661f).coerceAtLeast(1f)
            b = if (t <= 19f) 1f else (138.5177312231f * ln(t - 10f) - 305.0447927307f).coerceAtLeast(1f)
        } else {
            r = (329.698727446f * (t - 60f).pow(-0.1332047592f)).coerceAtLeast(1f)
            g = (288.1221695283f * (t - 60f).pow(-0.0755148492f)).coerceAtLeast(1f)
            b = 255f
        }
        val rn = (r / 255f).coerceIn(0.05f, 1f)
        val gn = (g / 255f).coerceIn(0.05f, 1f)
        val bn = (b / 255f).coerceIn(0.05f, 1f)
        val gainR = (gn / rn).coerceIn(0.25f, 4f)
        val gainB = (gn / bn).coerceIn(0.25f, 4f)
        return RggbChannelVector(gainR, 1f, 1f, gainB)
    }

    /**
     * Picks the AE target fps range closest to [targetFps] among the lens's advertised
     * ranges; returns null when the lens does not advertise anything usable.
     */
    fun fpsRangeFor(lens: LensCapabilities, targetFps: Int): Range<Int>? {
        val ranges = lens.fpsRanges
        if (ranges.isEmpty()) return null
        val exact = ranges.filter { it.lower <= targetFps && it.upper >= targetFps }
        val best = exact.minByOrNull { it.upper - it.lower } ?: ranges.maxByOrNull { it.upper }
        return best
    }
}
