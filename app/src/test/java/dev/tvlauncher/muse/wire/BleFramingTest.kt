package dev.tvlauncher.muse.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramingTest {
    private val vector = Vectors.extra.getJSONObject("ble_framing")
    private val deviceInfo = bytes(vector.getString("message_utf8"))

    private fun List<ByteArray>.joinedPayloads() =
        fold(ByteArray(0)) { acc, packet -> acc + packet.copyOfRange(BleFraming.HEADER_BYTES, packet.size) }

    private fun BleChunkAssembler.feedAll(packets: List<ByteArray>) = packets.map { feed(it) }

    @Test
    fun `get_device_info matches the SDK packets at MTU 23 and 185`() {
        assertEquals(23, vector.getInt("mtu"))
        assertEquals(
            vector.getJSONArray("packets_hex").strings(),
            BleFraming.encodeChunks(deviceInfo, 23).map { it.hex() },
        )
        assertEquals(
            vector.getJSONArray("mtu_185_packets_hex").strings(),
            BleFraming.encodeChunks(deviceInfo, 185).map { it.hex() },
        )
    }

    @Test
    fun `vector packets reassemble to the message`() {
        val results = BleChunkAssembler().feedAll(vector.getJSONArray("packets_hex").strings().map(::hex))
        assertNull(results[0])
        assertArrayEquals(deviceInfo, results[1])
    }

    @Test
    fun `chunk payload follows the MTU`() {
        val data = bytes("x".repeat(1000))
        for ((mtu, perChunk) in listOf(23 to 17, 185 to 157, 256 to 157, 3 to 17)) {
            val chunks = BleFraming.encodeChunks(data, mtu)
            assertTrue("MTU $mtu", chunks.dropLast(1).all { it.size - 3 == perChunk })
            assertArrayEquals(data, chunks.joinedPayloads())
        }
    }

    @Test
    fun `chunk headers carry magic, index and total`() {
        val chunks = BleFraming.encodeChunks(bytes("a".repeat(40)), 23)
        assertEquals(listOf("fe0003", "fe0103", "fe0203"), chunks.map { it.copyOf(3).hex() })
    }

    @Test
    fun `empty message is one chunk`() {
        assertEquals(listOf("fe0001"), BleFraming.encodeChunks(ByteArray(0)).map { it.hex() })
    }

    @Test
    fun `255 chunks is the limit`() {
        val max = BleFraming.encodeChunks(ByteArray(17 * 255), 23)
        assertEquals(255, max.size)
        assertEquals("fefeff", max.last().copyOf(3).hex())
        assertThrows(IllegalArgumentException::class.java) { BleFraming.encodeChunks(ByteArray(17 * 255 + 1), 23) }
    }

    @Test
    fun `MTUs with no room for a fragment are refused`() {
        for (mtu in 4..6) {
            assertThrows(IllegalArgumentException::class.java) { BleFraming.encodeChunks(byteArrayOf(1), mtu) }
        }
        assertEquals(3, BleFraming.encodeChunks(byteArrayOf(1, 2, 3), 7).size)
    }

    @Test
    fun `round trip`() {
        val message = ByteArray(2560) { it.toByte() }
        val results = BleChunkAssembler().feedAll(BleFraming.encodeChunks(message, 185))
        assertArrayEquals(message, results.last())
        assertTrue(results.dropLast(1).all { it == null })
    }

    @Test
    fun `indexes and totals above 127 survive the round trip`() {
        val message = ByteArray(17 * 255) { (it * 7).toByte() }
        assertArrayEquals(message, BleChunkAssembler().feedAll(BleFraming.encodeChunks(message, 23)).last())
    }

    @Test
    fun `plain write passes through`() {
        val plain = bytes("""{"action":"get_device_info"}""")
        assertArrayEquals(plain, BleChunkAssembler().feed(plain))
    }

    @Test
    fun `packets shorter than a header are unchunked messages`() {
        val assembler = BleChunkAssembler()
        assertArrayEquals(hex("fe00"), assembler.feed(hex("fe00")))
        assertArrayEquals(ByteArray(0), assembler.feed(ByteArray(0)))
    }

    @Test
    fun `an unchunked write leaves a message in progress alone`() {
        val message = bytes("m".repeat(30))
        val chunks = BleFraming.encodeChunks(message, 23)
        val assembler = BleChunkAssembler()
        assertNull(assembler.feed(chunks[0]))
        assertArrayEquals(bytes("plain"), assembler.feed(bytes("plain")))
        assertArrayEquals(message, assembler.feed(chunks[1]))
    }

    @Test
    fun `out of order chunk discards the message`() {
        val chunks = BleFraming.encodeChunks(bytes("y".repeat(60)), 23)
        val results = BleChunkAssembler().feedAll(listOf(chunks[0], chunks[2], chunks[1], chunks[3]))
        assertEquals(listOf(null, null, null, null), results)
    }

    @Test
    fun `index zero restarts the message`() {
        val old = BleFraming.encodeChunks(bytes("o".repeat(60)), 23)
        val new = BleFraming.encodeChunks(bytes("n".repeat(30)), 23)
        val assembler = BleChunkAssembler()
        assembler.feed(old[0])
        assembler.feed(old[1])
        assertArrayEquals(bytes("n".repeat(30)), assembler.feedAll(new).last())
    }

    @Test
    fun `changed total mid message discards it`() {
        val assembler = BleChunkAssembler()
        assembler.feed(hex("fe0003") + bytes("abc"))
        assertNull(assembler.feed(hex("fe0102") + bytes("def")))
        assertNull(assembler.feed(hex("fe0203") + bytes("ghi")))
    }

    @Test
    fun `zero total is ignored`() {
        assertNull(BleChunkAssembler().feed(hex("fe0000") + bytes("abc")))
    }

    @Test
    fun `oversize message is discarded`() {
        val assembler = BleChunkAssembler(maxBytes = 20)
        assertEquals(listOf(null, null), assembler.feedAll(BleFraming.encodeChunks(bytes("z".repeat(30)), 23)))
    }

    @Test
    fun `reset drops a partial message`() {
        val chunks = BleFraming.encodeChunks(ByteArray(30), 23)
        val assembler = BleChunkAssembler()
        assembler.feed(chunks[0])
        assembler.reset()
        assertNull(assembler.feed(chunks[1]))
        assertArrayEquals(ByteArray(30), assembler.feedAll(chunks).last())
    }
}
