package dev.tvlauncher.muse.wire

import java.io.ByteArrayOutputStream

/**
 * Chunk framing for BLE pairing messages, which outgrow one ATT packet (spec §2.3, SDK
 * `ble_framing.py`). Both directions use the same frame: `0xFE`, chunk index, total chunks,
 * then a fragment. A packet that doesn't start with `0xFE` is a complete, unchunked message.
 */
object BleFraming {
    const val CHUNK_MAGIC = 0xFE
    const val HEADER_BYTES = 3
    /** Notifications are capped here however large the negotiated MTU is. */
    const val MAX_PACKET_BYTES = 160
    const val MAX_CHUNKS = 255
    const val MAX_MESSAGE_BYTES = 8192
    const val DEFAULT_ATT_MTU = 23
    /** Pause between the notifications of one message (the SDK's `CHUNK_STAGGER_S`). */
    const val CHUNK_STAGGER_MS = 50L

    /**
     * Splits [data] into framed packets that each fit one notification at [mtu]: 17 payload
     * bytes at MTU 23, 157 from MTU 163 up. An MTU of 3 or less counts as 23. An empty message
     * is still one (empty) chunk; one needing more than [MAX_CHUNKS] is refused.
     */
    fun encodeChunks(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
        val usable = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES) - HEADER_BYTES
        // MTUs 4..6 leave no room (the SDK would send an empty message or raise there).
        require(usable > 0) { "ATT MTU $mtu leaves no room for a fragment" }
        val total = if (data.isEmpty()) 1 else (data.size - 1) / usable + 1
        require(total <= MAX_CHUNKS) { "message needs $total chunks (max $MAX_CHUNKS)" }
        return List(total) { index ->
            val start = index * usable
            val end = minOf(start + usable, data.size)
            ByteArray(HEADER_BYTES + end - start).also {
                it[0] = CHUNK_MAGIC.toByte()
                it[1] = index.toByte()
                it[2] = total.toByte()
                data.copyInto(it, HEADER_BYTES, start, end)
            }
        }
    }
}

/**
 * Reassembles chunked writes (the SDK's `ChunkAssembler`), strictly in order: index 0 or a
 * change in the total starts a new message, while an out-of-order chunk or an oversize message
 * discards what was collected. Like upstream, bad input is dropped rather than thrown.
 */
class BleChunkAssembler(private val maxBytes: Int = BleFraming.MAX_MESSAGE_BYTES) {
    private val buffer = ByteArrayOutputStream()
    private var total = 0
    private var next = 0

    fun reset() {
        buffer.reset()
        total = 0
        next = 0
    }

    /**
     * Adds one write; returns a complete message once one is available. An unchunked packet
     * comes straight back without disturbing a chunked message in progress.
     */
    fun feed(packet: ByteArray): ByteArray? {
        if (packet.size < BleFraming.HEADER_BYTES || packet[0] != BleFraming.CHUNK_MAGIC.toByte()) return packet
        val index = packet[1].toInt() and 0xFF
        val count = packet[2].toInt() and 0xFF
        if (count == 0) {
            reset()
            return null
        }
        if (index == 0 || count != total) {
            reset()
            total = count
        }
        val fragment = packet.size - BleFraming.HEADER_BYTES
        if (index != next || index >= total || buffer.size() + fragment > maxBytes) {
            reset()
            return null
        }
        buffer.write(packet, BleFraming.HEADER_BYTES, fragment)
        next = index + 1
        if (next < total) return null
        return buffer.toByteArray().also { reset() }
    }
}
