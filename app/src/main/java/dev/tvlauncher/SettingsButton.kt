package dev.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

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
 * and takes screenshots when Muse asks (tv.screenshot). Subscribes to no accessibility
 * events and never reads window content; every other key passes straight through.
 */
class SettingsKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!SettingsButton.matches(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) SettingsButton.open(this)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        /** The connected service, if it's turned on: Muse's tv.screenshot uses it. */
        @Volatile var running: SettingsKeyService? = null
            private set
    }
}
