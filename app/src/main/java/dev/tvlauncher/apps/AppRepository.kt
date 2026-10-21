package dev.tvlauncher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** PackageInfo.lastUpdateTime when the tile was rendered; a change re-renders the row. */
    val updatedAt: Long,
    /** Shared HARDWARE atlas holding every tile; this app's tile is [tileSrc]. */
    val atlas: Bitmap,
    val tileSrc: Rect,
) {
    val launchIntent: Intent
        get() = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(packageName, activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * TV-launchable apps with every tile pre-rendered at exact pixel size into one
 * atlas, cached on disk as raw pixels. Cold start = one file read + one GPU
 * upload (no PNG decodes, no PackageManager banner loading, which takes
 * seconds right after boot). Both methods block: call them off the main thread.
 */
class AppRepository(
    private val context: Context,
    /** Tile size in px (the tile's focused size, so focus stays crisp). */
    private val tileW: Int,
    private val tileH: Int,
) {
    private val dir = File(context.filesDir, "apps")
    private val index = File(dir, "index.json")
    private val atlasFile = File(dir, "atlas.raw")
    private val density = context.resources.displayMetrics.density

    fun loadCached(): List<AppEntry> {
        val json = try {
            JSONObject(index.readText())
        } catch (_: Exception) {
            return emptyList()
        }
        if (json.optInt("w") != tileW || json.optInt("h") != tileH) return emptyList()
        val apps = json.optJSONArray("apps") ?: return emptyList()
        val n = apps.length()
        if (n == 0) return emptyList()
        val atlas = readAtlas(n) ?: return emptyList()
        return (0 until n).map { i ->
            val o = apps.getJSONObject(i)
            AppEntry(o.getString("pkg"), o.getString("activity"), o.getString("label"), o.getLong("updated"), atlas, slot(i))
        }
    }

    /**
     * Installed apps now. Returns [known] untouched when nothing was installed,
     * removed or updated; otherwise re-renders the whole atlas and rewrites the cache.
     */
    fun scan(known: List<AppEntry>): List<AppEntry> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val installed = pm.queryIntentActivities(query, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                try {
                    info to pm.getPackageInfo(info.packageName, 0).lastUpdateTime
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .toList()

        val knownKeys = known.map { Triple(it.packageName, it.activityName, it.updatedAt) }.toSet()
        val installedKeys = installed.map { (info, updated) -> Triple(info.packageName, info.name, updated) }.toSet()
        if (known.isNotEmpty() && knownKeys == installedKeys) return known

        val sorted = installed
            .map { (info, updated) -> Triple(info, updated, info.loadLabel(pm).toString()) }
            .sortedBy { it.third.lowercase() }
        if (sorted.isEmpty()) return emptyList()
        val (atlasW, atlasH) = atlasSize(sorted.size)
        val soft = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(soft)
        sorted.forEachIndexed { i, (info, _, label) ->
            val src = slot(i)
            canvas.save()
            canvas.clipRect(src)
            canvas.translate(src.left.toFloat(), src.top.toFloat())
            val banner = info.loadBanner(pm)
            if (banner != null) {
                banner.setBounds(0, 0, tileW, tileH)
                banner.draw(canvas)
            } else {
                drawIconTile(canvas, info.loadIcon(pm), label)
            }
            canvas.restore()
        }
        writeCache(sorted, soft)
        val atlas = soft.copy(Bitmap.Config.HARDWARE, false).also { soft.recycle() }
        return sorted.mapIndexed { i, (info, updated, label) ->
            AppEntry(info.packageName, info.name, label, updated, atlas, slot(i))
        }
    }

    private fun slot(i: Int): Rect {
        val left = (i % ATLAS_COLUMNS) * (tileW + GUTTER)
        val top = (i / ATLAS_COLUMNS) * (tileH + GUTTER)
        return Rect(left, top, left + tileW, top + tileH)
    }

    private fun atlasSize(n: Int): Pair<Int, Int> {
        val cols = minOf(n, ATLAS_COLUMNS)
        val rows = (n + cols - 1) / cols
        return cols * (tileW + GUTTER) - GUTTER to rows * (tileH + GUTTER) - GUTTER
    }

    private fun readAtlas(n: Int): Bitmap? {
        val (w, h) = atlasSize(n)
        val bytes = w.toLong() * h * 4
        return try {
            RandomAccessFile(atlasFile, "r").use { raf ->
                if (raf.length() != bytes) return null
                val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
                val soft = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                soft.copyPixelsFromBuffer(buffer)
                soft.copy(Bitmap.Config.HARDWARE, false).also { soft.recycle() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawIconTile(canvas: Canvas, icon: Drawable?, label: String) {
        canvas.drawColor(FALLBACK_BG)
        val size = (tileH * 0.45f).toInt()
        if (icon != null) {
            val left = (tileW - size) / 2
            val top = (tileH * 0.12f).toInt()
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = tileH * 0.16f
            textAlign = Paint.Align.CENTER
        }
        val text = TextUtils.ellipsize(label, paint, tileW - 16 * density, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, tileW / 2f, tileH * 0.85f, paint)
    }

    private fun writeCache(apps: List<Triple<ActivityInfo, Long, String>>, atlas: Bitmap) {
        try {
            dir.mkdirs()
            val atlasTmp = File(dir, "atlas.raw.tmp")
            FileOutputStream(atlasTmp).channel.use { ch ->
                val buffer = ByteBuffer.allocate(atlas.byteCount)
                atlas.copyPixelsToBuffer(buffer)
                buffer.flip()
                while (buffer.hasRemaining()) ch.write(buffer)
            }
            val list = JSONArray()
            for ((info, updated, label) in apps) {
                list.put(
                    JSONObject()
                        .put("pkg", info.packageName)
                        .put("activity", info.name)
                        .put("label", label)
                        .put("updated", updated),
                )
            }
            val indexTmp = File(dir, "index.json.tmp")
            indexTmp.writeText(JSONObject().put("w", tileW).put("h", tileH).put("apps", list).toString())
            atlasTmp.renameTo(atlasFile)
            indexTmp.renameTo(index)
            dir.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "could not write app cache", e)
        }
    }

    companion object {
        private const val TAG = "Apps"
        /** Keeps the atlas well under the GPU's max texture size. */
        private const val ATLAS_COLUMNS = 6

        /** Transparent px between tiles so bilinear filtering never samples a neighbour. */
        private const val GUTTER = 2
        private val FALLBACK_BG = Color.rgb(0x22, 0x24, 0x2A)
    }
}
