package com.example.zoomhundred.model

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Every user preference that is *not* part of a single shot.
 *
 * Persisted in one SharedPreferences file so it can be exported/imported as JSON
 * (see util/PresetStore) and reset in one place. Reads are cheap; the camera screen
 * caches a snapshot and re-reads on resume.
 */
data class AppSettings(
    // ── General ───────────────────────────────────────────────────────────────
    var defaultMode: CaptureMode = CaptureMode.PHOTO,
    var themeDark: Boolean = true,
    var hapticsEnabled: Boolean = true,
    var shutterSound: Boolean = true,
    var shutterVolume: Int = 70,
    var volumeKeyAction: VolumeKeyAction = VolumeKeyAction.ZOOM,
    var keepScreenOn: Boolean = true,
    var onboardingShown: Boolean = false,

    // ── Photo ─────────────────────────────────────────────────────────────────
    var defaultAspect: AspectRatio = AspectRatio.R4_3,
    var photoFormat: PhotoFormat = PhotoFormat.JPEG,
    var flashMode: FlashMode = FlashMode.OFF,
    var timerSeconds: Int = 0,
    var burstCount: Int = 5,
    var intervalSeconds: Int = 3,
    var hdrEnabled: Boolean = false,
    var rawEnabled: Boolean = false,
    var mirrorSelfie: Boolean = false,
    var saveMirrored: Boolean = false,

    // ── Video ─────────────────────────────────────────────────────────────────
    var videoResolution: VideoResolution = VideoResolution.FHD,
    var videoFps: Int = 30,
    var videoCodec: VideoCodec = VideoCodec.H264,
    var videoBitrateMbps: Int = 0,           // 0 = encoder default
    var videoStabilization: StabilizationMode = StabilizationMode.AUTO,
    var recordAudio: Boolean = true,

    // ── Pro ───────────────────────────────────────────────────────────────────
    var proHudEnabled: Boolean = true,
    var peakingEnabled: Boolean = false,
    var zebraskaEnabled: Boolean = false,
    var histogramEnabled: Boolean = true,
    var levelEnabled: Boolean = false,
    var gridMode: GridMode = GridMode.THIRDS,

    // ── Zoom ──────────────────────────────────────────────────────────────────
    var zoomPresets: List<Float> = DEFAULT_ZOOM_PRESETS,
    var zoomLocked: Boolean = false,
    var showZoomLabels: Boolean = true,
    var antiShake: Boolean = true,

    // ── Computational ─────────────────────────────────────────────────────────
    var nightFrames: Int = 8,
    var astroFrames: Int = 6,
    var astroExposureSeconds: Int = 4,
    var hdrFrames: Int = 3,
    var nightTripodMode: Boolean = true,
    var zoomEnhance: Boolean = true,
    var zoomEnhanceStrength: Int = 55,

    // ── Image processing ──────────────────────────────────────────────────────
    var colorProfile: ColorProfile = ColorProfile.NEUTRAL,
    var frameStyle: FrameStyle = FrameStyle.NONE,
    var lutName: String? = null,
    var lutIntensity: Int = 100,
    var portraitStrength: Int = 55,
    var fisheyeStrength: Int = 60,
    var keepOriginals: Boolean = true,

    // ── Gallery / storage ─────────────────────────────────────────────────────
    var saveSubfolder: String = "Open100x",
    var stripGps: Boolean = false,

    // ── Interface ─────────────────────────────────────────────────────────────
    var showLoupe: Boolean = true,
    var loupeSizeDp: Int = 108,
    var loupeX: Float = -1f,
    var loupeY: Float = -1f,
    var showHud: Boolean = false,
    var showThumbnail: Boolean = true,
    var nightUi: Boolean = false
) {

    companion object {
        val DEFAULT_ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 30f, 100f)
        private const val PREF_FILE = "app_settings"

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val defaults = AppSettings()
            return defaults.copy(
                defaultMode = CaptureMode.entries.getOrElse(p.getInt("defaultMode", defaults.defaultMode.ordinal)) { defaults.defaultMode },
                hapticsEnabled = p.getBoolean("haptics", true),
                shutterSound = p.getBoolean("shutterSound", true),
                shutterVolume = p.getInt("shutterVolume", 70).coerceIn(0, 100),
                volumeKeyAction = VolumeKeyAction.entries.getOrElse(p.getInt("volumeKeys", 0)) { VolumeKeyAction.ZOOM },
                keepScreenOn = p.getBoolean("keepScreenOn", true),
                onboardingShown = p.getBoolean("onboarding", false),
                defaultAspect = AspectRatio.entries.getOrElse(p.getInt("aspect", AspectRatio.R4_3.ordinal)) { AspectRatio.R4_3 },
                photoFormat = PhotoFormat.entries.getOrElse(p.getInt("photoFormat", 0)) { PhotoFormat.JPEG },
                flashMode = FlashMode.entries.getOrElse(p.getInt("flash", 0)) { FlashMode.OFF },
                timerSeconds = p.getInt("timer", 0).coerceIn(0, 30),
                burstCount = p.getInt("burstCount", 5).coerceIn(1, 20),
                intervalSeconds = p.getInt("intervalSeconds", 3).coerceIn(1, 60),
                hdrEnabled = p.getBoolean("hdr", false),
                rawEnabled = p.getBoolean("raw", false),
                mirrorSelfie = p.getBoolean("mirrorSelfie", false),
                saveMirrored = p.getBoolean("saveMirrored", false),
                videoResolution = VideoResolution.entries.getOrElse(p.getInt("videoResolution", VideoResolution.FHD.ordinal)) { VideoResolution.FHD },
                videoFps = p.getInt("videoFps", 30),
                videoCodec = VideoCodec.entries.getOrElse(p.getInt("videoCodec", 0)) { VideoCodec.H264 },
                videoBitrateMbps = p.getInt("videoBitrate", 0),
                videoStabilization = StabilizationMode.entries.getOrElse(p.getInt("videoStab", 3)) { StabilizationMode.AUTO },
                recordAudio = p.getBoolean("recordAudio", true),
                proHudEnabled = p.getBoolean("proHud", true),
                peakingEnabled = p.getBoolean("peaking", false),
                zebraskaEnabled = p.getBoolean("zebra", false),
                histogramEnabled = p.getBoolean("histogram", true),
                levelEnabled = p.getBoolean("level", false),
                gridMode = GridMode.entries.getOrElse(p.getInt("grid", GridMode.THIRDS.ordinal)) { GridMode.THIRDS },
                zoomPresets = readPresets(p.getString("zoomPresets", null)),
                zoomLocked = p.getBoolean("zoomLocked", false),
                showZoomLabels = p.getBoolean("showZoomLabels", true),
                antiShake = p.getBoolean("antiShake", true),
                nightFrames = p.getInt("nightFrames", 8).coerceIn(3, 16),
                astroFrames = p.getInt("astroFrames", 6).coerceIn(2, 16),
                astroExposureSeconds = p.getInt("astroExposure", 4).coerceIn(1, 30),
                hdrFrames = p.getInt("hdrFrames", 3).coerceIn(2, 5),
                nightTripodMode = p.getBoolean("nightTripod", true),
                zoomEnhance = p.getBoolean("zoomEnhance", true),
                zoomEnhanceStrength = p.getInt("zoomEnhanceStrength", 55).coerceIn(0, 100),
                colorProfile = ColorProfile.entries.getOrElse(p.getInt("colorProfile", 0)) { ColorProfile.NEUTRAL },
                frameStyle = FrameStyle.entries.getOrElse(p.getInt("frameStyle", 0)) { FrameStyle.NONE },
                lutName = p.getString("lutName", null),
                lutIntensity = p.getInt("lutIntensity", 100).coerceIn(0, 100),
                portraitStrength = p.getInt("portraitStrength", 55).coerceIn(0, 100),
                fisheyeStrength = p.getInt("fisheyeStrength", 60).coerceIn(0, 100),
                keepOriginals = p.getBoolean("keepOriginals", true),
                saveSubfolder = p.getString("saveSubfolder", "Open100x") ?: "Open100x",
                stripGps = p.getBoolean("stripGps", false),
                showLoupe = p.getBoolean("showLoupe", true),
                loupeSizeDp = p.getInt("loupeSize", 108).coerceIn(72, 180),
                loupeX = p.getFloat("loupeX", -1f),
                loupeY = p.getFloat("loupeY", -1f),
                showHud = p.getBoolean("showHud", false),
                showThumbnail = p.getBoolean("showThumbnail", true),
                nightUi = p.getBoolean("nightUi", false)
            )
        }

        private fun readPresets(json: String?): List<Float> {
            if (json.isNullOrBlank()) return DEFAULT_ZOOM_PRESETS
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<Float>(arr.length())
                for (i in 0 until arr.length()) out.add(arr.getDouble(i).toFloat())
                out.ifEmpty { DEFAULT_ZOOM_PRESETS }
            } catch (_: Exception) {
                DEFAULT_ZOOM_PRESETS
            }
        }

        fun presetsToJson(presets: List<Float>): String {
            val arr = JSONArray()
            presets.forEach { arr.put(it.toDouble()) }
            return arr.toString()
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
            putInt("defaultMode", defaultMode.ordinal)
            putBoolean("haptics", hapticsEnabled)
            putBoolean("shutterSound", shutterSound)
            putInt("shutterVolume", shutterVolume.coerceIn(0, 100))
            putInt("volumeKeys", volumeKeyAction.ordinal)
            putBoolean("keepScreenOn", keepScreenOn)
            putBoolean("onboarding", onboardingShown)
            putInt("aspect", defaultAspect.ordinal)
            putInt("photoFormat", photoFormat.ordinal)
            putInt("flash", flashMode.ordinal)
            putInt("timer", timerSeconds.coerceIn(0, 30))
            putInt("burstCount", burstCount.coerceIn(1, 20))
            putInt("intervalSeconds", intervalSeconds.coerceIn(1, 60))
            putBoolean("hdr", hdrEnabled)
            putBoolean("raw", rawEnabled)
            putBoolean("mirrorSelfie", mirrorSelfie)
            putBoolean("saveMirrored", saveMirrored)
            putInt("videoResolution", videoResolution.ordinal)
            putInt("videoFps", videoFps)
            putInt("videoCodec", videoCodec.ordinal)
            putInt("videoBitrate", videoBitrateMbps)
            putInt("videoStab", videoStabilization.ordinal)
            putBoolean("recordAudio", recordAudio)
            putBoolean("proHud", proHudEnabled)
            putBoolean("peaking", peakingEnabled)
            putBoolean("zebra", zebraskaEnabled)
            putBoolean("histogram", histogramEnabled)
            putBoolean("level", levelEnabled)
            putInt("grid", gridMode.ordinal)
            putString("zoomPresets", presetsToJson(zoomPresets))
            putBoolean("zoomLocked", zoomLocked)
            putBoolean("showZoomLabels", showZoomLabels)
            putBoolean("antiShake", antiShake)
            putInt("nightFrames", nightFrames)
            putInt("astroFrames", astroFrames)
            putInt("astroExposure", astroExposureSeconds)
            putInt("hdrFrames", hdrFrames)
            putBoolean("nightTripod", nightTripodMode)
            putBoolean("zoomEnhance", zoomEnhance)
            putInt("zoomEnhanceStrength", zoomEnhanceStrength)
            putInt("colorProfile", colorProfile.ordinal)
            putInt("frameStyle", frameStyle.ordinal)
            putString("lutName", lutName)
            putInt("lutIntensity", lutIntensity)
            putInt("portraitStrength", portraitStrength)
            putInt("fisheyeStrength", fisheyeStrength)
            putBoolean("keepOriginals", keepOriginals)
            putString("saveSubfolder", saveSubfolder)
            putBoolean("stripGps", stripGps)
            putBoolean("showLoupe", showLoupe)
            putInt("loupeSize", loupeSizeDp)
            putFloat("loupeX", loupeX)
            putFloat("loupeY", loupeY)
            putBoolean("showHud", showHud)
            putBoolean("showThumbnail", showThumbnail)
            putBoolean("nightUi", nightUi)
        }
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit { clear() }
    }

    /** Human-readable dump used by the diagnostics export. */
    fun toDiagnosticMap(): Map<String, String> = linkedMapOf(
        "defaultMode" to defaultMode.name,
        "defaultAspect" to defaultAspect.name,
        "photoFormat" to photoFormat.name,
        "video" to "${videoResolution.label}@${videoFps}",
        "stabilization" to videoStabilization.name,
        "grid" to gridMode.name,
        "hud" to showHud.toString(),
        "loupe" to showLoupe.toString(),
        "colorProfile" to colorProfile.name,
        "frame" to frameStyle.name,
        "lut" to (lutName ?: "none"),
        "nightFrames" to nightFrames.toString(),
        "hdrFrames" to hdrFrames.toString(),
        "keepOriginals" to keepOriginals.toString(),
        "stripGps" to stripGps.toString()
    )
}

/** The primary shooting modes surfaced on the camera screen. */
enum class CaptureMode(val label: String, val shortLabel: String) {
    PHOTO("Photo", "PHOTO"),
    VIDEO("Video", "VIDEO"),
    PORTRAIT("Portrait", "PORTRAIT"),
    NIGHT("Night", "NIGHT"),
    PRO("Pro", "PRO"),
    ASTRO("Astro", "ASTRO"),
    PANORAMA("Panorama", "PANO"),
    LIGHT("Light trails", "LIGHT"),
    TIMELAPSE("Time-lapse", "LAPSE"),
    FISHEYE("Fisheye", "FISHEYE"),
    DOCUMENT("Document", "DOC"),
    MORE("More", "MORE");

    val isVideoMode: Boolean get() = this == VIDEO

    /** Modes that appear on the primary strip; the rest live behind MORE. */
    companion object {
        val PRIMARY = listOf(PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, ASTRO, PANORAMA, MORE)
        val SECONDARY = listOf(LIGHT, TIMELAPSE, FISHEYE, DOCUMENT)
    }
}
