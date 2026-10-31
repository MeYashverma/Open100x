package com.example.zoomhundred

import android.app.Application
import com.example.zoomhundred.diagnostics.CrashGuard

/**
 * Installs the crash recorder before any activity starts.
 *
 * Nothing else happens here on purpose: launching a camera app should not spend
 * time or memory on application-level initialisation.
 */
class Open100xApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
