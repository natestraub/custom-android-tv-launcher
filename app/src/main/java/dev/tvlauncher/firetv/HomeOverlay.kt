package dev.tvlauncher.firetv

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Our home screen in a window over Amazon's. After a Home press Fire OS holds back every
 * other app's activity starts for about 5 s, so bringing [Host] (the launcher activity) to
 * the front takes that long. Adding a window isn't an activity start: the launcher lends its
 * view tree to this accessibility overlay at once, and takes it back when it comes to the
 * front ([handBack]), so focus, scroll and the wallpaper carry straight over. Main thread only.
 */
class HomeOverlay(private val service: AccessibilityService) {
    /** The launcher activity's side of the loan. */
    interface Host {
        /** Move the home screen from the activity into [into], keeping its focus, and keep it running. */
        fun lendHome(into: ViewGroup)

        /** Move the home screen back into the activity, keeping its focus. */
        fun takeHomeBack()

        /** Home pressed while ours is already showing. */
        fun onHomeAgain()
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val frame = FrameLayout(service)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        windowAnimations = 0
        title = "Home"
    }
    private var attached = false
    /** Whose home screen is in [frame], if any. */
    private var host: Host? = null

    val showing get() = attached

    /** Cover Amazon's home with ours. False if the launcher has no home screen to lend yet. */
    fun show(host: Host?): Boolean {
        if (host == null) return false
        setFocusable(true)
        if (!attached) {
            try {
                wm.addView(frame, params)
                attached = true
            } catch (e: Exception) {
                Log.w(TAG, "overlay refused: $e")
                return false
            }
        }
        if (this.host == null) {
            this.host = host
            // The activity's window background doesn't come with its views.
            frame.setBackgroundColor(Color.BLACK)
            host.lendHome(frame)
        }
        return true
    }

    /** After a pick the overlay is only a cover until the app arrives; keys and focus go to it. */
    fun setFocusable(focusable: Boolean) {
        val flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        if (attached) wm.updateViewLayout(frame, params)
    }

    /** Give the home screen back to the activity (now in front) and remove the window after its next frame. */
    fun handBack(afterFrameOf: View?) {
        returnHome()
        if (afterFrameOf == null) return hide()
        afterFrameOf.postOnAnimation { afterFrameOf.postDelayed({ hide() }, SWAP_MS) }
    }

    /** Another app came up, or the launcher is gone: the window goes and the home screen goes home. */
    fun hide() {
        returnHome()
        if (!attached) return
        attached = false
        runCatching { wm.removeViewImmediate(frame) }
    }

    fun onHomeAgain() = host?.onHomeAgain()

    /** The activity was destroyed: drop its views without handing them back. */
    fun forget() {
        frame.removeAllViews()
        host = null
        hide()
    }

    private fun returnHome() {
        val lender = host ?: return
        host = null
        lender.takeHomeBack()
        // Clear, so the activity shows through until the window goes.
        frame.background = null
    }

    private companion object {
        const val TAG = "FireTvHome"

        /** Lets the activity's frame with the home screen reach the display before the window goes. */
        const val SWAP_MS = 32L
    }
}
