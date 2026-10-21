package dev.tvlauncher.muse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EndpointerTest {
    private val rate = Endpointer.SAMPLE_RATE

    /** A sine at [amplitude] (RMS ≈ amplitude / √2) for [ms] milliseconds. */
    private fun tone(ms: Int, amplitude: Int) = ShortArray(rate * ms / 1000) { (amplitude * sin(2 * PI * 220 * it / rate)).toInt().toShort() }

    private fun quiet(ms: Int, amplitude: Int = 20) = tone(ms, amplitude)

    /** Feeds [chunks] in 100 ms reads, like AudioRecord does, and returns the first decision. */
    private fun Endpointer.run(vararg chunks: ShortArray): Endpointer.End? {
        for (chunk in chunks) {
            var at = 0
            while (at < chunk.size) {
                val n = minOf(rate / 10, chunk.size - at)
                feed(chunk, at, n)?.let { return it }
                at += n
            }
        }
        return null
    }

    @Test
    fun `stops after a pause once speech was heard`() {
        val end = Endpointer().run(quiet(500), tone(1500, 2000), quiet(1000))
        assertEquals(Endpointer.End.FINISHED, end)
    }

    @Test
    fun `short pauses between words don't end it`() {
        val end = Endpointer().run(quiet(300), tone(800, 2000), quiet(400), tone(800, 2000), quiet(500))
        assertNull(end)
    }

    @Test
    fun `gives up when nobody speaks`() {
        val e = Endpointer()
        assertNull(e.run(quiet(7_900)))
        assertEquals(Endpointer.End.NO_SPEECH, e.run(quiet(200)))
    }

    @Test
    fun `a click is not speech`() {
        // One 30 ms burst, like the bridge's start-up pop seen on the TV, then quiet.
        val e = Endpointer()
        assertNull(e.run(quiet(200), tone(30, 6000), quiet(2000)))
        assertEquals(false, e.heardSpeech)
    }

    @Test
    fun `talking without a pause stops at the length limit`() {
        val e = Endpointer()
        assertNull(e.run(quiet(300), tone(29_600, 2000)))
        assertEquals(Endpointer.End.TOO_LONG, e.run(tone(200, 2000)))
    }

    @Test
    fun `background TV sound raises the bar for speech`() {
        // Steady TV audio at RMS ~700 isn't speech; a voice well above it is, and the pause
        // back to TV level ends it.
        val e = Endpointer()
        assertNull(e.run(tone(2000, 1000)))
        assertEquals(false, e.heardSpeech)
        assertEquals(Endpointer.End.FINISHED, e.run(tone(1500, 6000), tone(1200, 1000)))
    }

    @Test
    fun `silence before the audio arrives counts toward no speech`() {
        // The first ~100 ms on the TV were exact zeros; they say nothing about the room.
        val e = Endpointer()
        assertNull(e.run(ShortArray(rate / 10), quiet(200), tone(1000, 2000)))
        assertEquals(true, e.heardSpeech)
    }

    @Test
    fun `speech from the very first frame is caught after the first gap between words`() {
        val e = Endpointer()
        assertNull(e.run(tone(600, 2000), quiet(150), tone(600, 2000)))
        assertEquals(true, e.heardSpeech)
    }

    @Test
    fun `the clip keeps the speech plus a little before and after`() {
        val e = Endpointer()
        e.run(quiet(1000), tone(1500, 2000), quiet(1000))
        val total = rate * 3500 / 1000
        val range = e.speechRange(total)!!
        // Speech ran 1.0 s to 2.5 s (frames are 30 ms, so allow one frame of slack).
        val frame = Endpointer.FRAME_SAMPLES
        assertEquals((1.0 - 0.2) * rate, range.first.toDouble(), frame.toDouble())
        assertEquals((2.5 + 0.25) * rate, range.last.toDouble(), frame.toDouble())
    }

    @Test
    fun `the clip never reaches past the recording`() {
        val e = Endpointer()
        e.run(tone(600, 2000), quiet(150), tone(600, 2000))
        val total = rate * 1350 / 1000
        val range = e.speechRange(total)!!
        assertEquals(true, range.first >= 0)
        assertEquals(total - 1, range.last)
    }

    @Test
    fun `no speech, no clip`() {
        val e = Endpointer()
        e.run(quiet(2000))
        assertNull(e.speechRange(rate * 2))
    }

    @Test
    fun `level is the latest frame's RMS`() {
        val e = Endpointer()
        e.run(tone(100, 1414))
        assertEquals(1000.0, e.level, 30.0)
    }
}
