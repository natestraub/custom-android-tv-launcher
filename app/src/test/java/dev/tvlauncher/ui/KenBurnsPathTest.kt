package dev.tvlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class KenBurnsPathTest {
    private val w = 1920f
    private val h = 1080f

    @Test
    fun `never reveals an edge - pan stays inside the zoomed overflow`() {
        val rng = Random(42)
        repeat(500) {
            val path = KenBurnsPath.random(rng, maxScale = 1.1f)
            for (step in 0..20) {
                val f = path.frame(step / 20f, w, h)
                assertTrue("scale ${f.scale}", f.scale in 1f..1.1f)
                assertTrue("tx ${f.translateX}", abs(f.translateX) <= (f.scale - 1f) * w / 2f + 0.01f)
                assertTrue("ty ${f.translateY}", abs(f.translateY) <= (f.scale - 1f) * h / 2f + 0.01f)
            }
        }
    }

    @Test
    fun `starts and ends where it says`() {
        val path = KenBurnsPath(1f, 1.1f, 0f, 0f, 1f, -1f)
        assertEquals(1f, path.frame(0f, w, h).scale, 1e-4f)
        val end = path.frame(1f, w, h)
        assertEquals(1.1f, end.scale, 1e-4f)
        assertEquals(0.1f * w / 2f, end.translateX, 0.01f)
        assertEquals(-0.1f * h / 2f, end.translateY, 0.01f)
    }

    @Test
    fun `clamps progress outside 0 to 1`() {
        val path = KenBurnsPath(1f, 1.1f, 0f, 0f, 0f, 0f)
        assertEquals(1.1f, path.frame(5f, w, h).scale, 1e-4f)
        assertEquals(1f, path.frame(-1f, w, h).scale, 1e-4f)
    }
}
