package dev.tvlauncher.firetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogTest {
    // Lines as Fire OS 8.1 logs them (logcat -v epoch, system buffer).
    private val press = "         1792908143.703  1030  1124 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN " +
        "cat=[android.intent.category.HOME] flg=0x10000100 cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)} from uid 0"
    private val fromSettings = "1792908148.528  1030  2927 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN " +
        "cat=[android.intent.category.HOME] cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)} from uid 10187"
    private val settings = "1792908148.430  1030  2925 I ActivityTaskManager: START u0 {act=android.settings.SETTINGS " +
        "flg=0x10000000 cmp=com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler} from uid 2000"

    @Test
    fun `a HOME start gives its uid and time`() {
        assertEquals(HomeLog.Start(uid = 0, atMs = 1792908143703L), HomeLog.parse(press))
        assertEquals(10187, HomeLog.parse(fromSettings)?.uid)
    }

    @Test
    fun `other starts and other lines are not Home`() {
        assertNull(HomeLog.parse(settings))
        assertNull(HomeLog.parse("--------- beginning of system"))
        assertNull(HomeLog.parse("1792908143.703 1030 1124 I ActivityTaskManager: Displayed com.amazon.tv.launcher/.ui.HomeActivity_vNext"))
    }

    @Test
    fun `a line without a time still counts`() {
        val noTime = press.replace("1792908143.703", "x")
        assertEquals(HomeLog.Start(uid = 0, atMs = 0L), HomeLog.parse(noTime))
    }
}
