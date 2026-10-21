package dev.tvlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val apps = listOf("apple", "disney", "hulu", "netflix", "tcl")

    @Test
    fun `saved order first, then new apps in their incoming order, hidden removed`() {
        assertEquals(
            listOf("netflix", "apple", "disney", "hulu"),
            AppOrder.arrange(apps, { it }, order = listOf("netflix", "gone", "apple"), hidden = setOf("tcl")),
        )
    }

    @Test
    fun `no saved order keeps the incoming order`() {
        assertEquals(apps, AppOrder.arrange(apps, { it }, emptyList(), emptySet()))
    }

    @Test
    fun `moves one step and stops at the ends`() {
        val row = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), AppOrder.move(row, "a", +1))
        assertEquals(listOf("a", "c", "b"), AppOrder.move(row, "c", -1))
        assertEquals(row, AppOrder.move(row, "a", -1))
        assertEquals(row, AppOrder.move(row, "c", +1))
        assertEquals(row, AppOrder.move(row, "missing", +1))
    }
}
