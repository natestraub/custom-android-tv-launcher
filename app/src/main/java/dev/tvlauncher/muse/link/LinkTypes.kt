package dev.tvlauncher.muse.link

import org.json.JSONObject
import java.io.Closeable
import java.io.IOException

enum class Outcome {
    /** The connection ended; reconnect normally. */
    CLOSED,

    /** The edge refused the VM bearer; re-fetch VMs. */
    AUTH_REJECTED,

    /** Authenticated but not allowed right now. */
    FORBIDDEN,

    /** The Muse removed this device. */
    UNPAIRED,
    STOPPED,
}

/** What `link.register` tells the Muse about this device. */
class DeviceDescription(val nodeId: String, val displayName: String, val version: String, val commands: Map<String, Any>) {
    // Upstream's rule: register as linux/homehub. Never family "link" or a device.ota command,
    // because the server pushes ESP32 firmware updates to every "link" device.
    fun registerParams(): Map<String, Any> = linkedMapOf(
        "node_id" to nodeId,
        "display_name" to displayName,
        "platform" to "linux",
        "version" to version,
        "device_family" to "homehub",
        "model_id" to "linux",
        "is_wakeup_supported" to false,
        "commands_v2" to commands,
    )
}

/** A WebSocket as the session needs it, so tests can swap in an in-memory pipe. */
interface LinkSocket : Closeable {
    fun send(data: ByteArray)

    /** Blocks for the next message: its bytes, or null for a text frame. Throws [IOException] once closed. */
    fun receive(timeoutMs: Long = 0): ByteArray?
}

/** A device-originated request's result. [response] is the parsed JSON, or up to 2000 chars of text. */
class ChatResult(val ok: Boolean, val status: Int, val response: Any?)

/** `wss://host/v1/noise?vm_id=…`, with the id escaped like JavaScript's encodeURIComponent. */
fun noiseUrl(noiseHost: String, vmId: String) = "wss://$noiseHost${LinkSession.NOISE_PATH}?vm_id=${encodeUriComponent(vmId)}"

internal fun encodeUriComponent(text: String): String {
    val out = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

/** [text] with control characters replaced, so it can't forge log lines. */
internal fun printable(text: String) = text.map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) '?' else it }.joinToString("")

/** How an invoke ended, for the log: never its parameters, output or error, which can echo them. */
internal fun describeResult(result: Map<String, Any?>): String {
    if (result["ok"] != true) return "failed"
    val payload = result["payload"] as? Map<*, *> ?: return "ok"
    val exit = payload["exit_code"] as? Int ?: return "ok"
    return "ok, exit $exit" + if (payload["timed_out"] == true) ", timed out" else ""
}

/** A control message's kind and ids, never its params or payload, for the log. */
internal fun describeControl(message: JSONObject): String {
    fun field(key: String) = (message.opt(key) as? String)?.let { printable(it.take(48)) }
    return listOfNotNull(
        field("type")?.let { "type=$it" },
        field("method")?.let { "method=$it" },
        field("event")?.let { "event=$it" },
        field("command")?.let { "command=$it" },
        field("id")?.let { "id=$it" },
        message.opt("ok")?.let { "ok=$it" },
    ).joinToString(" ").ifEmpty { "keys=${message.keys().asSequence().take(8).joinToString(",")}" }
}

/** Python truthiness for a JSON value. */
internal fun truthy(value: Any?): Boolean = when (value) {
    null, JSONObject.NULL, false -> false
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    is JSONObject -> value.length() > 0
    is org.json.JSONArray -> value.length() > 0
    else -> true
}
