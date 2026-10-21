package dev.tvlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    private fun item(
        title: String,
        daysAgo: Int,
        pkg: String = "app",
        type: Int = WatchNext.TYPE_CONTINUE,
        position: Long = 0,
        duration: Long = 0,
    ) = WatchNextItem(
        packageName = pkg,
        title = title,
        subtitle = "",
        artUri = "content://art/$title",
        intentUri = "intent://$title",
        type = type,
        lastEngagedMillis = now - daysAgo * day,
        positionMillis = position,
        durationMillis = duration,
    )

    @Test
    fun `newest first, older than the cutoff dropped`() {
        val picked = WatchNext.select(
            listOf(item("old", 90), item("week", 7), item("today", 0)),
            nowMillis = now, maxAgeDays = 60, limit = 20,
        )
        assertEquals(listOf("today", "week"), picked.map { it.title })
    }

    @Test
    fun `one card per show per app, watchlist-only items left out`() {
        val picked = WatchNext.select(
            listOf(
                item("Severance", 1),
                item("Severance", 3),
                item("Severance", 2, pkg = "other"),
                item("Someday", 0, type = WatchNext.TYPE_WATCHLIST),
            ),
            nowMillis = now, maxAgeDays = 60, limit = 20,
        )
        assertEquals(listOf("Severance" to 1, "Severance" to 2), picked.map { it.title to ((now - it.lastEngagedMillis) / day).toInt() })
    }

    @Test
    fun `caps the row`() {
        val picked = WatchNext.select((1..30).map { item("t$it", it) }, nowMillis = now, maxAgeDays = 60, limit = 20)
        assertEquals(20, picked.size)
        assertEquals("t1", picked.first().title)
    }

    @Test
    fun `progress only when both position and duration are known`() {
        assertEquals(0.25f, item("a", 0, position = 25, duration = 100).progress!!, 1e-6f)
        assertNull(item("b", 0, position = 25).progress)
        assertNull(item("c", 0, duration = 100).progress)
    }

    @Test
    fun `episode subtitle reads like the TV apps do`() {
        assertEquals("S2 · E3 · The Grim Barbarity", WatchNext.subtitle("2", "3", "The Grim Barbarity"))
        assertEquals("E12", WatchNext.subtitle(null, "12", null))
        assertEquals("", WatchNext.subtitle(null, null, null))
        // Season 0 = specials/movies (Crunchyroll): no "S0".
        assertEquals("Reze Arc", WatchNext.subtitle("0", null, "Reze Arc"))
    }
}
