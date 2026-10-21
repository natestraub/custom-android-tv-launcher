package dev.tvlauncher.muse.link

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import dev.tvlauncher.SettingsKeyService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What's on the TV's screen, as a small JPEG for Muse to look at. Captured by the launcher's
 * accessibility service (Android 11+, no consent prompt). Protected video (e.g. Netflix) comes
 * out black, and so may any video the TV draws on its own hardware layer.
 */
object TvScreenshot {
    /** Base64 grows this by a third; the link takes at most 256 KB per message. */
    const val MAX_JPEG_BYTES = 150 * 1024
    private const val WIDTH = 960
    private val QUALITIES = listOf(80, 65, 50, 35)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "tv-screenshot") }

    /** Blocks; the JPEG and its size, or an error message for Muse. */
    fun capture(timeoutMs: Long = 5_000): Result<Triple<ByteArray, Int, Int>> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) error("screenshots need Android 11")
        val service = SettingsKeyService.running
            ?: error("turn on \"TV Launcher Settings button\" in the TV's Accessibility settings")
        val done = CountDownLatch(1)
        var shot: Bitmap? = null
        var failure: String? = null
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                }
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                failure = "screenshot failed (code $errorCode)"
                done.countDown()
            }
        })
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) error("screenshot timed out")
        failure?.let { error(it) }
        val full = shot ?: error("screenshot was empty")
        val height = full.height * WIDTH / full.width
        val small = Bitmap.createScaledBitmap(full, WIDTH, height, true)
        full.recycle()
        val jpeg = fit(MAX_JPEG_BYTES) { quality ->
            ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }
        small.recycle()
        Triple(jpeg ?: error("screenshot too large to send"), WIDTH, height)
    }

    /** The highest quality whose JPEG is at most [maxBytes], or null. */
    fun fit(maxBytes: Int, encode: (quality: Int) -> ByteArray): ByteArray? {
        for (quality in QUALITIES) encode(quality).let { if (it.size <= maxBytes) return it }
        return null
    }
}
