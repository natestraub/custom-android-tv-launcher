package dev.tvlauncher.muse.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides when someone has finished talking into the remote. The remote never reports the
 * button's release (docs/atv-remote-assist-protocol.md §8.7), so this listens for the pause
 * after speech, using loudness (RMS) over 30 ms frames of 16 kHz mono audio.
 *
 * The bar for speech adapts to the room: it's a multiple of the quietest frame heard before
 * anyone spoke, so steady TV sound doesn't count as talking. A few loud frames in a row
 * are needed, so the pop when the remote's audio starts doesn't count either.
 */
class Endpointer {
    enum class End { FINISHED, NO_SPEECH, TOO_LONG }

    /** Loudness of the latest frame, for a level meter. */
    var level = 0.0
        private set

    var heardSpeech = false
        private set

    private val frame = ShortArray(FRAME_SAMPLES)
    private var framed = 0
    private var frames = 0
    private var floor = Double.NaN
    private var loudRun = 0
    private var quietRun = 0
    private var speechStartFrame = -1
    private var lastLoudFrame = -1

    /** Feeds [count] samples of [samples] from [offset]; non-null once listening should stop. */
    fun feed(samples: ShortArray, offset: Int = 0, count: Int = samples.size): End? {
        var at = offset
        val until = offset + count
        while (at < until) {
            val n = min(FRAME_SAMPLES - framed, until - at)
            System.arraycopy(samples, at, frame, framed, n)
            framed += n
            at += n
            if (framed == FRAME_SAMPLES) {
                framed = 0
                onFrame(rms(frame))?.let { return it }
            }
        }
        return null
    }

    private fun onFrame(rms: Double): End? {
        frames++
        level = rms
        val threshold = if (floor.isNaN()) MIN_SPEECH_RMS else max(MIN_SPEECH_RMS, floor * FLOOR_RATIO)
        if (rms >= threshold) {
            loudRun++
            quietRun = 0
            if (loudRun >= SPEECH_FRAMES && !heardSpeech) {
                heardSpeech = true
                speechStartFrame = frames - SPEECH_FRAMES
            }
            if (heardSpeech) lastLoudFrame = frames - 1
        } else {
            loudRun = 0
            quietRun++
        }
        // Before speech, the quietest real frame (not the zeros before audio arrives) is the room.
        if (!heardSpeech && rms >= 1.0) floor = if (floor.isNaN()) rms else min(floor, rms)
        val elapsedMs = frames * FRAME_MS
        return when {
            heardSpeech && quietRun * FRAME_MS >= END_PAUSE_MS -> End.FINISHED
            !heardSpeech && elapsedMs >= NO_SPEECH_MS -> End.NO_SPEECH
            elapsedMs >= MAX_MS -> End.TOO_LONG
            else -> null
        }
    }

    /**
     * The samples worth sending out of a [totalSamples] recording: from just before speech
     * began to just after it last sounded, so the voice note carries no dead air. Null if
     * nobody spoke.
     */
    fun speechRange(totalSamples: Int): IntRange? {
        if (!heardSpeech || totalSamples <= 0) return null
        val first = maxOf(0, speechStartFrame * FRAME_SAMPLES - PRE_ROLL_SAMPLES)
        val last = minOf(totalSamples, (lastLoudFrame + 1) * FRAME_SAMPLES + TAIL_SAMPLES) - 1
        return first..last
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s * s.toDouble()
        return sqrt(sum / frame.size)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 30
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

        /** Speech on the TV measured RMS 300–7,000; a quiet room 10–40. */
        const val MIN_SPEECH_RMS = 300.0
        const val FLOOR_RATIO = 2.5
        const val SPEECH_FRAMES = 3
        const val END_PAUSE_MS = 900
        const val NO_SPEECH_MS = 8_000
        const val MAX_MS = 30_000

        /** Kept before speech began, so the first syllable isn't clipped. */
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE / 5

        /** Kept after the last loud frame, so trailing consonants aren't clipped. */
        const val TAIL_SAMPLES = SAMPLE_RATE / 4
    }
}
