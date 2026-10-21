package dev.tvlauncher.muse.net

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * The opening half of [WebSocketClient] (RFC 6455 §4): TCP connect across the
 * host's addresses, TLS with SNI and an explicit hostname check, and the
 * HTTP/1.1 upgrade. Everything after the 101 belongs to the client.
 */
internal object WebSocketHandshake {
    /** An upgraded connection. [input] may already hold the server's first frames. */
    class Upgraded(val raw: Socket, val socket: Socket, val input: InputStream, val output: OutputStream, val peer: String)

    private const val MAX_RESPONSE_HEAD = 16 * 1024
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val TOKEN_CHARS = "!#$%&'*+-.^_`|~"
    private val RESERVED_HEADERS = setOf(
        "host", "upgrade", "connection", "sec-websocket-key", "sec-websocket-version",
        "sec-websocket-extensions", "sec-websocket-protocol",
    )
    private val random = SecureRandom()

    /** See [WebSocketClient.connect]. */
    fun open(url: String, headers: Map<String, String>, connectTimeoutMs: Int, options: WebSocketClient.Options): Upgraded {
        val uri = URI.create(url)
        val secure = when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> false
            else -> throw IllegalArgumentException("not a ws:// or wss:// URL")
        }
        val authority = requireNotNull(uri.host) { "URL has no host" }
        val host = authority.removeSurrounding("[", "]")
        val defaultPort = if (secure) 443 else 80
        val port = if (uri.port == -1) defaultPort else uri.port
        val target = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val request = request(target, if (port == defaultPort) authority else "$authority:$port", key, headers)
        val deadline = System.nanoTime() + MILLISECONDS.toNanos(connectTimeoutMs.toLong())
        val raw = openSocket(host, port, deadline)
        var socket = raw
        try {
            if (secure) socket = startTls(raw, host, port, options, deadline)
            socket.soTimeout = remainingMs(deadline)
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            readResponse(input, socket, deadline, acceptFor(key))
            return Upgraded(raw, socket, input, output, "$host:$port")
        } catch (e: Throwable) {
            raw.closeQuietly()
            socket.closeQuietly()
            throw e
        }
    }

    /** Tries each address in turn, as upstream's asyncio connect does. */
    private fun openSocket(host: String, port: Int, deadline: Long): Socket {
        var failure: IOException? = null
        for (address in InetAddress.getAllByName(host)) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), remainingMs(deadline))
                return socket
            } catch (e: IOException) {
                socket.closeQuietly()
                failure = e
            }
        }
        throw failure ?: IOException("no address for the host")
    }

    private fun startTls(raw: Socket, host: String, port: Int, options: WebSocketClient.Options, deadline: Long): SSLSocket {
        val ssl = options.sslSocketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            if (':' !in host && !host.all { it.isDigit() || it == '.' }) { // SNI never carries an IP
                try {
                    ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                } catch (e: IllegalArgumentException) {
                    // Not a valid SNI name; the factory still got the host.
                }
            }
            ssl.soTimeout = remainingMs(deadline)
            ssl.startHandshake()
            // Unlike HttpsURLConnection, a raw SSLSocket never checks the certificate against the host.
            if (!options.hostnameVerifier.verify(host, ssl.session)) {
                throw SSLPeerUnverifiedException("certificate does not match $host")
            }
            return ssl
        } catch (e: Throwable) {
            ssl.closeQuietly()
            throw e
        }
    }

    private fun request(target: String, hostHeader: String, key: String, headers: Map<String, String>): String {
        val request = StringBuilder()
            .append("GET ").append(target).append(" HTTP/1.1\r\n")
            .append("Host: ").append(hostHeader).append("\r\n")
            .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            .append("Sec-WebSocket-Version: 13\r\n")
        for ((name, value) in headers) {
            // Messages name the header but never echo its value: values carry bearer tokens.
            require(name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_CHARS }) {
                "invalid header name"
            }
            require(name.lowercase() !in RESERVED_HEADERS) { "$name is set by the WebSocket client" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "invalid value for header $name" }
            request.append(name).append(": ").append(value).append("\r\n")
        }
        return request.append("\r\n").toString()
    }

    private fun readResponse(input: InputStream, socket: Socket, deadline: Long, expectedAccept: String) {
        var budget = MAX_RESPONSE_HEAD
        fun readLine(): String {
            socket.soTimeout = remainingMs(deadline)
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("connection closed during the WebSocket upgrade")
                if (--budget < 0) throw ProtocolException("WebSocket upgrade response too large")
                if (b == '\n'.code) return line.removeSuffix("\r").toString()
                line.append(b.toChar())
            }
        }
        val statusLine = readLine().split(' ', limit = 3)
        val status = statusLine.getOrNull(1)?.toIntOrNull()
        if (!statusLine[0].startsWith("HTTP/") || status == null) throw ProtocolException("not an HTTP response")
        if (status != 101) throw WebSocketUpgradeException(status)
        val fields = mutableMapOf<String, MutableList<String>>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw ProtocolException("malformed header in the WebSocket upgrade")
            fields.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        fun tokens(name: String) = fields[name].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens("upgrade").none { it.equals("websocket", ignoreCase = true) }) {
            throw ProtocolException("upgrade response lacks Upgrade: websocket")
        }
        if (tokens("connection").none { it.equals("upgrade", ignoreCase = true) }) {
            throw ProtocolException("upgrade response lacks Connection: upgrade")
        }
        if (fields["sec-websocket-accept"] != listOf(expectedAccept)) throw ProtocolException("wrong Sec-WebSocket-Accept")
        // None were offered, so frames using a server-chosen extension or subprotocol would be unreadable.
        if (tokens("sec-websocket-extensions").isNotEmpty() || tokens("sec-websocket-protocol").isNotEmpty()) {
            throw ProtocolException("server chose an extension or subprotocol that was not offered")
        }
    }

    private fun acceptFor(key: String): String = Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))

    private fun remainingMs(deadline: Long): Int {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) throw SocketTimeoutException("WebSocket connect timed out")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal fun Socket.closeQuietly() {
    try {
        close()
    } catch (e: IOException) {
        // Already gone.
    }
}
