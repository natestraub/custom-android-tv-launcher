package dev.tvlauncher.muse.net

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MuseApiTest {
    private val requests = CopyOnWriteArrayList<FakeHttpServer.Request>()

    @Volatile private var status = 200

    @Volatile private var body = "{}"

    @Volatile private var delayMs = 0L

    private val server = FakeHttpServer { request ->
        requests += request
        Thread.sleep(delayMs)
        status to body
    }
    private val base = "http://127.0.0.1:${server.port}"
    private val api = MuseApi(base, userAgent = "musegadget/0.1.0 (test)")

    @After
    fun stop() = server.close()

    private fun closedPort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun sentJson(body: String): Map<String, Any> = JSONObject(body).let { json ->
        json.keys().asSequence().associateWith { json.get(it) }
    }

    @Test
    fun `fetchVms presents the device token and keeps the usable VMs`() {
        body = """{"vm_list":[
            {"vm_ws_url":"wss://a","vm_url":"https://a","vm_auth_token":"t1","vm_name":"one","vm_id":"id1"},
            {"vm_url":"https://b","vm_auth_token":"t2","vm_name":"two","vm_id":"id2","default":true},
            {"vm_url":"https://c","vm_name":"no bearer"},
            {"vm_auth_token":"t4","vm_name":"no url"},
            {"vm_ws_url":"","vm_url":"https://e","vm_auth_token":"t5","vm_name":null,"vm_id":null,"default":0},
            "not an object"
        ]}"""
        val result = api.fetchVms("access-1") as FetchVmsResult.Vms
        assertEquals(200, result.status)
        assertEquals(
            listOf(
                MuseVm("wss://a", "t1", "one", "id1", isDefault = false),
                MuseVm("https://b", "t2", "two", "id2", isDefault = true),
                MuseVm("https://e", "t5", "", "", isDefault = false),
            ),
            result.vms,
        )
        assertEquals("id2", result.preferred?.id)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/fetch_vms", request.path)
        assertEquals("Bearer access-1", request.headers["authorization"])
        assertEquals("1.0.0", request.headers["x-api-version"])
        assertEquals("musegadget/0.1.0 (test)", request.headers["user-agent"])
    }

    @Test
    fun `without a default the first VM is used, and its name stands in for a missing id`() {
        body = """{"vm_list":[{"vm_url":"u1","vm_auth_token":"t1","vm_name":"first"},{"vm_url":"u2","vm_auth_token":"t2","vm_id":"x"}]}"""
        val preferred = (api.fetchVms("a") as FetchVmsResult.Vms).preferred!!
        assertEquals("first", preferred.name)
        assertEquals("first", preferred.linkId)
        assertEquals("x", MuseVm("u", "t", "name", "x", isDefault = false).linkId)
        assertNull(FetchVmsResult.Vms(emptyList(), 200).preferred)
    }

    @Test
    fun `paths are appended to a base URL with a path and trailing slash`() {
        body = """{"vm_list":[]}"""
        assertEquals(FetchVmsResult.Vms(emptyList(), 200), MuseApi("$base/v2/").fetchVms("a"))
        assertEquals("/v2/fetch_vms", requests.single().path)
    }

    @Test
    fun `a 401 from fetchVms means the device token was rejected`() {
        status = 401
        assertEquals(FetchVmsResult.TokenRejected, api.fetchVms("stale"))
    }

    @Test
    fun `error bodies and other statuses are retryable failures`() {
        val replies = listOf(
            """{"error_title":"Nope","vm_list":[]}""",
            """{"backend_error_code":7}""",
            "[]",
            """{"vms":[]}""",
            "not json",
            "",
        )
        for (reply in replies) {
            body = reply
            assertEquals(reply, 200, (api.fetchVms("a") as FetchVmsResult.Failed).status)
        }
        status = 503
        body = """{"vm_list":[]}"""
        assertEquals(503, (api.fetchVms("a") as FetchVmsResult.Failed).status)
    }

    @Test
    fun `transport failures and timeouts have no status`() {
        val down = MuseApi("http://127.0.0.1:${closedPort()}")
        assertNull((down.fetchVms("a") as FetchVmsResult.Failed).status)
        assertNull((down.refreshDeviceToken("homelink-abcdef", "r") as RefreshResult.Failed).status)
        delayMs = 2_000
        val started = System.nanoTime()
        assertNull((MuseApi(base, timeoutMs = 200).fetchVms("a") as FetchVmsResult.Failed).status)
        assertTrue(System.nanoTime() - started < 1_500_000_000L)
    }

    @Test
    fun `refresh sends only the device id when there is no sdk token`() {
        body = """{"access_token":"new-a","refresh_token":"new-r"}"""
        val result = api.refreshDeviceToken(deviceId = "homelink-abcdef", refreshToken = "r")
        assertEquals(RefreshResult.Refreshed(DeviceTokens("new-a", "new-r")), result)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/device_token/refresh", request.path)
        assertEquals("Bearer hatch_refresh:r", request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("musegadget/0.1.0 (test)", request.headers["user-agent"])
        assertEquals(mapOf("device_id" to "homelink-abcdef"), sentJson(request.body))
    }

    @Test
    fun `refresh sends the sdk token`() {
        body = """{"access_token":"new-a","refresh_token":"new-r"}"""
        api.refreshDeviceToken(deviceId = "homelink-abcdef", refreshToken = "r", sdkToken = "mgst_token")
        assertEquals(
            mapOf("device_id" to "homelink-abcdef", "sdk_token" to "mgst_token"),
            sentJson(requests.single().body),
        )
    }

    @Test
    fun `refresh never doubles the prefix and accepts a wrapped payload`() {
        body = """{"payload":{"access_token":"new-a","refresh_token":"new-r"}}"""
        for (stored in listOf("hatch_refresh:raw-r", "raw-r")) {
            requests.clear()
            val result = api.refreshDeviceToken("homelink-abcdef", stored)
            assertEquals(RefreshResult.Refreshed(DeviceTokens("new-a", "new-r")), result)
            assertEquals("Bearer hatch_refresh:raw-r", requests.single().headers["authorization"])
        }
    }

    @Test
    fun `a 401 on refresh means the device must pair again`() {
        status = 401
        assertEquals(RefreshResult.PairingRevoked, api.refreshDeviceToken("homelink-abcdef", "r"))
    }

    @Test
    fun `other refresh failures are transient`() {
        val replies = listOf(
            """{"access_token":"a"}""",
            """{"payload":{"refresh_token":"r"}}""",
            """{"access_token":"","refresh_token":"r"}""",
            "nope",
        )
        for (reply in replies) {
            body = reply
            assertEquals(reply, 200, (api.refreshDeviceToken("homelink-abcdef", "r") as RefreshResult.Failed).status)
        }
        status = 500
        assertEquals(500, (api.refreshDeviceToken("homelink-abcdef", "r") as RefreshResult.Failed).status)
    }

    @Test
    fun `tokens never show up in toString`() {
        val vm = MuseVm("wss://a", "vm-secret", "one", "id1", isDefault = true)
        val tokens = DeviceTokens("access-secret", "refresh-secret")
        val printed = listOf(vm, tokens, RefreshResult.Refreshed(tokens), FetchVmsResult.Vms(listOf(vm), 200)).joinToString()
        assertFalse(printed, printed.contains("secret"))
    }

    @Test
    fun `api root, user agent and raw refresh token`() {
        assertEquals("https://api.example", MuseApi.root("https://api.example/"))
        assertEquals("https://api.muse.ai", MuseApi.root(""))
        assertEquals("https://api.muse.ai", MuseApi.root(null))
        assertTrue(MuseApi.userAgent("TCL Smart TV Pro").startsWith("musegadget/0.1.0 (TCL Smart TV Pro; Android"))
        assertTrue(MuseApi.userAgent().startsWith("musegadget/0.1.0 (Android"))
        assertEquals("raw-r", MuseApi.rawRefreshToken("hatch_refresh:raw-r"))
        assertEquals("raw-r", MuseApi.rawRefreshToken("raw-r"))
    }
}

/**
 * Just enough HTTP/1.1 for [MuseApi]: one request per connection, answered with
 * `status to body` from [reply]. (The JDK's com.sun.net.httpserver isn't on the
 * Android unit-test compile classpath.)
 */
private class FakeHttpServer(private val reply: (Request) -> Pair<Int, String>) : Closeable {
    /** [headers] are keyed by lowercase name. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = listener.localPort

    init {
        thread(isDaemon = true, name = "fake-http") {
            while (true) {
                val client = try {
                    listener.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val (method, target) = readLine(input).split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val sent = ByteArray(headers["content-length"]?.toInt() ?: 0).also(input::readFully)
        val (status, body) = reply(Request(method, target.substringBefore('?'), headers, sent.decodeToString()))
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status Fake\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray() + bytes)
            flush()
        }
    }

    private fun readLine(input: DataInputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) return line.removeSuffix("\r").toString()
            line.append(b.toChar())
        }
    }

    override fun close() = listener.close()
}
