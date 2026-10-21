package dev.tvlauncher.muse.net

import dev.tvlauncher.muse.net.WebSocketClient.Message
import dev.tvlauncher.muse.net.WebSocketClient.Options
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS

class WebSocketClientTest {
    private val server = FakeWebSocketServer()
    private val clients = mutableListOf<WebSocketClient>()
    private val background = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        server.close()
        clients.forEach { it.close() }
        background.shutdownNow()
    }

    private fun connect(
        target: String = "/",
        headers: Map<String, String> = emptyMap(),
        options: Options = Options(),
    ): Pair<WebSocketClient, Peer> {
        val accepted = server.accept()
        val ws = WebSocketClient.connect(server.url(target), headers, 5_000, options).also(clients::add)
        return ws to accepted.get(5, SECONDS)
    }

    private fun <T> async(block: () -> T): Future<T> = background.submit(Callable(block))

    private fun WebSocketClient.binary() = (receive(5_000) as Message.Binary).data

    private fun WebSocketClient.text() = (receive(5_000) as Message.Text).text

    private fun WebSocketClient.closure() = assertThrows(WebSocketClosedException::class.java) { receive(5_000) }

    private fun violation(code: Int, act: (Peer) -> Unit) = code to act

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            assertTrue("condition not met in 2 s", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun `upgrade request carries the raw target, handshake fields and caller headers`() {
        val target = "/v1/noise?vm_id=a-b_c.d!~*'()%3F%26%3D"
        val (ws, peer) = connect(target, mapOf("Authorization" to "Bearer tok", "User-Agent" to "musegadget/0.1.0 (test)"))
        assertEquals("GET $target HTTP/1.1", peer.requestLine)
        assertEquals("127.0.0.1:${server.port}", peer.headers["host"])
        assertEquals("websocket", peer.headers["upgrade"])
        assertEquals("Upgrade", peer.headers["connection"])
        assertEquals("13", peer.headers["sec-websocket-version"])
        assertEquals(16, Base64.getDecoder().decode(peer.headers["sec-websocket-key"]).size)
        assertEquals("Bearer tok", peer.headers["authorization"])
        assertEquals("musegadget/0.1.0 (test)", peer.headers["user-agent"])
        assertTrue(ws.isOpen)
    }

    @Test
    fun `a refused upgrade reports the HTTP status without leaking headers`() {
        for (status in listOf(401, 403, 500)) {
            server.accept { "HTTP/1.1 $status Nope\r\nContent-Length: 0\r\n\r\n" }
            val refused = assertThrows(WebSocketUpgradeException::class.java) {
                WebSocketClient.connect(server.url(), mapOf("Authorization" to "Bearer secret-token"))
            }
            assertEquals(status, refused.status)
            assertFalse(refused.message!!.contains("secret-token"))
        }
    }

    @Test
    fun `a 101 that is not a valid upgrade is refused`() {
        val replies = listOf<(Peer) -> String>(
            { "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: AAAA\r\n\r\n" },
            { it.switchingProtocols().replace("Upgrade: websocket\r\n", "") },
            { it.switchingProtocols().replace("Connection: Upgrade\r\n", "") },
            { it.switchingProtocols().replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n") },
        )
        for (reply in replies) {
            server.accept(reply)
            assertThrows(ProtocolException::class.java) { WebSocketClient.connect(server.url()) }
        }
    }

    @Test
    fun `headers cannot smuggle extra lines or override the handshake`() {
        val smuggled = assertThrows(IllegalArgumentException::class.java) {
            WebSocketClient.connect(server.url(), mapOf("Authorization" to "Bearer x\r\nX-Evil: 1"))
        }
        assertFalse(smuggled.message!!.contains("Bearer"))
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketClient.connect(server.url(), mapOf("Sec-WebSocket-Key" to "x"))
        }
    }

    @Test
    fun `connect gives up when the upgrade never completes`() {
        server.accept { Thread.sleep(10_000); "" }
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) { WebSocketClient.connect(server.url(), connectTimeoutMs = 300) }
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
    }

    @Test
    fun `frames sent right behind the 101 are not lost`() {
        server.accept { it.switchingProtocols() + "\u0082\u0002\u0007\u0008" }
        val ws = WebSocketClient.connect(server.url()).also(clients::add)
        assertArrayEquals(byteArrayOf(7, 8), ws.binary())
    }

    @Test
    fun `client frames are masked and messages round trip`() {
        val (ws, peer) = connect()
        ws.sendBinary(byteArrayOf(1, 2, 3))
        peer.readFrame().let {
            assertTrue(it.fin && it.masked)
            assertEquals(2, it.opcode)
            assertArrayEquals(byteArrayOf(1, 2, 3), it.payload)
        }
        ws.sendText("héllo ✓")
        peer.readFrame().let {
            assertTrue(it.fin && it.masked)
            assertEquals(1, it.opcode)
            assertEquals("héllo ✓", it.text)
        }
        ws.sendBinary(ByteArray(0))
        assertEquals(0, peer.readFrame().payload.size)
        peer.sendFrame(2, byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(9, 8, 7), ws.binary())
        peer.sendFrame(1, "wörld".toByteArray())
        assertEquals("wörld", ws.text())
    }

    @Test
    fun `7-bit, 16-bit and 64-bit lengths both ways`() {
        val (ws, peer) = connect()
        for ((size, field) in listOf(125 to 125, 126 to 126, 65_535 to 126, 65_536 to 127, 200_000 to 127)) {
            val data = ByteArray(size) { (it * 31).toByte() }
            ws.sendBinary(data)
            val frame = peer.readFrame()
            assertEquals(field, frame.lengthField)
            assertArrayEquals(data, frame.payload)
            peer.sendFrame(2, data)
            assertArrayEquals(data, ws.binary())
        }
    }

    @Test
    fun `fragments are reassembled around an interleaved ping`() {
        val (ws, peer) = connect()
        val o = "ö".toByteArray() // two bytes, split across fragments below
        peer.sendFrame(1, "Hel".toByteArray(), fin = false)
        peer.sendFrame(9, "mid".toByteArray())
        peer.sendFrame(0, "lo, w".toByteArray() + o[0], fin = false)
        peer.sendFrame(0, byteArrayOf(o[1]) + "rld".toByteArray())
        assertEquals("Hello, wörld", ws.text())
        peer.readFrame().let {
            assertEquals(10, it.opcode)
            assertEquals("mid", it.text)
        }
        peer.sendFrame(2, byteArrayOf(1), fin = false)
        peer.sendFrame(0, byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), ws.binary())
    }

    @Test
    fun `server pings get matching masked pongs and stray pongs are ignored`() {
        val (ws, peer) = connect()
        peer.sendFrame(9, byteArrayOf(4, 2))
        peer.readFrame().let {
            assertEquals(10, it.opcode)
            assertTrue(it.masked)
            assertArrayEquals(byteArrayOf(4, 2), it.payload)
        }
        peer.sendFrame(10, byteArrayOf(1))
        peer.sendFrame(2, byteArrayOf(5))
        assertArrayEquals(byteArrayOf(5), ws.binary())
    }

    @Test
    fun `the client pings and hangs up when the pong is missing or wrong`() {
        val (ws, peer) = connect(options = Options(pingIntervalMs = 100, pongTimeoutMs = 300))
        val ping = peer.readFrame()
        assertEquals(9, ping.opcode)
        assertTrue(ping.masked)
        assertEquals(4, ping.payload.size)
        peer.sendFrame(10, ping.payload)
        val next = peer.readFrame() // a second ping: the answered one kept the connection up
        assertEquals(9, next.opcode)
        peer.sendFrame(10, ByteArray(4) { (next.payload[it] + 1).toByte() })
        assertEquals(1006, ws.closure().code)
        assertTrue(peer.hungUp())
        assertFalse(ws.isOpen)
    }

    @Test
    fun `close runs the closing handshake and stops the connection's threads`() {
        val (ws, peer) = connect()
        val closing = async { ws.close(1000, "bye") }
        val frame = peer.readFrame()
        assertEquals(8, frame.opcode)
        assertTrue(frame.masked)
        assertEquals(1000, frame.closeCode)
        assertEquals("bye", frame.closeReason)
        assertFalse(closing.isDone) // still waiting for the server's close frame
        peer.sendClose(1000)
        closing.get(5, SECONDS)
        assertTrue(peer.hungUp())
        assertEquals(1000, ws.closure().code)
        assertThrows(WebSocketClosedException::class.java) { ws.sendBinary(byteArrayOf(1)) }
        ws.close()
        awaitTrue { Thread.getAllStackTraces().keys.none { it.isAlive && it.name.endsWith(":${server.port}") } }
    }

    @Test
    fun `a close from the server is echoed after buffered messages are delivered`() {
        val (ws, peer) = connect()
        peer.sendFrame(2, byteArrayOf(1))
        peer.sendFrame(1, "two".toByteArray())
        peer.sendClose(1001, "going away")
        val echo = peer.readFrame()
        assertEquals(8, echo.opcode)
        assertEquals(1001, echo.closeCode)
        assertTrue(peer.hungUp())
        assertArrayEquals(byteArrayOf(1), ws.binary())
        assertEquals("two", ws.text())
        val closed = ws.closure()
        assertEquals(1001, closed.code)
        assertEquals("going away", closed.reason)
    }

    @Test
    fun `close stops waiting when the server never answers`() {
        val (ws, peer) = connect(options = Options(closeTimeoutMs = 200))
        val started = System.nanoTime()
        ws.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 150..3_000)
        assertEquals(8, peer.readFrame().opcode)
        assertTrue(peer.hungUp())
        assertEquals(1000, ws.closure().code)
    }

    @Test
    fun `close from another thread wakes a blocked receive`() {
        val (ws, _) = connect(options = Options(closeTimeoutMs = 200))
        val receiving = async { runCatching { ws.receive() }.exceptionOrNull() }
        ws.close(1001)
        assertTrue(receiving.get(5, SECONDS) is WebSocketClosedException)
    }

    @Test
    fun `receive can time out without closing`() {
        val (ws, peer) = connect()
        assertThrows(SocketTimeoutException::class.java) { ws.receive(timeoutMs = 50) }
        assertTrue(ws.isOpen)
        peer.sendFrame(2, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), ws.binary())
    }

    @Test
    fun `oversized messages are refused with 1009`() {
        for (fragmented in listOf(false, true)) {
            val (ws, peer) = connect(options = Options(maxMessageBytes = 1_000))
            peer.sendFrame(2, ByteArray(1_000))
            assertEquals(1_000, ws.binary().size)
            if (fragmented) {
                peer.sendFrame(2, ByteArray(600), fin = false)
                peer.sendFrame(0, ByteArray(600))
            } else {
                peer.sendFrame(2, ByteArray(1_001))
            }
            val close = peer.readFrame()
            assertEquals(8, close.opcode)
            assertEquals(1009, close.closeCode)
            assertEquals(1009, ws.closure().code)
            peer.close()
        }
    }

    @Test
    fun `protocol violations fail the connection with the matching close code`() {
        val violations = listOf(
            violation(1002) { it.sendFrame(2, byteArrayOf(1), masked = true) },
            violation(1002) { it.sendFrame(0, byteArrayOf(1)) }, // continuation with nothing to continue
            violation(1002) { it.sendFrame(1, byteArrayOf(1), fin = false); it.sendFrame(2, byteArrayOf(2)) },
            violation(1002) { it.writeRaw(byteArrayOf(0x83.toByte(), 0)) }, // reserved opcode
            violation(1002) { it.writeRaw(byteArrayOf(0xC2.toByte(), 0)) }, // RSV1 without an extension
            violation(1002) { it.sendFrame(9, ByteArray(126)) }, // control frame over 125 bytes
            violation(1002) { it.sendFrame(9, byteArrayOf(1), fin = false) }, // fragmented control frame
            violation(1002) { it.sendFrame(8, byteArrayOf(3)) }, // one-byte close payload
            violation(1002) { it.sendClose(1005) }, // a code that must never be sent
            violation(1007) { it.sendFrame(1, byteArrayOf(0xC3.toByte())) }, // truncated UTF-8
        )
        for ((code, misbehave) in violations) {
            val (ws, peer) = connect()
            misbehave(peer)
            val close = peer.readFrame()
            assertEquals(8, close.opcode)
            assertEquals(code, close.closeCode)
            assertEquals(code, ws.closure().code)
            peer.close()
        }
    }

    @Test
    fun `concurrent senders never interleave frames`() {
        val (ws, peer) = connect()
        val sizes = intArrayOf(5, 300, 70_000)
        fun payload(t: Int, i: Int) = ByteArray(sizes[i % 3]) { (t * 31 + i * 7 + it).toByte() }.also {
            it[0] = t.toByte()
            it[1] = i.toByte()
        }
        val senders = (0 until 4).map { t -> async { repeat(50) { i -> ws.sendBinary(payload(t, i)) } } }
        val next = IntArray(4)
        repeat(4 * 50) {
            val frame = peer.readFrame()
            val t = frame.payload[0].toInt()
            val i = frame.payload[1].toInt()
            assertEquals(next[t]++, i)
            assertArrayEquals(payload(t, i), frame.payload)
        }
        senders.forEach { it.get(5, SECONDS) }
    }
}
