package dev.tvlauncher.muse.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvScreenshotTest {
    @Test
    fun `uses the best quality that fits`() {
        val tried = ArrayList<Int>()
        val jpeg = TvScreenshot.fit(maxBytes = 100) { quality -> tried += quality; ByteArray(quality * 2) }
        assertEquals(listOf(80, 65, 50), tried)
        assertEquals(100, jpeg!!.size)
    }

    @Test
    fun `gives up when even the lowest quality is too big`() {
        assertNull(TvScreenshot.fit(maxBytes = 10) { ByteArray(1000) })
    }

    @Test
    fun `the base64 image stays well under the link's 256 KB message limit`() {
        assertTrue(TvScreenshot.MAX_JPEG_BYTES * 4 / 3 + 1024 < 256 * 1024)
    }

    @Test
    fun `the command is registered`() {
        assertTrue(TvCommands.SPECS.containsKey("tv.screenshot"))
    }
}
