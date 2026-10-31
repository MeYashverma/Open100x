package com.example.zoomhundred.imaging

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class GalleryItem(
    val uri: Uri,
    val name: String,
    val dateAddedSeconds: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

/**
 * All media I/O for the app: MediaStore writes, the private "originals" folder used for
 * non-destructive before/after, and gallery queries.
 *
 * Nothing here touches the network, and writes never overwrite an unrelated file.
 */
object ImageStore {

    fun relativeDir(subfolder: String): String =
        (Environment.DIRECTORY_PICTURES + "/" + subfolder.ifBlank { "Open100x" })

    fun writeBitmap(
        resolver: ContentResolver,
        bitmap: Bitmap,
        displayName: String,
        mime: String,
        relativePath: String,
        quality: Int = 95
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val format = when {
                mime.contains("webp", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    Bitmap.CompressFormat.WEBP_LOSSY
                mime.contains("webp", true) -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(format, quality, out) }
                ?: return null
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun originalsDir(context: Context): File =
        File(context.filesDir, "originals").apply { if (!exists()) mkdirs() }

    /** Copies the unmodified capture so the gallery can show before/after. */
    fun keepOriginal(context: Context, resolver: ContentResolver, uri: Uri, displayName: String): File? {
        return try {
            val target = File(originalsDir(context), displayName)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    fun originalFor(context: Context, name: String): File? {
        val file = File(originalsDir(context), name)
        return if (file.exists()) file else null
    }

    fun deleteOriginal(context: Context, name: String) {
        runCatching { File(originalsDir(context), name).delete() }
    }

    fun queryGallery(resolver: ContentResolver, subfolder: String, limit: Int = 400): List<GalleryItem> {
        val items = ArrayList<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%${subfolder.ifBlank { "Open100x" }}%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (cursor.moveToNext() && items.size < limit) {
                    val id = cursor.getLong(idCol)
                    items.add(
                        GalleryItem(
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            name = cursor.getString(nameCol) ?: "photo",
                            dateAddedSeconds = cursor.getLong(dateCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Gallery simply shows nothing rather than crashing.
        }
        return items
    }

    fun latestPhoto(resolver: ContentResolver, subfolder: String): GalleryItem? =
        queryGallery(resolver, subfolder, 1).firstOrNull()

    fun delete(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (_: Exception) {
        false
    }

    fun shareIntent(uri: Uri, mime: String = "image/jpeg"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun viewIntent(uri: Uri, mime: String = "image/jpeg"): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Free space in bytes on the volume that stores media. */
    fun freeBytes(): Long = try {
        Environment.getExternalStorageDirectory().usableSpace
    } catch (_: Exception) {
        0L
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toInt()} ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}
