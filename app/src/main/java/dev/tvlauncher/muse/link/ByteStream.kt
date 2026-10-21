package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.Reset
import dev.tvlauncher.muse.wire.ServiceFrame
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A response body read while it's still arriving, such as Muse's spoken answer (MP3, spec
 * §3.4.9). Bytes are kept, so a player can read any position it has already been sent;
 * [readAt] waits for bytes that haven't arrived yet. Opened by [LinkSession.openByteStream].
 */
class ByteStream internal constructor(
    val streamId: Long,
    private val maxBytes: Int,
    private val onClose: (ByteStream) -> Unit,
) {
    private val lock = Object()
    private var data = ByteArray(64 * 1024)
    private var size = 0
    private var status = 0
    private var headers = false
    private var ended = false
    private var error: IOException? = null
    private var closed = false
    private var vmEnded = false

    /** The VM finished or reset the stream, so closing needs no reset of our own. */
    internal val finishedByVm: Boolean get() = synchronized(lock) { ended || vmEnded }

    /** The whole length once the body has ended; null while it's still arriving. */
    val length: Long? get() = synchronized(lock) { if (ended) size.toLong() else null }

    /** The HTTP status, once the response starts. */
    fun awaitStatus(timeoutMs: Long): Int = synchronized(lock) {
        waitFor(timeoutMs) { headers }
        status
    }

    /** Up to [count] bytes at [position], waiting for them if needed; -1 past the end. */
    fun readAt(position: Long, into: ByteArray, offset: Int, count: Int, timeoutMs: Long): Int = synchronized(lock) {
        waitFor(timeoutMs) { size > position || ended }
        if (position >= size) return -1
        val n = minOf(count.toLong(), size - position).toInt()
        System.arraycopy(data, position.toInt(), into, offset, n)
        n
    }

    /** Stops the download; the session resets the stream unless the VM already ended it. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (error == null && !ended) error = IOException("closed")
            lock.notifyAll()
        }
        onClose(this)
    }

    /** Reader thread. Returns false when the stream failed and should be reset. */
    internal fun onFrame(kind: ServiceFrame.Kind?): Boolean = synchronized(lock) {
        if (closed || ended || error != null) return true
        val (bytes, end) = when (kind) {
            is Reset -> {
                vmEnded = true
                fail(IOException("Muse response stream reset"))
                return true
            }
            is ApplicationResponse -> {
                status = kind.status
                headers = true
                kind.body to kind.endBody
            }
            is BodyChunk -> kind.data to kind.endBody
            else -> return true
        }
        if (size + bytes.size > maxBytes) {
            fail(IOException("response over $maxBytes bytes"))
            return false
        }
        if (size + bytes.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + bytes.size).coerceAtMost(maxBytes))
        System.arraycopy(bytes, 0, data, size, bytes.size)
        size += bytes.size
        if (end) ended = true
        lock.notifyAll()
        true
    }

    /** The stream or the whole session failed. */
    internal fun fail(e: IOException) = synchronized(lock) {
        if (error == null && !ended) error = e
        lock.notifyAll()
    }

    private inline fun waitFor(timeoutMs: Long, ready: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            error?.let { throw IOException(it.message, it) }
            if (ready()) return
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("no Muse audio in $timeoutMs ms")
            lock.wait(left)
        }
    }
}
