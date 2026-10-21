package dev.tvlauncher.apps

import android.content.Context

/** The user's row arrangement: a saved order plus hidden apps (by package name). */
object AppOrder {
    /** Saved order first, then any apps not in it in their incoming order; hidden apps dropped. */
    fun <T> arrange(items: List<T>, key: (T) -> String, order: List<String>, hidden: Set<String>): List<T> {
        val visible = items.filter { key(it) !in hidden }
        val rank = order.withIndex().associate { (i, k) -> k to i }
        val (ranked, rest) = visible.partition { key(it) in rank }
        return ranked.sortedBy { rank.getValue(key(it)) } + rest
    }

    /** [row] with [key] swapped one step by [delta] (±1); unchanged at the ends. */
    fun move(row: List<String>, key: String, delta: Int): List<String> {
        val from = row.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in row.indices) return row
        return row.toMutableList().apply {
            this[from] = row[to]
            this[to] = key
        }
    }
}

/** Persists [AppOrder] state. */
class AppOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("row", Context.MODE_PRIVATE)

    var order: List<String>
        get() = prefs.getString(KEY_ORDER, "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_ORDER, value.joinToString(",")).apply()

    var hidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value).apply()

    /** Apps shown as small icons beside the search button instead of in the row. */
    var pinned: List<String>
        get() = prefs.getString(KEY_PINNED, null)?.split(',')?.filter { it.isNotEmpty() } ?: DEFAULT_PINNED
        set(value) = prefs.edit().putString(KEY_PINNED, value.joinToString(",")).apply()

    /** Logo key (see [HdmiInputs.LOGOS]) for a pinned HDMI port. */
    fun hdmiLogo(port: Int): String = prefs.getString("$KEY_HDMI_LOGO$port", null) ?: if (port == 2) "xbox" else "input"

    fun setHdmiLogo(port: Int, logo: String) = prefs.edit().putString("$KEY_HDMI_LOGO$port", logo).apply()

    private companion object {
        const val KEY_HDMI_LOGO = "hdmi_logo_"
        const val KEY_ORDER = "order"
        const val KEY_HIDDEN = "hidden"
        const val KEY_PINNED = "pinned"
        val DEFAULT_PINNED = listOf("com.google.android.youtube.tv", "com.spotify.tv.android", HdmiInputs.key(2))
    }
}
