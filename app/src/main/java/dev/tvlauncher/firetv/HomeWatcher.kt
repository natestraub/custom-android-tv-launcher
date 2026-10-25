package dev.tvlauncher.firetv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Follows the system log for HOME starts ([HomeLog]) and reports who started each one, on the
 * main thread. Needs READ_LOGS, granted over adb (scripts/install.sh); the log access it gives
 * applies from the next process start. Only ActivityTaskManager lines are read and none are kept.
 */
class HomeWatcher(private val context: Context, private val onHomeStart: (uid: Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var process: Process? = null

    val permitted get() = context.checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !permitted) return
        running = true
        Thread({ follow() }, "home-watcher").start()
    }

    fun stop() {
        running = false
        process?.destroy()
    }

    private fun follow() {
        var backoff = 2_000L
        while (running) {
            val since = System.currentTimeMillis()
            try {
                val p = ProcessBuilder(HomeLog.LOGCAT).redirectErrorStream(true).start().also { process = it }
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!running) break
                        // -T 1 replays the newest line first, which may be an old press.
                        val start = HomeLog.parse(line) ?: continue
                        if (start.atMs != 0L && start.atMs < since) continue
                        main.post { onHomeStart(start.uid) }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "home watch failed: $e")
            } finally {
                process?.destroy()
                process = null
            }
            if (!running) return
            if (System.currentTimeMillis() - since > 60_000L) backoff = 2_000L
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private companion object {
        const val TAG = "FireTvHome"
        const val READ_LOGS = "android.permission.READ_LOGS"
    }
}
