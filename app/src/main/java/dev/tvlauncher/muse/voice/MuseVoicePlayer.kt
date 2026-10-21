package dev.tvlauncher.muse.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tvlauncher.muse.link.ByteStream
import dev.tvlauncher.muse.link.HttpStreamError
import dev.tvlauncher.muse.link.LinkSession
import dev.tvlauncher.muse.link.MuseRuntime
import dev.tvlauncher.muse.link.encodeUriComponent
import java.util.concurrent.Executors

/**
 * Speaks Muse's answer in Muse's own voice: each reply message's MP3 (docs/muse-protocol.md
 * §3.4.9; the question must be sent with output_modality "voice") plays while it downloads.
 * Replies play in order. If a reply's audio isn't ready when it first appears, it's tried
 * again once its text is done. Main thread only; one per question.
 */
class MuseVoicePlayer {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "muse-tts") }
    private val created = SystemClock.elapsedRealtime()
    private val seen = HashSet<String>()
    private val done = HashSet<String>()
    private val retryWhenDone = HashSet<String>()
    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    private var stream: ByteStream? = null
    private var busy = false
    private var stopped = false

    /** A reply message appeared or changed; [textDone] once Muse finished writing it. */
    fun onReply(messageId: String, textDone: Boolean) {
        if (stopped) return
        if (textDone && done.add(messageId)) {
            log("reply text done")
            if (retryWhenDone.remove(messageId)) queue.addLast(messageId)
        }
        if (seen.add(messageId)) queue.addLast(messageId)
        playNext()
    }

    fun stop() {
        stopped = true
        queue.clear()
        release()
        io.shutdownNow()
    }

    private fun playNext() {
        if (busy || stopped) return
        val id = queue.removeFirstOrNull() ?: return
        busy = true
        io.execute {
            val opened = runCatching { open(id) }
            main.post {
                if (stopped) return@post opened.getOrNull()?.close() ?: Unit
                opened.fold({ play(it) }, { failed(id, it) })
            }
        }
    }

    /** Blocks: Muse's audio for [id], from the documented path or its fallback on 404. */
    private fun open(id: String): ByteStream {
        val session = MuseRuntime.session ?: throw IllegalStateException("not connected")
        val query = "?message_id=${encodeUriComponent(id)}"
        log("requesting audio")
        val first = request(session, PATH + query)
        if (first.second != 404) return accept(first)
        first.first.close()
        return accept(request(session, FALLBACK_PATH + query))
    }

    private fun request(session: LinkSession, path: String): Pair<ByteStream, Int> {
        val stream = session.openByteStream(path, "audio/mpeg")
        return try {
            stream to stream.awaitStatus(LinkSession.REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    private fun accept(response: Pair<ByteStream, Int>): ByteStream {
        val (stream, status) = response
        if (status !in 200..299) {
            stream.close()
            throw HttpStreamError(status, PATH)
        }
        log("audio response HTTP $status")
        return stream
    }

    private fun play(audio: ByteStream) {
        stream = audio
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setDataSource(StreamSource(audio))
            setOnPreparedListener {
                log("first audio")
                it.start()
            }
            setOnCompletionListener { finished() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "playback error $what/$extra")
                finished()
                true
            }
            prepareAsync()
        }
    }

    private fun failed(id: String, error: Throwable) {
        // Statuses only; never bodies or tokens.
        Log.w(TAG, "no audio yet: ${error.javaClass.simpleName} ${(error as? HttpStreamError)?.status ?: ""}".trimEnd())
        if (id !in done) retryWhenDone += id
        busy = false
        playNext()
    }

    private fun finished() {
        log("audio finished")
        release()
        busy = false
        playNext()
    }

    private fun release() {
        player?.release()
        player = null
        stream?.close()
        stream = null
    }

    private fun log(event: String) = Log.i(TAG, "$event +${SystemClock.elapsedRealtime() - created} ms")

    /** MediaPlayer reads the download as it grows; size unknown until it ends. */
    private class StreamSource(private val stream: ByteStream) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (size == 0) 0 else stream.readAt(position, buffer, offset, size, READ_TIMEOUT_MS)

        override fun getSize(): Long = stream.length ?: -1

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "MuseVoice"
        const val PATH = "/api/voice/tts-stream"
        const val FALLBACK_PATH = "/voice/tts-stream"
        const val READ_TIMEOUT_MS = 30_000L
    }
}
