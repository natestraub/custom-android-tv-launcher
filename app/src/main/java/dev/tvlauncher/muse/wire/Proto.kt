package dev.tvlauncher.muse.wire

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Objects

/**
 * The peer sent bytes that break the Muse wire format: a malformed protobuf, a bad frame
 * sequence or an oversize message (the SDK's `ProtoError` and framing `ValueError`s). As
 * upstream, the stream or session that produced them has to be dropped.
 */
class WireFormatException(message: String) : IOException(message)

internal val EMPTY_BYTES = ByteArray(0)

/** Strict UTF-8, or null when malformed (no silent U+FFFD substitution). */
internal fun decodeUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String? =
    try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

private const val VARINT = 0
private const val FIXED64 = 1
private const val LEN = 2
private const val FIXED32 = 5
private const val MAX_FIELD_NUMBER = (1L shl 29) - 1

private fun isValidField(number: Long) = number in 1..MAX_FIELD_NUMBER && number !in 19_000L..19_999L

/**
 * Proto3 wire writer for the hand-written Muse envelopes (SDK `noise/_proto.py`); there is
 * no protobuf runtime on the device. Each message's encoder decides which defaults to omit.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** A raw varint; negative values take the 10-byte two's complement form int64 uses. */
    fun varint(field: Int, value: Long) {
        key(field, VARINT)
        rawVarint(value)
    }

    fun int64(field: Int, value: Long) = varint(field, value)

    /** Negative int32s are sign-extended to 64 bits, so they take 10 bytes too. */
    fun int32(field: Int, value: Int) = varint(field, value.toLong())

    /** uint32 travels in an Int's bits, as in protobuf-java. */
    fun uint32(field: Int, value: Int) = varint(field, value.toLong() and 0xFFFF_FFFFL)

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray) {
        key(field, LEN)
        rawVarint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun key(field: Int, wireType: Int) {
        require(isValidField(field.toLong())) { "invalid field number $field" }
        rawVarint((field.toLong() shl 3) or wireType.toLong())
    }

    private fun rawVarint(value: Long) {
        var rest = value
        while ((rest and 0x7FL.inv()) != 0L) {
            out.write((rest.toInt() and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }
}

/** Proto3 wire reader; every malformation is a [WireFormatException] worded like the SDK's. */
internal class ProtoReader(private val data: ByteArray) {
    private var pos = 0
    private var wireType = 0

    /** Field number of the key [next] just read. */
    var field = 0
        private set

    /** Reads the next key; false at the end of the message. */
    fun next(): Boolean {
        if (pos >= data.size) return false
        val key = rawVarint()
        if (!isValidField(key ushr 3)) throw WireFormatException("invalid field number")
        wireType = (key and 7).toInt()
        if (wireType != VARINT && wireType != FIXED64 && wireType != LEN && wireType != FIXED32) {
            throw WireFormatException("invalid wire type")
        }
        field = (key ushr 3).toInt()
        return true
    }

    /** A raw varint (int64 or enum); [name] labels the wrong-wire-type error. */
    fun varint(name: String): Long {
        expect(VARINT, name)
        return rawVarint()
    }

    fun int32(name: String): Int {
        val value = varint(name)
        if (value.toInt().toLong() != value) throw WireFormatException("int32 value out of range")
        return value.toInt()
    }

    fun uint32(name: String): Int {
        val value = varint(name)
        if ((value ushr 32) != 0L) throw WireFormatException("uint32 value out of range")
        return value.toInt()
    }

    fun bool(name: String) = varint(name) != 0L

    fun bytes(name: String): ByteArray {
        expect(LEN, name)
        val start = skipDelimited()
        return data.copyOfRange(start, pos)
    }

    fun string(name: String): String {
        expect(LEN, name)
        val start = skipDelimited()
        return decodeUtf8(data, start, pos - start) ?: throw WireFormatException("invalid utf-8 string")
    }

    /** Steps over a field the message doesn't define. */
    fun skip() {
        when (wireType) {
            VARINT -> rawVarint()
            FIXED64 -> skipFixed(8, "truncated fixed64 field")
            LEN -> skipDelimited()
            else -> skipFixed(4, "truncated fixed32 field")
        }
    }

    private fun expect(type: Int, name: String) {
        if (wireType != type) throw WireFormatException("$name wrong wire type")
    }

    private fun rawVarint(): Long {
        var value = 0L
        for (i in 0 until 10) {
            if (pos >= data.size) throw WireFormatException("truncated varint")
            val byte = data[pos++].toInt() and 0xFF
            if (i == 9 && (byte and 0xFE) != 0) throw WireFormatException("malformed varint") // past 64 bits
            value = value or ((byte and 0x7F).toLong() shl (7 * i))
            if ((byte and 0x80) == 0) return value
        }
        throw WireFormatException("malformed varint")
    }

    /** Steps over a length-delimited value and returns where it starts. */
    private fun skipDelimited(): Int {
        val length = rawVarint()
        if (length < 0 || length > data.size - pos) throw WireFormatException("truncated delimited field")
        val start = pos
        pos += length.toInt()
        return start
    }

    private fun skipFixed(size: Int, error: String) {
        if (size > data.size - pos) throw WireFormatException(error)
        pos += size
    }
}

internal inline fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()

internal inline fun decodeFields(data: ByteArray, onField: ProtoReader.(field: Int) -> Unit) {
    val reader = ProtoReader(data)
    while (reader.next()) reader.onField(reader.field)
}

/** Which VM service a request is for; the gadget only talks to the daemon. */
enum class ServiceType(val number: Int) {
    SERVICE_DAEMON(0), SERVICE_SENTINEL(1), SERVICE_VAULT(2), SERVICE_AUTHD(3),
}

/** Why a stream was reset. The SDK cancels with [CANCELLED] and reason "consumer closed". */
enum class ResetCode(val number: Int) {
    CODE_UNSPECIFIED(0), CANCELLED(1), TIMEOUT(2), PROTOCOL_ERROR(3),
    REFUSED_STREAM(4), INTERNAL_ERROR(5), SERVICE_UNAVAILABLE(6),
}

// Unknown enum values are errors, as in the SDK (plain proto3 would keep them).
private fun serviceType(number: Long) = ServiceType.entries.firstOrNull { it.number.toLong() == number }
    ?: throw WireFormatException("unknown service type")

private fun resetCode(number: Int) = ResetCode.entries.firstOrNull { it.number == number }
    ?: throw WireFormatException("unknown reset code")

/*
 * The envelope messages of spec §3.4.3, field numbers as in the SDK's `noise/envelope.py`
 * and `noise/framing.py`. Encoders omit zero/empty fields like proto3. Decoders skip unknown
 * fields; when a field appears twice the last value wins (oneof included), except repeated
 * headers, which accumulate. Byte fields compare by content.
 */

data class Header(val key: String = "", val value: String = "") {
    fun encode(): ByteArray = proto {
        if (key.isNotEmpty()) string(1, key)
        if (value.isNotEmpty()) string(2, value)
    }

    companion object {
        fun decode(data: ByteArray): Header {
            var key = ""
            var value = ""
            decodeFields(data) { field ->
                when (field) {
                    1 -> key = string("Header.key")
                    2 -> value = string("Header.value")
                    else -> skip()
                }
            }
            return Header(key, value)
        }
    }
}

/**
 * One HTTP-like frame on a multiplexed stream. [kind] is the proto's `oneof kind`; it is null
 * when the frame carries none, which receivers ignore.
 */
data class ServiceFrame(val streamId: Long = 0, val kind: Kind? = null) {
    sealed interface Kind {
        fun encode(): ByteArray
    }

    fun encode(): ByteArray = proto {
        if (streamId != 0L) int64(1, streamId)
        if (kind != null) bytes(kind.fieldNumber, kind.encode())
    }

    companion object {
        fun decode(data: ByteArray): ServiceFrame {
            var streamId = 0L
            var kind: Kind? = null
            decodeFields(data) { field ->
                when (field) {
                    1 -> streamId = varint("ServiceFrame.stream_id")
                    2 -> kind = ApplicationRequest.decode(bytes("ServiceFrame.request"))
                    3 -> kind = ApplicationResponse.decode(bytes("ServiceFrame.response"))
                    4 -> kind = BodyChunk.decode(bytes("ServiceFrame.body_chunk"))
                    5 -> kind = Reset.decode(bytes("ServiceFrame.reset"))
                    else -> skip()
                }
            }
            return ServiceFrame(streamId, kind)
        }
    }
}

private val ServiceFrame.Kind.fieldNumber
    get() = when (this) {
        is ApplicationRequest -> 2
        is ApplicationResponse -> 3
        is BodyChunk -> 4
        is Reset -> 5
    }

data class ApplicationRequest(
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val body: ByteArray = EMPTY_BYTES,
    val endBody: Boolean = false,
) : ServiceFrame.Kind {
    override fun encode(): ByteArray = proto {
        if (verb.isNotEmpty()) string(1, verb)
        if (path.isNotEmpty()) string(2, path)
        for (header in headers) bytes(3, header.encode())
        if (body.isNotEmpty()) bytes(4, body)
        if (endBody) bool(5, true)
    }

    override fun equals(other: Any?) = other is ApplicationRequest && verb == other.verb && path == other.path &&
        headers == other.headers && body.contentEquals(other.body) && endBody == other.endBody

    override fun hashCode() = Objects.hash(verb, path, headers, body.contentHashCode(), endBody)

    companion object {
        fun decode(data: ByteArray): ApplicationRequest {
            var verb = ""
            var path = ""
            val headers = mutableListOf<Header>()
            var body = EMPTY_BYTES
            var endBody = false
            decodeFields(data) { field ->
                when (field) {
                    1 -> verb = string("ApplicationRequest.verb")
                    2 -> path = string("ApplicationRequest.path")
                    3 -> headers += Header.decode(bytes("ApplicationRequest.headers"))
                    4 -> body = bytes("ApplicationRequest.body")
                    5 -> endBody = bool("ApplicationRequest.end_body")
                    else -> skip()
                }
            }
            return ApplicationRequest(verb, path, headers, body, endBody)
        }
    }
}

data class ApplicationResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = EMPTY_BYTES,
    val endBody: Boolean = false,
) : ServiceFrame.Kind {
    override fun encode(): ByteArray = proto {
        if (status != 0) int32(1, status)
        for (header in headers) bytes(2, header.encode())
        if (body.isNotEmpty()) bytes(3, body)
        if (endBody) bool(4, true)
    }

    override fun equals(other: Any?) = other is ApplicationResponse && status == other.status &&
        headers == other.headers && body.contentEquals(other.body) && endBody == other.endBody

    override fun hashCode() = Objects.hash(status, headers, body.contentHashCode(), endBody)

    companion object {
        fun decode(data: ByteArray): ApplicationResponse {
            var status = 0
            val headers = mutableListOf<Header>()
            var body = EMPTY_BYTES
            var endBody = false
            decodeFields(data) { field ->
                when (field) {
                    1 -> status = int32("ApplicationResponse.status")
                    2 -> headers += Header.decode(bytes("ApplicationResponse.headers"))
                    3 -> body = bytes("ApplicationResponse.body")
                    4 -> endBody = bool("ApplicationResponse.end_body")
                    else -> skip()
                }
            }
            return ApplicationResponse(status, headers, body, endBody)
        }
    }
}

data class BodyChunk(val data: ByteArray = EMPTY_BYTES, val endBody: Boolean = false) : ServiceFrame.Kind {
    override fun encode(): ByteArray = proto {
        if (data.isNotEmpty()) bytes(1, data)
        if (endBody) bool(2, true)
    }

    override fun equals(other: Any?) = other is BodyChunk && data.contentEquals(other.data) && endBody == other.endBody

    override fun hashCode() = 31 * data.contentHashCode() + endBody.hashCode()

    companion object {
        fun decode(data: ByteArray): BodyChunk {
            var chunk = EMPTY_BYTES
            var endBody = false
            decodeFields(data) { field ->
                when (field) {
                    1 -> chunk = bytes("BodyChunk.data")
                    2 -> endBody = bool("BodyChunk.end_body")
                    else -> skip()
                }
            }
            return BodyChunk(chunk, endBody)
        }
    }
}

data class Reset(val code: ResetCode = ResetCode.CODE_UNSPECIFIED, val reason: String = "") : ServiceFrame.Kind {
    override fun encode(): ByteArray = proto {
        if (code != ResetCode.CODE_UNSPECIFIED) int32(1, code.number)
        if (reason.isNotEmpty()) string(2, reason)
    }

    companion object {
        fun decode(data: ByteArray): Reset {
            var code = ResetCode.CODE_UNSPECIFIED
            var reason = ""
            decodeFields(data) { field ->
                when (field) {
                    1 -> code = resetCode(int32("Reset.code"))
                    2 -> reason = string("Reset.reason")
                    else -> skip()
                }
            }
            return Reset(code, reason)
        }
    }
}

/** Device → VM envelope around an encoded [ServiceFrame]. */
data class ServiceRequest(val service: ServiceType = ServiceType.SERVICE_DAEMON, val payload: ByteArray = EMPTY_BYTES) {
    fun encode(): ByteArray = proto {
        if (service != ServiceType.SERVICE_DAEMON) varint(1, service.number.toLong())
        if (payload.isNotEmpty()) bytes(2, payload)
    }

    override fun equals(other: Any?) =
        other is ServiceRequest && service == other.service && payload.contentEquals(other.payload)

    override fun hashCode() = 31 * service.hashCode() + payload.contentHashCode()

    companion object {
        fun decode(data: ByteArray): ServiceRequest {
            var service = ServiceType.SERVICE_DAEMON
            var payload = EMPTY_BYTES
            decodeFields(data) { field ->
                when (field) {
                    1 -> service = serviceType(varint("ServiceRequest.service"))
                    2 -> payload = bytes("ServiceRequest.payload")
                    else -> skip()
                }
            }
            return ServiceRequest(service, payload)
        }
    }
}

/** VM → device envelope around an encoded [ServiceFrame]. */
data class ServiceResponse(val payload: ByteArray = EMPTY_BYTES) {
    fun encode(): ByteArray = proto { if (payload.isNotEmpty()) bytes(1, payload) }

    override fun equals(other: Any?) = other is ServiceResponse && payload.contentEquals(other.payload)

    override fun hashCode() = payload.contentHashCode()

    companion object {
        fun decode(data: ByteArray): ServiceResponse {
            var payload = EMPTY_BYTES
            decodeFields(data) { field -> if (field == 1) payload = bytes("ServiceResponse.payload") else skip() }
            return ServiceResponse(payload)
        }
    }
}

/**
 * The plaintext of one Noise transport message: chunk [chunkIndex] of [totalChunks] of an
 * envelope, keyed by [chunkId] (see [NoiseFrames]). The uint32 fields use an Int's bits, so
 * values past Int.MAX_VALUE read as negative. An absent total decodes as 1.
 */
data class NoiseTransportFrame(
    val chunkId: Long = 0,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val payload: ByteArray = EMPTY_BYTES,
) {
    fun encode(): ByteArray = proto {
        if (chunkId != 0L) int64(1, chunkId)
        if (chunkIndex != 0) uint32(2, chunkIndex)
        if (totalChunks != 0) uint32(3, totalChunks)
        if (payload.isNotEmpty()) bytes(4, payload)
    }

    override fun equals(other: Any?) = other is NoiseTransportFrame && chunkId == other.chunkId &&
        chunkIndex == other.chunkIndex && totalChunks == other.totalChunks && payload.contentEquals(other.payload)

    override fun hashCode() = Objects.hash(chunkId, chunkIndex, totalChunks, payload.contentHashCode())

    companion object {
        fun decode(data: ByteArray): NoiseTransportFrame {
            var chunkId = 0L
            var chunkIndex = 0
            var totalChunks = 1
            var payload = EMPTY_BYTES
            decodeFields(data) { field ->
                when (field) {
                    1 -> chunkId = varint("NoiseTransportFrame.chunk_id")
                    2 -> chunkIndex = uint32("NoiseTransportFrame.chunk_index")
                    3 -> totalChunks = uint32("NoiseTransportFrame.total_chunks")
                    4 -> payload = bytes("NoiseTransportFrame.payload")
                    else -> skip()
                }
            }
            return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
        }
    }
}
