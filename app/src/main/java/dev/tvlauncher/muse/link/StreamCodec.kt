package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.crypto.NoiseTransport
import dev.tvlauncher.muse.wire.ApplicationRequest
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.Header
import dev.tvlauncher.muse.wire.NoiseFrameDecoder
import dev.tvlauncher.muse.wire.NoiseFrames
import dev.tvlauncher.muse.wire.Reset
import dev.tvlauncher.muse.wire.ResetCode
import dev.tvlauncher.muse.wire.ServiceFrame
import dev.tvlauncher.muse.wire.ServiceRequest
import dev.tvlauncher.muse.wire.ServiceResponse
import dev.tvlauncher.muse.wire.WireFormatException

/**
 * HTTP-like streams over an established Noise session (the SDK's noise/transport.py).
 * Outbound: ServiceFrame → ServiceRequest (daemon) → NoiseTransportFrames, each encrypted
 * into one WebSocket message. Inbound goes the other way. Encrypting spends send nonces, so
 * the caller encrypts and writes under one lock; decrypting belongs to the one reader
 * thread. Any failure kills the codec, and with it the session.
 */
class StreamCodec(private val noise: NoiseTransport, private val decoder: NoiseFrameDecoder = NoiseFrameDecoder()) {
    class Encrypted(val streamId: Long, val frames: List<ByteArray>)

    private var nextStreamId = 1L

    @Volatile private var dead = false

    /** A one-shot request: body inline, `end_body` set. */
    fun encryptHttpRequest(verb: String, path: String, body: ByteArray = ByteArray(0), headers: List<Header> = emptyList()) =
        request(verb, path, body, headers, endBody = true)

    /** Opens a request whose body follows as [encryptBodyChunk] frames. */
    fun startStreamRequest(verb: String, path: String, headers: List<Header> = emptyList()) =
        request(verb, path, ByteArray(0), headers, endBody = false)

    fun encryptBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): List<ByteArray> =
        guarded { encrypt(ServiceFrame(streamId, BodyChunk(data, endBody))) }

    fun encryptReset(streamId: Long, reason: String = "", code: ResetCode = ResetCode.CANCELLED): List<ByteArray> =
        guarded { encrypt(ServiceFrame(streamId, Reset(code, reason))) }

    /**
     * One inbound WebSocket message. Null until a whole frame is in, and for a frame with no
     * kind. A request from the server is a protocol error.
     */
    fun decryptFrame(ciphertext: ByteArray): ServiceFrame? = guarded {
        val assembled = decoder.decode(noise.decrypt(ciphertext)) ?: return@guarded null
        val response = ServiceResponse.decode(assembled)
        if (response.payload.isEmpty()) throw WireFormatException("empty ServiceResponse payload")
        val frame = ServiceFrame.decode(response.payload)
        if (frame.kind is ApplicationRequest) throw WireFormatException("unexpected request frame from server")
        frame.takeIf { it.kind != null }
    }

    private fun request(verb: String, path: String, body: ByteArray, headers: List<Header>, endBody: Boolean) = guarded {
        val streamId = nextStreamId++
        Encrypted(streamId, encrypt(ServiceFrame(streamId, ApplicationRequest(verb, path, headers, body, endBody))))
    }

    private fun encrypt(frame: ServiceFrame): List<ByteArray> =
        NoiseFrames.encode(ServiceRequest(payload = frame.encode()).encode()).map(noise::encrypt)

    private inline fun <T> guarded(block: () -> T): T {
        if (dead) throw WireFormatException("stream codec is dead after an earlier failure")
        try {
            return block()
        } catch (failure: Exception) {
            dead = true
            throw failure
        }
    }
}
