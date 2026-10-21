package dev.tvlauncher.muse.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Test double for a WebSocket server. [accept] takes the next connection on a
 * background thread and answers its upgrade; the test then plays the server
 * side frame by frame through the returned [Peer].
 */
internal class FakeWebSocketServer(
    private val serverSocket: ServerSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()),
) : Closeable {
    private val pool = Executors.newCachedThreadPool { Thread(it, "fake-ws-server").apply { isDaemon = true } }
    private val peers = CopyOnWriteArrayList<Peer>()

    val port: Int get() = serverSocket.localPort

    fun url(target: String = "/", scheme: String = "ws", host: String = "127.0.0.1") = "$scheme://$host:$port$target"

    /** Accepts one connection, reads its upgrade request and writes [respond]'s reply verbatim. */
    fun accept(respond: (Peer) -> String = Peer::switchingProtocols): Future<Peer> = pool.submit(
        Callable {
            val peer = Peer(serverSocket.accept()).also(peers::add)
            peer.readRequest()
            peer.writeRaw(respond(peer).toByteArray(Charsets.ISO_8859_1))
            peer
        },
    )

    override fun close() {
        peers.forEach(Peer::close)
        pool.shutdownNow()
        serverSocket.close()
    }
}

/** The server end of one test connection. Its frames are unmasked, as a server's must be. */
internal class Peer(val socket: Socket) : Closeable {
    class Frame(val fin: Boolean, val opcode: Int, val masked: Boolean, val lengthField: Int, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
        val closeCode: Int get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val closeReason: String get() = String(payload, 2, payload.size - 2, Charsets.UTF_8)
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = socket.getOutputStream()
    var requestLine = ""
        private set
    val headers = mutableMapOf<String, String>()

    init {
        socket.soTimeout = 5_000
    }

    fun readRequest() {
        requestLine = readLine()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) return
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
    }

    fun switchingProtocols(): String =
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(headers.getValue("sec-websocket-key"))}\r\n\r\n"

    fun readFrame(): Frame {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val lengthField = b1 and 0x7F
        val length = when (lengthField) {
            126 -> input.readUnsignedShort().toLong()
            127 -> input.readLong()
            else -> lengthField.toLong()
        }
        val masked = b1 and 0x80 != 0
        val mask = ByteArray(4)
        if (masked) input.readFully(mask)
        val payload = ByteArray(length.toInt()).also(input::readFully)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0F, masked, lengthField, payload)
    }

    fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, masked: Boolean = false) {
        val frame = ByteArrayOutputStream()
        frame.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> frame.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(maskBit or 126)
                frame.write(payload.size shr 8)
                frame.write(payload.size)
            }
            else -> {
                frame.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) frame.write((payload.size.toLong() shr shift).toInt())
            }
        }
        if (masked) {
            val key = byteArrayOf(1, 2, 3, 4)
            frame.write(key)
            frame.write(ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() })
        } else {
            frame.write(payload)
        }
        writeRaw(frame.toByteArray())
    }

    fun sendClose(code: Int, reason: String = "") =
        sendFrame(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray(Charsets.UTF_8))

    fun writeRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** True once the client has hung up (EOF or reset) without sending anything more. */
    fun hungUp(): Boolean = try {
        input.read() == -1
    } catch (e: SocketException) {
        true
    }

    override fun close() = socket.close()

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("client hung up")
            if (b == '\n'.code) return line.removeSuffix("\r").toString()
            line.append(b.toChar())
        }
    }

    companion object {
        fun acceptKey(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
    }
}
