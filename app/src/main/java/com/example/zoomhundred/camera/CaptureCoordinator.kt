package com.example.zoomhundred.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.example.zoomhundred.computational.AccumulationMode
import com.example.zoomhundred.computational.FrameProvider
import com.example.zoomhundred.computational.HdrFusion
import com.example.zoomhundred.computational.LightTrailStacker
import com.example.zoomhundred.computational.NightStacker
import com.example.zoomhundred.computational.PanoramaStitcher
import com.example.zoomhundred.computational.SphericalStitcher
import com.example.zoomhundred.imaging.BitmapOps
import com.example.zoomhundred.imaging.ExifUtils
import com.example.zoomhundred.imaging.ImageStore
import com.example.zoomhundred.imaging.PostCapture
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.AspectRatio
import com.example.zoomhundred.model.CaptureMode
import com.example.zoomhundred.model.PhotoFormat
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.WbMode
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

/** Everything the coordinator needs to know about the shot being taken. */
data class ShotContext(
    val mode: CaptureMode,
    val zoomRatio: Float,
    val nativeZoom: Float,
    val lensLabel: String,
    val aspect: AspectRatio,
    val portraitStrength: Int = 0,
    val accumulation: AccumulationMode = AccumulationMode.LIGHTEN,
    val tripodStable: Boolean = false,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f
)

enum class SequenceKind { PANORAMA, LIGHT_TRAIL, TIMELAPSE, STAR_TRAIL }

/**
 * Turns a shutter press into a real capture pipeline.
 *
 * Single shots, bursts, exposure brackets, focus stacks, night/astro stacking, HDR
 * fusion, panorama sweeps, light-trail accumulation and interval capture all live here.
 * Heavy work (alignment, stacking, colour, frames, encoding) runs on [worker]; camera
 * callbacks stay on the main thread.
 */
