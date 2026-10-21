package dev.tvlauncher.apps

import android.content.Context
import android.media.tv.TvContract
import android.util.Log

/** One "continue watching" item an app published to the TV's Watch Next list. */
data class WatchNextItem(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val artUri: String,
    val intentUri: String,
    val type: Int,
    val lastEngagedMillis: Long,
    val positionMillis: Long,
    val durationMillis: Long,
) {
    /** 0..1 watched, or null when the app didn't report both position and duration. */
    val progress: Float?
        get() = if (positionMillis > 0 && durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else null
}

/**
 * Reads Android TV's Watch Next list (apps keep it current themselves; Google TV
 * just doesn't show it). Needs READ_TV_LISTINGS. Blocking: call off the main thread.
 */
object WatchNext {
    // TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*
    const val TYPE_CONTINUE = 0
    const val TYPE_NEXT = 1
    const val TYPE_NEW = 2
    const val TYPE_WATCHLIST = 3

    fun load(context: Context, maxAgeDays: Int, limit: Int): List<WatchNextItem> {
        val columns = arrayOf(
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        )
        val items = mutableListOf<WatchNextItem>()
        try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(12) && c.getInt(12) == 0) continue // app marked it hidden
                    val pkg = c.getString(0) ?: continue
                    val title = c.getString(1) ?: continue
                    val intent = c.getString(7) ?: continue
                    // Landscape thumbnail suits the 16:9 cards; fall back to the poster.
                    val art = c.getString(6) ?: c.getString(5) ?: continue
                    items += WatchNextItem(
                        packageName = pkg,
                        title = title,
                        subtitle = subtitle(c.getString(2), c.getString(3), c.getString(4)),
                        artUri = art,
                        intentUri = intent,
                        type = if (c.isNull(8)) TYPE_CONTINUE else c.getInt(8),
                        lastEngagedMillis = c.getLong(9),
                        positionMillis = c.getLong(10),
                        durationMillis = c.getLong(11),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.i("Apps", "watch next unavailable: ${e.message}")
            return emptyList()
        }
        return select(items, System.currentTimeMillis(), maxAgeDays, limit)
    }

    /** Recent, newest first, one card per show per app, watchlist-only entries dropped. */
    fun select(items: List<WatchNextItem>, nowMillis: Long, maxAgeDays: Int, limit: Int): List<WatchNextItem> {
        val cutoff = nowMillis - maxAgeDays * 24L * 60 * 60 * 1000
        return items
            .filter { it.type != TYPE_WATCHLIST && it.lastEngagedMillis >= cutoff }
            .sortedByDescending { it.lastEngagedMillis }
            .distinctBy { it.packageName to it.title }
            .take(limit)
    }

    /** "S2 · E3 · Title", whatever parts the app provided (season 0 = specials, left out). */
    fun subtitle(season: String?, episode: String?, episodeTitle: String?): String =
        listOfNotNull(
            season?.takeIf { it.isNotBlank() && it != "0" }?.let { "S$it" },
            episode?.takeIf { it.isNotBlank() }?.let { "E$it" },
            episodeTitle?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}
