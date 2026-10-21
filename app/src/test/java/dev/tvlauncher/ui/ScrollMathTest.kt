package dev.tvlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollMathTest {
    // Viewport 1000px wide; content 2000px; keep 100px of breathing room.
    private fun target(start: Int, end: Int, current: Float, content: Int = 2000) =
        ScrollMath.targetOffset(
            itemStart = start,
            itemEnd = end,
            viewport = 1000,
            content = content,
            margin = 100,
            current = current,
        )

    @Test
    fun `does not move when the item is already comfortably visible`() {
        assertEquals(0f, target(200, 400, current = 0f))
        assertEquals(-300f, target(600, 800, current = -300f))
    }

    @Test
    fun `scrolls left just enough to reveal an item past the right edge`() {
        // item end 1100 + margin 100 must sit at 1000 → offset -200
        assertEquals(-200f, target(900, 1100, current = 0f))
    }

    @Test
    fun `scrolls right just enough to reveal an item past the left edge`() {
        // item start 500 - margin 100 must sit at 0 → offset -400
        assertEquals(-400f, target(500, 700, current = -800f))
    }

    @Test
    fun `clamps to the content edges`() {
        assertEquals(0f, target(50, 250, current = -500f))
        assertEquals(-1000f, target(1800, 2000, current = 0f))
    }

    @Test
    fun `content narrower than the viewport never scrolls`() {
        assertEquals(0f, target(700, 900, current = 0f, content = 950))
    }
}
