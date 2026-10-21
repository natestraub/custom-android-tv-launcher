package dev.tvlauncher.muse.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One leased VM from `/fetch_vms`. [toString] leaves out the bearer. */
data class MuseVm(
    /** `vm_ws_url`, else `vm_url`. Informational: the link connects to the paired noise host (§3.1). */
    val url: String,
    /** Per-VM bearer for the WebSocket upgrade; each fetch hands out a fresh one. */
    val authToken: String,
    val name: String,
    val id: String,
    val isDefault: Boolean,
) {
    /** The `vm_id` query value for the link: the id, or the name when the id is empty. */
    val linkId: String get() = id.ifEmpty { name }

    override fun toString() = "MuseVm(name=$name, id=$id, default=$isDefault)"
}

sealed interface FetchVmsResult {
    /** A usable `vm_list`, possibly empty once entries without a URL or bearer are dropped. */
    data class Vms(val vms: List<MuseVm>, val status: Int) : FetchVmsResult {
        /** The VM to connect to: the first marked default, else the first. */
        val preferred: MuseVm? get() = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
    }

    /** HTTP 401: the access token was rejected. Force a token refresh; only a 401 there means re-pair. */
    data object TokenRejected : FetchVmsResult

    /** Anything else; retry with backoff. [status] is null when no HTTP response arrived. */
    data class Failed(val status: Int?, val detail: String) : FetchVmsResult
}

/** A rotated device token pair. [toString] leaves the tokens out. */
data class DeviceTokens(val accessToken: String, val refreshToken: String) {
    override fun toString() = "DeviceTokens(…)"
}

sealed interface RefreshResult {
    data class Refreshed(val tokens: DeviceTokens) : RefreshResult

    /** HTTP 401: the pairing was revoked. Delete it; the device has to pair again over BLE. */
    data object PairingRevoked : RefreshResult

    /** Transient: keep using the current token. [status] is null when no HTTP response arrived. */
    data class Failed(val status: Int?, val detail: String) : RefreshResult
}

/** The device API calls the link loop makes; [MuseApi] in production. */
interface DeviceApi {
    fun fetchVms(accessToken: String): FetchVmsResult

    fun refreshDeviceToken(deviceId: String, refreshToken: String, sdkToken: String? = null): RefreshResult
}

/**
 * The Muse device API (protocol spec §3.1–3.2): leased-VM lookup and device
 * token rotation. Blocking; call it off the main thread. Results and their
 * details never include token values, so they are safe to log.
 */
