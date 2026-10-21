package dev.tvlauncher.muse.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCommandsTest {
    private val labels = listOf("YouTube", "YouTube Kids", "Netflix", "Prime Video", "Disney+", "Spotify - Music", "Apple TV")

    @Test
    fun `names match ignoring case spaces and symbols`() {
        assertEquals("YouTube", labels[AppMatch.best("youtube", labels)!!])
        assertEquals("Prime Video", labels[AppMatch.best("primevideo", labels)!!])
        assertEquals("Disney+", labels[AppMatch.best("Disney+", labels)!!])
        assertEquals("Disney+", labels[AppMatch.best("disney", labels)!!])
        assertEquals("Apple TV", labels[AppMatch.best("apple tv", labels)!!])
    }

    @Test
    fun `prefix then substring, shortest first`() {
        assertEquals("YouTube", labels[AppMatch.best("you", labels)!!])
        assertEquals("Spotify - Music", labels[AppMatch.best("spotify", labels)!!])
        assertEquals("YouTube Kids", labels[AppMatch.best("kids", labels)!!])
        assertEquals("Prime Video", labels[AppMatch.best("video", labels)!!])
    }

    @Test
    fun `no match or an empty name finds nothing`() {
        assertNull(AppMatch.best("hulu", labels))
        assertNull(AppMatch.best("  ", labels))
        assertNull(AppMatch.best("!!!", labels))
    }

    @Test
    fun `registered commands are TV-safe and well formed`() {
        assertFalse(TvCommands.SPECS.containsKey("device.ota"))
        assertFalse(TvCommands.SPECS.keys.any { it.startsWith("system.") || it.startsWith("file.") })
        for ((name, spec) in TvCommands.SPECS) {
            spec as Map<*, *>
            assertTrue(name, (spec["description"] as String).isNotBlank())
            assertTrue(name, spec["required"] is Map<*, *> && spec["optional"] is Map<*, *>)
            for (param in (spec["required"] as Map<*, *>).values + (spec["optional"] as Map<*, *>).values) {
                param as Map<*, *>
                assertTrue(name, param["type"] in setOf("string", "integer", "boolean"))
            }
        }
    }
}
