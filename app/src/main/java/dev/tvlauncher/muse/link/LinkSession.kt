package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.crypto.NoiseXXInitiator
import dev.tvlauncher.muse.net.WebSocketClient
import dev.tvlauncher.muse.net.WebSocketUpgradeException
import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.Header
import dev.tvlauncher.muse.wire.MessageCodec
import dev.tvlauncher.muse.wire.MessageDecoder
import dev.tvlauncher.muse.wire.Reset
import dev.tvlauncher.muse.wire.ServiceFrame
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One control session with a Muse VM (a port of the SDK's link_client.py). Opens the
 * `/v1/noise` WebSocket with the per-VM bearer, runs the Noise XX handshake, then keeps a
 * `POST /link-control` stream open. Its body carries length-prefixed JSON both ways: the
 * device sends `link.register` and a `link.result` per invoke; the VM sends the register
 * reply, `link.invoke` requests and events such as `link.unpaired`. Chat messages from the
 * device go as separate `POST /chat/stream` requests on the same session.
 *
 * [run] blocks its thread until the session ends. Everything else is thread-safe.
 */
class LinkSession(
    noiseHost: String,
    vmId: String,
    private val vmAuthToken: String,
    private val device: DeviceDescription,
    private val runCommand: (command: String, params: JSONObject, timeoutMs: Long?) -> Map<String, Any?>,
    private val connect: (url: String, headers: Map<String, String>) -> LinkSocket,
    private val onRegistered: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    val url = noiseUrl(noiseHost, vmId)

    /** When `link.register` was accepted (monotonic ms), or null. */
    @Volatile var registeredAt: Long? = null
        private set

    val isRegistered: Boolean get() = running && registeredAt != null

    private val sendLock = Any()
    private val workers = ThreadPoolExecutor(
        MAX_CONCURRENT_INVOKES, MAX_CONCURRENT_INVOKES, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { Thread(it, "muse-invoke") }.apply { allowCoreThreadTimeOut(true) }
    private val requests = ConcurrentHashMap<Long, PendingRequest>()
    private val subscriptions = ConcurrentHashMap<Long, ChatSubscription>()
    private val downloads = ConcurrentHashMap<Long, ByteStream>()
    private val clientInvokes = LinkedHashSet<String>()

    @Volatile private var socket: LinkSocket? = null

    @Volatile private var codec: StreamCodec? = null

    @Volatile private var running = false

    @Volatile private var stopped = false
    private var controlStreamId = 0L
    private var registerId = ""

    /** Connects and serves until the session ends; never throws for network or protocol trouble. */
    fun run(): Outcome {
        val socket = try {
            connect(url, mapOf("Authorization" to "Bearer $vmAuthToken"))
        } catch (e: WebSocketUpgradeException) {
            log("VM refused connection: HTTP ${e.status}")
            return when (e.status) {
                401 -> Outcome.AUTH_REJECTED
                403 -> Outcome.FORBIDDEN
                else -> Outcome.CLOSED
            }
        } catch (e: IOException) {
            log("connect failed: ${e.javaClass.simpleName}")
            return if (stopped) Outcome.STOPPED else Outcome.CLOSED
        }
        this.socket = socket
        try {
            if (stopped) return Outcome.STOPPED
            codec = handshake(socket)
            running = true
            openControlStream()
            return readLoop(socket)
        } catch (e: Exception) {
            if (stopped) return Outcome.STOPPED
            log("session failed: ${e.javaClass.simpleName}: ${e.message}")
            return Outcome.CLOSED
        } finally {
            running = false
            workers.shutdownNow()
            val ended = IOException("session ended")
            requests.values.forEach { it.fail(ended) }
            requests.clear()
            subscriptions.values.forEach { it.fail(ended) }
            subscriptions.clear()
            downloads.values.forEach { it.fail(ended) }
            downloads.clear()
            runCatching { socket.close() }
        }
    }

    /** Ends [run] with [Outcome.STOPPED]; any thread, returns at once. */
    fun stop() {
        stopped = true
        val socket = socket ?: return
        thread(name = "muse-link-close", isDaemon = true) { runCatching { socket.close() } }
    }

    // -- Device-originated requests ------------------------------------------------------

    /**
     * Posts a user message to the Muse as coming from this device, and waits for the ack.
     * [sessionId] targets a side chat (a new id starts one); without it the message goes to
     * the main chat. The reply itself arrives on [subscribeChat].
     */
    fun sendChat(message: String, sessionId: String? = null, outputModality: String = "text"): ChatResult {
        val body = linkedMapOf<String, Any?>("message" to message, "output_modality" to outputModality, "device_id" to device.nodeId)
        if (!sessionId.isNullOrEmpty()) body["session_id"] = sessionId
        return postChat(body)
    }

    /** Posts a WAV voice note (at most 2 MiB), optionally with instructions in [message]. */
    fun sendVoice(wav: ByteArray, sessionId: String? = null, message: String = "", outputModality: String? = null): ChatResult {
        require(wav.isNotEmpty() && wav.size <= MAX_VOICE_BYTES) { "voice note must contain at most 2 MiB of WAV audio" }
        val item = linkedMapOf(
            "type" to "file",
            "mime_type" to "audio/wav",
            "filename" to "voice_note.wav",
            "data_base64" to Base64.getEncoder().encodeToString(wav),
        )
        val body = linkedMapOf<String, Any?>("message" to message, "device_id" to device.nodeId, "items" to listOf(item))
        if (!sessionId.isNullOrEmpty()) body["session_id"] = sessionId
        if (outputModality != null) body["output_modality"] = outputModality
        return postChat(body)
    }

    /**
     * Opens a chat event subscription and waits for the VM to accept it. Events can reach
     * [listener] before this returns.
     */
    fun subscribeChat(sessionId: String?, listener: ChatSubscription.Listener): ChatSubscription {
        if (!running) throw IOException("Muse session is not connected")
        val body = MessageCodec.compactJson(if (sessionId.isNullOrEmpty()) emptyMap<String, Any>() else mapOf("session_id" to sessionId))
        val headers = httpHeaders(Header("Content-Type", "application/json"), Header("Accept", "application/x-ndjson"))
        var subscription: ChatSubscription? = null
        // Muse delivers this TV's commands as `client.invoke` chat events when the TV is
        // subscribed (not as link.invoke), so they're answered here rather than passed on.
        val withInvokes = object : ChatSubscription.Listener {
            override fun onEvent(event: JSONObject) {
                if (event.opt("event") == "client.invoke") clientInvoke(event, subscription?.streamId) else listener.onEvent(event)
            }

            override fun onEnd(error: Exception?) = listener.onEnd(error)
        }
        sendFrames { codec ->
            val encrypted = codec.encryptHttpRequest("POST", SUBSCRIBE_PATH, body.toByteArray(), headers)
            subscription = ChatSubscription(encrypted.streamId, withInvokes, ::closeSubscription).also {
                subscriptions[encrypted.streamId] = it
            }
            encrypted.frames
        }
        val opened = subscription!!
        try {
            val status = opened.awaitReady(REQUEST_TIMEOUT_MS)
            log("chat subscription${if (sessionId.isNullOrEmpty()) "" else " (side chat)"}: HTTP $status ${opened.errorBody}".trimEnd())
            if (status !in 200..299) throw HttpStreamError(status, SUBSCRIBE_PATH)
            return opened
        } catch (e: Exception) {
            opened.close()
            throw e
        }
    }

    /** GETs [path] as a body that can be read while it arrives (Muse's spoken answers). */
    fun openByteStream(path: String, accept: String): ByteStream {
        if (!running) throw IOException("Muse session is not connected")
        var stream: ByteStream? = null
        sendFrames { codec ->
            val encrypted = codec.encryptHttpRequest("GET", path, headers = httpHeaders(Header("Accept", accept)))
            stream = ByteStream(encrypted.streamId, MAX_TTS_BUFFER_BYTES) { closed ->
                if (downloads.remove(closed.streamId) != null && !closed.finishedByVm) resetStream(closed.streamId)
            }.also { downloads[encrypted.streamId] = it }
            encrypted.frames
        }
        return stream!!
    }

    private fun postChat(requestBody: Map<String, Any?>): ChatResult {
        if (!running) throw IOException("Muse session is not connected")
        val body = MessageCodec.compactJson(requestBody).toByteArray()
        val headers = httpHeaders(Header("Content-Type", "application/json"))
        val inParts = body.size > MAX_INLINE_REQUEST_BYTES
        val pending = PendingRequest()
        var streamId = 0L
        try {
            sendFrames { codec ->
                val encrypted = if (inParts) {
                    codec.startStreamRequest("POST", CHAT_PATH, headers)
                } else {
                    codec.encryptHttpRequest("POST", CHAT_PATH, body, headers)
                }
                streamId = encrypted.streamId
                requests[streamId] = pending
                if (!inParts) {
                    encrypted.frames
                } else {
                    encrypted.frames + (body.indices step REQUEST_CHUNK_BYTES).flatMap { offset ->
                        val end = minOf(offset + REQUEST_CHUNK_BYTES, body.size)
                        codec.encryptBodyChunk(streamId, body.copyOfRange(offset, end), endBody = end == body.size)
                    }
                }
            }
            val (status, response) = pending.await(REQUEST_TIMEOUT_MS)
            return ChatResult(status in 200..299, status, decodeResponse(response))
        } catch (e: Exception) {
            if (streamId != 0L) resetStream(streamId)
            throw e
        } finally {
            requests.remove(streamId)
        }
    }

    private fun closeSubscription(subscription: ChatSubscription) {
        if (subscriptions.remove(subscription.streamId) != null && !subscription.ended) resetStream(subscription.streamId)
    }

    private fun resetStream(streamId: Long) {
        if (!running) return
        try {
            sendFrames { it.encryptReset(streamId, reason = "consumer closed") }
        } catch (e: Exception) {
            log("could not cancel a closed Muse stream")
        }
    }

    // -- Connection ----------------------------------------------------------------------

    private fun handshake(socket: LinkSocket): StreamCodec {
        val initiator = NoiseXXInitiator()
        socket.send(initiator.writeMessage1())
        val message2 = socket.receive(HANDSHAKE_TIMEOUT_MS) ?: throw IOException("Noise handshake got a text frame")
        initiator.readMessage2(message2)
        // The bearer already authenticated us at the upgrade; message 3 carries an empty payload.
        socket.send(initiator.writeMessage3())
        log("Noise session established")
        return StreamCodec(initiator.split())
    }

    private fun openControlStream() {
        sendFrames { codec -> codec.startStreamRequest("POST", CONTROL_PATH).also { controlStreamId = it.streamId }.frames }
        registerId = UUID.randomUUID().toString()
        send(linkedMapOf("type" to "req", "id" to registerId, "method" to "link.register", "params" to device.registerParams()))
        log("sent link.register as ${device.nodeId}")
    }

    /** One control message to the VM. */
    private fun send(message: Map<String, Any?>) = sendFrames { it.encryptBodyChunk(controlStreamId, MessageCodec.encode(message)) }

    /** Encrypting spends Noise nonces, so frames are encrypted and written in one critical section. */
    private fun sendFrames(build: (StreamCodec) -> List<ByteArray>) {
        synchronized(sendLock) {
            val codec = codec ?: throw IOException("Muse session is not connected")
            val socket = socket ?: throw IOException("Muse session is not connected")
            for (frame in build(codec)) socket.send(frame)
        }
    }

    // -- Receiving -----------------------------------------------------------------------

    private fun readLoop(socket: LinkSocket): Outcome {
        val codec = codec!!
        val decoder = MessageDecoder()
        while (true) {
            val raw = try {
                socket.receive()
            } catch (e: IOException) {
                if (stopped) return Outcome.STOPPED
                log("control connection closed: ${e.message}")
                return Outcome.CLOSED
            }
            if (raw == null) {
                log("ignoring text frame on Noise connection")
                continue
            }
            val frame = codec.decryptFrame(raw) ?: continue
            if (frame.streamId != controlStreamId) {
                dispatch(frame)
                continue
            }
            val (data, ended) = when (val kind = frame.kind) {
                is Reset -> {
                    log("control stream reset: ${printable(kind.reason)}")
                    return Outcome.CLOSED
                }
                is ApplicationResponse -> {
                    if (kind.status >= 400) {
                        log("/link-control refused: HTTP ${kind.status}")
                        return if (kind.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                    }
                    kind.body to kind.endBody
                }
                is BodyChunk -> kind.data to kind.endBody
                else -> continue
            }
            for (message in decoder.feed(data)) handle(message)?.let { return it }
            if (ended) {
                log("control stream ended by VM")
                return Outcome.CLOSED
            }
        }
    }

    /** A frame for a chat request or subscription. Frames for unknown streams are dropped. */
    private fun dispatch(frame: ServiceFrame) {
        downloads[frame.streamId]?.let { download ->
            if (!download.onFrame(frame.kind)) background { download.close() }
            return
        }
        val subscription = subscriptions[frame.streamId]
        if (subscription == null) {
            val request = requests[frame.streamId]
            if (request == null) log("dropped ${frame.kind?.javaClass?.simpleName} on unknown stream ${frame.streamId}")
            request?.onFrame(frame.kind)
            return
        }
        val healthy = subscription.onFrame(frame.kind)
        if (!healthy || subscription.ended) subscriptions.remove(frame.streamId)
        if (!healthy) background { resetStream(frame.streamId) }
    }

    private fun handle(message: JSONObject): Outcome? {
        val method = message.opt("method").takeUnless { it == JSONObject.NULL }
        log("control: ${describeControl(message)}")
        if (message.opt("id") == registerId && method == null) {
            if (truthy(message.opt("error")) || message.opt("ok") == false) {
                log("link.register rejected")
                return Outcome.FORBIDDEN
            }
            registeredAt = clock()
            log("registered with the Muse")
            // The reference firmware follows a "registered" reply with a heartbeat (then one a day).
            if ((message.opt("result") as? JSONObject)?.opt("status") == "registered") {
                background {
                    send(mapOf("method" to "link.heartbeat"))
                    log("sent link.heartbeat")
                }
            }
            onRegistered()
            return null
        }
        val event = message.opt("event")
        if (event == "link.unpaired" || event == "node.unpaired") {
            log("the Muse removed this device")
            return Outcome.UNPAIRED
        }
        if (method == "link.invoke") invoke(message)
        return null
    }

    private fun invoke(message: JSONObject) {
        val invokeId = message.opt("id")
        if (!truthy(invokeId)) return
        val command = message.opt("command") as? String ?: ""
        val params = message.opt("params") as? JSONObject ?: JSONObject()
        val timeoutMs = (message.opt("timeout_ms") as? Number)?.toLong()?.takeIf { it > 0 }
        val shown = printable(command)
        log("invoke $shown")
        background {
            val started = clock()
            val result = try {
                runCommand(command, params, timeoutMs)
            } catch (e: Exception) {
                mapOf("ok" to false, "error" to "${e.javaClass.simpleName} running $shown")
            }
            log("$shown ${describeResult(result)} in ${clock() - started} ms")
            send(LinkedHashMap<String, Any?>().apply {
                put("method", "link.result")
                put("id", invokeId)
                putAll(result)
            })
        }
    }

    /**
     * A command for this TV arriving as a chat event: `{command_id, invoke_id, params_json,
     * timeout_ms}`. Runs it like a link.invoke and answers with a link.result for invoke_id.
     */
    private fun clientInvoke(event: JSONObject, streamId: Long?) {
        val payload = event.opt("payload") as? JSONObject ?: return
        val invokeId = payload.opt("invoke_id") as? String ?: return
        val command = payload.opt("command_id") as? String ?: return
        val shown = printable(command.take(64))
        if (command !in device.commands || invokeId.isEmpty()) {
            log("client invoke for another command: $shown (stream $streamId)")
            return
        }
        synchronized(clientInvokes) {
            if (!clientInvokes.add(invokeId)) {
                log("client invoke $shown again (stream $streamId)") // a second subscription or a replay
                return
            }
            if (clientInvokes.size > MAX_REMEMBERED_INVOKES) clientInvokes.remove(clientInvokes.first())
        }
        val params = (payload.opt("params_json") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val timeoutMs = (payload.opt("timeout_ms") as? Number)?.toLong()?.takeIf { it > 0 }
        log("client invoke $shown (stream $streamId)")
        background {
            val started = clock()
            val result = try {
                runCommand(command, params, timeoutMs)
            } catch (e: Exception) {
                mapOf("ok" to false, "error" to "${e.javaClass.simpleName} running $shown")
            }
            log("$shown ${describeResult(result)} in ${clock() - started} ms (client invoke)")
            send(LinkedHashMap<String, Any?>().apply {
                put("method", "link.result")
                put("id", invokeId)
                putAll(result)
            })
        }
    }

    /** Off the reader thread, at most four at a time. Failures are logged, never thrown. */
    private fun background(task: () -> Unit) {
        try {
            workers.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log("background task failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: RejectedExecutionException) {
            // The session is ending.
        }
    }

    companion object {
        const val NOISE_PATH = "/v1/noise"
        const val CONTROL_PATH = "/link-control"
        const val CHAT_PATH = "/chat/stream"
        const val SUBSCRIBE_PATH = "/chat/subscribe"
        const val APP_ID = "musegadget"
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val MAX_CONCURRENT_INVOKES = 4
        const val MAX_VOICE_BYTES = 2 * 1024 * 1024
        const val MAX_TTS_BUFFER_BYTES = 8 * 1024 * 1024
        const val REQUEST_CHUNK_BYTES = 16 * 1024
        const val MAX_INLINE_REQUEST_BYTES = 64 * 1024
        private const val MAX_REMEMBERED_INVOKES = 64

        /** The default [LinkSession.connect]: a real WebSocket. */
        fun webSocketConnector(userAgent: String): (String, Map<String, String>) -> LinkSocket = { url, headers ->
            val ws = WebSocketClient.connect(url, headers + ("User-Agent" to userAgent), HANDSHAKE_TIMEOUT_MS.toInt())
            object : LinkSocket {
                override fun send(data: ByteArray) = ws.sendBinary(data)

                override fun receive(timeoutMs: Long): ByteArray? = when (val message = ws.receive(timeoutMs)) {
                    is WebSocketClient.Message.Binary -> message.data
                    is WebSocketClient.Message.Text -> null
                }

                override fun close() = ws.close()
            }
        }

        private fun httpHeaders(vararg headers: Header): List<Header> =
            headers.toList() + Header("x-request-id", UUID.randomUUID().toString()) + Header("x-app-id", APP_ID)

        private fun decodeResponse(body: ByteArray): Any? {
            if (body.isEmpty()) return null
            val text = String(body, Charsets.UTF_8)
            return try {
                JSONTokener(text).nextValue().takeUnless { it == JSONObject.NULL }
            } catch (e: JSONException) {
                text.take(2000)
            }
        }
    }
}
