package dev.tvlauncher.muse.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** The server answered the upgrade with [status] instead of 101; 401 and 403 mean the bearer was refused. */
class WebSocketUpgradeException(val status: Int) : IOException("WebSocket upgrade refused: HTTP $status")

/**
 * The connection is closed. [code] comes from the server's close frame, else
 * from the one this side sent, else it is 1006 (the connection was lost).
 */
class WebSocketClosedException(val code: Int, val reason: String) :
    IOException("WebSocket closed: $code" + if (reason.isEmpty()) "" else " $reason")

/**
 * Minimal RFC 6455 client for the Muse link (protocol spec §3.4.1). Android has
 * no public WebSocket API and the launcher takes no dependencies.
 *
 * [connect] returns an open connection. One thread loops on the blocking
 * [receive]; any thread may [sendBinary] or [sendText], and frames never
 * interleave. Behind that, a reader thread answers pings and buffers up to 16
 * messages (then stops reading, so TCP pushes back), and a keepalive thread
 * pings every [Options.pingIntervalMs], dropping the connection when a pong is
 * late. [close] runs the closing handshake. After a close or any failure,
 * [receive] hands out what was already buffered and then throws
 * [WebSocketClosedException]; sends throw it at once.
 */
class WebSocketClient private constructor(
    private val rawSocket: Socket,
    private val socket: Socket,
    input: InputStream,
    private val output: OutputStream,
    private val options: Options,
    private val peerName: String,
) : Closeable {

    /**
     * @param pingIntervalMs how often to ping the server; 0 turns keepalive off.
     * @param pongTimeoutMs how long a pong, or a write stalled by a dead network, may take.
     * @param maxMessageBytes the largest message accepted; bigger ones fail the connection
     *   with 1009. Muse messages are Noise frames, which never exceed 64 KiB.
     * @param closeTimeoutMs how long [close] waits for the server's close frame.
     * @param hostnameVerifier checks the certificate against the host after the TLS
     *   handshake. Android's default checks the SANs (the JVM's rejects everything).
     */
    class Options(
        val pingIntervalMs: Long = 20_000,
        val pongTimeoutMs: Long = 20_000,
        val maxMessageBytes: Int = 1 shl 20,
        val closeTimeoutMs: Long = 5_000,
        val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    ) {
        init {
            require(pingIntervalMs >= 0 && pongTimeoutMs > 0 && closeTimeoutMs > 0 && maxMessageBytes > 0)
        }
    }

    /** One complete data message. Muse only sends binary; text is surfaced so callers can reject it. */
    sealed class Message {
        class Binary(val data: ByteArray) : Message()

        class Text(val text: String) : Message()
    }

    private enum class State { OPEN, CLOSING, CLOSED }

    private class Closure(val code: Int, val reason: String)

    /** The server broke the protocol: fail the connection with [code]. */
    private class Violation(val code: Int, message: String) : Exception(message)

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private val input = DataInputStream(input)

    /** Keeps frames whole on the wire. Never taken while holding [lock]. */
    private val sendLock = ReentrantLock()

    /** Guards the fields below; [changed] is signalled whenever any of them changes. */
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val inbox = ArrayDeque<Message>()
    private var state = State.OPEN
    private var sentClose: Closure? = null
    private var closure: Closure? = null
    private var awaitedPong: ByteArray? = null

    val isOpen: Boolean get() = lock.withLock { state == State.OPEN }

    /** Sends one binary message. Thread-safe; waits while another frame is being written. */
    fun sendBinary(data: ByteArray) = sendData(OP_BINARY, data)

    /** Sends one text message. Thread-safe. */
    fun sendText(text: String) = sendData(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * Blocks until the next complete message. With [timeoutMs] > 0 it gives up
     * after that long with [SocketTimeoutException], leaving the connection open.
     * Once the connection is closed and drained, throws [WebSocketClosedException].
     */
    fun receive(timeoutMs: Long = 0): Message {
        lock.withLock {
            val arrived = await(if (timeoutMs > 0) timeoutMs else Long.MAX_VALUE) {
                inbox.isNotEmpty() || state == State.CLOSED
            }
            inbox.removeFirstOrNull()?.let { message ->
                changed.signalAll() // the reader may be waiting for room
                return message
            }
            if (!arrived) throw SocketTimeoutException("no WebSocket message in $timeoutMs ms")
            throw closedException()
        }
    }

    /** Closes with 1000 (normal closure). */
    override fun close() = close(NORMAL_CLOSURE)

    /**
     * Runs the closing handshake: sends a close frame, waits up to
     * [Options.closeTimeoutMs] for the server's, then closes the socket.
     * Idempotent and safe from any thread; wakes a blocked [receive].
     */
    fun close(code: Int, reason: String = "") {
        require(isValidCloseCode(code)) { "close code $code can't be sent" }
        require(reason.toByteArray(Charsets.UTF_8).size <= MAX_CLOSE_REASON) { "close reason too long" }
        val closure = Closure(code, reason)
        try {
            sendClose(closure, initiating = true)
            lock.withLock { await(options.closeTimeoutMs) { state == State.CLOSED } }
        } finally {
            shutDown(closure)
        }
    }

    private fun start() {
        // Keepalive decides liveness. This read timeout only backs it up, should a stalled write hold up the pinger.
        val backstopMs = if (options.pingIntervalMs > 0) options.pingIntervalMs + options.pongTimeoutMs else 0
        socket.soTimeout = backstopMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        thread(name = "WebSocket reader $peerName", isDaemon = true) { readLoop() }
        if (options.pingIntervalMs > 0) thread(name = "WebSocket keepalive $peerName", isDaemon = true) { keepAlive() }
    }

    // -- Sending --------------------------------------------------------------

    private fun sendData(opcode: Int, payload: ByteArray) {
        sendLock.lockInterruptibly()
        try {
            lock.withLock { if (state != State.OPEN) throw closedException() }
            writeFrame(opcode, payload)
        } catch (e: IOException) {
            if (e is WebSocketClosedException) throw e
            shutDown(Closure(ABNORMAL_CLOSURE, "send failed"))
            throw lock.withLock { closedException() }.apply { initCause(e) }
        } finally {
            sendLock.unlock()
        }
    }

    /** Writes a ping or pong while open. A write lock held past the pong timeout means the peer is gone. */
    private fun sendControl(opcode: Int, payload: ByteArray): Boolean {
        if (!sendLock.tryLock(options.pongTimeoutMs, MILLISECONDS)) {
            shutDown(Closure(ABNORMAL_CLOSURE, KEEPALIVE_TIMEOUT))
            return false
        }
        try {
            if (lock.withLock { state != State.OPEN }) return false
            writeFrame(opcode, payload)
            return true
        } catch (e: IOException) {
            shutDown(Closure(ABNORMAL_CLOSURE, "connection lost"))
            return false
        } finally {
            sendLock.unlock()
        }
    }

    /**
     * Moves OPEN → CLOSING and writes a close frame. False when a close was
     * already under way, or when the writer is stuck and the frame can't go out.
     */
    private fun sendClose(closure: Closure, initiating: Boolean): Boolean {
        if (!sendLock.tryLock(options.closeTimeoutMs, MILLISECONDS)) return false
        try {
            lock.withLock {
                if (state != State.OPEN) return false
                state = State.CLOSING
                if (initiating) sentClose = closure
                changed.signalAll()
            }
            val payload = if (closure.code == NO_STATUS) ByteArray(0) else {
                byteArrayOf((closure.code ushr 8).toByte(), closure.code.toByte()) + closure.reason.toByteArray(Charsets.UTF_8)
            }
            writeFrame(OP_CLOSE, payload)
            return true
        } catch (e: IOException) {
            shutDown(Closure(ABNORMAL_CLOSURE, "connection lost"))
            return false
        } finally {
            sendLock.unlock()
        }
    }

    /** One masked frame (RFC 6455 §5.3) in a single write. Caller holds [sendLock]. */
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val size = payload.size
        val lengthBytes = if (size < 126) 0 else if (size <= 0xFFFF) 2 else 8
        val frame = ByteArray(2 + lengthBytes + 4 + size)
        frame[0] = (FIN or opcode).toByte()
        frame[1] = (MASK or if (lengthBytes == 0) size else if (lengthBytes == 2) 126 else 127).toByte()
        for (i in 0 until lengthBytes) frame[2 + i] = (size.toLong() ushr (8 * (lengthBytes - 1 - i))).toByte()
        val key = ByteArray(4).also(random::nextBytes)
        val start = 2 + lengthBytes
        key.copyInto(frame, start)
        for (i in 0 until size) frame[start + 4 + i] = (payload[i].toInt() xor key[i and 3].toInt()).toByte()
        output.write(frame)
        output.flush()
    }

    // -- Receiving ------------------------------------------------------------

    private fun readLoop() {
        try {
            var partial: ByteArrayOutputStream? = null
            var partialOpcode = OP_BINARY
            while (true) {
                val frame = readFrame(partial?.size() ?: 0)
                when (frame.opcode) {
                    OP_PING -> sendControl(OP_PONG, frame.payload)
                    OP_PONG -> lock.withLock {
                        if (awaitedPong?.contentEquals(frame.payload) == true) {
                            awaitedPong = null
                            changed.signalAll()
                        }
                    }
                    OP_CLOSE -> {
                        val received = parseClose(frame.payload)
                        sendClose(received, initiating = false) // the echo, unless this side closed first
                        return shutDown(received)
                    }
                    OP_CONTINUATION -> {
                        val buffer = partial ?: throw Violation(PROTOCOL_ERROR, "continuation without a message")
                        buffer.write(frame.payload, 0, frame.payload.size)
                        if (frame.fin) {
                            partial = null
                            deliver(partialOpcode, buffer.toByteArray())
                        }
                    }
                    else -> {
                        if (partial != null) throw Violation(PROTOCOL_ERROR, "message interrupted by another")
                        if (frame.fin) {
                            deliver(frame.opcode, frame.payload)
                        } else {
                            partialOpcode = frame.opcode
                            partial = ByteArrayOutputStream().apply { write(frame.payload, 0, frame.payload.size) }
                        }
                    }
                }
            }
        } catch (v: Violation) {
            fail(Closure(v.code, v.message.orEmpty()))
        } catch (e: SocketTimeoutException) {
            shutDown(Closure(ABNORMAL_CLOSURE, KEEPALIVE_TIMEOUT)) // not even a pong in ping + pong time
        } catch (e: IOException) {
            shutDown(lock.withLock { sentClose } ?: Closure(ABNORMAL_CLOSURE, "connection lost"))
        } catch (e: InterruptedException) {
            shutDown(Closure(ABNORMAL_CLOSURE, "interrupted"))
        }
    }

    private fun readFrame(buffered: Int): Frame {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val fin = b0 and FIN != 0
        val opcode = b0 and 0x0F
        if (b0 and RSV_BITS != 0) throw Violation(PROTOCOL_ERROR, "reserved bits set")
        if (b1 and MASK != 0) throw Violation(PROTOCOL_ERROR, "masked server frame")
        if (opcode in 0x3..0x7 || opcode > OP_PONG) throw Violation(PROTOCOL_ERROR, "unknown opcode $opcode")
        val length = when (val n = b1 and 0x7F) {
            126 -> input.readUnsignedShort().toLong()
            127 -> input.readLong()
            else -> n.toLong()
        }
        if (length < 0) throw Violation(PROTOCOL_ERROR, "bad frame length")
        if (opcode >= OP_CLOSE) {
            if (!fin || length > 125) throw Violation(PROTOCOL_ERROR, "bad control frame")
        } else if (length > options.maxMessageBytes - buffered) {
            throw Violation(MESSAGE_TOO_BIG, "message over ${options.maxMessageBytes} bytes")
        }
        return Frame(fin, opcode, ByteArray(length.toInt()).also(input::readFully))
    }

    private fun parseClose(payload: ByteArray): Closure {
        if (payload.isEmpty()) return Closure(NO_STATUS, "")
        val code = if (payload.size < 2) 0 else ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (!isValidCloseCode(code)) throw Violation(PROTOCOL_ERROR, "bad close code")
        val reason = decodeUtf8(payload.copyOfRange(2, payload.size))
            ?: throw Violation(INVALID_PAYLOAD, "close reason is not UTF-8")
        return Closure(code, reason)
    }

    private fun deliver(opcode: Int, data: ByteArray) {
        val message = if (opcode == OP_TEXT) {
            Message.Text(decodeUtf8(data) ?: throw Violation(INVALID_PAYLOAD, "text is not UTF-8"))
        } else {
            Message.Binary(data)
        }
        lock.withLock {
            await(Long.MAX_VALUE) { inbox.size < QUEUE_CAPACITY || state != State.OPEN }
            if (state == State.OPEN) {
                inbox.addLast(message)
                changed.signalAll()
            }
        }
    }

    // -- Keepalive and shutdown ----------------------------------------------

    private fun keepAlive() {
        try {
            while (lock.withLock { !await(options.pingIntervalMs) { state != State.OPEN } }) {
                val payload = ByteArray(4).also(random::nextBytes)
                lock.withLock { awaitedPong = payload }
                if (!sendControl(OP_PING, payload)) return
                val answered = lock.withLock {
                    await(options.pongTimeoutMs) { awaitedPong == null || state != State.OPEN }
                }
                if (!answered) return shutDown(Closure(ABNORMAL_CLOSURE, KEEPALIVE_TIMEOUT))
            }
        } catch (e: InterruptedException) {
            shutDown(Closure(ABNORMAL_CLOSURE, "interrupted"))
        }
    }

    /**
     * Fails the connection (RFC 6455 §7.1.7): sends a close frame, then reads
     * and discards until the server hangs up, so it gets to read that frame
     * rather than a reset caused by unread data.
     */
    private fun fail(closure: Closure) {
        val sent = sendClose(closure, initiating = true)
        markClosed(closure)
        if (sent) {
            try {
                socket.soTimeout = options.closeTimeoutMs.toInt()
                val deadline = System.nanoTime() + MILLISECONDS.toNanos(options.closeTimeoutMs)
                val scratch = ByteArray(4096)
                var open = true
                while (open && System.nanoTime() < deadline) open = input.read(scratch) >= 0
            } catch (e: IOException) {
                // Gone either way.
            }
        }
        shutDown(closure)
    }

    /** Marks the connection closed (the first closure wins) and closes the socket, freeing stuck reads and writes. */
    private fun shutDown(closure: Closure) {
        markClosed(closure)
        rawSocket.closeQuietly() // TCP first: no TLS close_notify that could block on a dead peer
        socket.closeQuietly()
    }

    private fun markClosed(closure: Closure) = lock.withLock {
        if (state != State.CLOSED) {
            state = State.CLOSED
            this.closure = closure
            changed.signalAll()
        }
    }

    /** Caller holds [lock]. */
    private fun closedException(): WebSocketClosedException {
        val c = closure ?: sentClose ?: Closure(ABNORMAL_CLOSURE, "closing")
        return WebSocketClosedException(c.code, c.reason)
    }

    /** Waits, holding [lock], until [done] or [ms] pass; returns whether [done]. */
    private inline fun await(ms: Long, done: () -> Boolean): Boolean {
        var left = MILLISECONDS.toNanos(ms)
        while (!done()) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        return true
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
        const val PROTOCOL_ERROR = 1002
        const val NO_STATUS = 1005
        const val ABNORMAL_CLOSURE = 1006
        const val INVALID_PAYLOAD = 1007
        const val MESSAGE_TOO_BIG = 1009

        private const val QUEUE_CAPACITY = 16
        private const val MAX_CLOSE_REASON = 123
        private const val KEEPALIVE_TIMEOUT = "keepalive timeout"
        private const val FIN = 0x80
        private const val RSV_BITS = 0x70
        private const val MASK = 0x80
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private val random = SecureRandom()

        /**
         * Opens [url] (`wss://`, or `ws://` for tests) and performs the HTTP/1.1
         * upgrade with [headers] added, e.g. `Authorization` and `User-Agent`.
         * DNS aside, connecting, TLS and the upgrade share [connectTimeoutMs].
         *
         * @throws WebSocketUpgradeException when the server answers with a status other than 101
         * @throws java.net.ProtocolException when the 101 response isn't a valid WebSocket upgrade
         * @throws javax.net.ssl.SSLPeerUnverifiedException when the certificate doesn't match the host
         * @throws IOException on other connection failures and timeouts
         * @throws IllegalArgumentException for a malformed URL or header
         */
        fun connect(
            url: String,
            headers: Map<String, String> = emptyMap(),
            connectTimeoutMs: Int = 20_000,
            options: Options = Options(),
        ): WebSocketClient {
            val upgraded = WebSocketHandshake.open(url, headers, connectTimeoutMs, options)
            return WebSocketClient(upgraded.raw, upgraded.socket, upgraded.input, upgraded.output, options, upgraded.peer)
                .also { it.start() }
        }

        /** Codes an endpoint may put on the wire: RFC 6455 §7.4 plus the IANA-registered 1012–1014. */
        private fun isValidCloseCode(code: Int) = code in 1000..1003 || code in 1007..1014 || code in 3000..4999

        /** Strict UTF-8, as RFC 6455 requires for text, or null. */
        private fun decodeUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
