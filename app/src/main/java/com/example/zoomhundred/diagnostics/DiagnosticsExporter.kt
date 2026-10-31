package com.example.zoomhundred.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.zoomhundred.camera.DeviceCapabilities
import com.example.zoomhundred.camera.FeatureGate
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.ProSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes a capability + settings report to `Downloads/Open100x/`.
 *
 * Deliberately contains no GPS coordinates, no file paths outside the app's own public
 * media folder and no device identifiers beyond the model and API level — this is a bug
 * report, not a tracking payload. Nothing leaves the device unless the user shares it.
 */
object DiagnosticsExporter {

    fun buildReport(
        context: Context,
        settings: AppSettings,
        pro: ProSettings,
        capabilities: DeviceCapabilities
    ): String {
        val root = JSONObject()
        root.put("generatedAtUtc", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date()))
        root.put("app", "Open100x")
        root.put("packageName", context.packageName)
        root.put("versionName", versionName(context))
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("androidSdk", Build.VERSION.SDK_INT)
        root.put("locale", java.util.Locale.getDefault().toString())

        val settingsJson = JSONObject()
        settings.toDiagnosticMap().forEach { (key, value) -> settingsJson.put(key, value) }
        root.put("settings", settingsJson)

        val proJson = JSONObject()
            .put("manualMode", pro.manualMode)
            .put("isoIndex", pro.isoIndex)
            .put("iso", pro.isoValue())
            .put("shutterIndex", pro.shutterIndex)
            .put("ev", pro.evValue().toString())
            .put("focusMode", pro.focusMode.name)
            .put("focusPosition", pro.focusPosition.toString())
            .put("wbMode", pro.wbMode.name)
            .put("wbKelvin", pro.wbKelvin)
            .put("stabilization", pro.stabilization.name)
        root.put("pro", proJson)

        val encoders = JSONObject()
            .put("h264", capabilities.encoders.h264)
            .put("hevc", capabilities.encoders.hevc)
            .put("4k30", capabilities.encoders.supports4k30)
            .put("4k60", capabilities.encoders.supports4k60)
            .put("1080p60", capabilities.encoders.supports1080p60)
        root.put("encoders", encoders)

        val lenses = JSONArray()
        capabilities.lenses.forEach { lens ->
            lenses.put(
                JSONObject()
                    .put("id", lens.cameraId)
                    .put("label", lens.label)
                    .put("facing", if (lens.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "front" else "back")
                    .put("focalLengthsMm", JSONArray(lens.focalLengthsMm))
                    .put("fovHorizontalDeg", lens.fovHorizontalDeg.toDouble())
                    .put("fovVerticalDeg", lens.fovVerticalDeg.toDouble())
                    .put("maxNativeZoom", lens.maxZoomRatio.toDouble())
                    .put("maxPhotoMegapixels", lens.maxPhotoMegapixels.toDouble())
                    .put("raw", FeatureGate.raw(lens).name)
                    .put("manualExposure", FeatureGate.manualExposure(lens).name)
                    .put("manualFocus", FeatureGate.manualFocus(lens).name)
                    .put("ois", FeatureGate.ois(lens).name)
                    .put("eis", FeatureGate.eis(lens).name)
                    .put("isoRange", lens.isoRange?.let { "${it.lower}-${it.upper}" } ?: "none")
                    .put("exposureRangeNs", lens.exposureTimeRange?.let { "${it.lower}-${it.upper}" } ?: "none")
                    .put("hardwareLevel", lens.hardwareLevel)
                    .put("fpsRanges", JSONArray(lens.fpsRanges.map { "${it.lower}-${it.upper}" }))
                    .put("videoSizes", JSONArray(lens.videoOutputSizes.take(12).map { "${it.width}x${it.height}" }))
            )
        }
        root.put("lenses", lenses)
        return root.toString(2)
    }

    fun export(context: Context, fileName: String, body: String, mime: String = "application/json"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Downloads/Open100x")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            } ?: return null
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
