package com.example.zoomhundred.model

import android.content.Context
import androidx.core.content.edit

/**
 * Live professional-camera state (exposure, focus, white balance, locks).
 *
 * This is deliberately separate from [AppSettings]: these values describe the
 * *current shot* and are honoured by the capture request pipeline, while AppSettings
 * describes how the app behaves. Persisted so a session survives a restart — except
 * the locks, which always start released.
 */
data class ProSettings(
    // Exposure
    var manualMode: Boolean = false,
    var isoIndex: Int = 3,
    var shutterIndex: Int = 5,
    var evIndex: Int = 4,                     // index into EV_VALUES, 4 == 0 EV
    var exposureLocked: Boolean = false,
    // Focus
    var focusMode: FocusMode = FocusMode.AUTO,
    var focusPosition: Float = 0f,            // 0 = infinity, 1 = closest focus
    var focusLocked: Boolean = false,
    // White balance
    var wbMode: WbMode = WbMode.AUTO,
    var wbKelvin: Int = 5500,
    var wbLocked: Boolean = false,
    // Overlays (mirrored from AppSettings for engine convenience)
    var focusPeakingEnabled: Boolean = false,
    var histogramEnabled: Boolean = true,
    var gridMode: GridMode = GridMode.THIRDS,
    var hudEnabled: Boolean = true,
    // Anti-shake
    var antiShakeEnabled: Boolean = true,
    var antiShakeThreshold: Float = 0.05f,
    // Output
    var outputFormat: OutputFormat = OutputFormat.JPEG,
    var rawEnabled: Boolean = false,
    var stripGpsExif: Boolean = false,
    var shutterSoundEnabled: Boolean = true,
    var shutterSoundVolume: Int = 70,
    var zoomPostProcessEnabled: Boolean = true,
    var zoomPostProcessStrength: Int = 55,
    // Zoom
    var zoomLocked: Boolean = false,
    var stabilization: StabilizationMode = StabilizationMode.AUTO,
    // Night UI
    var nightUiEnabled: Boolean = false
) {

    fun isoValue(): Int = ISO_VALUES[isoIndex.coerceIn(0, ISO_VALUES.lastIndex)]
    fun shutterNanos(): Long = SHUTTER_VALUES[shutterIndex.coerceIn(0, SHUTTER_VALUES.lastIndex)]
    fun evValue(): Float = EV_VALUES[evIndex.coerceIn(0, EV_VALUES.lastIndex)]

    /** Manual values are only meaningful when the user has taken over exposure. */
    fun exposureOverrideActive(): Boolean = manualMode && !exposureLocked

    fun copyOf(): ProSettings = this.copy()

    companion object {
        val ISO_VALUES = intArrayOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
        /** Exposure times in nanoseconds, longest first. */
        val SHUTTER_VALUES = longArrayOf(
            1_000_000_000L, // 1s
            500_000_000L,   // 1/2
            250_000_000L,   // 1/4
            125_000_000L,   // 1/8
            62_500_000L,    // 1/16
            33_333_333L,    // 1/30
            16_666_667L,    // 1/60
            8_333_333L,     // 1/120
            4_000_000L,     // 1/250
            2_000_000L,     // 1/500
            1_000_000L,     // 1/1000
            500_000L        // 1/2000
        )
        val SHUTTER_LABELS = arrayOf(
            "1\"", "1/2", "1/4", "1/8", "1/16", "1/30", "1/60", "1/120", "1/250", "1/500", "1/1000", "1/2000"
        )
        /** Third-stop exposure compensation. */
        val EV_VALUES = floatArrayOf(-2f, -1.7f, -1.3f, -1f, 0f, 1f, 1.3f, 1.7f, 2f)

        val KELVIN_MIN = 2000
        val KELVIN_MAX = 9000

        private const val PREF_FILE = "pro_settings"

        fun reset(context: Context) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }

        fun load(context: Context): ProSettings {
            val p = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).wipeIfCorrupt()
            return ProSettings(
                manualMode = p.safeBoolean("manualMode", false),
                isoIndex = p.safeInt("isoIndex", 3).coerceIn(0, ISO_VALUES.lastIndex),
                shutterIndex = p.safeInt("shutterIndex", 6).coerceIn(0, SHUTTER_VALUES.lastIndex),
                evIndex = p.safeInt("evIndex", 4).coerceIn(0, EV_VALUES.lastIndex),
                focusMode = p.safeBoolean("manualFocus", false).let { if (it) FocusMode.MANUAL else FocusMode.AUTO },
                focusPosition = p.safeFloat("focusPosition", 0f).coerceIn(0f, 1f),
                wbMode = WbMode.entries.getOrElse(p.safeInt("wbMode", 0)) { WbMode.AUTO },
                wbKelvin = p.safeInt("wbKelvin", 5500).coerceIn(KELVIN_MIN, KELVIN_MAX),
                focusPeakingEnabled = p.safeBoolean("focusPeaking", false),
                histogramEnabled = p.safeBoolean("histogram", true),
                gridMode = GridMode.entries.getOrElse(p.safeInt("gridMode", GridMode.THIRDS.ordinal)) { GridMode.THIRDS },
                hudEnabled = p.safeBoolean("hud", true),
                antiShakeEnabled = p.safeBoolean("antiShake", true),
                antiShakeThreshold = p.safeFloat("antiShakeThreshold", 0.05f),
                outputFormat = OutputFormat.entries.getOrElse(p.safeInt("outputFormat", 0)) { OutputFormat.JPEG },
                rawEnabled = p.safeBoolean("raw", false),
                stripGpsExif = p.safeBoolean("stripGps", false),
                shutterSoundEnabled = p.safeBoolean("shutterSound", true),
                shutterSoundVolume = p.safeInt("shutterSoundVolume", 70).coerceIn(0, 100),
                zoomPostProcessEnabled = p.safeBoolean("zoomPostProcess", true),
                zoomPostProcessStrength = p.safeInt("zoomPostProcessStrength", 55).coerceIn(0, 100),
                zoomLocked = false,
                stabilization = StabilizationMode.entries.getOrElse(p.safeInt("stabilization", 0)) { StabilizationMode.AUTO },
                nightUiEnabled = p.safeBoolean("nightUi", false)
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
            putBoolean("manualMode", manualMode)
            putInt("isoIndex", isoIndex.coerceIn(0, ISO_VALUES.lastIndex))
            putInt("shutterIndex", shutterIndex.coerceIn(0, SHUTTER_VALUES.lastIndex))
            putInt("evIndex", evIndex.coerceIn(0, EV_VALUES.lastIndex))
            putBoolean("manualFocus", focusMode == FocusMode.MANUAL)
            putFloat("focusPosition", focusPosition.coerceIn(0f, 1f))
            putInt("wbMode", wbMode.ordinal)
            putInt("wbKelvin", wbKelvin.coerceIn(KELVIN_MIN, KELVIN_MAX))
            putBoolean("focusPeaking", focusPeakingEnabled)
            putBoolean("histogram", histogramEnabled)
            putInt("gridMode", gridMode.ordinal)
            putBoolean("hud", hudEnabled)
            putBoolean("antiShake", antiShakeEnabled)
            putFloat("antiShakeThreshold", antiShakeThreshold)
            putInt("outputFormat", outputFormat.ordinal)
            putBoolean("raw", rawEnabled)
            putBoolean("stripGps", stripGpsExif)
            putBoolean("shutterSound", shutterSoundEnabled)
            putInt("shutterSoundVolume", shutterSoundVolume.coerceIn(0, 100))
            putBoolean("zoomPostProcess", zoomPostProcessEnabled)
            putInt("zoomPostProcessStrength", zoomPostProcessStrength.coerceIn(0, 100))
            putInt("stabilization", stabilization.ordinal)
            putBoolean("nightUi", nightUiEnabled)
        }
    }
}
