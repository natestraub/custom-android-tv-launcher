package dev.tvlauncher.firetv

import dev.tvlauncher.firetv.HomeDecider.Action.COVER
import dev.tvlauncher.firetv.HomeDecider.Action.NONE
import dev.tvlauncher.firetv.HomeDecider.Action.UNCOVER
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDeciderTest {
    private val amazon = "com.amazon.tv.launcher"
    private val home = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val decider = HomeDecider(amazon, home, ownPackage = "dev.tvlauncher")

    @Test
    fun `a Home press from the system covers Amazon's home`() {
        assertEquals(COVER, decider.onHomeStart(uid = 0, now = 1_000))
        assertEquals(COVER, decider.onHomeStart(uid = 1000, now = 2_000))
    }

    @Test
    fun `Amazon's home coming up without a logged press still covers (Back out of an app)`() {
        assertEquals(COVER, decider.onWindow(amazon, home, now = 1_000))
    }

    @Test
    fun `opening Settings goes through Amazon's home and is left alone`() {
        assertEquals(NONE, decider.onHomeStart(uid = 10187, now = 1_000))
        assertEquals(NONE, decider.onWindow(amazon, home, now = 1_100))
        assertEquals(UNCOVER, decider.onWindow(amazon, "com.amazon.tv.launcher.ui.MainSettingsActivity", now = 1_200))
        // Back from Settings, after the grace period, lands on Amazon's home: cover it.
        assertEquals(COVER, decider.onWindow(amazon, home, now = 1_200 + HomeDecider.SETTINGS_GRACE_MS + 1))
    }

    @Test
    fun `a real Home press during the Settings grace still counts`() {
        decider.onHomeStart(uid = 10187, now = 1_000)
        assertEquals(COVER, decider.onHomeStart(uid = 0, now = 1_500))
        assertEquals(COVER, decider.onWindow(amazon, home, now = 1_600))
    }

    @Test
    fun `another app coming up uncovers, but our own windows, System UI and dialogs don't`() {
        assertEquals(UNCOVER, decider.onWindow("com.netflix.ninja", "com.netflix.ninja.MainActivity", now = 1_000))
        assertEquals(NONE, decider.onWindow("dev.tvlauncher", "dev.tvlauncher.LauncherActivity", now = 1_000))
        assertEquals(NONE, decider.onWindow("com.android.systemui", "android.widget.FrameLayout", now = 1_000))
        assertEquals(NONE, decider.onWindow(amazon, "android.app.Dialog", now = 1_000))
    }
}
