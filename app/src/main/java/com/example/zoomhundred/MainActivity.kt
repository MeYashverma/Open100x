package com.example.zoomhundred

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.example.zoomhundred.camera.AntiShakeController
import com.example.zoomhundred.camera.CameraEngine
import com.example.zoomhundred.camera.CaptureCoordinator
import com.example.zoomhundred.camera.DeviceCapabilities
import com.example.zoomhundred.camera.FeatureGate
import com.example.zoomhundred.camera.FeatureState
import com.example.zoomhundred.camera.LensCapabilities
import com.example.zoomhundred.camera.LumaFrame
import com.example.zoomhundred.camera.ShotContext
import com.example.zoomhundred.camera.VideoConfig
import com.example.zoomhundred.camera.ZoomEngine
import com.example.zoomhundred.computational.AccumulationMode
import com.example.zoomhundred.databinding.ActivityMainBinding
import com.example.zoomhundred.imaging.ImageStore
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.AspectRatio
import com.example.zoomhundred.model.CaptureMode
import com.example.zoomhundred.model.FlashMode
import com.example.zoomhundred.model.FrameStyle
import com.example.zoomhundred.model.GridMode
import com.example.zoomhundred.model.PhotoFormat
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.StabilizationMode
import com.example.zoomhundred.model.VideoResolution
import com.example.zoomhundred.overlay.CaptureButtonView
import com.example.zoomhundred.overlay.HudState
import com.example.zoomhundred.ui.ProPanelSheet
import com.example.zoomhundred.ui.ToolsSheet
import com.example.zoomhundred.video.VideoRecorderController
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "MainActivity"
private const val HISTOGRAM_INTERVAL_MS = 120L
private const val LOUPE_INTERVAL_MS = 110L
private const val MONITOR_INTERVAL_MS = 140L
private const val SCOPE_INTERVAL_MS = 260L
private const val SETTLED_MIN_DELAY_MS = 320L
private const val SETTLED_TIMEOUT_MS = 2600L
private const val EXTREME_ZOOM_THRESHOLD = 15f
private const val RECORD_TICK_MS = 500L

fun isGooglePixelDevice(): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    val model = Build.MODEL.lowercase()
    val device = Build.DEVICE.lowercase()
    val product = Build.PRODUCT.lowercase()
    val googleHardware = manufacturer == "google" || brand == "google"
    val pixelName = model.contains("pixel") || device.contains("pixel") || product.contains("pixel")
    return googleHardware && pixelName
}

/**
 * The camera screen.
 *
 * Deliberately thin: it owns UI state, gestures and lifecycle, and delegates hardware to
 * [CameraEngine], capture sequences to [CaptureCoordinator], recording to
 * [VideoRecorderController] and every value shown to the user to the capability layer.
 * Anything the device cannot do is either absent or explicitly labelled.
 */
