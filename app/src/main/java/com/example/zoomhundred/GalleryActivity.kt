package com.example.zoomhundred

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.zoomhundred.imaging.GalleryItem
import com.example.zoomhundred.imaging.ImageStore
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.ui.SheetUi
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device gallery for everything this app wrote into its own album folder.
 *
 * No media scanning of other apps, no cloud, no network: the list comes from a MediaStore
 * query filtered by the configured subfolder, and the detail sheet can reveal the
 * untouched original when "keep original frame" is on.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var countText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val items = ArrayList<GalleryItem>()
    private val adapter = ThumbnailAdapter()
    private val thumbCache = HashMap<String, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_gallery)

        settings = AppSettings.load(this)
        grid = findViewById(R.id.galleryGrid)
        empty = findViewById(R.id.galleryEmpty)
        countText = findViewById(R.id.galleryCount)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { showDetail(it) }
        }
        findViewById<TextView>(R.id.galleryClose).setOnClickListener { finish() }
        applyInsets()
        reload(null)
    }

    override fun onResume() {
        super.onResume()
        reload(null)
    }

    override fun onDestroy() {
        thumbExecutor.shutdown()
        ioExecutor.shutdown()
        thumbCache.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbCache.clear()
        super.onDestroy()
    }

    private fun applyInsets() {
        val header = findViewById<View>(R.id.galleryHeader)
        val base = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.galleryRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, base + bars.top, header.paddingRight, header.paddingBottom)
            grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, bars.bottom + 18)
            insets
        }
    }

    private fun reload(selectAfter: android.net.Uri?) {
        ioExecutor.execute {
            val loaded = ImageStore.queryGallery(contentResolver, settings.saveSubfolder)
            mainHandler.post {
                items.clear()
                items.addAll(loaded)
                adapter.notifyDataSetChanged()
                empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
                countText.text = "${loaded.size} item${if (loaded.size == 1) "" else "s"} · ${settings.saveSubfolder}"
                if (selectAfter != null) {
                    items.indexOfFirst { it.uri == selectAfter }.takeIf { it >= 0 }?.let { grid.setSelection(it) }
                }
            }
        }
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private inner class ThumbnailAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = items.getOrNull(position)?.uri?.hashCode()?.toLong() ?: 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items.getOrNull(position) ?: return View(this@GalleryActivity)
            val view = convertView ?: layoutInflater.inflate(R.layout.item_gallery_thumb, parent, false)
            val image = view.findViewById<ImageView>(R.id.thumbImage)
            val label = view.findViewById<TextView>(R.id.thumbLabel)
            label.text = item.name.take(18)
            val cached = thumbCache[item.uri.toString()]
            if (cached != null && !cached.isRecycled) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_panel))
                image.tag = item.uri.toString()
                thumbExecutor.execute {
                    val bitmap = runCatching {
                        contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
                    }.getOrNull()
                    mainHandler.post {
                        if (bitmap != null && image.tag == item.uri.toString()) {
                            thumbCache[item.uri.toString()] = bitmap
                            image.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            return view
        }
    }

    // ── Detail sheet ──────────────────────────────────────────────────────────

    private fun showDetail(item: GalleryItem) {
        val sheet = BottomSheetDialog(this)
        val column = SheetUi.column(this)
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ContextCompat.getColor(this@GalleryActivity, R.color.ink))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)
            )
        }
        column.addView(preview)
        column.addView(
            SheetUi.header(
                this,
                item.name,
                "${formatDate(item.dateAddedSeconds)} · ${ImageStore.formatBytes(item.sizeBytes)} · ${item.width}×${item.height}",
                "Done"
            ) { sheet.dismiss() }
        )
        column.addView(SheetUi.note(this, "Stored in Pictures/${settings.saveSubfolder}. EXIF is written by the capture pipeline; location is only present if you enabled it."))

        val original = ImageStore.originalFor(this, item.name)
        var showingOriginal = false
        val info = SheetUi.caption(this, "")
        column.addView(info)

        fun loadImage(fromOriginal: Boolean) {
            ioExecutor.execute {
                val bitmap = if (fromOriginal && original != null) {
                    decodeSampled(original)
                } else {
                    decodeSampled(item.uri)
                }
                mainHandler.post {
                    preview.setImageBitmap(bitmap)
                    info.text = if (fromOriginal) "Showing untouched original" else ""
                }
            }
        }
        loadImage(false)

        column.addView(
            SheetUi.buttonStrip(
                this,
                buildList {
                    if (original != null) {
                        add("Before / after" to {
                            showingOriginal = !showingOriginal
                            loadImage(showingOriginal)
                        })
                    }
                    add("Share" to {
                        startActivity(Intent.createChooser(ImageStore.shareIntent(item.uri), "Share photo"))
                    })
                    add("Open" to { startActivity(ImageStore.viewIntent(item.uri)) })
                }
            )
        )
        column.addView(
            SheetUi.buttonStrip(
                this,
                listOf("Delete" to {
                    val deleted = ImageStore.delete(contentResolver, item.uri)
                    if (deleted) {
                        sheet.dismiss()
                        reload(null)
                    } else {
                        toast("Could not delete that file")
                    }
                }),
                primaryFirst = false
            )
        )
        sheet.setContentView(column)
        sheet.show()
    }

    /**
     * Viewer decode. Uses the same EXIF path as the capture pipeline so a portrait shot
     * is never shown on its side — reading the tag from a provider stream is unreliable,
     * which is why [com.example.zoomhundred.imaging.BitmapOps] goes through a descriptor.
     */
    private fun decodeSampled(uri: android.net.Uri): Bitmap? {
        return try {
        val decoded = run {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longest / sample > 2048) sample *= 2
                contentResolver.openInputStream(uri)?.use { second ->
                    BitmapFactory.decodeStream(second, null, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }
            }
        } ?: return null
        val orientation = com.example.zoomhundred.imaging.BitmapOps.readExifOrientation(contentResolver, uri)
        if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) decoded
        else com.example.zoomhundred.imaging.BitmapOps.applyExifOrientation(decoded, orientation)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(file: java.io.File): Bitmap? {
        return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val orientation = com.example.zoomhundred.imaging.BitmapOps.readExifOrientation(file)
        if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) decoded
        else com.example.zoomhundred.imaging.BitmapOps.applyExifOrientation(decoded, orientation)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDate(seconds: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(seconds * 1000))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
