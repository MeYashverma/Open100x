package com.example.zoomhundred.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.VideoResolution
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/**
 * Reads every camera/encoder capability we can obtain without opening a session.
 *
 * All lookups are defensive: OEM camera HALs occasionally throw from
 * `getCameraCharacteristics`, and `MediaCodecInfo` capability queries can reject sizes
 * with `IllegalArgumentException`. A failure downgrades a single field, never the app.
 */
object CapabilityDetector {

    private const val TAG = "CapabilityDetector"

    fun detect(context: Context): DeviceCapabilities {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) return emptyCapabilities()
        // Whole-body guard: OEM camera HALs throw from getCameraCharacteristics,
        // from physicalCameraIds and even from cameraIdList when the camera service
        // is busy. Losing a capability must never take the app down.
        return runCatching { detectWith(manager, context) }.getOrElse { error ->
            Log.w(TAG, "capability detection failed: ${error.message}", error)
            emptyCapabilities()
        }
    }

    private fun emptyCapabilities(): DeviceCapabilities =
        DeviceCapabilities(sdkInt = Build.VERSION.SDK_INT, detectedAtMs = System.currentTimeMillis())

    private fun detectWith(manager: CameraManager, context: Context): DeviceCapabilities {
        val raw = ArrayList<RawLens>()
        val ids = runCatching { manager.cameraIdList?.toList() ?: emptyList() }.getOrElse { error ->
            Log.w(TAG, "camera id list unavailable: ${error.message}")
            emptyList()
        }
        ids.forEach { id ->
            runCatching { readLens(manager, id) }
                .onSuccess { if (it != null) raw.add(it) }
                .onFailure { Log.w(TAG, "lens $id unreadable: ${it.message}") }
        }

        val backRaw = raw.filter { it.characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val widestBackFov = backRaw.maxOfOrNull { it.fovHorizontalDeg } ?: 0f
        val backSorted = backRaw.sortedByDescending { it.fovHorizontalDeg }
        val ultraWideId = backSorted.firstOrNull()
            ?.takeIf { backRaw.size > 1 && it.fovHorizontalDeg >= 78f }?.id
        val mainBackId = backSorted.firstOrNull { it.id != ultraWideId }?.id ?: ultraWideId

        val lenses = raw.map { lens ->
            val facing = lens.characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
            val isUltraWide = isBack && lens.id == ultraWideId
            val isMainWide = isBack && lens.id == mainBackId
            val isTelephoto = isBack && !isUltraWide && !isMainWide &&
                lens.fovHorizontalDeg < widestBackFov - 12f

            val zoomFactor = if (widestBackFov > 0f && lens.fovHorizontalDeg > 0f) {
                tan(Math.toRadians(widestBackFov / 2.0)) / tan(Math.toRadians(lens.fovHorizontalDeg / 2.0))
            } else 1.0

            val label = when {
                facing == CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
                isUltraWide -> "Ultra wide"
                isTelephoto -> "Telephoto " + formatZoomFactor(zoomFactor)
                else -> "Wide"
            }

            LensCapabilities(
                cameraId = lens.id,
                facing = facing,
                label = label,
                isMainWide = isMainWide,
                isUltraWide = isUltraWide,
                isTelephoto = isTelephoto,
                focalLengthsMm = lens.focalLengths.toList(),
                sensorWidthMm = lens.sensorWidthMm,
                sensorHeightMm = lens.sensorHeightMm,
                fovHorizontalDeg = lens.fovHorizontalDeg,
                fovVerticalDeg = lens.fovVerticalDeg,
                minZoomRatio = 1f,
                maxZoomRatio = lens.maxDigitalZoom.coerceAtLeast(1f),
                sensorOrientation = lens.sensorOrientation,
                hardwareLevel = lens.hardwareLevel,
                supportsRaw = lens.raw,
                supportsManualSensor = lens.manualSensor,
                supportsManualPostProcessing = lens.manualPostProcessing,
                supportsDepthOutput = lens.depth,
                isoRange = lens.isoRange,
                exposureTimeRange = lens.exposureTimeRange,
                minFocusDistanceDiopters = lens.minFocusDistance,
                hasFlash = lens.hasFlash,
                oisSupported = lens.oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
                videoStabilizationSupported = lens.videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
                previewStabilizationSupported = lens.videoStabModes.contains(
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                ),
                photoOutputSizes = lens.photoSizes,
                videoOutputSizes = lens.videoSizes,
                fpsRanges = lens.fpsRanges,
                physicalIds = lens.physicalIds
            )
        }

        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return DeviceCapabilities(
            lenses = lenses,
            encoders = probeEncoders(),
            hasGyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            hasAccelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            sdkInt = Build.VERSION.SDK_INT,
            detectedAtMs = System.currentTimeMillis()
        )
    }

    private fun formatZoomFactor(factor: Double): String {
        val rounded = Math.round(factor * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}×" else "$rounded×"
    }

    private fun readLens(manager: CameraManager, id: String): RawLens? {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val widest = focal.minOrNull() ?: 0f
        val fovH = if (sensorSize != null && widest > 0f) {
            (2.0 * atan(sensorSize.width / (2.0 * widest)) * 180.0 / PI).toFloat()
        } else 0f
        val fovV = if (sensorSize != null && widest > 0f) {
            (2.0 * atan(sensorSize.height / (2.0 * widest)) * 180.0 / PI).toFloat()
        } else 0f
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

        val photoSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.sortedByDescending {
            it.width.toLong() * it.height
        } ?: emptyList()
        val videoSizes = runCatching { map?.getOutputSizes(MediaRecorder::class.java)?.toList() }
            .getOrNull()
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: emptyList()

        return RawLens(
            id = id,
            characteristics = c,
            focalLengths = focal,
            sensorWidthMm = sensorSize?.width ?: 0f,
            sensorHeightMm = sensorSize?.height ?: 0f,
            fovHorizontalDeg = fovH,
            fovVerticalDeg = fovV,
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualPostProcessing = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            depth = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf(),
            videoStabModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf(),
            photoSizes = photoSizes,
            videoSizes = videoSizes,
            fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
            physicalIds = c.physicalCameraIds
        )
    }

    private fun probeEncoders(): EncoderCapabilities {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
            .filter { it.isEncoder }

        fun supports(mime: String, w: Int, h: Int, fps: Double): Boolean = infos.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                val videoCaps = info.getCapabilitiesForType(mime).videoCapabilities
                videoCaps != null && videoCaps.areSizeAndRateSupported(w, h, fps)
            }.getOrDefault(false)
        }

        return EncoderCapabilities(
            h264 = infos.any { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } },
            hevc = infos.any { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } },
            supports4k30 = supports(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160, 30.0),
            supports4k60 = supports(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160, 60.0) ||
                supports(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 60.0),
            supports1080p60 = supports(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080, 60.0)
        )
    }

    /**
     * Plain-text capability report. Contains hardware facts and app settings only:
     * no photos, no EXIF, no location (see Privacy settings).
     */
    fun buildReport(
        context: Context,
        caps: DeviceCapabilities,
        settings: AppSettings,
        selectedLensId: String? = null
    ): String = buildString {
        appendLine("Open 100x — camera capability report")
        appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("APP")
        appendLine("  version=" + runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("unknown"))
        appendLine("  package=${context.packageName}")
        appendLine()
        appendLine("DEVICE")
        appendLine("  manufacturer=${Build.MANUFACTURER}")
        appendLine("  model=${Build.MODEL}")
        appendLine("  device=${Build.DEVICE}")
        appendLine("  sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        appendLine("  gyroscope=${caps.hasGyroscope} accelerometer=${caps.hasAccelerometer}")
        appendLine()
        appendLine("ENCODERS")
        appendLine("  h264=${caps.encoders.h264} hevc=${caps.encoders.hevc}")
        appendLine("  1080p60=${caps.encoders.supports1080p60} 4k30=${caps.encoders.supports4k30} 4k60=${caps.encoders.supports4k60}")
        appendLine()
        appendLine("LENSES (${caps.lenses.size})")
        caps.lenses.forEach { lens ->
            val selected = if (lens.cameraId == selectedLensId) " [selected]" else ""
            appendLine("  id=${lens.cameraId} ${lens.label}$selected")
            appendLine("    facing=${facingName(lens.facing)} hardwareLevel=${hardwareLevelName(lens.hardwareLevel)}")
            appendLine("    focal=${lens.focalLengthsMm.joinToString { "%.2f".format(it) }}mm sensor=${"%.2f".format(lens.sensorWidthMm)}x${"%.2f".format(lens.sensorHeightMm)}mm")
            appendLine("    fov=${"%.1f".format(lens.fovHorizontalDeg)}x${"%.1f".format(lens.fovVerticalDeg)}deg maxDigitalZoom=${"%.1f".format(lens.maxZoomRatio)}")
            appendLine("    raw=${lens.supportsRaw} manualSensor=${lens.supportsManualSensor} depth=${lens.supportsDepthOutput}")
            appendLine("    iso=${lens.isoRange?.toString() ?: "n/a"} exposureNs=${lens.exposureTimeRange?.toString() ?: "n/a"}")
            appendLine("    minFocusDiopters=${lens.minFocusDistanceDiopters} flash=${lens.hasFlash}")
            appendLine("    ois=${lens.oisSupported} videoStab=${lens.videoStabilizationSupported} previewStab=${lens.previewStabilizationSupported}")
            appendLine("    maxPhoto=${lens.photoOutputSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "n/a"} (${"%.1f".format(lens.maxPhotoMegapixels)}MP)")
            appendLine("    videoSizes=${lens.videoOutputSizes.take(6).joinToString { "${it.width}x${it.height}" }}")
            appendLine("    fpsRanges=${lens.fpsRanges.joinToString { "${it.lower}-${it.upper}" }}")
            appendLine("    4k60=${caps.supportsVideo(VideoResolution.UHD, 60, lens)} 4k30=${caps.supportsVideo(VideoResolution.UHD, 30, lens)}")
            if (lens.physicalIds.isNotEmpty()) appendLine("    physicalIds=${lens.physicalIds.joinToString()}")
        }
        appendLine()
        appendLine("FEATURES ON SELECTED LENS")
        val lens = caps.lens(selectedLensId) ?: caps.mainBackLens
        appendLine("  raw=${FeatureGate.raw(lens)}")
        appendLine("  manualExposure=${FeatureGate.manualExposure(lens)}")
        appendLine("  manualFocus=${FeatureGate.manualFocus(lens)}")
        appendLine("  hdr=${FeatureGate.hdr(lens)} night=${FeatureGate.night(lens)} astro=${FeatureGate.astro(lens)}")
        appendLine("  portrait=${FeatureGate.portrait()} panorama=${FeatureGate.panorama()} fisheye=${FeatureGate.fisheye()}")
        appendLine("  slowMotion=${FeatureGate.slowMotion()} timeLapse=${FeatureGate.timeLapse()}")
        appendLine("  ois=${FeatureGate.ois(lens)} eis=${FeatureGate.eis(lens)}")
        appendLine("  ultraWide=${FeatureGate.ultraWide(caps)} telephoto=${FeatureGate.telephoto(caps)}")
        appendLine()
        appendLine("SETTINGS SNAPSHOT")
        settings.toDiagnosticMap().forEach { (k, v) -> appendLine("  $k=$v") }
    }

    private fun facingName(facing: Int): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        else -> "external"
    }

    private fun hardwareLevelName(level: Int): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private data class RawLens(
        val id: String,
        val characteristics: CameraCharacteristics,
        val focalLengths: FloatArray,
        val sensorWidthMm: Float,
        val sensorHeightMm: Float,
        val fovHorizontalDeg: Float,
        val fovVerticalDeg: Float,
        val maxDigitalZoom: Float,
        val sensorOrientation: Int,
        val hardwareLevel: Int,
        val raw: Boolean,
        val manualSensor: Boolean,
        val manualPostProcessing: Boolean,
        val depth: Boolean,
        val isoRange: Range<Int>?,
        val exposureTimeRange: Range<Long>?,
        val minFocusDistance: Float,
        val hasFlash: Boolean,
        val oisModes: IntArray,
        val videoStabModes: IntArray,
        val photoSizes: List<Size>,
        val videoSizes: List<Size>,
        val fpsRanges: List<Range<Int>>,
        val physicalIds: Set<String>
    )
}
