package com.example.zoomhundred.ui

import android.content.Context
import android.widget.ScrollView
import com.example.zoomhundred.camera.DeviceCapabilities
import com.example.zoomhundred.camera.FeatureGate
import com.example.zoomhundred.camera.FeatureState
import com.example.zoomhundred.camera.LensCapabilities
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.AspectRatio
import com.example.zoomhundred.model.CaptureMode
import com.example.zoomhundred.model.ColorProfile
import com.example.zoomhundred.model.FrameStyle
import com.example.zoomhundred.model.GridMode
import com.example.zoomhundred.model.PhotoFormat
import com.example.zoomhundred.model.StabilizationMode
import com.example.zoomhundred.model.VideoCodec
import com.example.zoomhundred.model.VideoResolution
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * The "everything else" sheet.
 *
 * The viewfinder stays the primary surface, so anything that is not needed for every
 * frame lives here: capture modes that are not on the main strip, multi-frame tools,
 * look controls, monitoring toggles and video settings.
 *
 * Every option is rendered from [FeatureState]. A control the hardware cannot honour is
 * dimmed, labelled with the reason and never writes a setting — the app never pretends.
 */
class ToolsSheet(
    private val host: Context,
    private val settings: AppSettings,
    private val capabilities: DeviceCapabilities,
    private val lens: LensCapabilities,
    private val mode: CaptureMode,
    private val highlightSecondaryModes: Boolean,
    private val callbacks: Callbacks
) : BottomSheetDialog(host) {

    interface Callbacks {
        fun onSettingsMutated(rebind: Boolean)
        fun onSelectMode(mode: CaptureMode)
        fun onBurst()
        fun onBracket()
        fun onFocusStack()
        fun onStartSequence(target: CaptureMode)
        fun onPickLut()
        fun onOpenSettings()
        fun onOpenGallery()
        fun onOpenPro()
    }

    private val content = SheetUi.column(host)
    private val scroll = ScrollView(host).apply {
        isFillViewport = true
        addView(content)
    }

    init {
        setContentView(scroll)
        rebuild()
    }

    private fun rebuild() {
        val scrollY = scroll.scrollY
        content.removeAllViews()

        content.addView(
            SheetUi.header(
                context = host,
                title = "Tools",
                subtitle = "Everything below is gated by what this sensor can actually do",
                actionLabel = "Done"
            ) { dismiss() }
        )

        if (highlightSecondaryModes) addModeSection()
        addSequenceSection()
        addPhotoSection()
        addLookSection()
        addMonitoringSection()
        addVideoSection()
        addFooter()

        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    private fun addModeSection() {
        content.addView(SheetUi.section(host, "Capture modes"))
        val modes = CaptureMode.SECONDARY
        content.addView(
            SheetUi.chipBlock(host, modes.map { candidate ->
                val state = stateFor(candidate)
                SheetUi.ChipOption(
                    label = "${candidate.shortLabel} · ${state.label}",
                    selected = mode == candidate,
                    enabled = state.usable,
                    hint = describe(candidate, state)
                )
            }) { index ->
                callbacks.onSelectMode(modes[index])
                rebuild()
            }
        )
        content.addView(
            SheetUi.note(
                host,
                "Fisheye and Portrait are software-rendered — the frame is real, the optics are not."
            )
        )
    }

    private fun stateFor(candidate: CaptureMode): FeatureState = when (candidate) {
        CaptureMode.LIGHT -> FeatureGate.lightTrails()
        CaptureMode.TIMELAPSE -> FeatureGate.timeLapse()
        CaptureMode.FISHEYE -> FeatureGate.fisheye()
        CaptureMode.PORTRAIT -> FeatureGate.portrait()
        CaptureMode.PANORAMA -> FeatureGate.panorama()
        CaptureMode.ASTRO -> FeatureGate.astro(lens)
        else -> FeatureState.FULL
    }

    private fun describe(candidate: CaptureMode, state: FeatureState): String = when (candidate) {
        CaptureMode.LIGHT -> "Additive light-trail stacking on the capture engine"
        CaptureMode.TIMELAPSE -> "Interval stills saved to the gallery"
        CaptureMode.FISHEYE -> "Software barrel distortion, ${state.label.lowercase(Locale.US)}"
        else -> "${candidate.label} — ${state.label}"
    }

    private fun addSequenceSection() {
        content.addView(SheetUi.section(host, "Multi-frame tools"))
        content.addView(
            SheetUi.actionRow(
                host, "Burst", "${settings.burstCount} frames"
            ) { callbacks.onBurst(); dismiss() }
        )
        content.addView(
            SheetUi.actionRow(
                host, "Exposure bracket", "5 stops"
            ) { callbacks.onBracket(); dismiss() }
        )
        content.addView(
            SheetUi.actionRow(
                host,
                "Focus stack",
                if (lens.minFocusDistanceDiopters > 0f) "5 steps" else "Manual focus unavailable"
            ) {
                if (lens.minFocusDistanceDiopters > 0f) {
                    callbacks.onFocusStack()
                    dismiss()
                }
            }
        )
        content.addView(
            SheetUi.chipBlock(
                host,
                listOf(
                    SheetUi.ChipOption("Burst 3", settings.burstCount == 3),
                    SheetUi.ChipOption("Burst 5", settings.burstCount == 5),
                    SheetUi.ChipOption("Burst 10", settings.burstCount == 10)
                )
            ) { index ->
                settings.burstCount = listOf(3, 5, 10)[index]
                mutate(rebind = false)
            }
        )
    }

    private fun addPhotoSection() {
        content.addView(SheetUi.section(host, "Photo"))
        content.addView(
            SheetUi.chipBlock(
                host,
                AspectRatio.QUICK.map { SheetUi.ChipOption(it.label, settings.defaultAspect == it) }
            ) { index ->
                settings.defaultAspect = AspectRatio.QUICK[index]
                mutate(rebind = false)
            }
        )
        content.addView(SheetUi.spacer(host, 6f))

        val rawState = FeatureGate.raw(lens)
        val formats = PhotoFormat.entries
        content.addView(
            SheetUi.chipBlock(host, formats.map { format ->
                val needsRaw = format == PhotoFormat.RAW || format == PhotoFormat.RAW_JPEG
                val usable = !needsRaw || rawState.usable
                SheetUi.ChipOption(
                    label = if (usable) format.label else "${format.label} · unavailable",
                    selected = settings.photoFormat == format,
                    enabled = usable
                )
            }) { index ->
                settings.photoFormat = formats[index]
                mutate(rebind = true)
            }
        )
        content.addView(
            SheetUi.note(
                host,
                if (rawState.usable) "RAW is captured as the sensor's DNG, never re-encoded."
                else "This camera does not expose RAW capture, so only JPEG/WebP are offered."
            )
        )

        content.addView(
            SheetUi.switchRow(
                host,
                "HDR multi-frame",
                "3–5 bracketed exposures fused on device",
                settings.hdrEnabled
            ) { checked ->
                settings.hdrEnabled = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(
                host,
                "Keep untouched original",
                "Stores the pre-processed frame next to the developed photo",
                settings.keepOriginals
            ) { checked ->
                settings.keepOriginals = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.actionRow(
                host,
                "Self timer",
                if (settings.timerSeconds == 0) "Off" else "${settings.timerSeconds}s"
            ) {
                settings.timerSeconds = when (settings.timerSeconds) {
                    0 -> 3
                    3 -> 10
                    else -> 0
                }
                mutate(rebind = false)
            }
        )
    }

    private fun addLookSection() {
        content.addView(SheetUi.section(host, "Look"))
        content.addView(
            SheetUi.chipBlock(
                host,
                ColorProfile.entries.map { SheetUi.ChipOption(it.label, settings.colorProfile == it) }
            ) { index ->
                settings.colorProfile = ColorProfile.entries[index]
                mutate(rebind = false)
            }
        )
        content.addView(SheetUi.spacer(host, 6f))
        content.addView(
            SheetUi.chipBlock(
                host,
                FrameStyle.entries.map { SheetUi.ChipOption(it.label, settings.frameStyle == it) }
            ) { index ->
                settings.frameStyle = FrameStyle.entries[index]
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.actionRow(
                host,
                "3D LUT",
                settings.lutName?.substringBeforeLast('.') ?: "None"
            ) {
                if (settings.lutName == null) callbacks.onPickLut()
                else {
                    settings.lutName = null
                    mutate(rebind = false)
                }
            }
        )
        content.addView(
            SheetUi.buttonStrip(
                host,
                listOf(
                    "Import .cube" to { callbacks.onPickLut() },
                    "Clear LUT" to {
                        settings.lutName = null
                        mutate(rebind = false)
                        callbacks.onSettingsMutated(false)
                    }
                ),
                primaryFirst = false
            )
        )
        content.addView(
            SheetUi.note(
                host,
                "LUTs are parsed on device and never uploaded. Import copies the file into app storage."
            )
        )
    }

    private fun addMonitoringSection() {
        content.addView(SheetUi.section(host, "Monitoring"))
        val grids = GridMode.entries
        content.addView(
            SheetUi.actionRow(host, "Composition guide", settings.gridMode.name.lowercase(Locale.US).replace('_', ' ')) {
                settings.gridMode = grids[(settings.gridMode.ordinal + 1) % grids.size]
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Technical HUD", "ISO, shutter, focal length, stabilisation", settings.showHud) { checked ->
                settings.showHud = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Focus loupe", "Magnified live crop you can drag", settings.showLoupe) { checked ->
                settings.showLoupe = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Spirit level", "Horizon roll and pitch", settings.levelEnabled) { checked ->
                settings.levelEnabled = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Histogram", "Live luma distribution", settings.histogramEnabled) { checked ->
                settings.histogramEnabled = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Focus peaking", "High-contrast edges highlighted", settings.peakingEnabled) { checked ->
                settings.peakingEnabled = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Zebra stripes", "Highlights above 95% IRE", settings.zebraEnabled) { checked ->
                settings.zebraEnabled = checked
                mutate(rebind = false)
            }
        )
    }

    private fun addVideoSection() {
        content.addView(SheetUi.section(host, "Video"))
        val resolutions = VideoResolution.entries
        content.addView(
            SheetUi.chipBlock(host, resolutions.map { resolution ->
                val state = capabilities.supportsVideo(resolution, settings.videoFps, lens)
                SheetUi.ChipOption(
                    label = if (state.usable) resolution.label else "${resolution.label} · no",
                    selected = settings.videoResolution == resolution,
                    enabled = state.usable
                )
            }) { index ->
                settings.videoResolution = resolutions[index]
                mutate(rebind = true)
            }
        )
        content.addView(SheetUi.spacer(host, 6f))

        val fpsChoices = listOf(24, 30, 60)
        content.addView(
            SheetUi.chipBlock(host, fpsChoices.map { fps ->
                val state = capabilities.supportsVideo(settings.videoResolution, fps, lens)
                val encoderOk = when {
                    fps <= 30 -> true
                    settings.videoResolution == VideoResolution.UHD -> capabilities.encoders.supports4k60
                    else -> capabilities.encoders.supports1080p60
                }
                val usable = state.usable && encoderOk
                SheetUi.ChipOption(
                    "${fps} fps" + if (usable) "" else " · no",
                    selected = settings.videoFps == fps,
                    enabled = usable,
                    hint = if (!encoderOk) "Encoder cannot sustain this combination" else null
                )
            }) { index ->
                settings.videoFps = fpsChoices[index]
                mutate(rebind = true)
            }
        )
        content.addView(
            SheetUi.note(
                host,
                "4K60 is only offered when both the stream map and a hardware encoder report 3840×2160 at 58 fps or better."
            )
        )

        val stabilisations = StabilizationMode.entries
        content.addView(
            SheetUi.chipBlock(host, stabilisations.map { stabilisation ->
                val state = FeatureGate.stabilization(lens, stabilisation)
                SheetUi.ChipOption(
                    label = when (stabilisation) {
                        StabilizationMode.OFF -> "Stabilisation off"
                        StabilizationMode.OPTICAL -> "OIS"
                        StabilizationMode.ELECTRONIC -> "EIS"
                        StabilizationMode.AUTO -> "Auto"
                    } + if (state.usable) "" else " · no",
                    selected = settings.videoStabilization == stabilisation,
                    enabled = state.usable,
                    hint = "OIS and EIS are separate modes and are never combined into one claim"
                )
            }) { index ->
                settings.videoStabilization = stabilisations[index]
                mutate(rebind = true)
            }
        )

        val codecs = VideoCodec.entries
        content.addView(
            SheetUi.chipBlock(host, codecs.map { codec ->
                val usable = codec != VideoCodec.HEVC || capabilities.encoders.hevc
                SheetUi.ChipOption(
                    codec.label + if (usable) "" else " · no",
                    selected = settings.videoCodec == codec,
                    enabled = usable
                )
            }) { index ->
                settings.videoCodec = codecs[index]
                mutate(rebind = true)
            }
        )

        content.addView(
            SheetUi.switchRow(host, "Record audio", "AAC from the device microphone", settings.recordAudio) { checked ->
                settings.recordAudio = checked
                mutate(rebind = false)
            }
        )
        content.addView(
            SheetUi.actionRow(host, "Interval stills", "${settings.intervalSeconds}s") {
                settings.intervalSeconds = when (settings.intervalSeconds) {
                    1 -> 2
                    2 -> 3
                    3 -> 5
                    5 -> 10
                    else -> 1
                }
                mutate(rebind = false)
            }
        )
    }

    private fun addFooter() {
        content.addView(SheetUi.divider(host))
        content.addView(
            SheetUi.buttonStrip(
                host,
                listOf(
                    "Pro panel" to { dismiss(); callbacks.onOpenPro() },
                    "Settings" to { dismiss(); callbacks.onOpenSettings() },
                    "Gallery" to { dismiss(); callbacks.onOpenGallery() }
                )
            )
        )
        content.addView(
            SheetUi.note(
                host,
                "${lens.label} · ${String.format(Locale.US, "%.0f°", lens.fovHorizontalDeg)} horizontal FoV · " +
                    "${lens.maxPhotoMegapixels.toInt()} MP · RAW ${if (lens.supportsRaw) "yes" else "no"}"
            )
        )
    }

    private fun mutate(rebind: Boolean) {
        callbacks.onSettingsMutated(rebind)
        rebuild()
    }
}
