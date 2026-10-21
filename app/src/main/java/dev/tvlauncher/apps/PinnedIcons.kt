package dev.tvlauncher.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.Log
import dev.tvlauncher.R
import java.io.File

/**
 * Logo-only icons for pinned apps. Apps with a bundled white logo use it;
 * others get the foreground layer of their adaptive icon (no tile background),
 * rendered once at exact size and cached on disk keyed by the app's update
 * time. Blocking: call off the main thread.
 */
class PinnedIcons(
    private val context: Context,
    private val sizePx: Int,
    /** Logo drawable for an HDMI pin's port. */
    private val hdmiLogo: (Int) -> Int,
) {
    private val dir = File(context.filesDir, "pinned")

    fun load(packages: List<String>): List<Pair<String, Bitmap>> {
        val pm = context.packageManager
        return packages.mapNotNull { pkg ->
            HdmiInputs.port(pkg)?.let { port -> return@mapNotNull pkg to bundled(hdmiLogo(port)) }
            val updated = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            BUNDLED[pkg]?.let { res -> return@mapNotNull pkg to bundled(res) }
            val file = File(dir, "$pkg.$updated.$sizePx.png")
            val cached = BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE },
            )
            (cached ?: render(pkg, file))?.let { pkg to it }
        }
    }

    /** A bundled logo (PNG or vector) fitted, aspect kept and centered, into a [sizePx] square. */
    private fun bundled(res: Int): Bitmap {
        val logo = context.getDrawable(res)!!
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val iw = logo.intrinsicWidth.coerceAtLeast(1)
        val ih = logo.intrinsicHeight.coerceAtLeast(1)
        val scale = sizePx.toFloat() / maxOf(iw, ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val left = (sizePx - w) / 2
        val top = (sizePx - h) / 2
        logo.setBounds(left, top, left + w, top + h)
        (logo as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = true
        logo.draw(Canvas(out))
        return out.copy(Bitmap.Config.HARDWARE, false).also { out.recycle() }
    }

    private fun render(pkg: String, file: File): Bitmap? = try {
        val icon = context.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (icon is AdaptiveIconDrawable && icon.foreground != null) {
            // Adaptive layers are 108dp with the visible logo in the middle 72dp: overdraw by 25% per side.
            val bleed = sizePx / 4
            icon.foreground.setBounds(-bleed, -bleed, sizePx + bleed, sizePx + bleed)
            icon.foreground.draw(canvas)
        } else {
            icon.setBounds(0, 0, sizePx, sizePx)
            icon.draw(canvas)
        }
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$pkg.") }?.forEach { it.delete() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.copy(Bitmap.Config.HARDWARE, false).also { bitmap.recycle() }
    } catch (e: Exception) {
        Log.w("Apps", "pinned icon failed for $pkg", e)
        null
    }

    private companion object {
        val BUNDLED = mapOf(
            "com.spotify.tv.android" to R.drawable.logo_spotify,
            "com.google.android.youtube.tv" to R.drawable.logo_youtube,
        )
    }
}
