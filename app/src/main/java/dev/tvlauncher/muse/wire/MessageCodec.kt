package dev.tvlauncher.muse.wire

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JSON message framing on the Muse link (spec §3.4.5). The `/link-control` stream carries JSON
 * objects, each prefixed with its byte length as a little-endian u32.
 *
 * JSON is written like the SDK's `json.dumps(obj, separators=(",", ":"))`: compact, ASCII-only
 * with `\uXXXX` escapes, and no `\/`. That keeps encodings byte-identical to the Python and the
 * same on the JVM as on the device, whose `JSONObject.toString()` escapes differently.
 */
object MessageCodec {
    /** Largest inbound control message; a longer length prefix is fatal for the session. */
    const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024

    /** Largest `/chat/subscribe` event line, newline included. */
    const val MAX_CHAT_EVENT_BYTES = 256 * 1024

    /** Length prefix + compact JSON. A JSONObject's keys go out in its own iteration order. */
    fun encode(message: JSONObject): ByteArray = lengthPrefixed(compactJson(message))

    /** Length prefix + compact JSON, keys in the map's iteration order. */
    fun encode(message: Map<String, *>): ByteArray = lengthPrefixed(compactJson(message))

    /**
     * Compact JSON for a JSONObject, JSONArray, Map with String keys, Iterable, Array, String,
     * Number, Boolean or null. Non-finite numbers and other types are refused. Floating-point
     * numbers use Kotlin's formatting, so exponents read `1.0E20` where Python writes `1e+20`.
     */
    fun compactJson(value: Any?): String = StringBuilder().apply { appendJson(value) }.toString()

    private fun lengthPrefixed(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.US_ASCII) // compactJson only emits ASCII
        return ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).put(bytes).array()
    }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null, JSONObject.NULL -> append("null")
            is CharSequence -> appendString(value)
            is Boolean -> append(value)
            is Double, is Float -> {
                require((value as Number).toDouble().isFinite()) { "JSON has no $value" }
                append(value)
            }
            is Number -> append(value)
            is JSONObject -> appendObject(value.keys().asSequence().map { it to value.opt(it) })
            is Map<*, *> -> appendObject(value.entries.asSequence().map { (key, item) -> jsonKey(key) to item })
            is JSONArray -> appendArray((0 until value.length()).asSequence().map(value::opt))
            is Iterable<*> -> appendArray(value.asSequence())
            is Array<*> -> appendArray(value.asSequence())
            else -> throw IllegalArgumentException("${value.javaClass.name} is not JSON serializable")
        }
    }

    private fun jsonKey(key: Any?) =
        key as? String ?: throw IllegalArgumentException("JSON keys must be strings, not ${key?.javaClass?.name}")

    private fun StringBuilder.appendObject(entries: Sequence<Pair<String, Any?>>) {
        append('{')
        entries.forEachIndexed { i, (key, item) ->
            if (i > 0) append(',')
            appendString(key)
            append(':')
            appendJson(item)
        }
        append('}')
    }

    private fun StringBuilder.appendArray(items: Sequence<Any?>) {
        append('[')
        items.forEachIndexed { i, item ->
            if (i > 0) append(',')
            appendJson(item)
        }
        append(']')
    }

    /** Python's `ensure_ascii` escaping: anything outside printable ASCII becomes `\uXXXX`. */
    private fun StringBuilder.appendString(text: CharSequence) {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                in ' '..'~' -> append(c)
                else -> append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
            }
        }
        append('"')
    }
}

/**
 * Splits the `/link-control` byte stream into length-prefixed JSON objects (the SDK's
 * `MessageDecoder`). Bytes are buffered across body chunks, so any split works. Zero-length
 * keepalives, bad UTF-8, malformed JSON and non-objects are dropped.
 */
class MessageDecoder(private val maxMessageBytes: Int = MessageCodec.MAX_INBOUND_MESSAGE) {
    private var buffer = EMPTY_BYTES
    private var size = 0

    /**
     * Adds [data]; returns every message it completes. A length prefix over [maxMessageBytes]
     * throws [WireFormatException], fatal for the session as upstream.
     */
    fun feed(data: ByteArray): List<JSONObject> {
        if (size + data.size > buffer.size) buffer = buffer.copyOf(maxOf(size + data.size, buffer.size * 2))
        data.copyInto(buffer, size)
        size += data.size
        val messages = ArrayList<JSONObject>()
        var offset = 0
        try {
            while (size - offset >= 4) {
                val length = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong()
                if (length > maxMessageBytes) throw WireFormatException("inbound message too large: $length")
                if (size - offset - 4 < length) break
                val start = offset + 4
                offset = start + length.toInt()
                if (length > 0) parseJsonObject(buffer, start, length.toInt())?.let(messages::add)
            }
        } finally {
            if (offset > 0) buffer.copyInto(buffer, 0, offset, size)
            size -= offset
            if (size == 0 && buffer.size > SHRINK_ABOVE) buffer = EMPTY_BYTES // after a rare big message
        }
        return messages
    }

    private companion object {
        const val SHRINK_ABOVE = 64 * 1024
    }
}

/**
 * Splits a newline-delimited JSON body (`/chat/subscribe`, spec §3.4.7) into objects. Bytes are
 * buffered until `\n`, so lines and UTF-8 sequences may be split across chunks anywhere; `\r\n`
 * works, a lone `\r` doesn't end a line. Blank lines, malformed JSON and non-objects are dropped.
 * Chat subscribers then keep only objects whose `"type"` is `"event"`.
 *
 * Objects go to a callback, like the SDK's generator: those before an oversize line are still
 * delivered before the [WireFormatException]. That error fails only this stream; reset it.
 */
class NdjsonDecoder(private val maxLineBytes: Int = MessageCodec.MAX_CHAT_EVENT_BYTES) {
    private val line = ByteArrayOutputStream()

    /** Adds one body chunk, passing each complete object line to [onObject] in order. */
    fun feed(chunk: ByteArray, onObject: (JSONObject) -> Unit) {
        var start = 0
        while (start < chunk.size) {
            val newline = chunk.indexOf('\n'.code.toByte(), start)
            val end = if (newline < 0) chunk.size else newline + 1
            if (line.size() + end - start > maxLineBytes) {
                line.reset()
                throw WireFormatException("NDJSON line exceeds $maxLineBytes bytes")
            }
            line.write(chunk, start, end - start)
            start = end
            if (newline >= 0) emitLine(onObject)
        }
    }

    /** End of stream: a last line without its newline is still parsed. */
    fun finish(onObject: (JSONObject) -> Unit) {
        if (line.size() > 0) emitLine(onObject)
    }

    private fun emitLine(onObject: (JSONObject) -> Unit) {
        val bytes = line.toByteArray()
        line.reset()
        parseJsonObject(bytes)?.let(onObject)
    }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }
}

/** The JSON object in [bytes], or null if they aren't strict UTF-8 holding exactly one object. */
internal fun parseJsonObject(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): JSONObject? {
    val text = decodeUtf8(bytes, offset, length) ?: return null
    if (text.isBlank()) return null
    return try {
        val tokener = JSONTokener(text)
        // nextClean() is '\u0000' at the end: anything else is trailing data, which Python rejects.
        (tokener.nextValue() as? JSONObject)?.takeIf { tokener.nextClean() == '\u0000' }
    } catch (e: JSONException) {
        null
    } catch (e: StackOverflowError) {
        null // deeply nested input (Python raises RecursionError); BLE writes are unauthenticated
    }
}
