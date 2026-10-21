package dev.tvlauncher.muse.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtoTest {
    private val vectors = Vectors.extra.getJSONObject("protobuf")

    private fun vector(key: String) = hex(vectors.getString(key))

    /** Device → VM: ServiceFrame inside a daemon ServiceRequest. */
    private fun request(frame: ServiceFrame) = ServiceRequest(payload = frame.encode()).encode()

    private fun unwrapRequest(bytes: ByteArray) = ServiceFrame.decode(ServiceRequest.decode(bytes).payload)

    private fun unwrapResponse(bytes: ByteArray) = ServiceFrame.decode(ServiceResponse.decode(bytes).payload)

    private fun assertMalformed(message: String, decode: () -> Unit) {
        val error = assertThrows(message, WireFormatException::class.java) { decode() }
        assertEquals(message, error.message)
    }

    @Test
    fun `link-control request matches the SDK bytes`() {
        val link = vectors.getJSONObject("link_control_request")
        val frame = ServiceFrame(1, ApplicationRequest(verb = "POST", path = "/link-control"))
        assertEquals(link.getString("service_frame_hex"), frame.encode().hex())
        assertEquals(link.getString("service_request_hex"), request(frame).hex())
        val decoded = ServiceRequest.decode(hex(link.getString("service_request_hex")))
        assertEquals(ServiceType.SERVICE_DAEMON, decoded.service)
        assertEquals(frame, ServiceFrame.decode(decoded.payload))
    }

    @Test
    fun `link-register body chunk matches the SDK bytes`() {
        val message = vector("link_register_message_hex(u32le length + json)")
        val frame = ServiceFrame(1, BodyChunk(message))
        val wire = vectors.getString("link_register_body_chunk_service_request_hex")
        assertEquals(wire, request(frame).hex())
        assertEquals(frame, unwrapRequest(hex(wire)))
    }

    @Test
    fun `chat stream request matches the SDK bytes`() {
        val frame = ServiceFrame(
            2,
            ApplicationRequest(
                verb = "POST",
                path = "/chat/stream",
                headers = listOf(
                    Header("Content-Type", "application/json"),
                    Header("x-request-id", "11111111-1111-4111-8111-111111111111"),
                    Header("x-app-id", "musegadget"),
                ),
                body = bytes("""{"message": "hi", "output_modality": "text", "device_id": "homelink-abcdef"}"""),
                endBody = true,
            ),
        )
        val wire = vector("chat_stream_request_service_request_hex")
        assertEquals(wire.hex(), request(frame).hex())
        assertEquals(frame, unwrapRequest(wire))
    }

    @Test
    fun `cancel reset matches the SDK bytes`() {
        val frame = ServiceFrame(3, Reset(ResetCode.CANCELLED, "consumer closed"))
        val wire = vector("reset_stream3_service_request_hex")
        assertEquals(wire.hex(), request(frame).hex())
        assertEquals(frame, unwrapRequest(wire))
    }

    @Test
    fun `server responses match the SDK bytes`() {
        val ok = vector("server_response_200_ndjson_service_response_hex")
        val okFrame = ServiceFrame(1, ApplicationResponse(200, listOf(Header("Content-Type", "application/x-ndjson"))))
        assertEquals(okFrame, unwrapResponse(ok))
        assertEquals(ok.hex(), ServiceResponse(okFrame.encode()).encode().hex())

        val denied = vector("server_response_403_service_response_hex")
        val deniedFrame = ServiceFrame(1, ApplicationResponse(403, endBody = true))
        assertEquals(deniedFrame, unwrapResponse(denied))
        assertEquals(denied.hex(), ServiceResponse(deniedFrame.encode()).encode().hex())
    }

    @Test
    fun `negative int64 is a 10-byte two's complement varint`() {
        assertEquals("08fbffffffffffffffff012200", ServiceFrame(-5, BodyChunk()).encode().hex())
        assertEquals("08ffffffffffffffff7f", ServiceFrame(Long.MAX_VALUE).encode().hex())
        assertEquals("0880808080808080808001", ServiceFrame(Long.MIN_VALUE).encode().hex())
        for (id in listOf(-5L, -1L, 0L, 1L, 300L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(id, ServiceFrame.decode(ServiceFrame(id).encode()).streamId)
        }
    }

    @Test
    fun `negative int32 is sign-extended to 64 bits`() {
        assertEquals("08ffffffffffffffffff01", ApplicationResponse(status = -1).encode().hex())
        assertEquals(-1, ApplicationResponse.decode(hex("08ffffffffffffffffff01")).status)
        assertEquals(Int.MIN_VALUE, ApplicationResponse.decode(ApplicationResponse(Int.MIN_VALUE).encode()).status)
    }

    @Test
    fun `defaults are omitted, present sub-messages are not`() {
        assertEquals("", ServiceFrame().encode().hex())
        assertEquals("", ApplicationRequest().encode().hex())
        assertEquals("", ApplicationResponse().encode().hex())
        assertEquals("", Reset().encode().hex())
        assertEquals("", ServiceRequest().encode().hex())
        assertEquals("", ServiceResponse().encode().hex())
        assertEquals("1a00", ApplicationRequest(headers = listOf(Header())).encode().hex())
        assertEquals("0802120178", ServiceRequest(ServiceType.SERVICE_VAULT, bytes("x")).encode().hex())
        assertEquals(ServiceFrame(), ServiceFrame.decode(ByteArray(0)))
    }

    @Test
    fun `every message round trips`() {
        val headers = listOf(Header("k", "v"), Header("ключ", "値 😀"), Header())
        val frames = listOf(
            ServiceFrame(7, ApplicationRequest("GET", "/x?y=1", headers, byteArrayOf(0, -1), endBody = true)),
            ServiceFrame(8, ApplicationResponse(-404, headers, byteArrayOf(1, 2), endBody = true)),
            ServiceFrame(-9, BodyChunk(ByteArray(300) { it.toByte() }, endBody = true)),
            ServiceFrame(Long.MIN_VALUE, Reset(ResetCode.SERVICE_UNAVAILABLE, "gone")),
            ServiceFrame(10),
        )
        for (frame in frames) {
            assertEquals(frame, ServiceFrame.decode(frame.encode()))
            for (service in ServiceType.entries) {
                val request = ServiceRequest(service, frame.encode())
                assertEquals(request, ServiceRequest.decode(request.encode()))
            }
            val response = ServiceResponse(frame.encode())
            assertEquals(response, ServiceResponse.decode(response.encode()))
        }
        for (code in ResetCode.entries) assertEquals(Reset(code, "r"), Reset.decode(Reset(code, "r").encode()))
    }

    @Test
    fun `byte fields compare by content`() {
        assertEquals(BodyChunk(byteArrayOf(1, 2)), BodyChunk(byteArrayOf(1, 2)))
        assertEquals(BodyChunk(byteArrayOf(1, 2)).hashCode(), BodyChunk(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(BodyChunk(byteArrayOf(1, 2)), BodyChunk(byteArrayOf(1, 3)))
        assertNotEquals(ServiceResponse(byteArrayOf(1)), ServiceResponse(byteArrayOf(2)))
    }

    @Test
    fun `unknown fields of every wire type are skipped`() {
        // varint 6, fixed64 7, delimited 8 and fixed32 9 ahead of data = "hi", end_body = true
        val wire = hex("30013901020304050607084202aabb4d010203040a0268691001")
        assertEquals(BodyChunk(bytes("hi"), endBody = true), BodyChunk.decode(wire))
        assertEquals(BodyChunk(), BodyChunk.decode(hex("f8ffffff0f01"))) // highest legal field number
        val withExtra = ServiceFrame(4, Reset(ResetCode.TIMEOUT)).encode() + hex("3001")
        assertEquals(ServiceFrame(4, Reset(ResetCode.TIMEOUT)), ServiceFrame.decode(withExtra))
    }

    @Test
    fun `last oneof kind wins and repeated headers keep their order`() {
        val wire = ServiceFrame(1, BodyChunk(bytes("x"))).encode() + ServiceFrame(0, Reset(ResetCode.TIMEOUT)).encode()
        assertEquals(ServiceFrame(1, Reset(ResetCode.TIMEOUT)), ServiceFrame.decode(wire))
        val headers = (1..5).map { Header("h$it", "v$it") }
        assertEquals(headers, ApplicationResponse.decode(ApplicationResponse(headers = headers).encode()).headers)
    }

    @Test
    fun `malformed input fails like the SDK`() {
        val cases = mapOf(
            "0a" to "truncated varint",
            "0a05aa" to "truncated delimited field",
            "0affffffffffffffffff01" to "truncated delimited field",
            "ffffffffffffffffffff01" to "malformed varint",
            "08ffffffffffffffffff02" to "BodyChunk.data wrong wire type",
            "0d01020304" to "BodyChunk.data wrong wire type",
            "0b" to "invalid wire type",
            "0f" to "invalid wire type",
            "00" to "invalid field number",
            "c0b80901" to "invalid field number", // field 19336: reserved range
            "39010203" to "truncated fixed64 field",
            "4d0102" to "truncated fixed32 field",
        )
        for ((wire, message) in cases) assertMalformed(message) { BodyChunk.decode(hex(wire)) }
        assertMalformed("unknown reset code") { Reset.decode(hex("0807")) }
        assertMalformed("invalid utf-8 string") { Reset.decode(hex("1201ff")) }
        assertMalformed("int32 value out of range") { ApplicationResponse.decode(hex("08808080808020")) }
        assertMalformed("unknown service type") { ServiceRequest.decode(hex("0809")) }
        assertMalformed("uint32 value out of range") { NoiseTransportFrame.decode(hex("108080808010")) }
        assertMalformed("ServiceFrame.response wrong wire type") { ServiceFrame.decode(hex("1801")) }
    }

    @Test
    fun `writer handles multi-byte keys and refuses invalid field numbers`() {
        assertEquals("800101", proto { varint(16, 1) }.hex())
        assertEquals("f8ffffff0f01", proto { varint((1 shl 29) - 1, 1) }.hex())
        for (field in listOf(0, 19_000, 19_999, 1 shl 29)) {
            assertThrows(IllegalArgumentException::class.java) { proto { varint(field, 1) } }
        }
    }

    @Test
    fun `uint32 fields hold the full range in an Int's bits`() {
        val frame = NoiseTransportFrame.decode(hex("18ffffffff0f"))
        assertEquals(4294967295u, frame.totalChunks.toUInt())
        assertEquals("18ffffffff0f", frame.encode().hex())
    }
}
