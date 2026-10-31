package com.example.zoomhundred

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.zoomhundred.diagnostics.CrashGuard
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.ProSettings

/**
 * Launcher entry point, and the safe mode screen.
 *
 * Normal launch: starts [MainActivity] immediately and finishes — one extra activity with
 * no layout inflation, so startup cost is negligible.
 *
 * Failed launch: if the previous session left a crash report, this screen appears instead,
 * *before any camera code runs*. That guarantees the user can always reach the stack trace
 * (and copy it), reset the settings and try again — even when the camera screen itself
 * cannot start. Everything here is built in code from a plain [Activity], so it does not
 * depend on the AppCompat theme, the layouts or the camera stack that may be broken.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashGuard.lastCrash(this)
        if (report.isNullOrBlank()) startCamera() else showSafeMode(report)
    }

    private fun startCamera() {
        try {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (error: Throwable) {
            CrashGuard.record(this, "launcher", error)
            showSafeMode(CrashGuard.describe(this, error, "launcher"))
        }
    }

    private fun showSafeMode(report: String) {
        val body = TextView(this).apply {
            setTextColor(0xFFEDEDF2.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            inputType = InputType.TYPE_NULL
            text = report
        }
        val scroll = ScrollView(this).apply { addView(body) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF07080A.toInt())
            setPadding(dp(16), dp(32), dp(16), dp(16))
            addView(
                TextView(this@LauncherActivity).apply {
                    setTextColor(0xFFF3F4F6.toInt())
                    textSize = 20f
                    text = "Open 100x"
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                TextView(this@LauncherActivity).apply {
                    setTextColor(0xFFCDBBFF.toInt())
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(12))
                    text = "The last launch failed, so the camera was not started. " +
                        "Copy the report below and send it — it names the exact cause."
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                buttons(
                    "Copy report" to {
                        copyToClipboard(report)
                        CrashGuard.clear(this@LauncherActivity)
                        startCamera()
                    },
                    "Reset settings" to { resetSettingsAndStart() }
                ),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                buttons(
                    "Start anyway" to { startCamera() },
                    "Discard report" to {
                        CrashGuard.clear(this@LauncherActivity)
                        startCamera()
                    }
                ),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun buttons(vararg entries: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            entries.forEach { (label, action) ->
                addView(
                    Button(this@LauncherActivity).apply {
                        text = label
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                )
            }
        }

    private fun resetSettingsAndStart() {
        runCatching {
            AppSettings().reset(this)
            ProSettings.reset(this)
            CrashGuard.clear(this)
        }
        toast("Settings reset to defaults")
        startCamera()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Open 100x report", text))
            toast("Report copied")
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