class MainActivity : AppCompatActivity(),
    CameraEngine.Callback,
    CaptureCoordinator.Listener,
    VideoRecorderController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: CameraEngine
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var recorderController: VideoRecorderController
    private lateinit var antiShake: AntiShakeController
    private lateinit var settings: AppSettings
    private lateinit var pro: ProSettings
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val pixelPortraitOnly = isGooglePixelDevice()

    private var capabilities = DeviceCapabilities()
    private var currentLens: LensCapabilities? = null
    private var usingFrontCamera = false

    private var mode: CaptureMode = CaptureMode.PHOTO
    private var zoom = 1f
    private var cropOffsetX = 0f
    private var cropOffsetY = 0f
    private var zoomEngine = ZoomEngine()
    private var rawBound = false

    private var lastHistogramMs = 0L
    private var lastLoupeMs = 0L
    private var lastMonitorMs = 0L
    private var lastScopeMs = 0L
    private var monitorMode = 0 // 0 none, 1 histogram, 2 peaking, 3 zebra

    private var stableSinceMs = 0L
    private var shakeLevel = 0f
    private var settledCapturePending = false
    private var settledStartedMs = 0L

    private var countdownRemaining = 0
    private var countdownToken = 0
    private var pendingRawJpegSecondPass = false

    private var recordingActive = false
    private var recordingPaused = false
    private var lastScopeBitmap: Bitmap? = null
    private var rollDegrees = 0f
    private var pitchDegrees = 0f

    private val scaleDetector by lazy { ScaleGestureDetector(this, PinchListener()) }
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoom(if (zoom > 1.5f) 1f else min(5f, zoomEngine.maxZoom))
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                toggleFreezeScope()
            }
        })
    }

    private val settledRunnable = object : Runnable {
        override fun run() {
            updateSettledCapture()
        }
    }
    private val recordTickRunnable = object : Runnable {
        override fun run() {
            if (recordingActive) {
                binding.recordTimerText.text = formatDuration(recorderController.elapsedMs())
                updateStorageEstimate()
                mainHandler.postDelayed(this, RECORD_TICK_MS)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startEngine()
        else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        startRecordingInternal(withAudio = granted)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        if (pixelPortraitOnly) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings.load(this)
        pro = ProSettings.load(this)
        mode = settings.defaultMode
        monitorMode = when {
            settings.peakingEnabled -> 2
            settings.zebraskaEnabled -> 3
            settings.histogramEnabled -> 1
            else -> 0
        }
        if (settings.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        antiShake = AntiShakeController(this)
        recorderController = VideoRecorderController(this).apply { listener = this@MainActivity }

        engine = CameraEngine(this, this).apply { callback = this@MainActivity }
        coordinator = CaptureCoordinator(
            context = this,
            engine = engine,
            settings = { settings },
            pro = { pro }
        ).apply { listener = this@MainActivity }

        setupUi()
        setupInsets()
        setupSensors()
        ensurePermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        val previous = settings
        settings = AppSettings.load(this)
        applySettingsToUi()
        if (previous.photoFormat != settings.photoFormat ||
            previous.flashMode != settings.flashMode ||
            previous.videoResolution != settings.videoResolution ||
            previous.videoFps != settings.videoFps ||
            previous.videoStabilization != settings.videoStabilization
        ) {
            if (engine.isBound) bindCamera()
        }
        if (antiShake.isAvailable) startAntiShake()
        if (!pixelPortraitOnly && orientationListener.canDetectOrientation()) orientationListener.enable()
        refreshThumbnail()
    }

    override fun onPause() {
        cancelCountdown()
        cancelSettledCapture()
        if (recordingActive) stopRecording()
        antiShake.stop()
        if (!pixelPortraitOnly) orientationListener.disable()
        settings.save(this)
        pro.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        coordinator.release()
        engine.shutdown()
        recorderController.release()
        ioExecutor.shutdown()
        antiShake.stop()
        mainHandler.removeCallbacksAndMessages(null)
        lastScopeBitmap?.recycle()
        lastScopeBitmap = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!pixelPortraitOnly) engine.updateRotation(displayRotation())
        updateAspectGuide()
        updateLoupeSource()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                when (settings.volumeKeyAction) {
                    com.example.zoomhundred.model.VolumeKeyAction.ZOOM -> {
                        if (!settings.zoomLocked) nudgeZoom(direction)
                        true
                    }
                    com.example.zoomhundred.model.VolumeKeyAction.SHUTTER -> {
                        onShutterPressed()
                        true
                    }
                    com.example.zoomhundred.model.VolumeKeyAction.OFF -> super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupUi() {
        buildModeStrip()

        binding.captureButton.onTap = { onShutterPressed() }
        binding.captureButton.onLongPress = { onShutterLongPressed() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.pauseButton.setOnClickListener { toggleRecordingPause() }
        binding.thumbnailView.setOnClickListener { openGallery() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.toolsButton.setOnClickListener { openToolsSheet() }
        binding.flashButton.setOnClickListener { cycleFlash() }
        binding.timerButton.setOnClickListener { cycleTimer() }
        binding.hdrButton.setOnClickListener { toggleHdr() }
        binding.gridButton.setOnClickListener { cycleGrid() }
        binding.loupeToggleButton.setOnClickListener { toggleLoupe() }
        binding.monitorButton.setOnClickListener { cycleMonitoring() }
        binding.zoomLockButton.setOnClickListener { toggleZoomLock() }

        binding.proSettingsButton.setOnClickListener { openProPanel() }
        binding.switchLensButton.setOnClickListener { switchLens() }
        binding.resetZoomButton.setOnClickListener { animateZoom(1f) }
        binding.lockZoomButton.setOnClickListener { toggleZoomLock() }
        binding.flashlightButton.setOnClickListener { toggleTorch() }
        binding.zoomValueText.setOnLongClickListener {
            setDrawerOpen(!drawerOpen)
            true
        }
        binding.zoomValueText.setOnClickListener { setDrawerOpen(!drawerOpen) }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setZoom(zoomEngine.sliderToZoom(value), userInitiated = true)
        }

        binding.zoomRail.onPresetSelected = { preset -> animateZoom(preset.ratio) }
        binding.zoomRail.onZoomScrubbed = { value -> setZoom(value, userInitiated = true) }
        binding.zoomRail.onZoomCommit = { value -> animateZoom(value) }

        binding.loupeView.onMoved = { fx, fy ->
            settings.loupeX = fx
            settings.loupeY = fy
            updateLoupeSource()
        }
        binding.loupeView.onTapped = { toggleLoupe() }

        binding.gestureLayer.setOnTouchListener { _, event -> handleGesture(event) }
        binding.bottomPanel.setOnTouchListener { _, event -> handleDrawerTouch(event) }

        binding.freezeScopeView.setOnClickListener { toggleFreezeScope() }
        binding.gestureHintOverlay.setOnClickListener { binding.gestureHintOverlay.visibility = View.GONE }

        applySettingsToUi()
        showOnboardingIfNeeded()
    }

    private fun setupInsets() {
        val topBase = binding.topPanel.paddingTop
        val bottomBase = binding.bottomControlsBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topPanel.setPadding(
                binding.topPanel.paddingLeft, topBase + bars.top,
                binding.topPanel.paddingRight, binding.topPanel.paddingBottom
            )
            binding.bottomControlsBar.setPadding(
                binding.bottomControlsBar.paddingLeft, binding.bottomControlsBar.paddingTop,
                binding.bottomControlsBar.paddingRight, bottomBase + bars.bottom
            )
            insets
        }
    }

    private fun setupSensors() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (pixelPortraitOnly || orientation == ORIENTATION_UNKNOWN) return
                if (engine.isBound) engine.updateRotation(displayRotation())
            }
        }
        // Level + rotation used by the horizon indicator.
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
            sensorManager.registerListener(object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    rollDegrees = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                    pitchDegrees = Math.toDegrees(atan2(-z.toDouble(), sqrt((x * x + y * y).toDouble()))).toFloat()
                    if (binding.levelView.visibility == View.VISIBLE) {
                        binding.levelView.rollDegrees = rollDegrees
                        binding.levelView.pitchDegrees = pitchDegrees
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startEngine()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startEngine() {
        binding.captureStatusText.visibility = View.VISIBLE
        binding.captureStatusText.text = getString(R.string.loading_camera)
        engine.initialize { ready ->
            if (isFinishing || isDestroyed) return@initialize
            if (!ready) {
                toast(getString(R.string.bind_failed, "camera provider unavailable"))
                return@initialize
            }
            capabilities = engine.capabilities
            if (capabilities.lenses.isEmpty()) {
                toast(getString(R.string.no_camera_found))
                finish()
                return@initialize
            }
            currentLens = pickDefaultLens()
            bindCamera()
        }
    }

    private fun pickDefaultLens(): LensCapabilities? {
        val back = capabilities.backLenses
        if (back.isEmpty()) return capabilities.lenses.firstOrNull()
        // Prefer the telephoto module: the 100× hybrid engine is the point of this app.
        return back.maxByOrNull { it.focalLengthsMm.maxOrNull() ?: 0f } ?: back.first()
    }

    private fun bindCamera(forceRaw: Boolean = false) {
        val lens = currentLens ?: return
        val surfaceProvider = binding.previewView.surfaceProvider ?: return
        rawBound = forceRaw
        val video = videoConfig()
        val ok = engine.bind(
            lens = lens,
            mode = mode,
            surfaceProvider = surfaceProvider,
            pro = pro,
            settings = settings,
            rotation = displayRotation(),
            video = video,
            forceRaw = forceRaw
        )
        if (!ok) {
            toast(getString(R.string.bind_failed, "session rejected"))
            return
        }
        minZoom = engine.minZoom
        maxZoom = min(100f, max(engine.maxZoom, lens.maxZoomRatio))
        zoomEngine = ZoomEngine(minZoom, engine.maxZoom, maxZoom)
        refreshZoomPresets()
        setZoom(zoom, userInitiated = false)
        binding.lensBadgeText.text = lensLabel(lens)
        binding.proModeBadge.visibility = if (pro.manualMode || mode == CaptureMode.PRO) View.VISIBLE else View.GONE
        applySessionSettings()
        binding.captureStatusText.visibility = View.GONE
        refreshThumbnail()
        updateAspectGuide()
        binding.loupeView.zoomRatio = zoom
        if (settings.showLoupe) restoreLoupePosition()
    }

    private fun applySessionSettings() {
        val lens = currentLens ?: return
        engine.applySessionOptions(pro, lens, displayRotation(), videoConfig())
        engine.setExposureCompensation(pro.evValue())
        startAntiShakeIfNeeded()
    }

    private fun videoConfig(): VideoConfig {
        val lens = currentLens
        var resolution = settings.videoResolution
        var fps = settings.videoFps
        if (lens != null) {
            if (!lens.supportsVideo(resolution, fps)) {
                // Fall back to the closest combination this lens/encoder can actually do.
                resolution = when {
                    lens.supportsVideo(VideoResolution.FHD, 30) -> VideoResolution.FHD
                    lens.supportsVideo(VideoResolution.HD, 30) -> VideoResolution.HD
                    else -> resolution
                }
                if (!lens.supportsVideo(resolution, fps)) fps = 30
            }
        }
        return VideoConfig(
            resolution = resolution,
            fps = fps,
            codec = settings.videoCodec,
            bitrateMbps = settings.videoBitrateMbps,
            stabilization = settings.videoStabilization,
            recordAudio = settings.recordAudio
        )
    }

    // ── Camera engine callbacks ───────────────────────────────────────────────

    override fun onAnalysisFrame(frame: LumaFrame) {
        val now = SystemClock.uptimeMillis()
        if (monitorMode == 1 && now - lastHistogramMs >= HISTOGRAM_INTERVAL_MS) {
            lastHistogramMs = now
            binding.histogramView.updateFromLumaPlane(
                frame.buffer, frame.rowStride, frame.pixelStride, frame.width, frame.height
            )
        }
        if (settings.showLoupe && now - lastLoupeMs >= LOUPE_INTERVAL_MS) {
            lastLoupeMs = now
            binding.loupeView.submitFrame(frame)
        }
        if (now - lastMonitorMs >= MONITOR_INTERVAL_MS) {
            lastMonitorMs = now
            if (monitorMode == 2) binding.peakingView.submitFrame(frame)
            if (monitorMode == 3) binding.zebraView.submitFrame(frame)
            if (monitorMode == 4) binding.falseColorView.submitFrame(frame)
            if (binding.freezeScopeView.visibility == View.VISIBLE && now - lastScopeMs >= SCOPE_INTERVAL_MS) {
                lastScopeMs = now
                updateFreezeScope(frame)
            }
        }
    }

    override fun onEngineError(message: String) {
        mainHandler.post { toast(getString(R.string.bind_failed, message)) }
    }

    override fun onZoomCapabilitiesChanged(minZoomRatio: Float, maxZoomRatio: Float) {
        mainHandler.post {
            minZoom = minZoomRatio
            maxZoom = min(100f, max(maxZoomRatio, currentLens?.maxZoomRatio ?: 1f))
            zoomEngine = ZoomEngine(minZoom, maxZoomRatio, maxZoom)
            refreshZoomPresets()
        }
    }

    private var minZoom = 1f
    private var maxZoom = 100f

    // ── Capture coordinator callbacks ─────────────────────────────────────────

    override fun onBusy(active: Boolean, fraction: Float, message: String) {
        mainHandler.post {
            if (active) {
                binding.captureStatusText.visibility = View.VISIBLE
                binding.captureStatusText.text = message
                binding.captureButton.mode = CaptureButtonView.Mode.BUSY
                binding.captureButton.progress = if (fraction < 0f) 0.35f else fraction
            } else {
                binding.captureButton.mode = if (mode == CaptureMode.VIDEO) CaptureButtonView.Mode.VIDEO else CaptureButtonView.Mode.PHOTO
                binding.captureButton.progress = 0f
                if (message.isBlank()) {
                    binding.captureStatusText.visibility = View.GONE
                } else {
                    binding.captureStatusText.text = message
                    mainHandler.postDelayed({
                        if (!coordinator.isBusy) binding.captureStatusText.visibility = View.GONE
                    }, 1400L)
                }
            }
            if (message.isNotBlank() && active) updateStatusHints()
        }
    }

    override fun onSaved(uri: Uri) {
        mainHandler.post {
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            playShutterSound()
            refreshThumbnail()
            val isRaw = uri.toString().contains("dng", true)
            toast(if (isRaw) "RAW saved" else getString(R.string.capture_complete))
            if (pendingRawJpegSecondPass) {
                pendingRawJpegSecondPass = false
                bindCamera(forceRaw = false)
                mainHandler.postDelayed({ coordinator.singleShot(buildShotContext()) }, 450L)
            }
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            if (message.isNotBlank()) toast(message)
            binding.captureStatusText.visibility = View.GONE
            binding.captureButton.mode = CaptureButtonView.Mode.PHOTO
        }
    }

    override fun onFrameProgress(captured: Int, total: Int) {
        mainHandler.post {
            binding.captureStatusText.visibility = View.VISIBLE
            binding.captureStatusText.text = getString(R.string.capture_frames_progress, captured, total)
            binding.captureButton.progress = captured.toFloat() / total.coerceAtLeast(1)
        }
    }

    // ── Recording callbacks ───────────────────────────────────────────────────

    override fun onRecordingState(state: VideoRecorderController.State) {
        mainHandler.post {
            recordingActive = state.recording
            recordingPaused = state.paused
            binding.captureButton.mode = when {
                state.recording && state.paused -> CaptureButtonView.Mode.PAUSED
                state.recording -> CaptureButtonView.Mode.RECORDING
                else -> if (mode == CaptureMode.VIDEO) CaptureButtonView.Mode.VIDEO else CaptureButtonView.Mode.PHOTO
            }
            binding.pauseButton.visibility = if (state.recording) View.VISIBLE else View.GONE
            binding.pauseButton.setImageResource(
                if (state.paused) R.drawable.ic_record else R.drawable.ic_pause
            )
            binding.recordTimerText.visibility = if (state.recording) View.VISIBLE else View.GONE
            if (!state.recording) {
                mainHandler.removeCallbacks(recordTickRunnable)
                binding.recordTimerText.text = ""
            }
        }
    }

    override fun onRecordingFinalized(uri: Uri) {
        mainHandler.post {
            toast(getString(R.string.capture_complete))
            refreshThumbnail()
        }
    }

    override fun onRecordingError(message: String) {
        mainHandler.post { toast(message) }
    }

    // ── Shutter ───────────────────────────────────────────────────────────────

    private fun onShutterPressed() {
        if (mode == CaptureMode.VIDEO) {
            toggleRecording()
            return
        }
        if (coordinator.inSequence) {
            sequenceShutter()
            return
        }
        if (coordinator.isBusy) return
        if (settings.timerSeconds > 0) {
            startCountdown(settings.timerSeconds) { takePhoto() }
        } else {
            takePhoto()
        }
    }

    private fun onShutterLongPressed() {
        when {
            mode == CaptureMode.VIDEO -> toggleRecordingPause()
            mode == CaptureMode.PRO -> openProPanel()
            else -> {
                if (coordinator.isBusy) return
                haptic(HapticFeedbackConstants.LONG_PRESS)
                coordinator.burst(buildShotContext(), settings.burstCount)
            }
        }
    }

    private fun takePhoto() {
        if (mode == CaptureMode.PANORAMA) {
            startSequence(CaptureMode.PANORAMA, SequenceConfig(frames = 16, intervalMs = 0))
            return
        }
        if (mode == CaptureMode.LIGHT) {
            startSequence(CaptureMode.LIGHT, SequenceConfig(frames = settings.nightFrames.coerceIn(4, 12), intervalMs = 420L))
            return
        }
        if (mode == CaptureMode.TIMELAPSE) {
            val frames = (60 / settings.intervalSeconds.coerceAtLeast(1)).coerceIn(5, 120)
            startSequence(CaptureMode.TIMELAPSE, SequenceConfig(frames = frames, intervalMs = settings.intervalSeconds * 1000L))
            return
        }
        if (zoom >= EXTREME_ZOOM_THRESHOLD && !isStableForCapture()) {
            beginSettledCapture()
            return
        }
        captureNow()
    }

    private fun captureNow() {
        val lens = currentLens ?: return
        if (mode == CaptureMode.ASTRO) {
            if (abs(rollDegrees) > 3f || shakeLevel > 0.25f) {
                toast(getString(R.string.astro_tripod_needed))
            }
        }
        val wantsRaw = (settings.photoFormat == PhotoFormat.RAW || settings.photoFormat == PhotoFormat.RAW_JPEG) &&
            lens.supportsRaw
        if (wantsRaw && !rawBound) {
            bindCamera(forceRaw = true)
            mainHandler.postDelayed({ coordinator.singleShot(buildShotContext(), raw = true) }, 350L)
            if (settings.photoFormat == PhotoFormat.RAW_JPEG) pendingRawJpegSecondPass = true
            return
        }
        if (wantsRaw) {
            coordinator.singleShot(buildShotContext(), raw = true)
            if (settings.photoFormat == PhotoFormat.RAW_JPEG) pendingRawJpegSecondPass = true
            return
        }
        if (rawBound) {
            bindCamera(forceRaw = false)
            mainHandler.postDelayed({ coordinator.shoot(buildShotContext()) }, 350L)
            return
        }
        if (mode == CaptureMode.ASTRO) {
            // Astro needs a long manual exposure; the coordinator hands us a snapshot with
            // manual mode + a cool white balance, which we push into the live session.
            pro = coordinator.applyAstroExposure(
                pro,
                settings.astroExposureSeconds,
                pro.isoValue()
            )
            engine.applySessionOptions(pro, lens, displayRotation(), videoConfig())
            updateHud()
        }
        coordinator.shoot(buildShotContext())
    }

    private fun buildShotContext(): ShotContext {
        val mapping = zoomEngine.map(zoom)
        return ShotContext(
            mode = mode,
            zoomRatio = mapping.total,
            nativeZoom = mapping.native,
            lensLabel = currentLens?.label ?: "",
            aspect = settings.defaultAspect,
            portraitStrength = if (mode == CaptureMode.PORTRAIT) settings.portraitStrength else 0,
            accumulation = AccumulationMode.LIGHTEN,
            tripodStable = isStableForCapture(),
            cropOffsetX = cropOffsetX,
            cropOffsetY = cropOffsetY
        )
    }

    private data class SequenceConfig(val frames: Int, val intervalMs: Long)

    private fun startSequence(modeForSequence: CaptureMode, config: SequenceConfig) {
        val kind = when (modeForSequence) {
            CaptureMode.PANORAMA -> com.example.zoomhundred.camera.SequenceKind.PANORAMA
            CaptureMode.LIGHT -> com.example.zoomhundred.camera.SequenceKind.LIGHT_TRAIL
            CaptureMode.TIMELAPSE -> com.example.zoomhundred.camera.SequenceKind.TIMELAPSE
            else -> return
        }
        binding.captureButton.mode = CaptureButtonView.Mode.RECORDING
        coordinator.startSequence(buildShotContext(), kind, config.frames, config.intervalMs)
        when (kind) {
            com.example.zoomhundred.camera.SequenceKind.PANORAMA ->
                toast(getString(R.string.pano_capture_hint))
            com.example.zoomhundred.camera.SequenceKind.LIGHT_TRAIL ->
                toast(getString(R.string.light_trail_hint))
            else -> toast(getString(R.string.timelapse_hint))
        }
    }

    private fun sequenceShutter() {
        when (mode) {
            CaptureMode.PANORAMA -> coordinator.capturePanoramaFrame()
            else -> {
                coordinator.finishSequence()
                binding.captureButton.mode = CaptureButtonView.Mode.PHOTO
            }
        }
    }

    private fun beginSettledCapture() {
        settledCapturePending = true
        settledStartedMs = SystemClock.uptimeMillis()
        binding.captureStatusText.visibility = View.VISIBLE
        binding.captureStatusText.text = getString(R.string.capture_waiting_for_stillness)
        toast(getString(R.string.capture_waiting_for_stillness))
        mainHandler.removeCallbacks(settledRunnable)
        mainHandler.post(settledRunnable)
    }

    private fun updateSettledCapture() {
        if (!settledCapturePending) return
        val elapsed = SystemClock.uptimeMillis() - settledStartedMs
        binding.captureButton.progress = (elapsed.toFloat() / SETTLED_MIN_DELAY_MS).coerceIn(0f, 1f)
        if ((elapsed >= SETTLED_MIN_DELAY_MS && isStableForCapture()) || elapsed >= SETTLED_TIMEOUT_MS) {
            settledCapturePending = false
            binding.captureButton.progress = 0f
            captureNow()
        } else {
            mainHandler.postDelayed(settledRunnable, 33L)
        }
    }

    private fun cancelSettledCapture() {
        settledCapturePending = false
        mainHandler.removeCallbacks(settledRunnable)
    }

    // ── Timer / countdown ─────────────────────────────────────────────────────

    private fun startCountdown(seconds: Int, action: () -> Unit) {
        cancelCountdown()
        countdownRemaining = seconds
        val token = countdownToken
        binding.captureStatusText.visibility = View.VISIBLE
        binding.captureStatusText.text = countdownRemaining.toString()
        val tick = object : Runnable {
            override fun run() {
                // A cancelled or restarted countdown must never fire its capture.
                if (token != countdownToken || isFinishing || isDestroyed) return
                if (countdownRemaining <= 0) {
                    binding.captureStatusText.visibility = View.GONE
                    action()
                    return
                }
                binding.captureStatusText.text = countdownRemaining.toString()
                countdownRemaining--
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(tick, 1000L)
    }

    private fun cancelCountdown() {
        countdownToken++
        countdownRemaining = -1
        binding.captureStatusText.visibility = View.GONE
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private fun toggleRecording() {
        if (recordingActive) {
            stopRecording()
            return
        }
        if (settings.recordAudio &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecordingInternal(withAudio = settings.recordAudio)
    }

    private fun startRecordingInternal(withAudio: Boolean) {
        val capture = engine.videoCaptureUseCase
        if (capture == null) {
            toast(getString(R.string.video_unsupported))
            return
        }
        val name = "Open100x_video_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".mp4"
        val started = recorderController.start(
            capture = capture,
            displayName = name,
            relativePath = ImageStore.relativeDir(settings.saveSubfolder),
            withAudio = withAudio
        )
        if (started) {
            recordingActive = true
            mainHandler.post(recordTickRunnable)
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun stopRecording() {
        recorderController.stop()
        recordingActive = false
        mainHandler.removeCallbacks(recordTickRunnable)
        binding.recordTimerText.visibility = View.GONE
        binding.pauseButton.visibility = View.GONE
        binding.captureButton.mode = if (mode == CaptureMode.VIDEO) CaptureButtonView.Mode.VIDEO else CaptureButtonView.Mode.PHOTO
    }

    private fun toggleRecordingPause() {
        if (!recordingActive) return
        if (recordingPaused) recorderController.resume() else recorderController.pause()
    }

    private fun updateStorageEstimate() {
        val bytesPerSecond = recorderController.bytesPerSecond()
        val free = ImageStore.freeBytes()
        binding.storageText.text = if (bytesPerSecond > 0) {
            val remainingSeconds = free / bytesPerSecond
            getString(R.string.pro_remaining, formatDuration(remainingSeconds * 1000))
        } else {
            "${ImageStore.formatBytes(free)} free"
        }
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private fun refreshZoomPresets() {
        val hasUltraWide = capabilities.hasUltraWide
        val available = settings.zoomPresets + ZoomEngine.DEFAULT_STOPS
        binding.zoomRail.minZoom = minZoom
        binding.zoomRail.maxZoom = maxZoom
        binding.zoomRail.presets = zoomEngine.presets(available.distinct(), hasUltraWide)
        binding.zoomSlider.valueFrom = 0f
        binding.zoomSlider.valueTo = 1f
        binding.zoomSlider.stepSize = 0f
    }

    private fun setZoom(value: Float, userInitiated: Boolean) {
        if (userInitiated && settings.zoomLocked) return
        val clamped = zoomEngine.clamp(value)
        zoom = clamped
        val mapping = zoomEngine.map(clamped)
        engine.setZoomRatio(mapping.native)
        binding.previewView.scaleX = mapping.digital
        binding.previewView.scaleY = mapping.digital
        val maxShiftX = binding.previewView.width * (mapping.digital - 1f) / 2f
        val maxShiftY = binding.previewView.height * (mapping.digital - 1f) / 2f
        binding.previewView.translationX = -cropOffsetX * maxShiftX
        binding.previewView.translationY = -cropOffsetY * maxShiftY
        binding.extremeOverlay.updateCropOffset(cropOffsetX, cropOffsetY)
        binding.extremeOverlay.updateZoomInfo(clamped, currentFovH(), currentFovV())
        binding.zoomRail.zoom = clamped
        binding.zoomValueText.text = ZoomEngine.formatReadout(clamped)
        binding.loupeView.zoomRatio = clamped
        if (drawerOpen) binding.zoomSlider.value = zoomEngine.zoomToSlider(clamped)
        updateHud()
        updateStatusHints()
        if (settings.showLoupe) updateLoupeSource()
    }

    private fun animateZoom(target: Float) {
        if (settings.zoomLocked) {
            toast(getString(R.string.zoom_locked, zoom))
            return
        }
        val start = zoom
        val end = zoomEngine.clamp(target)
        val animator = ValueAnimator.ofFloat(start, end).apply {
            duration = 220L
            addUpdateListener { setZoom(it.animatedValue as Float, userInitiated = false) }
            start()
        }
        // Keep a reference so the animator is not garbage collected mid-flight.
        zoomAnimator = animator
    }

    private var zoomAnimator: ValueAnimator? = null

    private fun nudgeZoom(direction: Int) {
        val next = if (direction > 0) zoom * 1.35f else zoom / 1.35f
        setZoom(next, userInitiated = true)
    }

    private fun toggleZoomLock() {
        settings.zoomLocked = !settings.zoomLocked
        binding.zoomRail.locked = settings.zoomLocked
        binding.zoomLockButton.alpha = if (settings.zoomLocked) 1f else 0.55f
        toast(
            if (settings.zoomLocked) getString(R.string.zoom_locked, zoom)
            else getString(R.string.zoom_unlocked)
        )
        settings.save(this)
    }

    private fun currentFovH(): Float = (currentLens?.fovHorizontalDeg ?: 0f) / zoom.coerceAtLeast(0.1f)

    private fun currentFovV(): Float = (currentLens?.fovVerticalDeg ?: 0f) / zoom.coerceAtLeast(0.1f)

    // ── Lens switching ────────────────────────────────────────────────────────

    private fun switchCamera() {
        usingFrontCamera = !usingFrontCamera
        currentLens = if (usingFrontCamera) capabilities.frontLenses.firstOrNull() ?: capabilities.lenses.first()
        else pickDefaultLens()
        if (usingFrontCamera && capabilities.frontLenses.isEmpty()) {
            usingFrontCamera = false
            toast(getString(R.string.no_front_camera))
        }
        cropOffsetX = 0f
        cropOffsetY = 0f
        zoom = 1f
        bindCamera()
        refreshZoomPresets()
    }

    private fun switchLens() {
        val back = capabilities.backLenses
        if (back.size <= 1) {
            toast(getString(R.string.no_other_lens))
            return
        }
        val index = back.indexOfFirst { it.cameraId == currentLens?.cameraId }
        currentLens = back[(index + 1).mod(back.size)]
        usingFrontCamera = false
        bindCamera()
    }

    private fun lensLabel(lens: LensCapabilities): String =
        "${lens.label} · ${String.format(java.util.Locale.US, "%.1f×", zoomEngine.map(zoom).native)} native"

    // ── Modes & UI state ──────────────────────────────────────────────────────

    private fun buildModeStrip() {
        val container = binding.modeStripContainer
        container.removeAllViews()
        CaptureMode.PRIMARY.forEach { candidate ->
            val chip = TextView(this).apply {
                text = candidate.shortLabel
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                letterSpacing = 0.08f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_selector)
                isClickable = true
                isFocusable = true
                contentDescription = candidate.label
                setOnClickListener { onModeChipClicked(candidate) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            container.addView(chip, lp)
        }
        highlightActiveMode()
    }

    private fun onModeChipClicked(candidate: CaptureMode) {
        if (candidate == CaptureMode.MORE) {
            openToolsSheet(showSecondaryModes = true)
            return
        }
        setMode(candidate)
    }

    private fun setMode(newMode: CaptureMode) {
        if (newMode == mode) return
        if (recordingActive) {
            toast("Stop recording first")
            return
        }
        val previousVideo = mode.isVideoMode
        mode = newMode
        highlightActiveMode()
        binding.nightOverlay.visibility = if (mode == CaptureMode.NIGHT || settings.nightUi) View.VISIBLE else View.GONE
        binding.peakingView.visibleIf(monitorMode == 2)
        if (mode.isVideoMode != previousVideo) {
            bindCamera()
        } else {
            updateStatusHints()
        }
        binding.captureButton.mode = when {
            mode.isVideoMode -> CaptureButtonView.Mode.VIDEO
            else -> CaptureButtonView.Mode.PHOTO
        }
        binding.hdrButton.visibility = if (mode == CaptureMode.PHOTO) View.VISIBLE else View.GONE
        applySettingsToUi()
    }

    private fun highlightActiveMode() {
        val container = binding.modeStripContainer
        for (index in 0 until container.childCount) {
            val chip = container.getChildAt(index) as? TextView ?: continue
            val candidate = CaptureMode.PRIMARY.getOrNull(index) ?: continue
            val active = candidate == mode
            chip.isSelected = active
            chip.setTextColor(
                ContextCompat.getColor(this, if (active) R.color.text_on_accent else R.color.text_secondary)
            )
        }
    }

    private fun applySettingsToUi() {
        binding.hdrButton.isSelected = settings.hdrEnabled
        binding.hdrButton.visibility = if (mode == CaptureMode.PHOTO) View.VISIBLE else View.GONE
        binding.gridOverlay.gridMode = if (settings.gridMode == GridMode.NONE) GridMode.NONE else settings.gridMode
        binding.levelView.visibleIf(settings.levelEnabled)
        binding.hudView.visibleIf(settings.showHud)
        binding.loupeView.visibleIf(settings.showLoupe)
        binding.thumbnailView.visibleIf(settings.showThumbnail)
        binding.shakeMeterRow.visibleIf(settings.antiShake)
        binding.captureStatusText.visibility = View.GONE
        binding.zoomRail.locked = settings.zoomLocked
        binding.nightOverlay.visibility = if (settings.nightUi || mode == CaptureMode.NIGHT) View.VISIBLE else View.GONE
        binding.timerButton.alpha = if (settings.timerSeconds > 0) 1f else 0.6f
        binding.flashButton.setImageResource(
            when (settings.flashMode) {
                FlashMode.OFF, FlashMode.TORCH -> R.drawable.ic_flash_off
                FlashMode.AUTO -> R.drawable.ic_flash_auto
                FlashMode.ON -> R.drawable.ic_flash_on
            }
        )
        binding.hdrButton.alpha = 1f
        if (!recordingActive && !coordinator.isBusy) {
            binding.captureButton.mode = if (mode.isVideoMode)
                CaptureButtonView.Mode.VIDEO else CaptureButtonView.Mode.PHOTO
        }
        updateMonitorVisibility()
        applyLoupeSize()
        updateHud()
        updateAspectGuide()
        updateStatusHints()
    }

    private fun applyLoupeSize() {
        val params = binding.loupeView.layoutParams
        val size = dp(settings.loupeSizeDp)
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            binding.loupeView.layoutParams = params
        }
    }

    private fun restoreLoupePosition() {
        binding.loupeView.post {
            val parent = binding.loupeView.parent as? View ?: return@post
            val maxX = (parent.width - binding.loupeView.width).toFloat().coerceAtLeast(1f)
            val maxY = (parent.height - binding.loupeView.height).toFloat().coerceAtLeast(1f)
            val fx = if (settings.loupeX < 0f) 0.92f else settings.loupeX
            val fy = if (settings.loupeY < 0f) 0.03f else settings.loupeY
            binding.loupeView.moveTo(fx * maxX, fy * maxY)
            updateLoupeSource()
        }
    }

    private fun updateLoupeSource() {
        val preview = binding.previewView
        val loupe = binding.loupeView
        if (preview.width == 0 || preview.height == 0) return
        val previewLoc = IntArray(2)
        val loupeLoc = IntArray(2)
        preview.getLocationInWindow(previewLoc)
        loupe.getLocationInWindow(loupeLoc)
        val centerX = loupeLoc[0] + loupe.width / 2f - previewLoc[0]
        val centerY = loupeLoc[1] + loupe.height / 2f - previewLoc[1]
        loupe.setSourceCenter(centerX / preview.width, centerY / preview.height)
    }

    private fun updateAspectGuide() {
        val previewAspect = if (binding.previewView.height > 0) {
            binding.previewView.width.toFloat() / binding.previewView.height
        } else 0.75f
        binding.gridOverlay.aspectGuide = settings.defaultAspect.guideFraction(previewAspect)
        binding.gridOverlay.showCenterCross = settings.gridMode == GridMode.CROSS
    }

    private fun updateMonitorVisibility() {
        binding.histogramView.visibleIf(monitorMode == 1)
        binding.peakingView.visibleIf(monitorMode == 2)
        binding.zebraView.visibleIf(monitorMode == 3)
        binding.falseColorView.visibleIf(monitorMode == 4)
        binding.monitorButton.alpha = if (monitorMode == 0) 0.6f else 1f
    }

    private fun cycleMonitoring() {
        monitorMode = (monitorMode + 1) % 5
        settings.peakingEnabled = monitorMode == 2
        settings.zebraskaEnabled = monitorMode == 3
        settings.histogramEnabled = monitorMode == 1
        settings.save(this)
        updateMonitorVisibility()
        if (monitorMode == 2) binding.peakingView.clear()
        if (monitorMode == 3) binding.zebraView.clear()
        if (monitorMode == 4) binding.falseColorView.clear()
    }

    private fun cycleGrid() {
        val next = when (settings.gridMode) {
            GridMode.NONE -> GridMode.THIRDS
            GridMode.THIRDS -> GridMode.GOLDEN
            GridMode.GOLDEN -> GridMode.SPIRAL
            GridMode.SPIRAL -> GridMode.CROSS
            GridMode.CROSS -> GridMode.DIAGONAL
            GridMode.DIAGONAL -> GridMode.SQUARE
            GridMode.SQUARE -> GridMode.NONE
        }
        settings.gridMode = next
        settings.save(this)
        applySettingsToUi()
    }

    private fun toggleLoupe() {
        settings.showLoupe = !settings.showLoupe
        settings.save(this)
        binding.loupeView.visibleIf(settings.showLoupe)
        if (settings.showLoupe) {
            restoreLoupePosition()
            toast(getString(R.string.loupe))
        } else {
            binding.loupeView.clear()
        }
    }

    private fun cycleFlash() {
        settings.flashMode = when (settings.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON, FlashMode.TORCH -> FlashMode.OFF
        }
        if (settings.flashMode != FlashMode.TORCH && engine.hasFlash()) engine.setTorch(false)
        settings.save(this)
        applySettingsToUi()
        if (engine.isBound) bindCamera()
    }

    private fun cycleTimer() {
        settings.timerSeconds = when (settings.timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        settings.save(this)
        applySettingsToUi()
        toast(
            if (settings.timerSeconds == 0) getString(R.string.timer_off)
            else getString(R.string.timer_seconds, settings.timerSeconds)
        )
    }

    private fun toggleHdr() {
        settings.hdrEnabled = !settings.hdrEnabled
        settings.save(this)
        applySettingsToUi()
        toast(if (settings.hdrEnabled) "HDR burst on" else "HDR off")
    }

    private fun toggleTorch() {
        val on = !binding.flashlightButton.isSelected
        if (!engine.setTorch(on)) {
            toast(getString(R.string.flash_unavailable))
            return
        }
        binding.flashlightButton.isSelected = on
        binding.flashlightButton.text = if (on) getString(R.string.flash_on) else getString(R.string.flash_off)
        if (on) settings.flashMode = FlashMode.TORCH else settings.flashMode = FlashMode.OFF
        applySettingsToUi()
    }

    private fun showOnboardingIfNeeded() {
        if (settings.onboardingShown) return
        binding.gestureHintOverlay.visibility = View.VISIBLE
        settings.onboardingShown = true
        settings.save(this)
        mainHandler.postDelayed({
            binding.gestureHintOverlay.animate().alpha(0f).setDuration(300L)
                .withEndAction {
                    binding.gestureHintOverlay.visibility = View.GONE
                    binding.gestureHintOverlay.alpha = 1f
                }.start()
        }, 6500L)
    }

    // ── HUD / status ──────────────────────────────────────────────────────────

    private fun updateHud() {
        if (!settings.showHud) return
        val mapping = zoomEngine.map(zoom)
        val lens = currentLens
        val stabilisation = when {
            lens?.oisSupported == true && settings.videoStabilization != StabilizationMode.OFF -> "OIS"
            lens?.previewStabilizationSupported == true -> "EIS+"
            lens?.videoStabilizationSupported == true -> "EIS"
            else -> "OFF"
        }
        val focus = when {
            pro.focusMode == com.example.zoomhundred.model.FocusMode.MANUAL -> "MF ${(pro.focusPosition * 100).toInt()}%"
            pro.focusLocked -> "AF-L"
            else -> "AF-C"
        }
        binding.hudView.state = HudState(
            focalLengthMm = lens?.focalLengthsMm?.minOrNull() ?: 0f,
            focalLength35mm = ZoomEngine.focalLength35mm(zoom),
            zoom = mapping.total,
            nativeZoom = mapping.native,
            iso = if (pro.manualMode) pro.isoValue().toString() else "AUTO",
            shutter = if (pro.manualMode) ProSettings.SHUTTER_LABELS[pro.shutterIndex.coerceIn(0, ProSettings.SHUTTER_LABELS.lastIndex)] else "AUTO",
            ev = String.format(java.util.Locale.US, "%+.1f", pro.evValue()),
            whiteBalance = if (pro.wbMode == com.example.zoomhundred.model.WbMode.CUSTOM) "${pro.wbKelvin}K" else pro.wbMode.name.take(3),
            focus = focus,
            fps = settings.videoFps,
            resolution = settings.videoResolution.label,
            stabilization = stabilisation,
            hint = if (abs(rollDegrees) > 2f) "TILT ${String.format(java.util.Locale.US, "%.1f°", rollDegrees)}" else null,
            clipWarning = binding.histogramView.clipping
        )
    }

    private fun updateStatusHints() {
        if (settledCapturePending || coordinator.isBusy) return
        val hint = when {
            mode == CaptureMode.NIGHT -> getString(R.string.night_hint)
            mode == CaptureMode.ASTRO -> getString(R.string.astro_hint)
            mode == CaptureMode.PORTRAIT -> getString(R.string.portrait_software_note)
            mode == CaptureMode.FISHEYE -> getString(R.string.fisheye_note)
            coordinator.inSequence && mode == CaptureMode.PANORAMA -> getString(R.string.pano_guide_keep_going)
            coordinator.inSequence && mode == CaptureMode.LIGHT -> getString(R.string.light_trail_hint)
            coordinator.inSequence && mode == CaptureMode.TIMELAPSE -> getString(R.string.timelapse_hint)
            zoom >= EXTREME_ZOOM_THRESHOLD && !isStableForCapture() -> getString(R.string.capture_hold_still)
            else -> null
        }
        if (hint != null) {
            binding.captureStatusText.visibility = View.VISIBLE
            binding.captureStatusText.text = hint
        } else if (!coordinator.isBusy) {
            binding.captureStatusText.visibility = View.GONE
        }
    }

    // ── Anti-shake & stability ────────────────────────────────────────────────

    private fun startAntiShakeIfNeeded() {
        if (!settings.antiShake || !antiShake.isAvailable) {
            antiShake.stop()
            return
        }
        startAntiShake()
    }

    private fun startAntiShake() {
        antiShake.onShakeLevel = { level ->
            shakeLevel = level
            mainHandler.post {
                binding.shakeMeter.level = level
                binding.shakeMeterLabel.text = if (level < 0.2f)
                    getString(R.string.anti_shake_ready) else getString(R.string.anti_shake_wait)
                // Edge glow is the peripheral stability cue: it brightens as the hand drifts.
                val glow = (level * 1.4f).coerceIn(0f, 1f) * 0.75f
                binding.edgeGlowLeft.alpha = glow
                binding.edgeGlowRight.alpha = glow
                binding.edgeGlowTop.alpha = glow * 0.6f
                binding.edgeGlowBottom.alpha = glow * 0.6f
            }
            if (level < 0.18f) {
                if (stableSinceMs == 0L) stableSinceMs = SystemClock.uptimeMillis()
            } else {
                stableSinceMs = 0L
            }
            mainHandler.post { updateStatusHints() }
        }
        antiShake.start(threshold = pro.antiShakeThreshold, quietWindowMs = 150L)
    }

    private fun isStableForCapture(): Boolean =
        stableSinceMs != 0L && SystemClock.uptimeMillis() - stableSinceMs >= 180L

    // ── Freeze scope ──────────────────────────────────────────────────────────

    private fun updateFreezeScope(frame: LumaFrame) {
        val targetW = 360
        val scale = max(1, frame.width / targetW)
        val w = max(1, frame.width / scale)
        val h = max(1, frame.height / scale)
        val pixels = IntArray(w * h)
        try {
            for (y in 0 until h) {
                val row = (y * scale) * frame.rowStride
                for (x in 0 until w) {
                    val luma = frame.buffer.get(row + (x * scale) * frame.pixelStride).toInt() and 0xFF
                    pixels[y * w + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
        } catch (_: Exception) {
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        mainHandler.post {
            val previous = lastScopeBitmap
            lastScopeBitmap = bitmap
            binding.freezeScopeView.setImageBitmap(bitmap)
            previous?.recycle()
        }
    }

    private fun toggleFreezeScope() {
        if (binding.freezeScopeView.visibility == View.VISIBLE) {
            binding.freezeScopeView.visibility = View.GONE
            binding.freezeScopeView.setImageDrawable(null)
            return
        }
        if (lastScopeBitmap == null) {
            toast(getString(R.string.freeze_scope_wait))
            return
        }
        binding.freezeScopeView.visibility = View.VISIBLE
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    // ── Gestures ──────────────────────────────────────────────────────────────

    private inner class PinchListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            dragActive = false
            pinchStartZoom = zoom
            return !settings.zoomLocked
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (settings.zoomLocked) return false
            pinchStartZoom *= detector.scaleFactor
            setZoom(pinchStartZoom, userInitiated = true)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            snapZoomToPreset()
        }
    }

    private var dragStartY = 0f
    private var dragStartZoom = 1f
    private var dragStartX = 0f
    private var dragActive = false
    private var pinchStartZoom = 1f

    private fun handleGesture(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            dragActive = false
            return true
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                dragStartZoom = zoom
                dragActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (settings.zoomLocked) return true
                val dy = event.y - dragStartY
                val dx = event.x - dragStartX
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                    dragActive = true
                    // Drag up = zoom in, with zoom-dependent sensitivity.
                    val sensitivity = zoomEngine.dragSensitivity(zoom) * 2.4f
                    val factor = kotlin.math.exp(-dy * sensitivity).toFloat()
                    setZoom(dragStartZoom * factor, userInitiated = true)
                } else if (mode == CaptureMode.PRO && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    // Horizontal drag in Pro mode trims exposure compensation.
                    val steps = (dx / (slop * 4f)).toInt()
                    pro.evIndex = (pro.evIndex - steps).coerceIn(0, ProSettings.EV_VALUES.lastIndex)
                    engine.setExposureCompensation(pro.evValue())
                    updateHud()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragActive) snapZoomToPreset()
                dragActive = false
            }
            MotionEvent.ACTION_CANCEL -> dragActive = false
        }
        return true
    }

    private fun snapZoomToPreset() {
        val nearest = binding.zoomRail.presets.minByOrNull { abs(it.ratio - zoom) } ?: return
        if (abs(nearest.ratio - zoom) <= 0.05f) return
        if (abs(nearest.ratio - zoom) < nearest.ratio * 0.18f) animateZoom(nearest.ratio)
    }

    private fun focusAt(x: Float, y: Float) {
        val factory = binding.previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        engine.focusAndMeter(point)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        showFocusRing(x, y)
        if (zoom >= 10f) binding.extremeOverlay.lockTarget(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.visibility = View.VISIBLE
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.animate().cancel()
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.alpha = 1f
        ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(600L)
            .withEndAction { ring.visibility = View.GONE }
            .start()
    }

    // ── Precision zoom drawer ─────────────────────────────────────────────────

    private var drawerOpen = false
    private var drawerDownY = 0f

    private fun setDrawerOpen(open: Boolean) {
        if (drawerOpen == open) return
        drawerOpen = open
        val panel = binding.bottomPanel
        val closedOffset = dp(360).toFloat()
        if (open) {
            panel.visibility = View.VISIBLE
            panel.translationY = closedOffset
            panel.alpha = 0f
            panel.animate().translationY(0f).alpha(1f).setDuration(180L).start()
            if (!coordinator.isBusy) panel.postDelayed({
                if (drawerOpen) {
                    panel.animate().translationY(closedOffset).alpha(0f).setDuration(160L)
                        .withEndAction { if (!drawerOpen) panel.visibility = View.GONE }
                        .start()
                    drawerOpen = false
                }
            }, 4200L)
        } else {
            panel.animate().translationY(closedOffset).alpha(0f).setDuration(160L)
                .withEndAction { if (!drawerOpen) panel.visibility = View.GONE }
                .start()
        }
    }

    private fun handleDrawerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> drawerDownY = event.y
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (event.y - drawerDownY > slop * 3) {
                    setDrawerOpen(false)
                    return true
                }
            }
        }
        return false
    }

    // ── Sheets / navigation ───────────────────────────────────────────────────

    private fun openToolsSheet(showSecondaryModes: Boolean = false) {
        val lens = currentLens ?: return
        ToolsSheet(
            host = this,
            settings = settings,
            capabilities = capabilities,
            lens = lens,
            mode = mode,
            highlightSecondaryModes = showSecondaryModes,
            callbacks = object : ToolsSheet.Callbacks {
                override fun onSettingsMutated(rebind: Boolean) {
                    settings.save(this@MainActivity)
                    applySettingsToUi()
                    if (rebind && engine.isBound) bindCamera()
                }

                override fun onSelectMode(newMode: CaptureMode) {
                    setMode(newMode)
                }

                override fun onBurst() = coordinator.burst(buildShotContext(), settings.burstCount)

                override fun onBracket() = coordinator.bracket(buildShotContext())

                override fun onFocusStack() {
                    if (pro.focusMode != com.example.zoomhundred.model.FocusMode.MANUAL) {
                        toast(getString(R.string.focus_sweep_unavailable))
                        return
                    }
                    coordinator.focusStack(buildShotContext(), frames = 5, stepMs = 300L) { index, total ->
                        pro.focusPosition = index.toFloat() / (total - 1).coerceAtLeast(1)
                        engine.applySessionOptions(pro, lens, displayRotation(), videoConfig())
                    }
                }

                override fun onStartSequence(target: CaptureMode) {
                    when (target) {
                        CaptureMode.PANORAMA -> startSequence(CaptureMode.PANORAMA, SequenceConfig(16, 0L))
                        CaptureMode.LIGHT -> startSequence(
                            CaptureMode.LIGHT,
                            SequenceConfig(settings.nightFrames.coerceIn(4, 12), 420L)
                        )
                        CaptureMode.TIMELAPSE -> startSequence(
                            CaptureMode.TIMELAPSE,
                            SequenceConfig(
                                frames = (60 / settings.intervalSeconds.coerceAtLeast(1)).coerceIn(5, 120),
                                intervalMs = settings.intervalSeconds * 1000L
                            )
                        )
                        else -> setMode(target)
                    }
                }

                override fun onPickLut() {
                    lutPickerLauncher.launch(arrayOf("*/*"))
                }

                override fun onOpenSettings() = openSettings()

                override fun onOpenGallery() = openGallery()

                override fun onOpenPro() = openProPanel()
            }
        ).show()
    }

    private val lutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        ioExecutor.execute {
            val name = queryDisplayName(uri) ?: "imported_lut.cube"
            val file = contentResolver.openInputStream(uri)?.use { input ->
                com.example.zoomhundred.imaging.LutStore.importStream(filesDir, name, input)
            }
            mainHandler.post {
                if (file == null) {
                    toast("Could not read that .cube file")
                } else {
                    settings.lutName = file.name
                    settings.save(this)
                    toast("LUT imported: ${file.nameWithoutExtension}")
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun openProPanel() {
        val lens = currentLens ?: return
        ProPanelSheet(
            host = this,
            initial = pro.copyOf(),
            settings = settings,
            lens = lens,
            capabilities = capabilities,
            callbacks = object : ProPanelSheet.Callbacks {
                override fun onProChanged(updated: ProSettings, commit: Boolean) {
                    val exposureChanged = updated.manualMode != pro.manualMode ||
                        updated.isoIndex != pro.isoIndex || updated.shutterIndex != pro.shutterIndex
                    pro = updated
                    pro.save(this@MainActivity)
                    binding.proModeBadge.visibility =
                        if (pro.manualMode || mode == CaptureMode.PRO) View.VISIBLE else View.GONE
                    engine.applySessionOptions(pro, lens, displayRotation(), videoConfig())
                    updateHud()
                    if (commit && exposureChanged && engine.isBound) bindCamera()
                }

                override fun onMonitoringChanged(
                    histogram: Boolean,
                    peaking: Boolean,
                    zebra: Boolean,
                    level: Boolean
                ) {
                    settings.histogramEnabled = histogram
                    settings.peakingEnabled = peaking
                    settings.zebraskaEnabled = zebra
                    settings.levelEnabled = level
                    monitorMode = when {
                        peaking -> 2
                        zebra -> 3
                        histogram -> 1
                        else -> 0
                    }
                    settings.save(this@MainActivity)
                    applySettingsToUi()
                }
            }
        ).show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // ── Media & misc helpers ──────────────────────────────────────────────────

    private fun refreshThumbnail() {
        ioExecutor.execute {
            val latest = ImageStore.latestPhoto(contentResolver, settings.saveSubfolder)
            val bitmap = latest?.let {
                runCatching { contentResolver.loadThumbnail(it.uri, Size(160, 160), null) }.getOrNull()
            }
            mainHandler.post {
                if (bitmap != null) {
                    binding.thumbnailView.setImageBitmap(bitmap)
                    binding.thumbnailView.alpha = 1f
                } else {
                    binding.thumbnailView.setImageResource(R.drawable.ic_gallery)
                    binding.thumbnailView.alpha = 0.7f
                }
            }
        }
    }

    private fun playShutterSound() {
        if (!settings.shutterSound || settings.shutterVolume <= 0) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, settings.shutterVolume.coerceIn(0, 100))
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 45)
            mainHandler.postDelayed({ tone.release() }, 140L)
        } catch (_: RuntimeException) {
            // No sound hardware: capture should still work.
        }
    }

    private fun haptic(type: Int) {
        if (!settings.hapticsEnabled) return
        binding.root.performHapticFeedback(type)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun displayRotation(): Int =
        binding.previewView.display?.rotation ?: Surface.ROTATION_0

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAspectGuide()
            updateLoupeSource()
        }
    }
}

// ── Small view extensions used above ─────────────────────────────────────────

private fun View.visibleIf(condition: Boolean) {
    visibility = if (condition) View.VISIBLE else View.GONE
}
