package dev.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.tvlauncher.firetv.FireTvHome

/**
 * The remote's Settings button. System-wide it asks for the Google TV home
 * app's quick-settings panel, which is disabled here and can't be replaced,
 * so we open the TV's settings ourselves.
 */
object SettingsButton {
    /** TCL remote "Settings" button (scan 233 → TCL_SET in Generic.kl). */
    private const val KEYCODE_TCL_SETTINGS = 4077

    fun matches(keyCode: Int) =
        keyCode == KEYCODE_TCL_SETTINGS || keyCode == KeyEvent.KEYCODE_SETTINGS

    fun open(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Launcher", "settings unavailable", e)
        }
    }
}

/**
 * Key-filter accessibility service: makes the Settings button work inside any app,
 * and takes screenshots when Muse asks (tv.screenshot). On a Fire TV it also makes this
 * launcher Home ([FireTvHome]) from which app's window is in front. It never reads window
 * content; every other key passes straight through.
 */
class SettingsKeyService : AccessibilityService() {
    private var fireTvHome: FireTvHome? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        fireTvHome = FireTvHome.create(this)?.also { it.start() }
    }

    override fun onDestroy() {
        fireTvHome?.stop()
        fireTvHome = null
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!SettingsButton.matches(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) SettingsButton.open(this)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val home = fireTvHome ?: return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        home.onWindow(event.packageName?.toString() ?: return, event.className?.toString() ?: "")
    }

    override fun onInterrupt() = Unit

    companion object {
        /** The connected service, if it's turned on: Muse's tv.screenshot uses it. */
        @Volatile var running: SettingsKeyService? = null
            private set
    }
}
