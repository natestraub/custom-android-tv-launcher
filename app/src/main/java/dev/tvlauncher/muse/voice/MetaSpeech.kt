package dev.tvlauncher.muse.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tvlauncher.muse.net.WebSocketClient
import java.util.UUID

/**
 * The remote's speech as text, live, from Meta's realtime speech-to-text ([MetaAsrProtocol]).
 * We record the remote ourselves (the remote service pipes its mic into our VOICE_RECOGNITION
 * recording, docs/atv-remote-assist-protocol.md §8) and stream it while you talk; Meta
 * decides when you've finished. If Meta can't be reached, what was recorded goes out as a
 * voice note instead ([Listener.onFallback]), so nothing is asked twice.
 * Callbacks run on the main thread. One use per instance.
 */
class MetaSpeech(private val apiKey: String, private val listener: Listener) {
    interface Listener {
        fun onLevel(level: Double)

        /** The words so far. */
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onHeardNothing()

        /** Meta couldn't transcribe it: the recorded question as a WAV, or null if the mic failed. */
        fun onFallback(wav: ByteArray?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val started = SystemClock.elapsedRealtime()
    private val endpointer = Endpointer()
    private val audio = ShortArray(Endpointer.SAMPLE_RATE * Endpointer.MAX_MS / 1000)
    private var recorded = 0

    // All guarded by [lock].
    private var socket: WebSocketClient? = null
    private var ready = false
    private val backlog = ArrayList<ByteArray>()
    private var recordingDone = false
    private var endRequested = false
    private var endSentAt = 0L
    private var socketFailed = false
    private var lastPartial = ""
    private var done = false
    @Volatile private var stopRecording = false
    @Volatile private var cancelled = false

    fun start() {
        Thread({ connect() }, "meta-asr-socket").start()
        Thread({ record() }, "meta-asr-mic").start()
    }

    /** Stop listening now and use what was said (the mic button pressed again). */
    fun finish() {
        stopRecording = true
    }

    /** Stop and drop it (Back). */
    fun cancel() {
        cancelled = true
        stopRecording = true
        synchronized(lock) { done = true }
        closeSocket()
    }

    @SuppressLint("MissingPermission") // granted over adb with the Assistant role (scripts/assistant-role.sh)
    private fun record() {
        val rate = Endpointer.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 2))
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) error("state ${it.state}") }
        } catch (e: Exception) {
            Log.w(TAG, "no microphone: $e")
            closeSocket()
            return deliver { listener.onFallback(null) }
        }
        var end: Endpointer.End? = null
        try {
            recorder.startRecording()
            while (!stopRecording && end == null && recorded < audio.size) {
                val n = recorder.read(audio, recorded, minOf(MetaAsrProtocol.FRAME_SAMPLES, audio.size - recorded))
                if (n < 0) error("read $n")
                end = endpointer.feed(audio, recorded, n)
                send(MetaAsrProtocol.pcmBytes(audio, recorded, n))
                recorded += n
                val level = endpointer.level
                main.post { if (!cancelled) listener.onLevel(level) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording failed: $e")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        log("recorded ${recorded * 1000L / rate} ms, end=${end ?: "stopped"}, speech=${endpointer.heardSpeech}")
        if (cancelled) return
        if (!endpointer.heardSpeech) {
            closeSocket()
            return deliver { listener.onHeardNothing() }
        }
        val fallBack = synchronized(lock) {
            recordingDone = true
            if (!socketFailed) {
                endRequested = true
                if (ready) sendEndLocked()
            }
            socketFailed
        }
        if (fallBack) fallBack()
    }

    private fun send(frame: ByteArray) {
        synchronized(lock) {
            if (socketFailed || done) return
            if (!ready) return run { backlog += frame }
            try {
                socket?.sendBinary(frame)
            } catch (e: Exception) {
                failLocked("send: $e")
            }
        }
    }

    private fun connect() {
        val session = "tv-${UUID.randomUUID()}"
        try {
            val ws = WebSocketClient.connect(MetaAsrProtocol.url(session), connectTimeoutMs = CONNECT_TIMEOUT_MS)
            synchronized(lock) {
                if (done) return ws.close()
                socket = ws
            }
            ws.sendText(MetaAsrProtocol.handshake(apiKey)) // the key travels only here, never logged
            log("connected")
            while (true) {
                val message = try {
                    ws.receive(POLL_MS)
                } catch (e: java.net.SocketTimeoutException) {
                    null
                }
                if (message is WebSocketClient.Message.Text) onEvent(MetaAsrProtocol.parse(message.text))
                if (checkTimeout()) return
                synchronized(lock) { if (done) return }
            }
        } catch (e: Exception) {
            val fallBack = synchronized(lock) {
                if (done) return
                failLocked("socket: ${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}")
                recordingDone
            }
            if (fallBack) fallBack()
        }
    }

    private fun onEvent(event: MetaAsrProtocol.Event) {
        when (event) {
            MetaAsrProtocol.Event.Ready -> synchronized(lock) {
                log("ready, sending ${backlog.size} buffered frames")
                ready = true
                try {
                    backlog.forEach { socket?.sendBinary(it) }
                } catch (e: Exception) {
                    failLocked("backlog: $e")
                }
                backlog.clear()
                if (endRequested) sendEndLocked()
            }
            is MetaAsrProtocol.Event.Words -> if (event.final) {
                log("final words")
                stopRecording = true
                closeSocket()
                deliver { listener.onFinal(event.text) }
            } else {
                synchronized(lock) { lastPartial = event.text }
                main.post { if (!cancelled) listener.onPartial(event.text) }
            }
            is MetaAsrProtocol.Event.Failed -> {
                val fallBack = synchronized(lock) {
                    failLocked("error event: ${event.message.take(80)}")
                    recordingDone
                }
                if (fallBack) fallBack()
            }
            MetaAsrProtocol.Event.Other -> Unit
        }
    }

    /** No final words long after the end was sent: use the last partial, or fall back. True when finished. */
    private fun checkTimeout(): Boolean {
        val partial = synchronized(lock) {
            if (endSentAt == 0L || SystemClock.elapsedRealtime() - endSentAt < FINAL_TIMEOUT_MS) return false
            lastPartial
        }
        log("no final words; using ${if (partial.isBlank()) "the voice note" else "the last partial"}")
        closeSocket()
        if (partial.isBlank()) fallBack() else deliver { listener.onFinal(partial) }
        return true
    }

    private fun sendEndLocked() {
        try {
            socket?.sendText(MetaAsrProtocol.END_STREAM)
            endSentAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            failLocked("endStream: $e")
        }
    }

    private fun failLocked(why: String) {
        if (socketFailed) return
        socketFailed = true
        Log.w(TAG, "Meta speech-to-text failed ($why); falling back to a voice note")
    }

    /** The recording so far, trimmed to the speech, as a voice note. */
    private fun fallBack() {
        closeSocket()
        val range = endpointer.speechRange(recorded) ?: 0 until recorded
        val wav = if (recorded == 0) null else Wav.encode(audio.copyOfRange(range.first, range.last + 1), range.last - range.first + 1)
        deliver { listener.onFallback(wav) }
    }

    private fun closeSocket() {
        val ws = synchronized(lock) { socket.also { socket = null } } ?: return
        Thread({ runCatching { ws.close() } }, "meta-asr-close").start()
    }

    private fun deliver(callback: () -> Unit) {
        synchronized(lock) {
            if (done) return
            done = true
        }
        main.post { if (!cancelled) callback() }
    }

    // Timings and states only; never the key, the audio or the words.
    private fun log(event: String) = Log.i(TAG, "Meta ASR: $event +${SystemClock.elapsedRealtime() - started} ms")

    private companion object {
        const val TAG = "MuseVoice"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val POLL_MS = 250L
        const val FINAL_TIMEOUT_MS = 4_000L
    }
}
