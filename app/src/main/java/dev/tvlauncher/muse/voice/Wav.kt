package dev.tvlauncher.muse.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM → a WAV file, the voice-note format Muse takes. */
object Wav {
    fun encode(samples: ShortArray, count: Int, sampleRate: Int = Endpointer.SAMPLE_RATE): ByteArray {
        val dataBytes = count * 2
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16)
            .putShort(1) // PCM
            .putShort(1) // mono
            .putInt(sampleRate)
            .putInt(sampleRate * 2) // bytes per second
            .putShort(2) // bytes per sample frame
            .putShort(16) // bits per sample
        out.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until count) out.putShort(samples[i])
        return out.array()
    }

    private const val HEADER_BYTES = 44
}
