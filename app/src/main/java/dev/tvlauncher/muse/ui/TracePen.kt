package dev.tvlauncher.muse.ui

/**
 * Where the pen is while the Muse "M" draws itself as someone talks: the visible stroke runs
 * from [start] to [end] (fractions of the path). The pen moves with the voice (louder is
 * faster, silence nearly stops it), draws the whole M, erases it in the same direction, and
 * starts over.
 */
class TracePen {
    var start = 0.0
        private set
    var end = 0.0
        private set
    private var erasing = false

    /** Moves the pen by [dtSeconds] at a speed set by [level] (frame RMS of the voice). */
    fun advance(dtSeconds: Double, level: Double) {
        val loudness = (level / SPEECH_RMS).coerceIn(0.0, 1.0)
        val step = dtSeconds / FULL_SPEED_PASS_S * (IDLE_SPEED + (1 - IDLE_SPEED) * loudness)
        if (!erasing) {
            end = minOf(1.0, end + step)
            if (end == 1.0) erasing = true
        } else {
            start = minOf(1.0, start + step)
            if (start == 1.0) {
                // The leftover step begins the next pass.
                start = 0.0
                end = 0.0
                erasing = false
            }
        }
    }

    companion object {
        /** Seconds to draw (or erase) the whole M while someone is talking at full voice. */
        const val FULL_SPEED_PASS_S = 1.4

        /** Speech on the TV measured RMS 300–7,000; from here up the pen is at full speed. */
        const val SPEECH_RMS = 2500.0

        /** Fraction of full speed when it's quiet. */
        const val IDLE_SPEED = 0.08
    }
}
