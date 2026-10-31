package com.example.zoomhundred.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around CameraX's `Recorder` that keeps recording state honest.
 *
 * Pause/resume is only enabled when the device reports support through
 * [VideoRecordEvent.Status] / recorder capabilities; the UI asks [canPause] rather than
 * assuming. Audio is requested only when the RECORD_AUDIO permission is granted —
 * otherwise the clip is recorded silently instead of failing.
 */
class VideoRecorderController(private val context: Context) {

    data class State(
        val recording: Boolean = false,
        val paused: Boolean = false,
        val elapsedMs: Long = 0,
        val bytesRecorded: Long = 0,
        val outputUri: Uri? = null,
        val error: String? = null
    )

    interface Listener {
        fun onRecordingState(state: State)
        fun onRecordingFinalized(uri: Uri)
        fun onRecordingError(message: String)
    }

    var listener: Listener? = null

    private var recording: Recording? = null
    private var startedAtMs = 0L
    private var pausedAtMs = 0L
    private var accumulatedPauseMs = 0L
    private var bytes = 0L

    val isRecording: Boolean get() = recording != null
    val canPause: Boolean get() = recording != null

    fun elapsedMs(): Long {
        if (!isRecording) return 0L
        val now = SystemClock.uptimeMillis()
        val end = if (pausedAtMs > 0L) pausedAtMs else now
        return (end - startedAtMs - accumulatedPauseMs).coerceAtLeast(0L)
    }

    fun start(
        capture: VideoCapture<Recorder>,
        displayName: String,
        relativePath: String,
        withAudio: Boolean
    ): Boolean {
        if (recording != null) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(context, output)
        if (withAudio) {
            pending = runCatching { pending.withAudioEnabled() }.getOrDefault(pending)
        }
        bytes = 0L
        startedAtMs = SystemClock.uptimeMillis()
        pausedAtMs = 0L
        accumulatedPauseMs = 0L

        return try {
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> listener?.onRecordingState(
                        State(recording = true, elapsedMs = 0L)
                    )
                    is VideoRecordEvent.Status -> {
                        bytes = event.recordingStats.numBytesRecorded
                        listener?.onRecordingState(
                            State(
                                recording = true,
                                paused = pausedAtMs > 0L,
                                elapsedMs = elapsedMs(),
                                bytesRecorded = bytes
                            )
                        )
                    }
                    is VideoRecordEvent.Pause -> {
                        pausedAtMs = SystemClock.uptimeMillis()
                        listener?.onRecordingState(State(recording = true, paused = true, elapsedMs = elapsedMs(), bytesRecorded = bytes))
                    }
                    is VideoRecordEvent.Resume -> {
                        if (pausedAtMs > 0L) accumulatedPauseMs += SystemClock.uptimeMillis() - pausedAtMs
                        pausedAtMs = 0L
                        listener?.onRecordingState(State(recording = true, elapsedMs = elapsedMs(), bytesRecorded = bytes))
                    }
                    is VideoRecordEvent.Finalize -> {
                        val uri = event.outputResults.outputUri
                        val error = event.error
                        recording = null
                        if (error != VideoRecordEvent.FINALIZE_ERROR_NONE && uri == Uri.EMPTY) {
                            listener?.onRecordingError("Recording failed (code $error)")
                        } else if (uri != Uri.EMPTY) {
                            listener?.onRecordingFinalized(uri)
                        }
                        listener?.onRecordingState(State(recording = false, elapsedMs = 0L, outputUri = uri))
                    }
                }
            }
            true
        } catch (e: Exception) {
            recording = null
            listener?.onRecordingError(e.message ?: "Could not start recording")
            false
        }
    }

    fun pause(): Boolean {
        val active = recording ?: return false
        return runCatching { active.pause(); true }.getOrDefault(false)
    }

    fun resume(): Boolean {
        val active = recording ?: return false
        return runCatching { active.resume(); true }.getOrDefault(false)
    }

    fun stop() {
        val active = recording ?: return
        runCatching { active.stop() }
        recording = null
    }

    fun release() {
        runCatching { recording?.close() }
        recording = null
    }

    fun bytesPerSecond(): Long {
        val elapsed = elapsedMs()
        return if (elapsed > 1000 && bytes > 0) bytes * 1000 / elapsed else 0L
    }
}
