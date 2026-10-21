package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.Reset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class ByteStreamTest {
    private var closed: ByteStream? = null
    private fun stream(maxBytes: Int = 1024) = ByteStream(7, maxBytes) { closed = it }

    private fun ByteStream.read(position: Long, size: Int, timeoutMs: Long = 1000): ByteArray? {
        val buffer = ByteArray(size)
        val n = readAt(position, buffer, 0, size, timeoutMs)
        return if (n < 0) null else buffer.copyOf(n)
    }

    @Test
    fun `status arrives with the response headers`() {
        val s = stream()
        s.onFrame(ApplicationResponse(status = 200, body = byteArrayOf(1, 2)))
        assertEquals(200, s.awaitStatus(1000))
        assertArrayEquals(byteArrayOf(1, 2), s.read(0, 10))
    }

    @Test
    fun `reads see body chunks as they arrive, then the end`() {
        val s = stream()
        s.onFrame(ApplicationResponse(status = 200))
        s.onFrame(BodyChunk(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(2, 3), s.read(1, 10))
        assertNull(s.length)
        s.onFrame(BodyChunk(byteArrayOf(4), endBody = true))
        assertArrayEquals(byteArrayOf(4), s.read(3, 10))
        assertNull(s.read(4, 10)) // past the end
        assertEquals(4L, s.length)
    }

    @Test
    fun `a read waits for bytes that haven't arrived yet`() {
        val s = stream()
        s.onFrame(ApplicationResponse(status = 200))
        val late = thread { Thread.sleep(100); s.onFrame(BodyChunk(byteArrayOf(9))) }
        assertArrayEquals(byteArrayOf(9), s.read(0, 4))
        late.join()
    }

    @Test
    fun `a read gives up after its timeout`() {
        val s = stream()
        s.onFrame(ApplicationResponse(status = 200))
        assertThrows(SocketTimeoutException::class.java) { s.read(0, 4, timeoutMs = 50) }
    }

    @Test
    fun `a reset fails waiting reads and the status`() {
        val s = stream()
        s.onFrame(Reset(reason = "gone"))
        assertThrows(IOException::class.java) { s.awaitStatus(1000) }
        assertThrows(IOException::class.java) { s.read(0, 4) }
    }

    @Test
    fun `too much audio fails the stream rather than eating memory`() {
        val s = stream(maxBytes = 4)
        s.onFrame(ApplicationResponse(status = 200))
        assertTrue(s.onFrame(BodyChunk(ByteArray(3))))
        assertEquals(false, s.onFrame(BodyChunk(ByteArray(3))))
        assertThrows(IOException::class.java) { s.read(0, 4) }
    }

    @Test
    fun `closing tells the session once and wakes readers`() {
        val s = stream()
        s.onFrame(ApplicationResponse(status = 200))
        val reader = thread { assertThrows(IOException::class.java) { s.read(0, 4, timeoutMs = 5000) } }
        Thread.sleep(50)
        s.close()
        reader.join(2000)
        assertEquals(s, closed)
    }
}
