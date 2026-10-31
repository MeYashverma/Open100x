package com.example.zoomhundred.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture.
 *
 * Every entry point is wrapped so this code can never throw on its own: it runs from
 * the process' uncaught-exception handler, where a second failure would hide the first.
 *
 * A report is written to `files/crash/last_crash.txt`. The next launch shows it and
 * offers a copy button, so a user can report a startup failure without a debugger —
 * which is the only way to diagnose an OEM-specific crash in the field.
 *
 * Deliberately privacy-safe: device/OS/stack-trace facts only. No photos, no EXIF,
 * no location, no identifiers, and nothing is ever uploaded.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val ERRORS_FILE = "recent_errors.txt"
    private const val MAX_CHARS = 16_000

    @Volatile
    private var installed = false

    /** Installs the handler once per process, chaining to whatever was there before. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "uncaught:${thread.name}", error) }
            if (previous != null) {
                runCatching { previous.uncaughtException(thread, error) }
            }
        }
    }

    /**
     * Records a recoverable failure (camera bind, background work, thumbnail loading).
     *
     * Deliberately kept out of [lastCrash]: a handled camera error must not send the next
     * launch into safe mode. These diagnostics are for the in-app report and diagnostics
     * export only.
     */
    fun record(context: Context, where: String, error: Throwable) {
        Log.w(TAG, "$where failed", error)
        runCatching {
            val file = File(File(context.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }, ERRORS_FILE)
            val existing = if (file.exists()) file.readText() else ""
            val entry = stamp() + " [" + where + "] " + error.javaClass.name + ": " + (error.message ?: "") + "\n"
            file.writeText((entry + existing).take(MAX_CHARS))
        }
    }

    /** Recent handled failures, newest first. Empty when nothing has gone wrong. */
    fun recentErrors(context: Context): String? = runCatching {
        val file = File(context.filesDir, DIR + "/" + ERRORS_FILE)
        if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }.getOrNull()

    /** The previous session's report, or null when the last session was clean. */
    fun lastCrash(context: Context): String? = runCatching {
        val file = file(context)
        if (file.exists()) file.readText().takeLast(MAX_CHARS) else null
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** A self-contained report for an error the UI is currently showing. */
    fun describe(context: Context, error: Throwable, where: String = "startup"): String = buildString {
        appendLine("Open 100x failure report")
        appendLine(stamp())
        appendLine("where=$where")
        appendLine(deviceLine())
        appendLine()
        appendLine(trace(error))
    }

    private fun file(context: Context): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE)
    }

    private fun write(context: Context, where: String, error: Throwable) {
        val entry = buildString {
            appendLine("Open 100x failure report")
            appendLine(stamp())
            appendLine("where=$where")
            appendLine(deviceLine())
            appendLine()
            appendLine(trace(error))
        }
        val file = file(context)
        // Append, and keep the *head* of the file: the first failure is the root cause,
        // everything after it is usually a consequence. Overwriting the file used to hide
        // the origin of the bug behind whatever crashed next.
        val existing = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (existing.contains(entry.trim())) return
        val combined = if (existing.isBlank()) entry else existing + "\n\n" + entry
        file.writeText(combined.take(MAX_CHARS))
    }

    private fun deviceLine(): String =
        "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"

    private fun trace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
