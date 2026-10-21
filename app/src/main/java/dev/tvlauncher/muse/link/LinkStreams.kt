package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.NdjsonDecoder
import dev.tvlauncher.muse.wire.Reset
import dev.tvlauncher.muse.wire.ServiceFrame
import dev.tvlauncher.muse.wire.WireFormatException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Muse refused an HTTP request carried by the Noise session. The body is never included. */
class HttpStreamError(val status: Int, path: String) : IOException("Muse request $path failed: HTTP $status")

/** Collects the response to one request stream (the SDK's `_Request`). Frames arrive on the reader thread. */
internal class PendingRequest(private val maxBytes: Int = LinkSession.MAX_RESPONSE_BYTES) {
    private val done = CountDownLatch(1)
    private val body = ByteArrayOutputStream()
    private var status = 0
    private var error: Exception? = null

    fun onFrame(kind: ServiceFrame.Kind?) {
        synchronized(this) {
            if (done.count == 0L) return
            val (data, end) = when (kind) {
                is Reset -> return finishLocked(IOException("stream reset: ${printable(kind.reason)}"))
                is ApplicationResponse -> {
                    status = kind.status
                    kind.body to kind.endBody
                }
                is BodyChunk -> kind.data to kind.endBody
                else -> return
            }
            body.write(data)
            if (body.size() > maxBytes) finishLocked(IOException("response too large")) else if (end) finishLocked(null)
        }
    }

    fun fail(error: Exception) {
        synchronized(this) { if (done.count > 0L) finishLocked(error) }
    }

    /** The status and body once the response ends; throws on failure or after [timeoutMs]. */
    fun await(timeoutMs: Long): Pair<Int, ByteArray> {
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw SocketTimeoutException("no Muse response in $timeoutMs ms")
        synchronized(this) {
            error?.let { throw it }
            return status to body.toByteArray()
        }
    }

    private fun finishLocked(error: Exception?) {
        this.error = error
        done.countDown()
    }
}

/**
 * A `/chat/subscribe` stream (spec §3.4.7): newline-delimited JSON, of which only
 * `"type":"event"` objects reach the [Listener]. Opened by [LinkSession.subscribeChat].
 */
class ChatSubscription internal constructor(
    val streamId: Long,
    private val listener: Listener,
    private val onClose: (ChatSubscription) -> Unit,
) {
    interface Listener {
        /** One chat event, on the session's reader thread: hand it off, don't block. */
        fun onEvent(event: JSONObject)

        /** The stream ended: by the VM ([error] null) or a failure. Never called after [close]. */
        fun onEnd(error: Exception?)
    }

    private val ready = CountDownLatch(1)
    private val ndjson = NdjsonDecoder()
    private var status = 0
    private var readyError: Exception? = null

    /** The start of a refused response's body, for diagnostics only (never put in exceptions). */
    @Volatile internal var errorBody = ""
        private set

    @Volatile private var finished = false

    /** Still delivering events: not closed, failed or ended. */
    val isOpen: Boolean get() = !finished

    /** The VM finished or reset the stream, so closing it needs no reset of our own. */
    @Volatile internal var ended = false
        private set

    /** Ends the subscription: no more callbacks, and the VM is told to stop unless it already ended it. */
    fun close() {
        finished = true
        ready.countDown()
        onClose(this)
    }

    internal fun awaitReady(timeoutMs: Long): Int {
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) throw SocketTimeoutException("no response to /chat/subscribe")
        synchronized(this) {
            readyError?.let { throw it }
            return status
        }
    }

    /** Reader thread. Returns false when the stream failed and should be reset. */
    internal fun onFrame(kind: ServiceFrame.Kind?): Boolean {
        if (finished) return true
        val (data, end) = when (kind) {
            is Reset -> {
                ended = true
                fail(IOException("Muse response stream reset"))
                return true
            }
            is ApplicationResponse -> {
                synchronized(this) { status = kind.status }
                if (kind.status !in 200..299) errorBody = printable(String(kind.body.copyOf(minOf(kind.body.size, 160))))
                ready.countDown()
                kind.body to kind.endBody
            }
            is BodyChunk -> {
                if (ready.count > 0L) {
                    fail(IOException("Muse response body arrived without headers"))
                    return false
                }
                kind.data to kind.endBody
            }
            else -> return true
        }
        try {
            if (data.isNotEmpty()) ndjson.feed(data, ::deliver)
            if (end) {
                ndjson.finish(::deliver)
                ended = true
                if (!finished) {
                    finished = true
                    safely { listener.onEnd(null) }
                }
            }
        } catch (e: WireFormatException) {
            fail(e) // an event over 256 KiB: this stream only
            return false
        }
        return true
    }

    /** The stream or the whole session failed. */
    internal fun fail(error: Exception) {
        if (finished) return
        finished = true
        synchronized(this) { if (ready.count > 0L) readyError = error }
        if (ready.count > 0L) ready.countDown() else safely { listener.onEnd(error) }
    }

    private fun deliver(event: JSONObject) {
        if (!finished && event.opt("type") == "event") safely { listener.onEvent(event) }
    }

    /** A listener's bug mustn't take the reader thread, and with it the whole session, down. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Dropped: the subscription carries on.
        }
    }
}
