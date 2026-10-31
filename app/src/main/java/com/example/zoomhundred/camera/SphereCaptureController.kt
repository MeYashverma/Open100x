package com.example.zoomhundred.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.example.zoomhundred.computational.SphericalStitcher
import com.example.zoomhundred.overlay.SphereOverlayView
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Drives a guided photo-sphere capture.
 *
 * The controller watches the device attitude and takes a frame whenever the camera has
 * moved far enough since the previous one — the user just sweeps the phone across the
 * scene, they never press the shutter. Every frame is stored with the attitude that was
 * live at capture, which is what lets [SphericalStitcher] put it back onto the sphere in
 * the right place.
 *
 * A horizontal sweep therefore yields a panorama, adding tilt yields a 360, and covering
 * up/down/around yields a sphere. Nothing here assumes a specific sweep path.
 */
class SphereCaptureController(
    context: Context,
    private val hFovDegrees: Float,
    private val overlay: SphereOverlayView?,
    private val captureFrame: (onSaved: (File) -> Unit, onError: (String) -> Unit) -> Unit,
    private val onProgress: (captured: Int, coveragePercent: Int, message: String) -> Unit,
    private val onAutoFinish: () -> Unit
) : SensorEventListener {

    class Shot(val file: File, val rotation: FloatArray)

    companion object {
        /** Degrees of view change between two frames; smaller = denser sweep. */
        private const val STEP_DEGREES = 9f
        private const val TICK_MS = 220L
        private const val MAX_SHOTS = 44
        private const val AUTO_FINISH_COVERAGE = 92
    }

    val shots = ArrayList<Shot>()
    var isActive: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation = FloatArray(9).also {
        it[0] = 1f; it[4] = 1f; it[8] = 1f
    }
    private var hasAttitude = false
    private var capturing = false
    private var hiddenSensor = false

    private val cells: Array<FloatArray> = buildCells()
    private val covered = BooleanArray(cells.size)

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            maybeCapture()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** @return false when the device has no usable orientation sensor. */
    fun start(): Boolean {
        if (isActive) return true
        val manager = sensorManager ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (sensor == null) {
            hiddenSensor = true
            return false
        }
        shots.clear()
        covered.fill(false)
        isActive = true
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        overlay?.hFovDegrees = hFovDegrees
        overlay?.setGrid(cells, covered)
        handler.postDelayed(tick, TICK_MS)
        onProgress(0, 0, "Sweep slowly — the dots fill as you cover the scene")
        return true
    }

    /** Stops sensing and returns the frames captured so far (oldest first). */
    fun stop(): List<Shot> {
        if (!isActive) return shots.toList()
        isActive = false
        handler.removeCallbacks(tick)
        runCatching { sensorManager?.unregisterListener(this) }
        return shots.toList()
    }

    fun cancel() {
        isActive = false
        handler.removeCallbacks(tick)
        runCatching { sensorManager?.unregisterListener(this) }
        shots.forEach { runCatching { it.file.delete() } }
        shots.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isActive || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) {
            return
        }
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        System.arraycopy(matrix, 0, rotation, 0, 9)
        hasAttitude = true
        overlay?.setOrientation(rotation)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeCapture() {
        if (!hasAttitude || capturing) return
        if (shots.size >= MAX_SHOTS) {
            onAutoFinish()
            return
        }
        val last = shots.lastOrNull()
        val moved = last == null ||
            SphericalStitcher.angularDistance(rotation, last.rotation) >= STEP_DEGREES
        if (!moved) return
        capturing = true
        val snapshot = rotation.copyOf()
        captureFrame(
            { file -> handler.post { onFrameSaved(file, snapshot) } },
            { handler.post { capturing = false } }
        )
    }

    private fun onFrameSaved(file: File, snapshot: FloatArray) {
        capturing = false
        if (!isActive) {
            runCatching { file.delete() }
            return
        }
        shots.add(Shot(file, snapshot))
        markCoverage(snapshot)
        val percent = coveragePercent()
        overlay?.setGrid(cells, covered)
        onProgress(
            shots.size,
            percent,
            "Sphere ${shots.size} frames · $percent% covered"
        )
        if (percent >= AUTO_FINISH_COVERAGE) onAutoFinish()
    }

    private fun markCoverage(snapshot: FloatArray) {
        val tanH = tan(Math.toRadians((hFovDegrees.coerceIn(25f, 120f) / 2f).toDouble())).toFloat()
        val tanV = tanH * 0.75f
        for (index in cells.indices) {
            if (covered[index]) continue
            val cell = cells[index]
            val vx = snapshot[0] * cell[0] + snapshot[3] * cell[1] + snapshot[6] * cell[2]
            val vy = snapshot[1] * cell[0] + snapshot[4] * cell[1] + snapshot[7] * cell[2]
            val vz = snapshot[2] * cell[0] + snapshot[5] * cell[1] + snapshot[8] * cell[2]
            if (vz > -0.05f) continue
            val u = (vx / -vz) / (2f * tanH)
            val v = (vy / -vz) / (2f * tanV)
            if (u > -0.45f && u < 0.45f && v > -0.45f && v < 0.45f) covered[index] = true
        }
    }

    private fun coveragePercent(): Int {
        if (covered.isEmpty()) return 0
        var count = 0
        for (value in covered) if (value) count++
        return count * 100 / covered.size
    }

    /**
     * Target directions: three latitude bands times twelve longitudes. That is 36 dots —
     * enough to guide a full sphere, few enough to read at a glance.
     */
    private fun buildCells(): Array<FloatArray> {
        val latitudes = intArrayOf(-40, 0, 40)
        val out = ArrayList<FloatArray>(latitudes.size * 12)
        for (lat in latitudes) {
            val latRad = Math.toRadians(lat.toDouble())
            for (step in 0 until 12) {
                val lonRad = Math.toRadians(step * 30.0)
                out.add(
                    floatArrayOf(
                        (cos(latRad) * sin(lonRad)).toFloat(),
                        sin(latRad).toFloat(),
                        (cos(latRad) * cos(lonRad)).toFloat()
                    )
                )
            }
        }
        return out.toTypedArray()
    }

    val hasSensor: Boolean get() = !hiddenSensor
}
