package dev.tvlauncher.muse.wire

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageCodecTest {
    private fun u32le(n: Long) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun prefixed(json: String) = bytes(json).let { u32le(it.size.toLong()) + it }

    private fun List<JSONObject>.maps() = map { it.toPlainMap() }

    /** Feeds [chunks] then ends the stream, collecting every object the decoder yields. */
    private fun NdjsonDecoder.decodeAll(vararg chunks: ByteArray): List<Map<String, Any?>> {
        val out = mutableListOf<JSONObject>()
        for (chunk in chunks) feed(chunk, out::add)
        finish(out::add)
        return out.maps()
    }

    @Test
    fun `link register matches the SDK bytes`() {
        val register = linkedMapOf(
            "type" to "req",
            "id" to "00000000-0000-4000-8000-000000000000",
            "method" to "link.register",
            "params" to linkedMapOf(
                "node_id" to "homelink-abcdef",
                "display_name" to "tv",
                "platform" to "linux",
                "version" to "0.1.0",
                "device_family" to "homehub",
                "model_id" to "linux",
                "is_wakeup_supported" to false,
                "commands_v2" to emptyMap<String, Any>(),
            ),
        )
        val wire = Vectors.extra.getJSONObject("protobuf").getString("link_register_message_hex(u32le length + json)")
        assertEquals(wire, MessageCodec.encode(register).hex())
        val decoded = MessageDecoder().feed(hex(wire)).single()
        assertEquals("link.register", decoded.getString("method"))
        assertEquals(false, decoded.getJSONObject("params").getBoolean("is_wakeup_supported"))
    }

    @Test
    fun `length prefix is little-endian`() {
        assertEquals("070000007b2261223a317d", MessageCodec.encode(mapOf("a" to 1)).hex())
        assertEquals("2c010000", MessageCodec.encode(mapOf("k" to "x".repeat(292))).copyOf(4).hex())
    }

    @Test
    fun `JSON is written like Python's compact json dumps`() {
        val value = linkedMapOf(
            "s" to "café/" + Char(0x2028) + "\u007f\u0001😀\"\\\b\u000c\n\r\t",
            "n" to listOf(1, -2, 1.5, true, false, null),
            "e" to emptyMap<String, Any>(),
        )
        // ensure_ascii: anything outside printable ASCII becomes a lowercase \uXXXX escape, astral
        // characters as a surrogate pair. The expected text is assembled from `u` to keep it ASCII.
        val u = "\\u"
        assertEquals(
            """{"s":"caf${u}00e9/${u}2028${u}007f${u}0001${u}d83d${u}de00\"\\\b\f\n\r\t","n":[1,-2,1.5,true,false,null],"e":{}}""",
            MessageCodec.compactJson(value),
        )
    }

    @Test
    fun `org json values are written too`() {
        val nested = JSONObject().put("a", JSONArray().put(1).put(JSONObject.NULL).put(JSONObject().put("b", "c/d")))
        assertEquals("""{"a":[1,null,{"b":"c/d"}]}""", MessageCodec.compactJson(nested))
        assertEquals(prefixed("""{"a":[1,null,{"b":"c/d"}]}""").hex(), MessageCodec.encode(nested).hex())
        assertEquals("""[["x"],{}]""", MessageCodec.compactJson(arrayOf(listOf("x"), JSONObject())))
    }

    @Test
    fun `values JSON cannot carry are refused`() {
        for (bad in listOf(Double.NaN, Float.POSITIVE_INFINITY, Any(), mapOf(1 to "x"))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { MessageCodec.compactJson(listOf(bad)) }
        }
    }

    @Test
    fun `decoder handles split and batched messages`() {
        val data = MessageCodec.encode(mapOf("a" to 1)) + MessageCodec.encode(mapOf("b" to 2)) + u32le(0)
        val decoder = MessageDecoder()
        assertEquals(emptyList<JSONObject>(), decoder.feed(data.copyOfRange(0, 5)))
        assertEquals(listOf(mapOf("a" to 1), mapOf("b" to 2)), decoder.feed(data.copyOfRange(5, data.size)).maps())
    }

    @Test
    fun `decoder rejects oversize messages`() {
        val error = assertThrows(WireFormatException::class.java) { MessageDecoder().feed(u32le(1L shl 30)) }
        assertEquals("inbound message too large: 1073741824", error.message)
        assertThrows(WireFormatException::class.java) { MessageDecoder().feed(u32le(0xFFFF_FFFFL)) }
    }

    @Test
    fun `size limit allows exactly the maximum`() {
        val message = prefixed("""{"a":"xxxxxxxxxx"}""")
        assertEquals(1, MessageDecoder(maxMessageBytes = message.size - 4).feed(message).size)
        val tooSmall = MessageDecoder(maxMessageBytes = message.size - 5)
        assertThrows(WireFormatException::class.java) { tooSmall.feed(message) }
    }

    @Test
    fun `decoder drops messages that are not UTF-8`() {
        val garbage = bytes("{\"a\":\"") + byteArrayOf(0xFF.toByte()) + bytes("\"}")
        val data = u32le(garbage.size.toLong()) + garbage + MessageCodec.encode(mapOf("b" to 2))
        assertEquals(listOf(mapOf("b" to 2)), MessageDecoder().feed(data).maps())
    }

    @Test
    fun `decoder drops malformed JSON and non-objects`() {
        val lines = listOf("[1]", "\"x\"", "3", "{\"a\":", "{\"a\":1} trailing", "", "{\"ok\":true}")
        val data = lines.fold(ByteArray(0)) { acc, json -> acc + prefixed(json) }
        assertEquals(listOf(mapOf("ok" to true)), MessageDecoder().feed(data).maps())
    }

    @Test
    fun `decoder accepts any byte split`() {
        val data = MessageCodec.encode(mapOf("a" to "é😀")) + u32le(0) +
            MessageCodec.encode(mapOf("b" to listOf(1, 2)))
        val expected = listOf(mapOf("a" to "é😀"), mapOf("b" to listOf(1, 2)))
        for (cut in 0..data.size) {
            val decoder = MessageDecoder()
            val messages = decoder.feed(data.copyOfRange(0, cut)) + decoder.feed(data.copyOfRange(cut, data.size))
            assertEquals("cut at $cut", expected, messages.maps())
        }
        val decoder = MessageDecoder()
        assertEquals(expected, data.flatMap { decoder.feed(byteArrayOf(it)) }.maps())
    }

    @Test
    fun `ndjson events survive a split mid UTF-8 and junk lines are dropped`() {
        // The SDK's subscription test: an ack, a blank and a malformed line, then an event
        // whose "café" is split inside the two-byte "é".
        val event = bytes("""{"type": "event", "seq": 1, "event": "delta.text_append", "payload": {"text": "café"}}""")
        val split = event.indexOf(0xC3.toByte()) + 1
        val decoder = NdjsonDecoder()
        val out = mutableListOf<JSONObject>()
        decoder.feed(bytes("{\"type\":\"ack\"}\n\nmalformed\n") + event.copyOfRange(0, split), out::add)
        decoder.feed(event.copyOfRange(split, event.size) + bytes("\n"), out::add)
        assertEquals(listOf("ack", "event"), out.map { it.getString("type") })
        assertEquals("café", out[1].getJSONObject("payload").getString("text"))

        val last = """{"type":"event","seq":2,"event":"delta.message_done","payload":{"message_id":"reply-1"}}"""
        decoder.feed(bytes(last), out::add)
        assertEquals(2, out.size) // no newline yet
        decoder.finish(out::add)
        assertEquals(JSONObject(last).toPlainMap(), out.last().toPlainMap())
    }

    @Test
    fun `ndjson accepts any byte split`() {
        val body = bytes("{\"t\":\"é\"}\r\n\n{\"t\":\"😀\"}\n \n{\"t\":\"end\"}")
        val expected = listOf(mapOf("t" to "é"), mapOf("t" to "😀"), mapOf("t" to "end"))
        for (cut in 0..body.size) {
            val chunks = arrayOf(body.copyOfRange(0, cut), body.copyOfRange(cut, body.size))
            assertEquals("cut at $cut", expected, NdjsonDecoder().decodeAll(*chunks))
        }
        assertEquals(expected, NdjsonDecoder().decodeAll(*body.map { byteArrayOf(it) }.toTypedArray()))
    }

    @Test
    fun `ndjson keeps only object lines`() {
        val body = bytes("[1]\n\"x\"\n3\nnull\n{\"a\":1}\r\n{\"b\":\n")
        assertEquals(listOf(mapOf("a" to 1)), NdjsonDecoder().decodeAll(body))
    }

    @Test
    fun `a lone carriage return does not end an ndjson line`() {
        // The SDK splits with splitlines() but only emits on "\n": this is one malformed line.
        assertEquals(emptyList<Any>(), NdjsonDecoder().decodeAll(bytes("{\"a\":1}\r{\"b\":2}\n")))
        val crlfAcrossChunks = NdjsonDecoder().decodeAll(bytes("{\"a\":1}\r"), bytes("\n{\"b\":2}"))
        assertEquals(listOf(mapOf("a" to 1), mapOf("b" to 2)), crlfAcrossChunks)
    }

    @Test
    fun `oversized ndjson event fails the stream`() {
        // The SDK's oversized-event test: 256 KiB of payload fed in 32 KiB body chunks.
        val oversized = bytes("{\"type\":\"event\",\"payload\":\"" + "x".repeat(256 * 1024) + "\"}\n")
        val decoder = NdjsonDecoder()
        val error = assertThrows(WireFormatException::class.java) {
            for (offset in oversized.indices step 32_768) {
                val chunk = oversized.copyOfRange(offset, minOf(offset + 32_768, oversized.size))
                decoder.feed(chunk) { fail("delivered $it") }
            }
        }
        assertTrue(error.message!!.contains("exceeds"))
    }

    @Test
    fun `ndjson line limit counts the newline`() {
        val limit = MessageCodec.MAX_CHAT_EVENT_BYTES
        val exact = bytes("{\"p\":\"" + "x".repeat(limit - 9) + "\"}\n")
        assertEquals(limit, exact.size)
        assertEquals(1, NdjsonDecoder().decodeAll(exact).size)
        val over = bytes("{\"p\":\"" + "x".repeat(limit - 8) + "\"}\n")
        assertThrows(WireFormatException::class.java) { NdjsonDecoder().feed(over) { } }
        // An unterminated tail over the limit fails as soon as it is buffered.
        assertThrows(WireFormatException::class.java) { NdjsonDecoder(maxLineBytes = 7).feed(bytes("{\"a\":12}")) { } }
    }

    @Test
    fun `objects before an oversize line are still delivered`() {
        val out = mutableListOf<JSONObject>()
        assertThrows(WireFormatException::class.java) {
            NdjsonDecoder(maxLineBytes = 16).feed(bytes("{\"a\":1}\n" + "x".repeat(17)), out::add)
        }
        assertEquals(listOf(mapOf("a" to 1)), out.maps())
    }

    @Test
    fun `finish without a pending line yields nothing`() {
        assertEquals(emptyList<Any>(), NdjsonDecoder().decodeAll())
        assertEquals(emptyList<Any>(), NdjsonDecoder().decodeAll(bytes("{\"a\":1}\n"), bytes("  ")).drop(1))
    }
}
