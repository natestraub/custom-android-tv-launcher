package dev.tvlauncher.muse.wire

import dev.tvlauncher.muse.wire.NoiseFrames.MAX_CHUNK_PAYLOAD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFramesTest {
    private val vector = Vectors.extra.getJSONObject("noise_transport_frame")

    private fun frame(id: Long, index: Int, total: Int, payload: String = "x") =
        NoiseTransportFrame(id, index, total, bytes(payload)).encode()

    private fun assertFails(message: String, decoder: NoiseFrameDecoder = NoiseFrameDecoder(), frame: ByteArray) {
        val error = assertThrows(message, WireFormatException::class.java) { decoder.decode(frame) }
        assertEquals(message, error.message)
    }

    @Test
    fun `frame with a fixed chunk id matches the SDK bytes`() {
        val payload = hex(vector.getString("payload_hex"))
        val chunkId = vector.getLong("chunk_id")
        val wire = vector.getString("frame_hex")
        assertEquals(listOf(wire), NoiseFrames.encode(payload, chunkId).map { it.hex() })
        assertEquals(NoiseTransportFrame(chunkId, 0, 1, payload), NoiseTransportFrame.decode(hex(wire)))
        assertArrayEquals(payload, NoiseFrameDecoder().decode(hex(wire)))
    }

    @Test
    fun `negative chunk id matches the SDK bytes`() {
        val chunkId = vector.getLong("negative_chunk_id")
        val wire = vector.getString("negative_frame_hex")
        assertEquals(-2L, chunkId)
        assertEquals(listOf(wire), NoiseFrames.encode(byteArrayOf(1), chunkId).map { it.hex() })
        assertEquals(NoiseTransportFrame(chunkId, 0, 1, byteArrayOf(1)), NoiseTransportFrame.decode(hex(wire)))
        assertArrayEquals(byteArrayOf(1), NoiseFrameDecoder().decode(hex(wire)))
    }

    @Test
    fun `absent total decodes as one`() {
        assertEquals(NoiseTransportFrame(chunkId = 7, totalChunks = 1), NoiseTransportFrame.decode(hex("0807")))
    }

    @Test
    fun `empty payload is one frame without a payload field`() {
        assertEquals(listOf("08071801"), NoiseFrames.encode(ByteArray(0), 7).map { it.hex() })
        assertArrayEquals(ByteArray(0), NoiseFrameDecoder().decode(hex("08071801")))
    }

    @Test
    fun `payloads split every 65489 bytes`() {
        val frames = NoiseFrames.encode(bytes("a".repeat(MAX_CHUNK_PAYLOAD * 2 + 1)), -1)
        assertEquals(listOf(65506, 65508, 18), frames.map { it.size })
        assertEquals("08ffffffffffffffffff011001180322d1ff0361", frames[1].copyOf(20).hex())
        assertEquals("08ffffffffffffffffff0110021803220161", frames[2].hex())
        assertEquals(1, NoiseFrames.encode(ByteArray(MAX_CHUNK_PAYLOAD)).size)
        assertEquals(2, NoiseFrames.encode(ByteArray(MAX_CHUNK_PAYLOAD + 1)).size)
    }

    @Test
    fun `256 chunks is the most one payload may take`() {
        val payload = ByteArray(MAX_CHUNK_PAYLOAD * 256) { (it / MAX_CHUNK_PAYLOAD).toByte() }
        val decoder = NoiseFrameDecoder()
        val results = NoiseFrames.encode(payload, 3).map { decoder.decode(it) }
        assertEquals(256, results.size)
        assertArrayEquals(payload, results.last())
        assertThrows(IllegalArgumentException::class.java) { NoiseFrames.encode(ByteArray(payload.size + 1)) }
    }

    @Test
    fun `chunk ids are random per payload and shared by its frames`() {
        val frames = NoiseFrames.encode(ByteArray(MAX_CHUNK_PAYLOAD + 1)).map { NoiseTransportFrame.decode(it) }
        assertEquals(frames[0].chunkId, frames[1].chunkId)
        val ids = List(8) { NoiseTransportFrame.decode(NoiseFrames.encode(byteArrayOf(1)).single()).chunkId }
        assertTrue(ids.toSet().size > 1)
    }

    @Test
    fun `reassembles out of order and interleaved`() {
        val upload = ByteArray(180_000) { it.toByte() } // the SDK's multi-frame upload test size
        val other = ByteArray(70_000) { (it * 3).toByte() }
        val a = NoiseFrames.encode(upload, 1)
        val b = NoiseFrames.encode(other, -1)
        val decoder = NoiseFrameDecoder()
        assertNull(decoder.decode(a[2]))
        assertNull(decoder.decode(b[1]))
        assertNull(decoder.decode(a[0]))
        assertArrayEquals(other, decoder.decode(b[0]))
        assertArrayEquals(upload, decoder.decode(a[1]))
    }

    @Test
    fun `a finished chunk id can be used again`() {
        val decoder = NoiseFrameDecoder()
        repeat(2) {
            assertNull(decoder.decode(frame(9, 0, 2, "a")))
            assertArrayEquals(bytes("ab"), decoder.decode(frame(9, 1, 2, "b")))
        }
    }

    @Test
    fun `duplicate index poisons the decoder`() {
        val decoder = NoiseFrameDecoder()
        decoder.decode(frame(5, 0, 2))
        assertFails("duplicate chunkIndex 0", decoder, frame(5, 0, 2))
        val error = assertThrows(IllegalStateException::class.java) { decoder.decode(frame(5, 1, 2)) }
        assertEquals("NoiseFrameDecoder: poisoned after prior failure", error.message)
    }

    @Test
    fun `inconsistent total is an error`() {
        val decoder = NoiseFrameDecoder()
        decoder.decode(frame(5, 0, 2))
        assertFails("inconsistent totalChunks for chunkId: expected 2, got 3", decoder, frame(5, 1, 3))
    }

    @Test
    fun `frame headers are checked`() {
        assertFails("invalid totalChunks: 0", frame = hex("1800"))
        assertFails("invalid totalChunks: 257", frame = frame(1, 0, 257))
        assertFails("invalid totalChunks: 4294967295", frame = frame(1, 0, -1))
        assertFails("chunkIndex 1 out of range [0, 1)", frame = frame(1, 1, 1))
        assertFails("chunkIndex 4294967295 out of range [0, 2)", frame = frame(1, -1, 2))
        val oversized = NoiseTransportFrame(1, 0, 1, ByteArray(MAX_CHUNK_PAYLOAD + 1)).encode()
        assertFails("payload too large for noise frame (65490 bytes)", frame = oversized)
    }

    @Test
    fun `at most 16 assemblies may be pending`() {
        val decoder = NoiseFrameDecoder()
        repeat(16) { assertNull(decoder.decode(frame(it.toLong(), 0, 2))) }
        assertFails("too many pending noise frame assemblies", decoder, frame(16, 0, 2))
    }

    @Test
    fun `assemblies expire 60 seconds after their first chunk`() {
        var now = 1_000L
        val decoder = NoiseFrameDecoder(clock = { now })
        decoder.decode(frame(1, 0, 2, "a"))
        now += 60_000
        assertArrayEquals(bytes("ab"), decoder.decode(frame(1, 1, 2, "b")))

        repeat(16) { decoder.decode(frame(100L + it, 0, 2)) }
        now += 60_001
        assertNull(decoder.decode(frame(100, 1, 2))) // its first half is gone: a fresh assembly
        repeat(15) { assertNull(decoder.decode(frame(200L + it, 0, 2))) }
    }

    @Test
    fun `assembly byte budget is enforced`() {
        val decoder = NoiseFrameDecoder(maxAssemblyBytes = 4)
        decoder.decode(frame(1, 0, 2, "abc"))
        assertFails("assembly exceeded byte budget", decoder, frame(1, 1, 2, "de"))
    }

    @Test
    fun `malformed frame bytes poison the decoder`() {
        val decoder = NoiseFrameDecoder()
        assertFails("truncated varint", decoder, hex("08"))
        assertThrows(IllegalStateException::class.java) { decoder.decode(NoiseFrames.encode(byteArrayOf(1)).single()) }
    }
}
