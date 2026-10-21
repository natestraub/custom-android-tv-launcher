package dev.tvlauncher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Feeds [ContinueRowView] from the TV's Watch Next list on its own background
 * thread (first-time art downloads never hold up app or wallpaper loading).
 * Cached art paints first; missing art is fetched and the row repainted once.
 */
class ContinueWatching(
    private val context: Context,
    private val main: Handler,
    private val row: ContinueRowView,
    cardW: Int,
    cardH: Int,
    focusScale: Float,
) {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "launcher-watchnext")
    }
    private val art = WatchNextArt(context, (cardW * focusScale).roundToInt(), (cardH * focusScale).roundToInt())
    private var shown: List<String> = emptyList() // main thread: what the row currently draws

    fun hasPermission() =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasPermission()) return
        io.execute {
            val items = WatchNext.load(context, MAX_AGE_DAYS, LIMIT)
            val first = items.map { it to art.cached(it.artUri) }
            main.post { show(first) }
            if (first.any { it.second == null }) {
                val full = first.map { (item, bmp) -> item to (bmp ?: art.load(item.artUri)) }
                main.post { show(full) }
            }
        }
    }

    fun open(item: WatchNextItem) {
        try {
            context.startActivity(Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Apps", "watch next link failed, opening the app: ${item.packageName}", e)
            context.packageManager.getLeanbackLaunchIntentForPackage(item.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun shutdown() = io.shutdownNow()

    /** Repaint only when something visible changed (items, progress, or art arriving). */
    private fun show(cards: List<Pair<WatchNextItem, android.graphics.Bitmap?>>) {
        val signature = cards.map { (i, b) -> "${i.packageName}|${i.title}|${i.subtitle}|${i.progress}|${b != null}" }
        if (signature == shown) return
        shown = signature
        row.setItems(cards)
    }

    companion object {
        /** Runtime permission to read the TV provider (not exposed as a public SDK constant). */
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
        private const val MAX_AGE_DAYS = 60
        private const val LIMIT = 20
    }
}
