package dev.tvlauncher.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import dev.tvlauncher.Http
import dev.tvlauncher.wallpaper.ImageMath
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Card art for Watch Next items: fetched once (content://, android.resource://
 * or http/https — whatever the app published), center-cropped to exact card
 * size, cached on disk as a small JPEG, returned as a HARDWARE bitmap.
 * Blocking: call off the main thread.
 */
class WatchNextArt(private val context: Context, private val width: Int, private val height: Int) {
    private val dir = File(context.cacheDir, "watchnext-art")

    /** Cached art only (fast; for the first paint of the row). */
    fun cached(uri: String): Bitmap? = file(uri).takeIf { it.isFile }?.let { decode(ImageDecoder.createSource(it)) }

    /** Cached, or fetched and cached now. Null when the image can't be loaded. */
    fun load(uri: String): Bitmap? {
        cached(uri)?.let { return it }
        return try {
            val bytes = fetch(uri) ?: return null
            val soft = decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), ImageDecoder.ALLOCATOR_SOFTWARE) ?: return null
            dir.mkdirs()
            val out = file(uri)
            val tmp = File(dir, out.name + ".tmp")
            tmp.outputStream().use { soft.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(out)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
            soft.copy(Bitmap.Config.HARDWARE, false).also { soft.recycle() }
        } catch (e: Exception) {
            Log.w("Apps", "card art failed: $uri", e)
            null
        }
    }

    private fun fetch(uri: String): ByteArray? {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "http", "https" -> {
                val conn = Http.open(uri)
                try {
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
            else -> context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
        }
    }

    private fun decode(source: ImageDecoder.Source, allocator: Int = ImageDecoder.ALLOCATOR_HARDWARE): Bitmap? = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val plan = ImageMath.coverPlan(info.size.width, info.size.height, width, height)
            decoder.setTargetSize(plan.scaledW, plan.scaledH)
            decoder.crop = plan.crop.run { Rect(left, top, right, bottom) }
            decoder.allocator = allocator
        }
    } catch (e: Exception) {
        null
    }

    private fun file(uri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest("$uri#${width}x$height".toByteArray())
        return File(dir, hash.joinToString("") { "%02x".format(it) }.take(24) + ".jpg")
    }

    private companion object {
        const val MAX_FILES = 80
    }
}