class MuseApi(
    /** The pairing's `api_url_v2`; empty means the Muse API. */
    baseUrl: String = API_BASE,
    private val userAgent: String = userAgent(),
    private val timeoutMs: Int = TIMEOUT_MS,
) : DeviceApi {
    private val root = root(baseUrl)

    override fun fetchVms(accessToken: String): FetchVmsResult {
        val headers = mapOf("Authorization" to "Bearer $accessToken", "X-API-Version" to API_VERSION)
        val response = try {
            request("GET", FETCH_PATH, headers, body = null)
        } catch (e: IOException) {
            return FetchVmsResult.Failed(null, describe(e))
        }
        val status = response.status
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) return FetchVmsResult.TokenRejected
        if (status !in 200..299) return FetchVmsResult.Failed(status, "HTTP $status")
        val data = parseObject(response.body) ?: return FetchVmsResult.Failed(status, "response is not a JSON object")
        val errors = listOf("error_title", "backend_error_code").mapNotNull { key -> data.opt(key)?.takeIf(::truthy) }
        if (errors.isNotEmpty()) return FetchVmsResult.Failed(status, "API error ${errors.joinToString(" ")}")
        val list = data.opt("vm_list") as? JSONArray ?: return FetchVmsResult.Failed(status, "missing vm_list")
        val vms = (0 until list.length()).mapNotNull { i -> (list.opt(i) as? JSONObject)?.let(::parseVm) }
        return FetchVmsResult.Vms(vms, status)
    }

    /**
     * Rotates the device tokens. [deviceId] is the node id (`homelink-…`). Only
     * the refresh token is presented: the server can accept an access token
     * here and answer with replacements every endpoint then rejects.
     */
    override fun refreshDeviceToken(deviceId: String, refreshToken: String, sdkToken: String?): RefreshResult {
        val body = JSONObject().put("device_id", deviceId)
        if (!sdkToken.isNullOrEmpty()) body.put("sdk_token", sdkToken)
        val headers = mapOf(
            "Authorization" to "Bearer $REFRESH_PREFIX${rawRefreshToken(refreshToken)}",
            "Content-Type" to "application/json",
        )
        val response = try {
            request("POST", REFRESH_PATH, headers, body.toString().toByteArray())
        } catch (e: IOException) {
            return RefreshResult.Failed(null, describe(e))
        }
        val status = response.status
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) return RefreshResult.PairingRevoked
        if (status !in 200..299) return RefreshResult.Failed(status, "HTTP $status")
        var data = parseObject(response.body) ?: return RefreshResult.Failed(status, "response is not a JSON object")
        (data.opt("payload") as? JSONObject)?.let { data = it }
        val access = string(data, "access_token")
        val refresh = string(data, "refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) return RefreshResult.Failed(status, "response is missing tokens")
        return RefreshResult.Refreshed(DeviceTokens(access, refresh))
    }

    private class Response(val status: Int, val body: String)

    private fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): Response {
        val conn = URL(root + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            if (status < 0) throw IOException("not an HTTP response")
            // Error bodies are never read: nothing in them is needed, and they never reach a log.
            return Response(status, if (status in 200..299) conn.inputStream.use(::readText) else "")
        } finally {
            conn.disconnect()
        }
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toString("UTF-8")
            if (out.size() + n > MAX_BODY_BYTES) throw IOException("response over $MAX_BODY_BYTES bytes")
            out.write(buffer, 0, n)
        }
    }

    private fun parseVm(entry: JSONObject): MuseVm? {
        val url = string(entry, "vm_ws_url").ifEmpty { string(entry, "vm_url") }
        val token = string(entry, "vm_auth_token")
        if (url.isEmpty() || token.isEmpty()) return null
        return MuseVm(url, token, string(entry, "vm_name"), string(entry, "vm_id"), truthy(entry.opt("default")))
    }

    private fun parseObject(text: String): JSONObject? = try {
        JSONTokener(text).nextValue() as? JSONObject
    } catch (e: JSONException) {
        null
    }

    /** A string field, else "". (Android's optString turns JSON null into "null".) */
    private fun string(o: JSONObject, key: String): String = o.opt(key) as? String ?: ""

    /** Python truthiness, which upstream applies to these fields. */
    private fun truthy(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is JSONArray -> value.length() > 0
        is JSONObject -> value.length() > 0
        else -> true
    }

    private fun describe(e: IOException) = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    companion object {
        const val API_BASE = "https://api.muse.ai"
        const val FETCH_PATH = "/fetch_vms"
        const val REFRESH_PATH = "/device_token/refresh"
        const val TIMEOUT_MS = 15_000

        /** The upstream SDK version this port speaks for (User-Agent, `link.register`). */
        const val VERSION = "0.1.0"
        private const val API_VERSION = "1.0.0"
        private const val REFRESH_PREFIX = "hatch_refresh:"
        private const val MAX_BODY_BYTES = 1 shl 20

        /** The URL API paths are appended to: [apiUrlV2] from the pairing, or the Muse API. */
        fun root(apiUrlV2: String?): String = (apiUrlV2?.takeIf { it.isNotEmpty() } ?: API_BASE).trimEnd('/')

        /** Informational, in upstream's shape: `musegadget/0.1.0 (TCL Smart TV Pro; Android armv8l)`. */
        fun userAgent(model: String? = null): String {
            val platform = "Android ${System.getProperty("os.arch").orEmpty()}".trim()
            val details = listOfNotNull(model?.trim()?.takeIf { it.isNotEmpty() }, platform)
            return "musegadget/$VERSION (${details.joinToString("; ")})"
        }

        /**
         * The refresh token without a `hatch_refresh:` prefix. Apps now hand
         * over tokens that already carry it, and the server rejects it doubled.
         */
        fun rawRefreshToken(refreshToken: String): String = refreshToken.substringAfterLast(':')
    }
}
