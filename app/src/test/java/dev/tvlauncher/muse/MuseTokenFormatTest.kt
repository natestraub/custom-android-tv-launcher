package dev.tvlauncher.muse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseTokenFormatTest {
    // Cases ported from the SDK's tests/test_config.py.
    @Test
    fun `accepts a canonical mgst token`() {
        assertTrue(MuseTokenFormat.isValid("mgst_" + "A".repeat(42) + "w"))
    }

    @Test
    fun `rejects short, overlong and non-canonical tokens`() {
        assertFalse(MuseTokenFormat.isValid("mgst_short"))
        assertFalse(MuseTokenFormat.isValid("mgst_" + "A".repeat(43) + "x"))
        // 43 base64url chars carry 32 bytes, so the last char's low 2 bits must be zero.
        assertFalse(MuseTokenFormat.isValid("mgst_" + "A".repeat(42) + "B"))
        assertFalse(MuseTokenFormat.isValid("MGST_" + "A".repeat(42) + "w"))
        assertFalse(MuseTokenFormat.isValid("mgst_" + "A".repeat(41) + "+w"))
        assertFalse(MuseTokenFormat.isValid(" mgst_" + "A".repeat(42) + "w"))
    }
}
