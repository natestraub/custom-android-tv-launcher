package dev.tvlauncher.muse.voice

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Meta's realtime speech-to-text (Muse Voice Transcribe, dev.meta.ai/docs/speech-to-text):
 * a WebSocket that takes a JSON handshake with the API key, then raw 16-bit mono PCM in
 * binary frames, and answers with JSON events. Endpointing mode: Meta decides when the
 * speaker has finished and sends the whole question as `speechComplete`.
 */
object MetaAsrProtocol {
    /** 80 ms of 16 kHz audio, the frame size Meta's examples stream at. */
    const val FRAME_SAMPLES = Endpointer.SAMPLE_RATE * 80 / 1000

    sealed class Event {
        /** The handshake was accepted; audio may flow. */
        object Ready : Event()

        /** The words so far ([final] = false, each replacing the last), or the finished question. */
        data class Words(val text: String, val final: Boolean) : Event()

        data class Failed(val message: String) : Event()

        object Other : Event()
    }

    fun url(sessionId: String) = "wss://api.meta.ai/v1/asr/realtime?sessionId=$sessionId"

    fun handshake(apiKey: String): String = JSONObject()
        .put("authorization", JSONObject().put("accessToken", "Bearer $apiKey"))
        .put("audioEncoding", "PCM_16KHZ")
        .put("model", "muse-voice-transcribe-1.0")
        .put("mode", "ENDPOINTING")
        .put("partialMode", "CUMULATIVE")
        .put("emitAudioProgress", false)
        .put("languageBias", JSONArray().put("English"))
        .toString()

    const val END_STREAM = """{"type":"endStream"}"""

    fun parse(text: String): Event {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Event.Other
        }
        return when (json.optString("type")) {
            "" -> if (json.has("sessionId")) Event.Ready else Event.Other
            "transcript" -> Event.Words(json.optString("transcript"), json.optBoolean("final"))
            "speechComplete" -> Event.Words(json.optString("transcript"), final = true)
            "error" -> Event.Failed(json.optString("message", "error"))
            else -> Event.Other
        }
    }

    /** [count] samples from [offset] as little-endian bytes. */
    fun pcmBytes(samples: ShortArray, offset: Int, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[offset + i].toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }
}
