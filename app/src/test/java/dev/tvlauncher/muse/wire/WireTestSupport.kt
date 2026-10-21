package dev.tvlauncher.muse.wire

import org.json.JSONArray
import org.json.JSONObject

/** `extra_vectors.json`: what the SDK's own Python produces for fixed inputs. */
internal object Vectors {
    val extra: JSONObject by lazy {
        JSONObject(checkNotNull(javaClass.getResource("/muse/extra_vectors.json")).readText())
    }
}

internal fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

/** Plain maps and lists for assertions; tests compile against Android's org.json, which has no toMap(). */
internal fun JSONObject.toPlainMap(): Map<String, Any?> = keys().asSequence().associateWith { plain(opt(it)) }

private fun plain(value: Any?): Any? = when (value) {
    is JSONObject -> value.toPlainMap()
    is JSONArray -> List(value.length()) { plain(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}
