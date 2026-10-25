package dev.tvlauncher.firetv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import dev.tvlauncher.LauncherActivity

/**
 * Makes this launcher the Fire TV's home screen. Fire OS keeps Amazon's launcher as Home
 * whatever the default is set to, so the accessibility service watches for Amazon's home
 * coming up ([HomeDecider], from window events and, earlier, the system log via
 * [HomeWatcher]) and puts ours over it at once ([HomeOverlay]). Once Fire OS allows activity
 * starts again (~5 s after a Home press) the launcher activity comes to the front and takes
 * its home screen back. Fire TV only ([create]); main thread only.
 */
class FireTvHome private constructor(
    private val service: AccessibilityService,
    amazonPackage: String,
    amazonHome: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val decider = HomeDecider(amazonPackage, amazonHome, service.packageName)
    private val overlay = HomeOverlay(service)
    private val watcher = HomeWatcher(service) { uid -> act(decider.onHomeStart(uid, now()), press = true) }
    private var picking = false
    private var afterHandBack: (() -> Unit)? = null
    private val handOff = Runnable { bringLauncherForward() }
    private val giveUp = Runnable { if (picking) uncover() }

    fun start() {
        current = this
        watcher.start()
        Log.i(TAG, "Fire TV home redirect on; Home presses from the log: ${watcher.permitted}")
        // Right after boot Amazon's home is up and ours hasn't started.
        if (launcher == null && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) bringLauncherForward()
    }

    fun stop() {
        watcher.stop()
        uncover()
        main.removeCallbacksAndMessages(null)
        if (current === this) current = null
    }

    fun onWindow(pkg: String, cls: String) = act(decider.onWindow(pkg, cls, now()), press = false)

    private fun act(action: HomeDecider.Action, press: Boolean) {
        when (action) {
            HomeDecider.Action.COVER -> cover(press)
            HomeDecider.Action.UNCOVER -> uncover()
            HomeDecider.Action.NONE -> Unit
        }
    }

    private fun cover(press: Boolean) {
        // Amazon's home stays behind the cover until the picked app starts; only a real press counts.
        if (picking && !press) return
        val wasShowing = overlay.showing && !picking
        picking = false
        main.removeCallbacks(giveUp)
        if (wasShowing) {
            if (press) {
                overlay.onHomeAgain()
                main.removeCallbacks(handOff)
                main.postDelayed(handOff, HANDOFF_MS)
            }
            return
        }
        overlay.setFocusable(true)
        if (!overlay.show(launcher)) {
            // Nothing to lend yet (launcher not started): it shows once Fire OS lets it start.
            bringLauncherForward()
            return
        }
        main.removeCallbacks(handOff)
        main.postDelayed(handOff, HANDOFF_MS)
    }

    private fun uncover() {
        picking = false
        afterHandBack = null
        main.removeCallbacks(handOff)
        main.removeCallbacks(giveUp)
        overlay.hide()
    }

    private fun bringLauncherForward() {
        try {
            service.startActivity(
                Intent(service, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_HANDOFF, true),
            )
        } catch (e: Exception) {
            Log.w(TAG, "launcher start failed: $e")
        }
        // If it never lands (a focused overlay hides the window events that would tell us), try again.
        main.removeCallbacks(handOff)
        if (overlay.showing) main.postDelayed(handOff, RETRY_MS)
    }

    // ---- from the launcher activity ----

    /** The launcher is at the front: take the home screen back from the overlay. */
    private fun onLauncherFront(drawn: View?) {
        main.removeCallbacks(handOff)
        if (picking) return
        overlay.handBack(drawn)
        afterHandBack?.also { afterHandBack = null }?.invoke()
    }

    /** An app or input was picked on the overlay: keep covering until it arrives, without the keys. */
    private fun onPick() {
        if (!overlay.showing) return
        picking = true
        afterHandBack = null
        main.removeCallbacks(handOff)
        overlay.setFocusable(false)
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, PICK_TIMEOUT_MS)
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "FireTvHome"
        const val EXTRA_HANDOFF = "dev.tvlauncher.HANDOFF"

        /** Just past Fire OS's hold on activity starts after a Home press. */
        private const val HANDOFF_MS = 5_300L
        private const val RETRY_MS = 2_000L
        /** Give up covering if the picked app never shows. */
        private const val PICK_TIMEOUT_MS = 8_000L
        private const val BOOT_WINDOW_MS = 3 * 60_000L

        /** The running redirect, if this is a Fire TV and the accessibility service is on. */
        private var current: FireTvHome? = null

        /** The launcher activity while it exists; the overlay borrows its home screen. */
        private var launcher: HomeOverlay.Host? = null

        /** Whether this is a Fire TV whose Home is Amazon's launcher; the redirect if so. */
        fun create(service: AccessibilityService): FireTvHome? {
            val pm = service.packageManager
            if (!pm.hasSystemFeature("amazon.hardware.fire_tv")) return null
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo ?: return null
            if (home.packageName == service.packageName) return null
            return FireTvHome(service, home.packageName, home.name)
        }

        fun attach(host: HomeOverlay.Host) {
            launcher = host
        }

        fun detach(host: HomeOverlay.Host) {
            if (launcher !== host) return
            launcher = null
            current?.overlay?.forget()
        }

        fun launcherFront(drawn: View?) = current?.onLauncherFront(drawn)

        fun picked() = current?.onPick()

        /** Run [action] once the launcher activity is in front (dialogs need it); now if it is. */
        fun inFront(lent: Boolean, action: () -> Unit) {
            val home = current
            if (!lent || home == null) return action()
            home.afterHandBack = action
            home.bringLauncherForward()
        }
    }
}
