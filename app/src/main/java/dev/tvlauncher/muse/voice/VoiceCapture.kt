package dev.tvlauncher.muse.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Records one spoken question from the remote. After a mic press, Google's remote service
 * pipes the remote's microphone into any VOICE_RECOGNITION recording that starts within 15 s
 * (docs/atv-remote-assist-protocol.md §8), so this is an ordinary AudioRecord. It stops on
 * its own at the pause after speech ([Endpointer]), or when told to.
 * Callbacks run on the main thread. One use per instance.
 */
class VoiceCapture(private val listener: Listener) {
    interface Listener {
        /** Loudness of the latest 30 ms, for animation. */
        fun onLevel(level: Double)

        /** A finished question as a WAV voice note. */
        fun onSpoken(wav: ByteArray)

        /** Nothing was said before the time limit, or the mic couldn't be opened. */
        fun onNothing(micFailed: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false

    @SuppressLint("MissingPermission") // granted over adb with the Assistant role (scripts/assistant-role.sh)
    fun start() {
        Thread({ record() }, "muse-voice").start()
    }

    /** Stop now and send what was heard (the mic button pressed again). */
    fun finish() {
        stopRequested = true
    }

    /** Stop now and drop it (Back). */
    fun cancel() {
        cancelled = true
        stopRequested = true
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val rate = Endpointer.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 2))
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("state ${it.state}") }
        } catch (e: Exception) {
            Log.w(TAG, "no microphone: $e")
            return post { listener.onNothing(micFailed = true) }
        }
        val endpointer = Endpointer()
        val audio = ShortArray(rate * Endpointer.MAX_MS / 1000)
        var recorded = 0
        var end: Endpointer.End? = null
        try {
            recorder.startRecording()
            val chunk = rate / 20 // 50 ms reads keep the animation responsive
            while (!stopRequested && end == null && recorded < audio.size) {
                val n = recorder.read(audio, recorded, minOf(chunk, audio.size - recorded))
                if (n < 0) throw IllegalStateException("read $n")
                end = endpointer.feed(audio, recorded, n)
                recorded += n
                val level = endpointer.level
                post { listener.onLevel(level) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording failed: $e")
            end = null
            cancelled = cancelled || recorded == 0
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        // Sizes and reasons only; never the audio.
        Log.i(TAG, "recorded ${recorded * 1000L / rate} ms, end=${end ?: if (cancelled) "cancelled" else "stopped"}, speech=${endpointer.heardSpeech}")
        when {
            cancelled -> Unit
            end == Endpointer.End.NO_SPEECH || !endpointer.heardSpeech -> post { listener.onNothing(micFailed = false) }
            else -> {
                // Only the speech (plus a little before and after) goes up: less to upload.
                val range = endpointer.speechRange(recorded) ?: 0 until recorded
                val wav = Wav.encode(audio.copyOfRange(range.first, range.last + 1), range.last - range.first + 1)
                Log.i(TAG, "sending ${(range.last - range.first + 1) * 1000L / rate} ms of ${recorded * 1000L / rate} ms")
                post { listener.onSpoken(wav) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!cancelled) block() }
    }

    private companion object {
        const val TAG = "MuseVoice"
    }
}
