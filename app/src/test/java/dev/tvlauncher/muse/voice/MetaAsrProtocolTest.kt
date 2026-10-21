package dev.tvlauncher.muse.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaAsrProtocolTest {
    @Test
    fun `handshake carries the key, 16 kHz audio, endpointing and cumulative partials`() {
        val h = JSONObject(MetaAsrProtocol.handshake("k-123"))
        assertEquals("Bearer k-123", h.getJSONObject("authorization").getString("accessToken"))
        assertEquals("PCM_16KHZ", h.getString("audioEncoding"))
        assertEquals("muse-voice-transcribe-1.0", h.getString("model"))
        assertEquals("ENDPOINTING", h.getString("mode"))
        assertEquals("CUMULATIVE", h.getString("partialMode"))
    }

    @Test
    fun `the url carries a session id`() {
        assertTrue(MetaAsrProtocol.url("s-1") == "wss://api.meta.ai/v1/asr/realtime?sessionId=s-1")
    }

    @Test
    fun `the acknowledgement has a session id and no type`() {
        assertEquals(MetaAsrProtocol.Event.Ready, MetaAsrProtocol.parse("""{"sessionId":"550e8400"}"""))
    }

    @Test
    fun `partials replace the words so far`() {
        assertEquals(
            MetaAsrProtocol.Event.Words("how is the weather", final = false),
            MetaAsrProtocol.parse("""{"type":"transcript","transcript":"how is the weather","final":false,"audioProcessedMs":3200}"""),
        )
    }

    @Test
    fun `speech complete is the finished question`() {
        assertEquals(
            MetaAsrProtocol.Event.Words("How is the weather?", final = true),
            MetaAsrProtocol.parse("""{"type":"speechComplete","turnId":1,"transcript":"How is the weather?","audioProcessedMs":3600}"""),
        )
    }

    @Test
    fun `a final transcript also finishes it`() {
        assertEquals(
            MetaAsrProtocol.Event.Words("open netflix", final = true),
            MetaAsrProtocol.parse("""{"type":"transcript","transcript":"open netflix","final":true}"""),
        )
    }

    @Test
    fun `errors keep their message`() {
        assertEquals(MetaAsrProtocol.Event.Failed("bad key"), MetaAsrProtocol.parse("""{"type":"error","message":"bad key"}"""))
    }

    @Test
    fun `boundaries, unknown events and junk are ignored`() {
        for (text in listOf("""{"type":"speechStart","turnId":1}""", """{"type":"speechEnd","turnId":1}""", """{"type":"audioProgress"}""", "not json")) {
            assertEquals(MetaAsrProtocol.Event.Other, MetaAsrProtocol.parse(text))
        }
    }

    @Test
    fun `audio goes in 80 ms frames of 16 kHz 16-bit mono`() {
        assertEquals(1280, MetaAsrProtocol.FRAME_SAMPLES)
        val bytes = MetaAsrProtocol.pcmBytes(shortArrayOf(1, -2), 0, 2)
        assertEquals(listOf<Byte>(1, 0, -2, -1), bytes.toList())
    }
}
