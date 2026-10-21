package dev.tvlauncher.muse.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun `16 kHz mono 16-bit header and little-endian samples`() {
        val wav = Wav.encode(shortArrayOf(1, -2, 0x1234), 3, sampleRate = 16000)
        assertEquals(44 + 6, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, b.getInt(16)) // fmt chunk size
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(wav, 36, 4))
        assertEquals(6, b.getInt(40))
        assertArrayEquals(byteArrayOf(1, 0, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), wav.copyOfRange(44, 50))
    }

    @Test
    fun `only the first count samples are written`() {
        val wav = Wav.encode(ShortArray(10) { 7 }, 4)
        assertEquals(44 + 8, wav.size)
    }

    @Test
    fun `30 seconds of speech fits a Muse voice note`() {
        val samples = 30 * 16000
        assertEquals(true, Wav.encode(ShortArray(samples), samples).size <= 2 * 1024 * 1024)
    }
}