class CaptureCoordinator(
    private val context: Context,
    private val engine: CameraEngine,
    private val settings: () -> AppSettings,
    private val pro: () -> ProSettings
) {

    interface Listener {
        fun onBusy(active: Boolean, fraction: Float, message: String)
        fun onSaved(uri: Uri)
        fun onError(message: String)
        fun onFrameProgress(captured: Int, total: Int)
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sessionDir: File by lazy {
        File(context.cacheDir, "captures").apply { if (!exists()) mkdirs() }
    }

    var isBusy: Boolean = false
        private set
    private var sequenceActive = false
    private var sequenceKind: SequenceKind? = null
    private var sequenceFrames = ArrayList<File>()
    private var sequenceContext: ShotContext? = null
    private var sequenceFrameTarget = 12
    private var timelapseRemaining = 0
    private var timelapseSaved = 0

    // ── Single / computational shots ──────────────────────────────────────────

    fun shoot(ctx: ShotContext) {
        if (isBusy) return
        when (ctx.mode) {
            CaptureMode.NIGHT -> nightShot(ctx, astro = false)
            CaptureMode.ASTRO -> nightShot(ctx, astro = true)
            CaptureMode.PHOTO -> if (settings().hdrEnabled) hdrShot(ctx) else singleShot(ctx)
            CaptureMode.PRO -> singleShot(ctx)
            CaptureMode.PORTRAIT -> singleShot(ctx, portrait = true)
            CaptureMode.FISHEYE, CaptureMode.DOCUMENT -> singleShot(ctx)
            CaptureMode.VIDEO -> listener?.onError("Use the record button for video")
            CaptureMode.PANORAMA -> listener?.onError("Start the panorama sweep first")
            CaptureMode.LIGHT -> listener?.onError("Start the light-trail capture first")
            CaptureMode.TIMELAPSE -> listener?.onError("Start the time-lapse first")
            CaptureMode.MORE -> singleShot(ctx)
        }
    }

    fun singleShot(ctx: ShotContext, portrait: Boolean = false, raw: Boolean = false) {
        val app = settings()
        val format = if (raw) PhotoFormat.RAW else app.photoFormat
        val isWebp = !raw && format == PhotoFormat.WEBP
        val mime = when {
            raw -> "image/x-adobe-dng"
            isWebp -> "image/webp"
            else -> "image/jpeg"
        }
        val extension = when {
            raw -> "dng"
            isWebp -> "webp"
            else -> "jpg"
        }
        val name = fileName(ctx.mode, ctx.zoomRatio, extension)
        val values = mediaValues(name, mime, app.saveSubfolder)
        isBusy = true
        listener?.onBusy(true, -1f, if (raw) "Saving RAW" else "Capturing")

        engine.captureStill(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values = values,
            onSaved = { uri, capture ->
                if (raw) {
                    isBusy = false
                    listener?.onBusy(false, 1f, "")
                    listener?.onSaved(uri)
                    return@captureStill
                }
                worker.execute {
                    val ok = try {
                        PostCapture.process(context, uri, postParams(ctx, portrait))
                    } catch (e: Exception) {
                        Log.w(TAG, "post-capture failed: ${e.message}")
                        true // the capture itself is valid; keep it
                    }
                    main.post {
                        isBusy = false
                        listener?.onBusy(false, 1f, "")
                        if (ok) listener?.onSaved(uri) else listener?.onError("Could not process the photo")
                    }
                }
            },
            onError = { message ->
                main.post {
                    isBusy = false
                    listener?.onBusy(false, 1f, "")
                    listener?.onError(message)
                }
            }
        )
    }

    // ── Night / astro ─────────────────────────────────────────────────────────

    private fun nightShot(ctx: ShotContext, astro: Boolean) {
        val app = settings()
        val frames = if (astro) app.astroFrames else app.nightFrames
        val total = min(frames, 12).coerceAtLeast(2)
        if (astro && !ctx.tripodStable) {
            listener?.onError("Hold the phone still — astro needs a stable mount")
        }
        val files = ArrayList<File>(total)
        isBusy = true
        listener?.onBusy(true, 0f, if (astro) "Astro exposure 1/$total" else "Night capture 1/$total")

        fun captureFrame(index: Int) {
            if (index >= total) {
                stackNight(files, total, ctx, astro)
                return
            }
            listener?.onFrameProgress(index + 1, total)
            listener?.onBusy(true, index.toFloat() / total, if (astro)
                "Astro exposure ${index + 1}/$total" else "Night capture ${index + 1}/$total")
            val file = File(sessionDir, "night_${System.currentTimeMillis()}_$index.jpg")
            engine.captureStillToFile(
                file = file,
                onSaved = {
                    files.add(it)
                    val gap = if (astro) 260L else 120L
                    main.postDelayed({ captureFrame(index + 1) }, gap)
                },
                onError = { message ->
                    main.post {
                        if (files.size >= 2) stackNight(files, total, ctx, astro)
                        else {
                            isBusy = false
                            listener?.onBusy(false, 1f, "")
                            listener?.onError(message)
                        }
                    }
                }
            )
        }
        captureFrame(0)
    }

    private fun stackNight(files: List<File>, planned: Int, ctx: ShotContext, astro: Boolean) {
        listener?.onBusy(true, 0.4f, "Aligning frames")
        worker.execute {
            val provider = FrameProvider { index ->
                files.getOrNull(index)?.let { file ->
                    BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(file))
                }
            }
            val merged = NightStacker.stack(
                frameCount = files.size,
                provider = provider,
                options = com.example.zoomhundred.computational.StackOptions()
            )
            var saved: Uri? = null
            if (merged != null) {
                val processed = PostCapture.renderBitmap(merged, postParams(ctx, portrait = false))
                saved = saveBitmap(processed, ctx, astro)
                if (processed !== merged) processed.recycle()
                merged.recycle()
            }
            files.forEach { it.delete() }
            main.post {
                isBusy = false
                listener?.onBusy(false, 1f, "")
                if (saved != null) {
                    listener?.onSaved(saved!!)
                } else {
                    listener?.onError("Merge failed — keep the phone steadier")
                }
            }
        }
    }

    // ── HDR ───────────────────────────────────────────────────────────────────

    private fun hdrShot(ctx: ShotContext) {
        val app = settings()
        // HDR+ takes frames at a single exposure and merges them (align + motion-reject +
        // tone map), which is what buys the cleaner shadows. The bracket path shifts EV
        // instead and fuses for dynamic range.
        val sameExposure = app.hdrPlus
        val stops = if (sameExposure) {
            List(app.hdrFrames.coerceIn(2, 5)) { 0f }
        } else when (app.hdrFrames) {
            2 -> listOf(-2f, 2f)
            3 -> listOf(-2f, 0f, 2f)
            4 -> listOf(-3f, -1f, 1f, 3f)
            else -> listOf(-3f, -1.5f, 0f, 1.5f, 3f)
        }
        val files = ArrayList<File>(stops.size)
        val restoreEv = pro().evValue()
        isBusy = true

        fun captureFrame(index: Int) {
            if (index >= stops.size) {
                mergeHdr(files, ctx, restoreEv)
                return
            }
            listener?.onBusy(true, index.toFloat() / stops.size, "HDR ${index + 1}/${stops.size}")
            listener?.onFrameProgress(index + 1, stops.size)
            engine.setExposureCompensation(stops[index])
            main.postDelayed({
                val file = File(sessionDir, "hdr_${System.currentTimeMillis()}_$index.jpg")
                engine.captureStillToFile(
                    file = file,
                    onSaved = {
                        files.add(it)
                        main.postDelayed({ captureFrame(index + 1) }, 90L)
                    },
                    onError = { message ->
                        main.post {
                            engine.setExposureCompensation(restoreEv)
                            if (files.size >= 2) mergeHdr(files, ctx, restoreEv)
                            else {
                                isBusy = false
                                listener?.onBusy(false, 1f, "")
                                listener?.onError(message)
                            }
                        }
                    }
                )
            }, 240L)
        }
        captureFrame(0)
    }

    private fun mergeHdr(files: List<File>, ctx: ShotContext, restoreEv: Float) {
        engine.setExposureCompensation(restoreEv)
        val sameExposure = settings().hdrPlus
        listener?.onBusy(true, 0.5f, if (sameExposure) "Merging frames" else "Fusing exposures")
        worker.execute {
            val provider = FrameProvider { index ->
                files.getOrNull(index)?.let { BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(it)) }
            }
            val fused = if (sameExposure) {
                NightStacker.stack(files.size, provider)
            } else {
                HdrFusion.fuse(files.size, provider)
            }
            var saved: Uri? = null
            if (fused != null) {
                val processed = PostCapture.renderBitmap(fused, postParams(ctx, portrait = false))
                saved = saveBitmap(processed, ctx, astro = false)
                if (processed !== fused) processed.recycle()
                fused.recycle()
            }
            files.forEach { it.delete() }
            main.post {
                isBusy = false
                listener?.onBusy(false, 1f, "")
                if (saved != null) listener?.onSaved(saved!!) else listener?.onError("HDR merge failed")
            }
        }
    }

    // ── Burst / bracket / focus stack ─────────────────────────────────────────

    fun burst(ctx: ShotContext, count: Int, intervalMs: Long = 110L) {
        if (isBusy) return
        isBusy = true
        var captured = 0
        fun captureNext(index: Int) {
            if (index >= count) {
                isBusy = false
                listener?.onBusy(false, 1f, "Burst complete")
                return
            }
            listener?.onFrameProgress(index + 1, count)
            listener?.onBusy(true, index.toFloat() / count, "Burst ${index + 1}/$count")
            val name = fileName(CaptureMode.PHOTO, ctx.zoomRatio, "jpg")
            engine.captureStill(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values = mediaValues(name, "image/jpeg", settings().saveSubfolder),
                onSaved = { uri, _ ->
                    captured++
                    ExifUtils.writeProMetadata(context.contentResolver, uri, ctx.zoomRatio, ctx.nativeZoom, null, null, pro().stripGpsExif)
                    listener?.onSaved(uri)
                    main.postDelayed({ captureNext(index + 1) }, intervalMs)
                },
                onError = { message ->
                    main.post {
                        isBusy = false
                        listener?.onBusy(false, 1f, "")
                        if (captured == 0) listener?.onError(message)
                    }
                }
            )
        }
        captureNext(0)
    }

    /** Exposure bracket: saves every exposure, and reports how many were written. */
    fun bracket(ctx: ShotContext, steps: FloatArray = floatArrayOf(-2f, -1f, 0f, 1f, 2f)) {
        if (isBusy) return
        isBusy = true
        val restoreEv = pro().evValue()
        var saved = 0
        fun captureNext(index: Int) {
            if (index >= steps.size) {
                engine.setExposureCompensation(restoreEv)
                isBusy = false
                listener?.onBusy(false, 1f, "")
                return
            }
            listener?.onFrameProgress(index + 1, steps.size)
            listener?.onBusy(true, index.toFloat() / steps.size, "Bracket ${index + 1}/${steps.size}")
            engine.setExposureCompensation(steps[index])
            main.postDelayed({
                engine.captureStill(
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values = mediaValues(fileName(CaptureMode.PRO, ctx.zoomRatio, "jpg"), "image/jpeg", settings().saveSubfolder),
                    onSaved = { uri, _ ->
                        saved++
                        listener?.onSaved(uri)
                        main.postDelayed({ captureNext(index + 1) }, 90L)
                    },
                    onError = {
                        engine.setExposureCompensation(restoreEv)
                        main.post {
                            isBusy = false
                            listener?.onBusy(false, 1f, "")
                        }
                    }
                )
            }, 240L)
        }
        captureNext(0)
    }

    /**
     * Focus stack. MainActivity drives focus positions through the engine between calls;
     * this collects the frames and merges the sharpest regions.
     */
    fun focusStack(ctx: ShotContext, frames: Int = 5, stepMs: Long = 320L, applyFocus: (Int, Int) -> Unit) {
        if (isBusy) return
        isBusy = true
        val files = ArrayList<File>(frames)
        fun captureNext(index: Int) {
            if (index >= frames) {
                mergeFocusStack(files, ctx)
                return
            }
            applyFocus(index, frames)
            listener?.onBusy(true, index.toFloat() / frames, "Focus ${index + 1}/$frames")
            main.postDelayed({
                engine.captureStillToFile(
                    file = File(sessionDir, "focus_${System.currentTimeMillis()}_$index.jpg"),
                    onSaved = {
                        files.add(it)
                        captureNext(index + 1)
                    },
                    onError = { message ->
                        main.post {
                            if (files.size >= 2) mergeFocusStack(files, ctx)
                            else {
                                isBusy = false
                                listener?.onBusy(false, 1f, "")
                                listener?.onError(message)
                            }
                        }
                    }
                )
            }, stepMs)
        }
        captureNext(0)
    }

    private fun mergeFocusStack(files: List<File>, ctx: ShotContext) {
        listener?.onBusy(true, 0.6f, "Selecting sharp regions")
        worker.execute {
            val provider = FrameProvider { index ->
                files.getOrNull(index)?.let { BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(it)) }
            }
            val stacked = com.example.zoomhundred.computational.FocusStacker.stack(files.size, provider)
            var saved: Uri? = null
            if (stacked != null) {
                val processed = PostCapture.renderBitmap(stacked, postParams(ctx, portrait = false))
                saved = saveBitmap(processed, ctx, astro = false)
                if (processed !== stacked) processed.recycle()
                stacked.recycle()
            }
            files.forEach { it.delete() }
            main.post {
                isBusy = false
                listener?.onBusy(false, 1f, "")
                if (saved != null) listener?.onSaved(saved!!) else listener?.onError("Focus stack failed")
            }
        }
    }

    // ── Guided photo sphere ───────────────────────────────────────────────────

    /**
     * Stitches a guided sphere capture into an equirectangular image.
     *
     * Each frame keeps the attitude that was live when it was taken, so the projection is
     * correct whatever path the user swept: a horizontal pass gives a panorama, adding
     * tilt gives a 360, covering up/down/around gives a full sphere.
     */
    fun stitchSphere(shots: List<SphereCaptureController.Shot>, hFovDegrees: Float, ctx: ShotContext) {
        if (isBusy) return
        if (shots.size < 3) {
            listener?.onError("Sweep a little further — three frames minimum")
            return
        }
        isBusy = true
        listener?.onBusy(true, 0.15f, "Stitching sphere")
        worker.execute {
            val frames = shots.map { SphericalStitcher.Frame(it.rotation) }
            val provider = FrameProvider { index ->
                shots.getOrNull(index)?.let {
                    BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(it.file), 3_000_000)
                }
            }
            val stitched = SphericalStitcher.stitch(
                frames = frames,
                outWidth = SphericalStitcher.DEFAULT_OUT_WIDTH,
                hFovDegrees = hFovDegrees,
                provider = provider
            )
            var saved: Uri? = null
            if (stitched != null) {
                val app = settings()
                val params = PostCapture.Params(
                    zoomRatio = 1f,
                    nativeZoom = 1f,
                    aspect = AspectRatio.FULL,
                    profile = app.colorProfile,
                    lut = com.example.zoomhundred.imaging.LutStore.loadByName(context.filesDir, app.lutName),
                    lutIntensity = app.lutIntensity,
                    frame = app.frameStyle,
                    stripGps = app.stripGps || pro().stripGpsExif,
                    keepOriginal = false,
                    modeLabel = "SPHERE"
                )
                val processed = PostCapture.renderBitmap(stitched, params)
                saved = saveBitmap(processed, ctx, astro = false)
                if (processed !== stitched) processed.recycle()
                stitched.recycle()
            }
            shots.forEach { runCatching { it.file.delete() } }
            main.post {
                isBusy = false
                listener?.onBusy(false, 1f, "")
                if (saved != null) listener?.onSaved(saved!!) else listener?.onError("Sphere stitch failed")
            }
        }
    }

    // ── Sequences: panorama, light trails, time-lapse ─────────────────────────

    fun startSequence(ctx: ShotContext, kind: SequenceKind, frameTarget: Int, intervalMs: Long) {
        if (isBusy || sequenceActive) return
        sequenceActive = true
        sequenceKind = kind
        sequenceContext = ctx
        sequenceFrames = ArrayList(frameTarget)
        sequenceFrameTarget = frameTarget
        timelapseRemaining = frameTarget
        isBusy = (kind != SequenceKind.TIMELAPSE)
        when (kind) {
            SequenceKind.PANORAMA -> {
                listener?.onBusy(true, 0f, "Pan slowly — tap to add a frame")
            }
            SequenceKind.LIGHT_TRAIL, SequenceKind.STAR_TRAIL -> {
                listener?.onBusy(true, 0f, "Accumulating light…")
            }
            SequenceKind.TIMELAPSE -> listener?.onBusy(true, 0f, "Time-lapse running")
        }
        if (kind == SequenceKind.LIGHT_TRAIL || kind == SequenceKind.STAR_TRAIL) {
            autoCaptureLoop(intervalMs, maxFrames = frameTarget, captureIndex = 0)
        }
        if (kind == SequenceKind.TIMELAPSE) {
            timelapseLoop(intervalMs)
        }
    }

    private fun autoCaptureLoop(intervalMs: Long, maxFrames: Int, captureIndex: Int) {
        if (!sequenceActive || captureIndex >= maxFrames) {
            finishSequence()
            return
        }
        val file = File(sessionDir, "seq_${System.currentTimeMillis()}_$captureIndex.jpg")
        engine.captureStillToFile(
            file = file,
            onSaved = {
                sequenceFrames.add(it)
                listener?.onFrameProgress(sequenceFrames.size, maxFrames)
                listener?.onBusy(true, sequenceFrames.size.toFloat() / maxFrames, "Frames ${sequenceFrames.size}/$maxFrames")
                main.postDelayed({ autoCaptureLoop(intervalMs, maxFrames, captureIndex + 1) }, intervalMs)
            },
            onError = {
                main.postDelayed({ autoCaptureLoop(intervalMs, maxFrames, captureIndex + 1) }, intervalMs)
            }
        )
    }

    private fun timelapseLoop(intervalMs: Long) {
        if (!sequenceActive) return
        if (timelapseRemaining <= 0) {
            finishSequence()
            return
        }
        val ctx = sequenceContext ?: return
        engine.captureStill(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values = mediaValues(fileName(CaptureMode.TIMELAPSE, ctx.zoomRatio, "jpg"), "image/jpeg", settings().saveSubfolder),
            onSaved = { uri, _ ->
                timelapseSaved++
                timelapseRemaining--
                listener?.onFrameProgress(sequenceFrameTarget - timelapseRemaining, sequenceFrameTarget)
                listener?.onSaved(uri)
                main.postDelayed({ timelapseLoop(intervalMs) }, intervalMs)
            },
            onError = {
                timelapseRemaining--
                main.postDelayed({ timelapseLoop(intervalMs) }, intervalMs)
            }
        )
    }

    /** Adds a frame to an in-progress panorama sweep. */
    fun capturePanoramaFrame() {
        if (!sequenceActive || sequenceKind != SequenceKind.PANORAMA) return
        if (sequenceFrames.size >= sequenceFrameTarget) {
            finishSequence()
            return
        }
        val file = File(sessionDir, "pano_${System.currentTimeMillis()}.jpg")
        engine.captureStillToFile(
            file = file,
            onSaved = {
                sequenceFrames.add(it)
                listener?.onFrameProgress(sequenceFrames.size, sequenceFrameTarget)
            },
            onError = { }
        )
    }

    fun finishSequence() {
        val kind = sequenceKind
        val frames = sequenceFrames.toList()
        val ctx = sequenceContext
        sequenceActive = false
        sequenceKind = null
        if (kind == null || ctx == null) {
            isBusy = false
            listener?.onBusy(false, 1f, "")
            return
        }
        if (kind == SequenceKind.TIMELAPSE) {
            isBusy = false
            listener?.onBusy(false, 1f, "")
            listener?.onFrameProgress(timelapseSaved, sequenceFrameTarget)
            return
        }
        if (frames.size < 2) {
            frames.forEach { it.delete() }
            isBusy = false
            listener?.onBusy(false, 1f, "")
            listener?.onError("Need at least two frames")
            return
        }
        listener?.onBusy(true, 0.5f, if (kind == SequenceKind.PANORAMA) "Stitching panorama" else "Stacking frames")
        worker.execute {
            val saved = when (kind) {
                SequenceKind.PANORAMA -> stitchPanorama(frames, ctx)
                SequenceKind.LIGHT_TRAIL, SequenceKind.STAR_TRAIL -> stackLight(frames, ctx)
                SequenceKind.TIMELAPSE -> null
            }
            frames.forEach { it.delete() }
            main.post {
                isBusy = false
                listener?.onBusy(false, 1f, "")
                if (saved != null) listener?.onSaved(saved) else listener?.onError("Processing failed")
            }
        }
    }

    fun cancelSequence() {
        if (!sequenceActive) return
        sequenceActive = false
        sequenceKind = null
        sequenceFrames.forEach { it.delete() }
        sequenceFrames.clear()
        isBusy = false
        listener?.onBusy(false, 1f, "")
    }

    val inSequence: Boolean get() = sequenceActive
    val framesCaptured: Int get() = sequenceFrames.size

    private fun stitchPanorama(frames: List<File>, ctx: ShotContext): Uri? {
        val provider = FrameProvider { index ->
            frames.getOrNull(index)?.let { BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(it), 6_000_000) }
        }
        val result = PanoramaStitcher.stitch(frames.size, provider) ?: return null
        val processed = PostCapture.renderBitmap(result.bitmap, postParams(ctx, portrait = false))
        val uri = saveBitmap(processed, ctx, astro = false)
        if (processed !== result.bitmap) processed.recycle()
        result.bitmap.recycle()
        return uri
    }

    private fun stackLight(frames: List<File>, ctx: ShotContext): Uri? {
        val provider = FrameProvider { index ->
            frames.getOrNull(index)?.let { BitmapOps.loadOriented(context.contentResolver, Uri.fromFile(it)) }
        }
        val stacked = LightTrailStacker.accumulate(frames.size, provider, ctx.accumulation) ?: return null
        val processed = PostCapture.renderBitmap(stacked, postParams(ctx, portrait = false))
        val uri = saveBitmap(processed, ctx, astro = false)
        if (processed !== stacked) processed.recycle()
        stacked.recycle()
        return uri
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun postParams(ctx: ShotContext, portrait: Boolean): PostCapture.Params {
        val app = settings()
        val p = pro()
        val lut = com.example.zoomhundred.imaging.LutStore.loadByName(context.filesDir, app.lutName)
        return PostCapture.Params(
            zoomRatio = ctx.zoomRatio,
            nativeZoom = ctx.nativeZoom,
            iso = if (p.manualMode) p.isoValue() else null,
            shutterNs = if (p.manualMode) p.shutterNanos() else null,
            aspect = ctx.aspect,
            profile = app.colorProfile,
            lut = lut,
            lutIntensity = app.lutIntensity,
            frame = app.frameStyle,
            portraitBlur = if (portrait) ctx.portraitStrength else if (ctx.mode == CaptureMode.DOCUMENT) 0 else 0,
            fisheye = ctx.mode == CaptureMode.FISHEYE,
            fisheyeStrength = app.fisheyeStrength,
            mirror = app.saveMirrored,
            enhanceZoom = app.zoomEnhance,
            enhanceStrength = app.zoomEnhanceStrength,
            stripGps = app.stripGps || p.stripGpsExif,
            keepOriginal = app.keepOriginals,
            polish = app.autoPolish && ctx.mode != CaptureMode.DOCUMENT,
            document = ctx.mode == CaptureMode.DOCUMENT,
            digitalCrop = (ctx.zoomRatio / ctx.nativeZoom.coerceAtLeast(1f)).coerceAtLeast(1f),
            cropOffsetX = ctx.cropOffsetX,
            cropOffsetY = ctx.cropOffsetY,
            lensLabel = ctx.lensLabel,
            modeLabel = ctx.mode.label
        )
    }

    private fun saveBitmap(bitmap: Bitmap, ctx: ShotContext, astro: Boolean): Uri? {
        val app = settings()
        val webp = app.photoFormat == PhotoFormat.WEBP
        val mime = if (webp) "image/webp" else "image/jpeg"
        val ext = if (webp) "webp" else "jpg"
        val name = fileName(ctx.mode, ctx.zoomRatio, ext)
        val uri = ImageStore.writeBitmap(
            context.contentResolver, bitmap, name, mime,
            ImageStore.relativeDir(app.saveSubfolder), quality = if (webp) 92 else 95
        ) ?: return null
        val p = pro()
        ExifUtils.writeProMetadata(
            context.contentResolver, uri, ctx.zoomRatio, ctx.nativeZoom,
            if (p.manualMode) p.isoValue() else null,
            if (p.manualMode) p.shutterNanos() else null,
            app.stripGps
        )
        return uri
    }

    private fun mediaValues(name: String, mime: String, subfolder: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, ImageStore.relativeDir(subfolder))
        }

    private fun fileName(mode: CaptureMode, zoom: Float, extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val zoomTag = if (zoom >= 10f) "${zoom.toInt()}x" else String.format(java.util.Locale.US, "%.1fx", zoom)
        return "Open100x_${mode.name.lowercase()}_${zoomTag}_$stamp.$extension"
    }

    /** Applies a manual astro exposure profile before capturing. Returns the restore values. */
    fun applyAstroExposure(pro: ProSettings, exposureSeconds: Int, iso: Int): ProSettings {
        val snapshot = pro.copyOf()
        pro.manualMode = true
        pro.wbMode = WbMode.CUSTOM
        pro.wbKelvin = 4200
        pro.isoIndex = ProSettings.ISO_VALUES.indexOfFirst { it >= iso }.let { if (it < 0) 6 else it }
        pro.shutterIndex = ProSettings.SHUTTER_VALUES.indexOfFirst { it <= exposureSeconds * 1_000_000_000L }
            .let { if (it < 0) 0 else it }
        return snapshot
    }

    fun release() {
        cancelSequence()
        worker.shutdown()
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "CaptureCoordinator"
    }
}
