package dev.tvlauncher.muse.wire

import java.security.SecureRandom

/**
 * NoiseTransportFrame chunking (spec §3.4.3, SDK `noise/framing.py`). A Noise message holds
 * at most 65535 bytes, so each encoded ServiceRequest/ServiceResponse is cut into frames that
 * share a chunk id, and each frame is encrypted into its own WebSocket message.
 */
object NoiseFrames {
    const val MAX_CHUNK_PAYLOAD = 65489
    const val MAX_TOTAL_CHUNKS = 256
    const val MAX_PENDING_ASSEMBLIES = 16
    const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
    const val ASSEMBLY_TTL_MS = 60_000L

    private val random = SecureRandom()

    /** A random id over the whole int64 range, so about half are negative (10-byte varints). */
    fun randomChunkId(): Long = random.nextLong()

    /**
     * The encoded frames for [payload], all tagged [chunkId]: random by default, injectable so
     * tests and vectors can pin it. An empty payload still takes one frame; one needing more
     * than [MAX_TOTAL_CHUNKS] frames is refused.
     */
    fun encode(payload: ByteArray, chunkId: Long = randomChunkId()): List<ByteArray> {
        val total = if (payload.isEmpty()) 1 else (payload.size - 1) / MAX_CHUNK_PAYLOAD + 1
        require(total <= MAX_TOTAL_CHUNKS) {
            "payload too large for noise framing (${payload.size} bytes, $total chunks > $MAX_TOTAL_CHUNKS)"
        }
        return List(total) { index ->
            val start = index * MAX_CHUNK_PAYLOAD
            val chunk = payload.copyOfRange(start, minOf(start + MAX_CHUNK_PAYLOAD, payload.size))
            NoiseTransportFrame(chunkId, index, total, chunk).encode()
        }
    }
}

/**
 * Reassembles decrypted NoiseTransportFrames by chunk id (the SDK's `NoiseFrameDecoder`).
 * Chunks may arrive in any order and different ids may interleave. A malformed frame, a
 * duplicate index, an inconsistent total or a breached limit throws [WireFormatException] and
 * poisons the decoder for good, as upstream: the session has to be rebuilt.
 *
 * Unfinished assemblies expire [NoiseFrames.ASSEMBLY_TTL_MS] after their first chunk. [clock]
 * is monotonic milliseconds rather than the SDK's wall time, which jumps when a TV sets its
 * clock after boot. Not thread-safe: feed it from the one reader thread.
 */
class NoiseFrameDecoder(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAssemblyBytes: Int = NoiseFrames.MAX_ASSEMBLY_BYTES,
) {
    private class Assembly(val total: Int, val createdAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var received = 0
        var bytes = 0L
    }

    private val pending = HashMap<Long, Assembly>()
    private var poisoned = false

    /** Adds one frame; returns the whole payload once its last chunk is in, else null. */
    fun decode(frameBytes: ByteArray): ByteArray? {
        check(!poisoned) { "NoiseFrameDecoder: poisoned after prior failure" }
        try {
            return add(NoiseTransportFrame.decode(frameBytes))
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    private fun add(frame: NoiseTransportFrame): ByteArray? {
        val (id, index, total, payload) = frame
        if (total !in 1..NoiseFrames.MAX_TOTAL_CHUNKS) fail("invalid totalChunks: ${total.toUInt()}")
        if (index !in 0 until total) fail("chunkIndex ${index.toUInt()} out of range [0, $total)")
        if (payload.size > NoiseFrames.MAX_CHUNK_PAYLOAD) {
            fail("payload too large for noise frame (${payload.size} bytes)")
        }

        val now = clock()
        pending.values.removeAll { now - it.createdAt > NoiseFrames.ASSEMBLY_TTL_MS }
        val assembly = pending.getOrPut(id) {
            if (pending.size >= NoiseFrames.MAX_PENDING_ASSEMBLIES) fail("too many pending noise frame assemblies")
            Assembly(total, now)
        }
        if (assembly.total != total) {
            drop(id, "inconsistent totalChunks for chunkId: expected ${assembly.total}, got $total")
        }
        if (assembly.chunks[index] != null) drop(id, "duplicate chunkIndex $index")
        assembly.bytes += payload.size
        if (assembly.bytes > maxAssemblyBytes) drop(id, "assembly exceeded byte budget")
        assembly.chunks[index] = payload
        if (++assembly.received < total) return null

        pending.remove(id)
        if (total == 1) return payload
        val whole = ByteArray(assembly.bytes.toInt())
        var offset = 0
        for (chunk in assembly.chunks) {
            chunk!!.copyInto(whole, offset)
            offset += chunk.size
        }
        return whole
    }

    private fun drop(id: Long, message: String): Nothing {
        pending.remove(id)
        fail(message)
    }

    private fun fail(message: String): Nothing = throw WireFormatException(message)
}
