package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.crypto.NoiseTransport
import dev.tvlauncher.muse.crypto.NoiseXXResponder
import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.MessageCodec
import dev.tvlauncher.muse.wire.MessageDecoder
import dev.tvlauncher.muse.wire.NoiseFrameDecoder
import dev.tvlauncher.muse.wire.NoiseFrames
import dev.tvlauncher.muse.wire.ServiceFrame
import dev.tvlauncher.muse.wire.ServiceRequest
import dev.tvlauncher.muse.wire.ServiceResponse
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** An in-memory WebSocket: what one end sends, the other receives. */
class Pipe {
    private object Closed

    val toDevice = LinkedBlockingQueue<Any>()
    val toVm = LinkedBlockingQueue<Any>()

    @Volatile var closed = false
        private set

    val device = object : LinkSocket {
        override fun send(data: ByteArray) {
            if (closed) throw IOException("closed")
            toVm.put(data)
        }

        override fun receive(timeoutMs: Long): ByteArray? {
            val item = (if (timeoutMs > 0) toDevice.poll(timeoutMs, TimeUnit.MILLISECONDS) else toDevice.take())
                ?: throw SocketTimeoutException("no message")
            if (item === Closed) {
                toDevice.put(Closed)
                throw IOException("closed")
            }
            return item as? ByteArray // a String stands for a text frame
        }

        override fun close() {
            closed = true
            toDevice.put(Closed)
            toVm.put(Closed)
        }
    }

    /** The VM's side hangs up. */
    fun drop() = device.close()

    fun takeFromDevice(): ByteArray? {
        val item = toVm.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("nothing from the device in 5 s")
        return item as? ByteArray
    }
}

/** Just enough of the Muse VM: the Noise responder plus the control stream (the SDK's test FakeVm). */
class FakeVm(val pipe: Pipe = Pipe()) {
    private lateinit var transport: NoiseTransport
    private val frames = NoiseFrameDecoder()
    private val messages = MessageDecoder()
    var controlStreamId = 0L

    fun handshake() {
        val responder = NoiseXXResponder()
        pipe.toDevice.put(responder.readMessage1AndWriteMessage2(receive()))
        responder.readMessage3(receive())
        transport = responder.split()
    }

    fun nextFrame(): ServiceFrame {
        while (true) {
            val assembled = frames.decode(transport.decrypt(receive())) ?: continue
            return ServiceFrame.decode(ServiceRequest.decode(assembled).payload)
        }
    }

    fun nextMessage(): JSONObject {
        while (true) {
            val chunk = nextFrame().kind as? BodyChunk ?: throw AssertionError("expected a body chunk")
            messages.feed(chunk.data).firstOrNull()?.let { return it }
        }
    }

    fun sendFrame(frame: ServiceFrame) {
        for (chunk in NoiseFrames.encode(ServiceResponse(frame.encode()).encode())) pipe.toDevice.put(transport.encrypt(chunk))
    }

    fun acceptControlStream(status: Int = 200): ServiceFrame {
        val request = nextFrame()
        controlStreamId = request.streamId
        sendFrame(ServiceFrame(controlStreamId, ApplicationResponse(status = status, endBody = status >= 400)))
        return request
    }

    fun sendMessage(message: Map<String, Any?>) =
        sendFrame(ServiceFrame(controlStreamId, BodyChunk(MessageCodec.encode(message))))

    /** Handshake, control stream, and an accepted `link.register`. Returns the register request. */
    fun register(): JSONObject {
        handshake()
        acceptControlStream()
        val register = nextMessage()
        sendMessage(mapOf("type" to "res", "id" to register.getString("id"), "ok" to true))
        return register
    }

    private fun receive(): ByteArray = pipe.takeFromDevice() ?: throw AssertionError("device sent a text frame")
}
