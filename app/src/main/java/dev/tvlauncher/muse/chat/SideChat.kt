package dev.tvlauncher.muse.chat

import android.content.Context
import java.util.UUID

/** The TV's own side chat in Muse: one id, kept across questions so Muse has the context. */
object SideChat {
    private const val PREFS = "muse_chat"
    private const val KEY_ID = "side_chat_id"

    @Synchronized
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    /** Starts a fresh side chat: Muse no longer knows this one (e.g. the TV was paired again). */
    @Synchronized
    fun reset(context: Context): String {
        val fresh = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ID, fresh).apply()
        return fresh
    }
}
