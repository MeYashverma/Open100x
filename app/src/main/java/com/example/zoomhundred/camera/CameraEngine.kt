package com.example.zoomhundred.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.Preview.SurfaceProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.CaptureMode
import com.example.zoomhundred.model.FlashMode
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.StabilizationMode
import com.example.zoomhundred.model.VideoCodec
import com.example.zoomhundred.model.VideoResolution
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One YUV frame's luma plane, valid only for the duration of the callback. */
class LumaFrame(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
)

data class VideoConfig(
    val resolution: VideoResolution = VideoResolution.FHD,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateMbps: Int = 0,
    val stabilization: StabilizationMode = StabilizationMode.AUTO,
    val recordAudio: Boolean = true
)

/**
 * Owns the CameraX session and every Camera2 control Open 100x uses.
 *
 * Responsibilities: provider lifecycle, lens selection, use-case binding per mode,
 * zoom, torch, manual exposure/focus/WB, stabilization, frame analysis, still capture
 * and video-recorder construction. Capture *sequences* (burst, night, panorama…) live in
 * `CaptureCoordinator` so this class stays a thin, predictable hardware layer.
 *
 * Threading: `bind`/`capture` must be called on the main thread. Analysis frames arrive
 * on [analysisExecutor]; anything the UI does with them must copy synchronously.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    interface Callback {
        fun onAnalysisFrame(frame: LumaFrame)
        fun onEngineError(message: String)
        fun onZoomCapabilitiesChanged(minZoom: Float, maxZoom: Float)
    }

    var callback: Callback? = null

    @Volatile
    var capabilities: DeviceCapabilities = DeviceCapabilities()
        private set

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null

    var currentLens: LensCapabilities? = null
        private set
    var currentMode: CaptureMode = CaptureMode.PHOTO
        private set
    var minZoom: Float = 1f
        private set
    var maxZoom: Float = 1f
        private set
    var isBound: Boolean = false
        private set

    private var rawOutput = false
    private var lastZoom = 1f
    private var torchOn = false

    fun initialize(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val deliver: () -> Unit = {
            future.addListener({
                provider = runCatching { future.get() }.getOrNull()
                onReady(provider != null)
            }, mainExecutor)
        }
        // Detection walks every lens and every encoder: keep it off the main thread so a
        // slow OEM HAL cannot delay the first frame or trigger an ANR at launch.
        val queued = runCatching {
            analysisExecutor.execute {
                capabilities = CapabilityDetector.detect(context)
                deliver()
            }
        }
        if (queued.isFailure) {
            capabilities = CapabilityDetector.detect(context)
            deliver()
        }
    }

    fun lenses(): List<LensCapabilities> = capabilities.lenses

    fun lens(cameraId: String?): LensCapabilities? =
        if (cameraId == null) capabilities.mainBackLens else capabilities.lens(cameraId)

    /**
     * Binds preview + still capture (+ analysis) or preview + video for [mode].
     * Returns true when the session is live.
     */
    fun bind(
        lens: LensCapabilities,
        mode: CaptureMode,
        surfaceProvider: SurfaceProvider,
        pro: ProSettings,
        settings: AppSettings,
        rotation: Int,
        video: VideoConfig,
        forceRaw: Boolean = false
    ): Boolean {
        val cameraProvider = provider ?: return false
        currentMode = mode
        currentLens = lens
        rawOutput = forceRaw

        val previewStabSupported = lens.previewStabilizationSupported &&
            (settings.videoStabilization == StabilizationMode.ELECTRONIC ||
                settings.videoStabilization == StabilizationMode.AUTO)
        val previewBuilder = CameraControls.previewBuilder(pro, lens, rotation, settings.videoStabilization, previewStabSupported)
        val newPreview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }

        val analysisResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { proxy -> deliverFrame(proxy) }
            }

        val selector = selectorFor(lens)
        val useCases = ArrayList<androidx.camera.core.UseCase>(3)
        useCases.add(newPreview)
        useCases.add(newAnalysis)

        var newImageCapture: ImageCapture? = null
        var newVideoCapture: VideoCapture<Recorder>? = null

        if (mode.isVideoMode) {
            val newRecorder = buildRecorder(video)
            val videoBuilder = VideoCapture.Builder(newRecorder)
            if (video.stabilization != StabilizationMode.OFF) {
                val supported = if (video.stabilization == StabilizationMode.OPTICAL) lens.oisSupported
                else lens.videoStabilizationSupported || lens.previewStabilizationSupported
                if (supported) runCatching { videoBuilder.setVideoStabilizationEnabled(true) }
            }
            newVideoCapture = videoBuilder.build()
            useCases.add(newVideoCapture)
            recorder = newRecorder
        } else {
            newImageCapture = CameraControls.imageCaptureBuilder(
                pro = pro,
                lens = lens,
                rotation = rotation,
                flash = settings.flashMode,
                raw = forceRaw,
                stabilization = settings.videoStabilization
            ).build()
            useCases.add(newImageCapture)
        }

        return try {
            cameraProvider.unbindAll()
            val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            camera = boundCamera
            preview = newPreview
            imageAnalysis = newAnalysis
            imageCapture = newImageCapture
            videoCapture = newVideoCapture
            isBound = true

            val zoomState = boundCamera.cameraInfo.zoomState.value
            minZoom = zoomState?.minZoomRatio ?: 1f
            maxZoom = zoomState?.maxZoomRatio ?: lens.maxZoomRatio
            callback?.onZoomCapabilitiesChanged(minZoom, maxZoom)

            applySessionOptions(pro, lens, rotation, video)
            setZoomRatio(lastZoom)
            if (torchOn) boundCamera.cameraControl.enableTorch(true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "bind failed: ${e.message}")
            isBound = false
            callback?.onEngineError(e.message ?: "Camera unavailable")
            false
        }
    }

    private fun selectorFor(lens: LensCapabilities): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId }
            }
            .build()

    private fun buildRecorder(config: VideoConfig): Recorder {
        val quality = when (config.resolution) {
            VideoResolution.UHD -> Quality.UHD
            VideoResolution.FHD -> Quality.FHD
            VideoResolution.HD -> Quality.HD
        }
        val fallback = when (config.resolution) {
            VideoResolution.UHD -> FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            VideoResolution.FHD -> FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            VideoResolution.HD -> FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        }
        val builder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, fallback))
            .setExecutor(captureExecutor)
        if (config.bitrateMbps > 0) {
            runCatching { builder.setTargetVideoEncodingBitRate(config.bitrateMbps * 1_000_000) }
        }
        return builder.build()
    }

    /** Applies live session controls (exposure/focus/WB/fps/stabilization). */
    fun applySessionOptions(
        pro: ProSettings,
        lens: LensCapabilities,
        rotation: Int = 0,
        video: VideoConfig? = null
    ) {
        val cam = camera ?: return
        val fpsRange: Range<Int>? = if (video != null && currentMode.isVideoMode) {
            CameraControls.fpsRangeFor(lens, video.fps)
        } else null
        val options = CameraControls.sessionOptions(pro, lens, fpsRange, pro.stabilization)
        Camera2CameraControl.from(cam.cameraControl)
            .setCaptureRequestOptions(options)
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        lastZoom = ratio.coerceIn(minZoom, maxZoom)
        val future = cam.cameraControl.setZoomRatio(lastZoom)
        future.addListener({}, ContextCompat.getMainExecutor(context))
    }

    fun updateRotation(rotation: Int) {
        runCatching {
            preview?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            imageAnalysis?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        val cam = camera ?: return false
        if (enabled && !cam.cameraInfo.hasFlashUnit()) return false
        torchOn = enabled
        cam.cameraControl.enableTorch(enabled)
        return true
    }

    /** Tap-to-focus with automatic AE metering at the tapped point. */
    fun focusAndMeter(point: MeteringPoint) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun cancelFocusAndMetering() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() ?: (currentLens?.hasFlash == true)

    /** Exposure compensation, clamped to the camera's advertised range. */
    fun setExposureCompensation(ev: Float): Int {
        val cam = camera ?: return -1
        val state = cam.cameraInfo.exposureState
        val index = Math.round(ev / state.exposureCompensationStep.toFloat()).toInt()
            .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
        return index
    }

    fun exposureRange(): Range<Int>? = camera?.cameraInfo?.exposureState?.exposureCompensationRange

    /**
     * Captures one still into MediaStore. [values] must contain DISPLAY_NAME/MIME_TYPE and
     * (on Q+) RELATIVE_PATH.
     */
    fun captureStill(
        collection: Uri,
        values: ContentValues,
        onSaved: (Uri, ImageCapture) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError("Camera is not ready")
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, collection, values).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val uri = outputFileResults.savedUri
                if (uri == null) onError("No output URI") else onSaved(uri, capture)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Capture failed")
            }
        })
    }

    /** Captures one still to a private file (used by burst/stack sequences). */
    fun captureStillToFile(file: File, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onError("Camera is not ready")
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(file)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Capture failed")
            }
        })
    }

    val imageCaptureUseCase: ImageCapture? get() = imageCapture
    val videoCaptureUseCase: VideoCapture<Recorder>? get() = videoCapture
    val recorderInstance: Recorder? get() = recorder

    private fun deliverFrame(proxy: androidx.camera.core.ImageProxy) {
        try {
            val plane = proxy.planes.firstOrNull()
            if (plane != null) {
                callback?.onAnalysisFrame(
                    LumaFrame(
                        buffer = plane.buffer,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        width = proxy.width,
                        height = proxy.height,
                        rotationDegrees = proxy.imageInfo.rotationDegrees
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis frame failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        videoCapture = null
        recorder = null
        isBound = false
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraEngine"

        /** Camera id of a lens, exposed for diagnostics. */
        fun cameraIdOf(lens: LensCapabilities): String = lens.cameraId

        /** True when this lens looks like a periscope/tele module worth defaulting to. */
        fun preferLens(caps: DeviceCapabilities): LensCapabilities? =
            caps.backLenses.maxByOrNull { it.focalLengthsMm.maxOrNull() ?: 0f } ?: caps.lenses.firstOrNull()

        fun nowMs(): Long = SystemClock.uptimeMillis()
    }
}
