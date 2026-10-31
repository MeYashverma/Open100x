package com.example.zoomhundred.model

import android.content.SharedPreferences

/**
 * Type-safe SharedPreferences reads.
 *
 * `SharedPreferences.getInt`/`getBoolean`/`getFloat`/`getString` throw
 * [ClassCastException] when the stored value has another type. That happens for real:
 * a key changes type between releases, or an older build of the app wrote it
 * differently. Because settings are read during `onCreate`, such a mismatch used to
 * crash the app on launch, before any UI existed.
 *
 * These helpers turn that class of failure into "use the default and keep going".
 * The camera must always open.
 */

fun SharedPreferences.safeInt(key: String, def: Int): Int =
    runCatching { getInt(key, def) }.getOrDefault(def)

fun SharedPreferences.safeBoolean(key: String, def: Boolean): Boolean =
    runCatching { getBoolean(key, def) }.getOrDefault(def)

fun SharedPreferences.safeFloat(key: String, def: Float): Float =
    runCatching { getFloat(key, def) }.getOrDefault(def)

fun SharedPreferences.safeLong(key: String, def: Long): Long =
    runCatching { getLong(key, def) }.getOrDefault(def)

fun SharedPreferences.safeString(key: String, def: String?): String? =
    runCatching { getString(key, def) }.getOrDefault(def)

/** Wipes the file when it is unreadable, instead of failing every read forever. */
fun SharedPreferences.wipeIfCorrupt(): SharedPreferences = try {
    getAll()
    this
} catch (_: Throwable) {
    try {
        edit().clear().commit()
    } catch (_: Throwable) {
        // Nothing left to try; callers fall back to defaults.
    }
    this
}
