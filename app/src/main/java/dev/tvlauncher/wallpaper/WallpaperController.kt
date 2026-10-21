package dev.tvlauncher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.widget.ImageView
import dev.tvlauncher.ui.FrameAnimator
import dev.tvlauncher.ui.KenBurnsAnimator
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Rotating, crossfading, slowly panning/zooming wallpaper, from the photos in the launcher's
 * wallpaper folder ([WallpaperFolder]; `scripts/wallpapers.sh` fills it over adb).
 *
 * Speed choices: each image is decoded once, off the main thread, straight to
 * its final size as a HARDWARE bitmap (GPU memory only). Images are
 * [OVERSCAN]× the screen so the slow zoom never goes past 1:1 pixels. At most
 * two are alive, only during a crossfade. Fade and motion are RenderNode
 * properties (alpha/scale/translation) on views without backgrounds, so no
 * offscreen layers and no re-recording.
 */
class WallpaperController(
    context: Context,
    private val back: ImageView,
    private val front: ImageView,
    private val io: Executor,
    private val main: Handler,
) {
    private val folder = WallpaperFolder(context.getExternalFilesDir(FOLDER))
    private val screenDir = File(context.cacheDir, "wallpapers-fitted")
    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val imageW = (context.resources.displayMetrics.widthPixels * OVERSCAN).roundToInt()
    private val imageH = (context.resources.displayMetrics.heightPixels * OVERSCAN).roundToInt()
    private val backMotion = KenBurnsAnimator(back, OVERSCAN)
    private val frontMotion = KenBurnsAnimator(front, OVERSCAN)

    // Crossfades alternate between fading [front] in (new image on top) and
    // fading it out (new image revealed underneath), so each image keeps its
    // own view and its motion never jumps.
    private var fadeFrontIn = true
    private val fade = FrameAnimator(FADE_MS) { front.alpha = if (fadeFrontIn) it else 1f - it }
    private var shownView: ImageView = back
    private val tick = Runnable { advance() }

    private var running = false
    private var showing: String? = null // cache file name on screen

    // Touched only on the io thread.
    private var playlist: List<File> = emptyList()
    private var index = -1

    init {
        front.alpha = 0f
    }

    fun start() {
        if (running) return
        running = true
        if (showing == null) {
            restoreLast()
        } else {
            motionOf(shownView).resume()
            schedule(INTERVAL_MS)
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        fade.finish()
        backMotion.pause()
        frontMotion.pause()
    }

    fun next() {
        main.removeCallbacks(tick)
        advance()
    }

    /** Drop the decoded image under memory pressure; it is re-decoded on return. */
    fun release() {
        if (running) return
        back.setImageDrawable(null)
        front.setImageDrawable(null)
        backMotion.clear()
        frontMotion.clear()
        front.alpha = 0f
        shownView = back
        showing = null
    }

    private fun motionOf(view: ImageView) = if (view === back) backMotion else frontMotion

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(tick)
        if (running) main.postDelayed(tick, delayMs)
    }

    private fun restoreLast() {
        io.execute {
            val file = folder.find(prefs.getString(KEY_LAST, null))
            val bitmap = file?.let { decodeOrNull(it) }
            main.post {
                if (bitmap != null && file != null) {
                    front.alpha = 0f
                    back.setImageBitmap(bitmap)
                    shownView = back
                    backMotion.start(INTERVAL_MS + FADE_MS)
                    showing = file.name
                    schedule(INTERVAL_MS)
                } else {
                    advance()
                }
            }
        }
    }

    private fun advance() {
        if (!running) return
        val current = showing
        io.execute {
            val file = nextFile(current)
            val bitmap = file?.let { decodeOrNull(it) }
            main.post {
                if (bitmap != null && file != null && running) crossfadeTo(bitmap, file.name)
                schedule(INTERVAL_MS)
            }
        }
    }

    private fun crossfadeTo(bitmap: Bitmap, name: String) {
        fade.finish()
        val outgoing = shownView
        val incoming = if (outgoing === back) front else back
        incoming.setImageBitmap(bitmap)
        motionOf(incoming).start(INTERVAL_MS + FADE_MS)
        fadeFrontIn = incoming === front
        front.alpha = if (fadeFrontIn) 0f else 1f
        showing = name
        prefs.edit().putString(KEY_LAST, name).apply()
        fade.start {
            outgoing.setImageDrawable(null)
            motionOf(outgoing).clear()
            shownView = incoming
        }
    }

    // ---- io thread ----

    private fun nextFile(current: String?): File? {
        refreshPlaylist()
        if (playlist.isEmpty()) return null
        repeat(playlist.size) {
            index = (index + 1) % playlist.size
            val file = playlist[index]
            if (file.name != current && file.isFile) return file
        }
        return null // only the image already on screen
    }

    /** Picks up photos added or removed since the last look; reshuffles when the set changed. */
    private fun refreshPlaylist() {
        val now = folder.list()
        if (now.map { it.name }.toSet() != playlist.map { it.name }.toSet()) {
            playlist = now.shuffled()
            index = -1
        }
    }

    /**
     * Decode [file] as an [imageW]×[imageH] HARDWARE bitmap. The first decode of
     * an image that isn't already that size also saves a fitted JPEG beside it,
     * so later decodes (including every cold start) read a small file with no scaling.
     */
    private fun decodeOrNull(file: File): Bitmap? {
        // Photos already at the decode size (wallpapers.sh renders them so) decode as-is.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth == imageW && bounds.outHeight == imageH) {
            decode(file, ImageDecoder.ALLOCATOR_HARDWARE)?.let { return it }
        }
        val fitted = File(screenDir, "${file.name}-${file.length()}-${file.lastModified()}-${imageW}x$imageH.jpg")
        if (fitted.isFile) decode(fitted, ImageDecoder.ALLOCATOR_HARDWARE)?.let { return it }
        val soft = decode(file, ImageDecoder.ALLOCATOR_SOFTWARE) ?: return null
        try {
            screenDir.mkdirs()
            val tmp = File(screenDir, fitted.name + ".tmp")
            tmp.outputStream().use { soft.compress(Bitmap.CompressFormat.JPEG, FITTED_QUALITY, it) }
            tmp.renameTo(fitted)
            screenDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FITTED)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "could not save fitted copy", e)
        }
        return soft.copy(Bitmap.Config.HARDWARE, false).also { soft.recycle() }
    }

    private fun decode(file: File, allocator: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val plan = ImageMath.coverPlan(info.size.width, info.size.height, imageW, imageH)
            decoder.setTargetSize(plan.scaledW, plan.scaledH)
            decoder.crop = plan.crop.run { Rect(left, top, right, bottom) }
            decoder.allocator = allocator
        }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: ${file.name}", e)
        null
    }

    companion object {
        private const val TAG = "Wallpaper"
        private const val KEY_LAST = "last"
        private const val INTERVAL_MS = 60_000L
        private const val FOLDER = "wallpapers"
        private const val FADE_MS = 1_200L
        private const val FITTED_QUALITY = 92
        private const val MAX_FITTED = 40

        /** Images are this much larger than the screen; the zoom tops out at 1:1. */
        private const val OVERSCAN = 1.2f
    }
}
