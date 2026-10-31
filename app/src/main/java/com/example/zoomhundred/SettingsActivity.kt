package com.example.zoomhundred

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.zoomhundred.camera.CapabilityDetector
import com.example.zoomhundred.camera.DeviceCapabilities
import com.example.zoomhundred.camera.FeatureGate
import com.example.zoomhundred.diagnostics.DiagnosticsExporter
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.AspectRatio
import com.example.zoomhundred.model.CaptureMode
import com.example.zoomhundred.model.ColorProfile
import com.example.zoomhundred.model.FrameStyle
import com.example.zoomhundred.model.GridMode
import com.example.zoomhundred.model.PhotoFormat
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.StabilizationMode
import com.example.zoomhundred.model.VideoCodec
import com.example.zoomhundred.model.VideoResolution
import com.example.zoomhundred.model.VolumeKeyAction
import com.example.zoomhundred.ui.SheetUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Twelve grouped setting categories, all generated from the current settings object and
 * the detected hardware, so a row can never describe a capability the phone lacks.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private var capabilities: DeviceCapabilities? = null

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var subtitle: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        settings = AppSettings.load(this)
        container = findViewById(R.id.settingsContainer)
        scroll = findViewById(R.id.settingsScroll)
        subtitle = findViewById(R.id.settingsSubtitle)

        subtitle.text = "v${appVersion()} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        findViewById<TextView>(R.id.settingsBack).setOnClickListener { finish() }

        applyInsets()
        build()

        ioExecutor.execute {
            val detected = CapabilityDetector.detect(this)
            mainHandler.post {
                capabilities = detected
                build()
            }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun applyInsets() {
        val header = findViewById<View>(R.id.settingsHeader)
        val base = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, base + bars.top, header.paddingRight, header.paddingBottom)
            insets
        }
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private fun build() {
        val scrollY = scroll.scrollY
        container.removeAllViews()
        val column = SheetUi.column(this, horizontalPaddingDp = 18f, topPaddingDp = 2f)
        container.addView(column)

        addCapture(column)
        addPhoto(column)
        addVideo(column)
        addZoom(column)
        addComputational(column)
        addProAndMonitoring(column)
        addComposition(column)
        addLook(column)
        addControls(column)
        addStorage(column)
        addPrivacy(column)
        addAbout(column)

        column.addView(SheetUi.spacer(this, 12f))
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun addCapture(column: LinearLayout) {
        column.addView(SheetUi.section(this, "1 · Capture"))
        val modes = CaptureMode.PRIMARY.filter { it != CaptureMode.MORE }
        column.addView(SheetUi.chipBlock(this, modes.map {
            SheetUi.ChipOption(it.shortLabel, settings.defaultMode == it)
        }) { index ->
            settings.defaultMode = modes[index]
            persist()
        })
        column.addView(SheetUi.actionRow(this, "Self timer", if (settings.timerSeconds == 0) "Off" else "${settings.timerSeconds}s") {
            settings.timerSeconds = when (settings.timerSeconds) {
                0 -> 3; 3 -> 10; else -> 0
            }
            persist()
        })
        column.addView(SheetUi.chipBlock(this, listOf(3, 5, 10).map {
            SheetUi.ChipOption("Burst $it", settings.burstCount == it)
        }) { index ->
            settings.burstCount = listOf(3, 5, 10)[index]
            persist()
        })
        val flashes = com.example.zoomhundred.model.FlashMode.entries
        column.addView(SheetUi.chipBlock(this, flashes.map {
            SheetUi.ChipOption(
                when (it) {
                    com.example.zoomhundred.model.FlashMode.OFF -> "Flash off"
                    com.example.zoomhundred.model.FlashMode.AUTO -> "Flash auto"
                    com.example.zoomhundred.model.FlashMode.ON -> "Flash on"
                    com.example.zoomhundred.model.FlashMode.TORCH -> "Torch"
                },
                settings.flashMode == it,
                enabled = capabilities?.backLenses?.any { lens -> lens.hasFlash } ?: true
            )
        }) { index ->
            settings.flashMode = flashes[index]
            persist()
        })
        column.addView(SheetUi.switchRow(this, "Mirror selfie preview", null, settings.mirrorSelfie) {
            settings.mirrorSelfie = it; persist()
        })
    }

    private fun addPhoto(column: LinearLayout) {
        column.addView(SheetUi.section(this, "2 · Photo"))
        val rawSupported = capabilities?.lenses?.any { it.supportsRaw } ?: true
        val formats = PhotoFormat.entries
        column.addView(SheetUi.chipBlock(this, formats.map { format ->
            val needsRaw = format == PhotoFormat.RAW || format == PhotoFormat.RAW_JPEG
            SheetUi.ChipOption(
                if (needsRaw && !rawSupported) "${format.label} · no" else format.label,
                settings.photoFormat == format,
                enabled = !needsRaw || rawSupported
            )
        }) { index ->
            settings.photoFormat = formats[index]
            persist()
        })
        column.addView(SheetUi.chipBlock(this, AspectRatio.QUICK.map {
            SheetUi.ChipOption(it.label, settings.defaultAspect == it)
        }) { index ->
            settings.defaultAspect = AspectRatio.QUICK[index]
            persist()
        })
        column.addView(SheetUi.switchRow(this, "HDR multi-frame", "Fuses 3–5 bracketed frames", settings.hdrEnabled) {
            settings.hdrEnabled = it; persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "Night frames", valueText = "${settings.nightFrames}",
            from = 2f, to = 12f, step = 1f, initial = settings.nightFrames.toFloat(),
            onValue = { settings.nightFrames = it.toInt() },
            onCommit = { settings.nightFrames = it.toInt(); persist() }
        ))
        column.addView(SheetUi.sliderRow(
            context = this, label = "Astro frames", valueText = "${settings.astroFrames}",
            from = 2f, to = 10f, step = 1f, initial = settings.astroFrames.toFloat(),
            onValue = { settings.astroFrames = it.toInt() },
            onCommit = { settings.astroFrames = it.toInt(); persist() }
        ))
        column.addView(SheetUi.sliderRow(
            context = this, label = "Astro exposure", valueText = "${settings.astroExposureSeconds}s",
            from = 1f, to = 30f, step = 1f, initial = settings.astroExposureSeconds.toFloat(),
            onValue = { settings.astroExposureSeconds = it.toInt() },
            onCommit = { settings.astroExposureSeconds = it.toInt(); persist() }
        ))
        column.addView(SheetUi.sliderRow(
            context = this, label = "Portrait blur", valueText = "${settings.portraitStrength}%",
            from = 10f, to = 100f, step = 5f, initial = settings.portraitStrength.toFloat(),
            onValue = { settings.portraitStrength = it.toInt() },
            onCommit = { settings.portraitStrength = it.toInt(); persist() }
        ))
        column.addView(SheetUi.sliderRow(
            context = this, label = "Fisheye strength", valueText = "${settings.fisheyeStrength}%",
            from = 10f, to = 100f, step = 5f, initial = settings.fisheyeStrength.toFloat(),
            onValue = { settings.fisheyeStrength = it.toInt() },
            onCommit = { settings.fisheyeStrength = it.toInt(); persist() }
        ))
    }

    private fun addVideo(column: LinearLayout) {
        column.addView(SheetUi.section(this, "3 · Video"))
        val lens = capabilities?.backLenses?.firstOrNull()
        val resolutions = VideoResolution.entries
        column.addView(SheetUi.chipBlock(this, resolutions.map { resolution ->
            val state = capabilities?.supportsVideo(resolution, settings.videoFps, lens)
            SheetUi.ChipOption(resolution.label, settings.videoResolution == resolution, enabled = state?.usable ?: true)
        }) { index ->
            settings.videoResolution = resolutions[index]
            persist()
        })
        val fpsChoices = listOf(24, 30, 60)
        column.addView(SheetUi.chipBlock(this, fpsChoices.map { fps ->
            val state = capabilities?.supportsVideo(settings.videoResolution, fps, lens)
            val encoderOk = when {
                fps <= 30 -> true
                settings.videoResolution == VideoResolution.UHD -> capabilities?.encoders?.supports4k60 ?: true
                else -> capabilities?.encoders?.supports1080p60 ?: true
            }
            SheetUi.ChipOption("$fps fps", settings.videoFps == fps, enabled = (state?.usable ?: true) && encoderOk)
        }) { index ->
            settings.videoFps = fpsChoices[index]
            persist()
        })
        val stabilisations = StabilizationMode.entries
        column.addView(SheetUi.chipBlock(this, stabilisations.map { mode ->
            val state = FeatureGate.stabilization(lens, mode)
            SheetUi.ChipOption(
                when (mode) {
                    StabilizationMode.OFF -> "Off"
                    StabilizationMode.OPTICAL -> "OIS"
                    StabilizationMode.ELECTRONIC -> "EIS"
                    StabilizationMode.AUTO -> "Auto"
                },
                settings.videoStabilization == mode,
                enabled = state.usable
            )
        }) { index ->
            settings.videoStabilization = stabilisations[index]
            persist()
        })
        val codecs = VideoCodec.entries
        column.addView(SheetUi.chipBlock(this, codecs.map { codec ->
            SheetUi.ChipOption(
                codec.label,
                settings.videoCodec == codec,
                enabled = codec != VideoCodec.HEVC || (capabilities?.encoders?.hevc ?: true)
            )
        }) { index ->
            settings.videoCodec = codecs[index]
            persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "Bitrate", valueText = if (settings.videoBitrateMbps == 0) "Encoder default" else "${settings.videoBitrateMbps} Mbps",
            from = 0f, to = 120f, step = 5f, initial = settings.videoBitrateMbps.toFloat(),
            onValue = { settings.videoBitrateMbps = it.toInt() },
            onCommit = { settings.videoBitrateMbps = it.toInt(); persist() }
        ))
        column.addView(SheetUi.switchRow(this, "Record audio", null, settings.recordAudio) {
            settings.recordAudio = it; persist()
        })
        column.addView(SheetUi.note(this, "Slow motion and log video are not offered: this pipeline has no high-speed session and no sensor log curve. A software flat profile and .cube LUTs are available instead."))
    }

    private fun addZoom(column: LinearLayout) {
        column.addView(SheetUi.section(this, "4 · Zoom"))
        column.addView(SheetUi.note(this, "The 100× engine keeps the optical module at its native ratio and adds a digital crop on top; the crop is applied to the saved file, so the photo matches the frame."))
        column.addView(SheetUi.switchRow(this, "Zoom lock", "Prevents accidental zoom changes", settings.zoomLocked) {
            settings.zoomLocked = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Settled capture", "Waits for stillness above 15× before firing", settings.antiShake) {
            settings.antiShake = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Zoom enhancement", "Detail/contrast pass after digital crop", settings.zoomEnhance) {
            settings.zoomEnhance = it; persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "Enhancement strength", valueText = "${settings.zoomEnhanceStrength}%",
            from = 20f, to = 100f, step = 5f, initial = settings.zoomEnhanceStrength.toFloat(),
            onValue = { settings.zoomEnhanceStrength = it.toInt() },
            onCommit = { settings.zoomEnhanceStrength = it.toInt(); persist() }
        ))
        column.addView(SheetUi.switchRow(this, "Zoom labels", null, settings.showZoomLabels) {
            settings.showZoomLabels = it; persist()
        })
    }

    private fun addComputational(column: LinearLayout) {
        column.addView(SheetUi.section(this, "5 · Computational"))
        column.addView(SheetUi.sliderRow(
            context = this, label = "HDR frames", valueText = "${settings.hdrFrames}",
            from = 2f, to = 5f, step = 1f, initial = settings.hdrFrames.toFloat(),
            onValue = { settings.hdrFrames = it.toInt() },
            onCommit = { settings.hdrFrames = it.toInt(); persist() }
        ))
        column.addView(SheetUi.switchRow(this, "Tripod night mode", "Longer merge window when stillness holds", settings.nightTripodMode) {
            settings.nightTripodMode = it; persist()
        })
        column.addView(SheetUi.actionRow(this, "Interval stills", "${settings.intervalSeconds}s") {
            settings.intervalSeconds = when (settings.intervalSeconds) {
                1 -> 2; 2 -> 3; 3 -> 5; 5 -> 10; else -> 1
            }
            persist()
        })
    }

    private fun addProAndMonitoring(column: LinearLayout) {
        column.addView(SheetUi.section(this, "6 · Pro & monitoring"))
        column.addView(SheetUi.switchRow(this, "Technical HUD", "ISO, shutter, focal length, stabilisation", settings.showHud) {
            settings.showHud = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Histogram", null, settings.histogramEnabled) {
            settings.histogramEnabled = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Focus peaking", null, settings.peakingEnabled) {
            settings.peakingEnabled = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Zebra", null, settings.zebraskaEnabled) {
            settings.zebraskaEnabled = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Spirit level", null, settings.levelEnabled) {
            settings.levelEnabled = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Focus loupe", "Drag it anywhere on the preview", settings.showLoupe) {
            settings.showLoupe = it; persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "Loupe size", valueText = "${settings.loupeSizeDp}dp",
            from = 72f, to = 168f, step = 4f, initial = settings.loupeSizeDp.toFloat(),
            onValue = { settings.loupeSizeDp = it.toInt() },
            onCommit = { settings.loupeSizeDp = it.toInt(); persist() }
        ))
    }

    private fun addComposition(column: LinearLayout) {
        column.addView(SheetUi.section(this, "7 · Composition"))
        val grids = GridMode.entries
        column.addView(SheetUi.chipBlock(this, grids.map {
            SheetUi.ChipOption(
                when (it) {
                    GridMode.NONE -> "Off"
                    GridMode.THIRDS -> "Thirds"
                    GridMode.GOLDEN -> "Golden"
                    GridMode.SPIRAL -> "Spiral"
                    GridMode.CROSS -> "Cross"
                    GridMode.DIAGONAL -> "Diagonal"
                    GridMode.SQUARE -> "Square"
                },
                settings.gridMode == it
            )
        }) { index ->
            settings.gridMode = grids[index]
            persist()
        })
        column.addView(SheetUi.switchRow(this, "Night UI", "Dim chrome for dark scenes", settings.nightUi) {
            settings.nightUi = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Thumbnail", "Show the last shot next to the shutter", settings.showThumbnail) {
            settings.showThumbnail = it; persist()
        })
    }

    private fun addLook(column: LinearLayout) {
        column.addView(SheetUi.section(this, "8 · Look"))
        column.addView(SheetUi.chipBlock(this, ColorProfile.entries.map {
            SheetUi.ChipOption(it.label, settings.colorProfile == it)
        }) { index ->
            settings.colorProfile = ColorProfile.entries[index]
            persist()
        })
        column.addView(SheetUi.chipBlock(this, FrameStyle.entries.map {
            SheetUi.ChipOption(it.label, settings.frameStyle == it)
        }) { index ->
            settings.frameStyle = FrameStyle.entries[index]
            persist()
        })
        column.addView(SheetUi.actionRow(this, "3D LUT", settings.lutName ?: "None") {
            settings.lutName = null
            persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "LUT intensity", valueText = "${settings.lutIntensity}%",
            from = 0f, to = 100f, step = 5f, initial = settings.lutIntensity.toFloat(),
            onValue = { settings.lutIntensity = it.toInt() },
            onCommit = { settings.lutIntensity = it.toInt(); persist() }
        ))
        column.addView(SheetUi.note(this, "Import .cube files from the Tools sheet; they are parsed locally and copied into app storage."))
    }

    private fun addControls(column: LinearLayout) {
        column.addView(SheetUi.section(this, "9 · Controls"))
        val volumeActions = VolumeKeyAction.entries
        column.addView(SheetUi.chipBlock(this, volumeActions.map {
            SheetUi.ChipOption(
                when (it) {
                    VolumeKeyAction.ZOOM -> "Volume = zoom"
                    VolumeKeyAction.SHUTTER -> "Volume = shutter"
                    VolumeKeyAction.OFF -> "Volume = off"
                },
                settings.volumeKeyAction == it
            )
        }) { index ->
            settings.volumeKeyAction = volumeActions[index]
            persist()
        })
        column.addView(SheetUi.switchRow(this, "Haptics", null, settings.hapticsEnabled) {
            settings.hapticsEnabled = it; persist()
        })
        column.addView(SheetUi.switchRow(this, "Shutter sound", null, settings.shutterSound) {
            settings.shutterSound = it; persist()
        })
        column.addView(SheetUi.sliderRow(
            context = this, label = "Shutter volume", valueText = "${settings.shutterVolume}%",
            from = 0f, to = 100f, step = 5f, initial = settings.shutterVolume.toFloat(),
            onValue = { settings.shutterVolume = it.toInt() },
            onCommit = { settings.shutterVolume = it.toInt(); persist() }
        ))
        column.addView(SheetUi.switchRow(this, "Keep screen on", null, settings.keepScreenOn) {
            settings.keepScreenOn = it
            if (it) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            persist()
        })
        column.addView(SheetUi.switchRow(this, "Show gesture hints", "Overlay shown on first launch", !settings.onboardingShown) {
            settings.onboardingShown = !it
            persist()
        })
    }

    private fun addStorage(column: LinearLayout) {
        column.addView(SheetUi.section(this, "10 · Storage"))
        column.addView(SheetUi.actionRow(this, "Album folder", settings.saveSubfolder) {
            promptSubfolder()
        })
        column.addView(SheetUi.switchRow(this, "Keep original frame", "Saves the pre-processed file beside the developed photo", settings.keepOriginals) {
            settings.keepOriginals = it; persist()
        })
        column.addView(SheetUi.note(this, "Files are written to MediaStore/Pictures and MediaStore/Movies — no private paths are exposed and nothing is uploaded."))
    }

    private fun addPrivacy(column: LinearLayout) {
        column.addView(SheetUi.section(this, "11 · Privacy & data"))
        column.addView(SheetUi.switchRow(this, "Strip location EXIF", "Removes GPS tags from saved photos", settings.stripGps) {
            settings.stripGps = it; persist()
        })
        column.addView(SheetUi.actionRow(this, "Export diagnostics", "Capability + settings report, no location") {
            exportDiagnostics()
        })
        column.addView(SheetUi.actionRow(this, "Export presets", "JSON with your zoom presets and settings") {
            exportPresets()
        })
        column.addView(SheetUi.note(this, "Everything is processed on device. The app requests no network permission and performs no analytics."))
    }

    private fun addAbout(column: LinearLayout) {
        column.addView(SheetUi.section(this, "12 · About"))
        val caps = capabilities
        if (caps == null) {
            column.addView(SheetUi.note(this, "Reading camera characteristics…"))
        } else {
            column.addView(SheetUi.note(this, "Detected ${caps.lenses.size} camera module(s) · gyro ${if (caps.hasGyroscope) "yes" else "no"} · 4K60 encoder ${if (caps.encoders.supports4k60) "yes" else "no"}"))
            caps.lenses.take(4).forEach { lens ->
                column.addView(SheetUi.note(this, "${lens.label}: ${lens.maxPhotoMegapixels.toInt()} MP · ${String.format(Locale.US, "%.0f°", lens.fovHorizontalDeg)} FoV · RAW ${if (lens.supportsRaw) "yes" else "no"} · OIS ${if (lens.oisSupported) "yes" else "no"}"))
            }
        }
        column.addView(SheetUi.actionRow(this, "Privacy & licences", "Open source notices") {
            startActivity(android.content.Intent(this, AppInfoActivity::class.java))
        })
        column.addView(SheetUi.buttonStrip(this, listOf(
            "Reset all settings" to { confirmReset() }
        ), primaryFirst = false))
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun promptSubfolder() {
        val input = EditText(this).apply {
            setText(settings.saveSubfolder)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Album folder")
            .setMessage("Photos are stored under Pictures/<folder>.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val cleaned = input.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "")
                if (cleaned.isNotEmpty()) {
                    settings.saveSubfolder = cleaned
                    persist()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset all settings?")
            .setMessage("Restores every default. Media already captured is untouched.")
            .setPositiveButton("Reset") { _, _ ->
                settings.reset(this)
                ProSettings.reset(this)
                settings = AppSettings.load(this)
                build()
                toast("Settings reset")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportDiagnostics() {
        val caps = capabilities
        if (caps == null) {
            toast("Still reading camera capabilities")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val body = DiagnosticsExporter.buildReport(this, settings, ProSettings.load(this), caps)
        val uri = DiagnosticsExporter.export(this, "open100x-diagnostics-$stamp.json", body)
        toast(if (uri != null) "Diagnostics saved to Downloads/Open100x" else "Could not write diagnostics")
    }

    private fun exportPresets() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val root = org.json.JSONObject()
        root.put("app", "Open100x")
        root.put("exportedAtUtc", System.currentTimeMillis())
        root.put("zoomPresets", org.json.JSONArray(settings.zoomPresets))
        val map = org.json.JSONObject()
        settings.toDiagnosticMap().forEach { (key, value) -> map.put(key, value) }
        root.put("settings", map)
        val pro = ProSettings.load(this)
        root.put("pro", org.json.JSONObject().apply {
            put("manualMode", pro.manualMode)
            put("iso", pro.isoValue())
            put("shutter", pro.shutterNanos())
            put("ev", pro.evValue().toString())
            put("kelvin", pro.wbKelvin)
        })
        val uri = DiagnosticsExporter.export(
            this,
            "open100x-presets-$stamp.json",
            root.toString(2)
        )
        toast(if (uri != null) "Presets saved to Downloads/Open100x" else "Could not write presets")
    }

    private fun persist() {
        settings.save(this)
        build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
