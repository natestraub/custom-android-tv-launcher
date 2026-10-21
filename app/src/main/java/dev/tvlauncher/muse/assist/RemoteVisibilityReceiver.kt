package dev.tvlauncher.muse.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Does nothing; it exists so Google's TV remote service can see this app. On Android 11+
 * it can only see apps matching its manifest `<queries>`, whose single entry is the
 * FIND_MY_REMOTE action. Without this, it can't look up our UID and won't hand the
 * assistant the remote's mic audio (spec §1, §5). The manifest protects it with DUMP, so no
 * app can actually trigger it.
 */
class RemoteVisibilityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
