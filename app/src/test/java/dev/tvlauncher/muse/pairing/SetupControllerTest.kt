package dev.tvlauncher.muse.pairing

import dev.tvlauncher.muse.pairing.PairingTestKit.APP
import dev.tvlauncher.muse.pairing.PairingTestKit.Mobile
import dev.tvlauncher.muse.pairing.PairingTestKit.clientFinishedRecord
import dev.tvlauncher.muse.pairing.PairingTestKit.hello
import dev.tvlauncher.muse.pairing.PairingTestKit.makeDevice
import dev.tvlauncher.muse.pairing.PairingTestKit.toMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Port of the SDK's tests/test_ble_setup.py. Chunk framing and write reassembly live in
 * the GATT server here (BleFramingTest covers them), so the transport sees whole messages.
 */
class SetupControllerTest {
    private class FakeTransport : SetupTransport {
        val messages = mutableListOf<Any>() // a Map per framed JSON message, a String per raw status
        val raw = mutableListOf<String>()
        val disconnects = mutableListOf<Long>()

        override fun send(message: ByteArray, framed: Boolean) {
            synchronized(this) {
                val text = String(message)
                raw += text
                messages += if (framed) JSONObject(text).toMap() else text
            }
        }

        override fun disconnect(delayMillis: Long) {
            disconnects += delayMillis
        }
    }

    private class FakeNetwork(var online: Boolean) : SetupNetwork {
        override fun isOnline() = online

        override fun currentConnectionEntry(): Map<String, Any> = linkedMapOf("ssid" to "HomeNet", "rssi" to -40, "secure" to false)
    }

    private class Harness(
        online: Boolean = true,
        provision: ((Credentials, (() -> Boolean) -> Boolean) -> Unit)? = null,
    ) {
        val transport = FakeTransport()
        val network = FakeNetwork(online)
        val pairing = makeDevice()
        val saved = mutableListOf<Credentials>()
        val events = mutableListOf<SetupEvent>()
        val controller = SetupController(
            pairing = pairing,
            identity = GadgetIdentity(APP.getString("mac")),
            version = APP.getString("firmware_version"),
            transport = transport,
            network = network,
            provision = provision ?: { credentials, commit ->
                if (!commit { saved.add(credentials) }) throw ProvisionFailed("error_storage")
            },
            worker = Executor { it.run() },
            provisioner = Executor { it.run() },
            onEvent = { events += it },
        )
        val mobile = Mobile()

        fun send(message: JSONObject) = controller.handleMessage(message.toString().toByteArray())

        fun sendEncrypted(command: JSONObject) = send(mobile.seal(command))

        fun pair() {
            send(hello())
            send(clientFinishedRecord())
            mobile.txCounter = 1
        }

        @Suppress("UNCHECKED_CAST")
        fun opened() = transport.messages.filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "pairing_encrypted" }
            .map { mobile.open(it as Map<String, Any?>).toMap() }

        fun statuses() = opened().filter { it["type"] == "status" }.map { it["status"] }
    }

    private fun action(name: String) = JSONObject().put("action", name)

    private fun provision() = JSONObject()
        .put("action", "provision_v2")
        .put("ssid", "HomeNet")
        .put("password", "")
        .put("access_token", "device-access")
        .put("refresh_token", "device-refresh")
        .put("token_type", "device")
        .put("username", "someone")
        .put("api_url", "https://legacy-api.example")
        .put("api_url_v2", "https://api.example")

    @Test
    fun `device info matches the pairing transcript fields`() {
        val h = Harness()
        h.send(action("get_device_info"))
        val info = h.transport.messages.last() as Map<*, *>
        assertEquals("device_info", info["type"])
        assertEquals(APP.getString("node_id"), info["node_id"])
        assertEquals(APP.getString("firmware_version"), info["version"])
        assertEquals("hatch_link", info["model"])
        assertEquals(5L, info["pairing_protocol"])
        assertEquals("none", info["pairing_auth"])
        assertEquals("confirm_app", info["pairing_policy"])
        assertEquals(true, info["network_ready"])
        val keys = Regex("\"([a-z_]+)\":").findAll(h.transport.raw.last()).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "type", "node_id", "version", "device_id", "mac", "model", "pairing_protocol", "pairing_auth",
                "pairing_auth_epoch", "pairing_policy", "build_sha", "network_ready",
            ),
            keys,
        )
    }

    @Test
    fun `full setup flow`() {
        val h = Harness()
        h.pair()
        assertEquals(listOf("pairing_confirmed"), h.statuses())

        h.sendEncrypted(action("wifi_scan"))
        assertEquals(
            mapOf("type" to "wifi_scan_result", "networks" to listOf(mapOf("ssid" to "HomeNet", "rssi" to -40L, "secure" to false))),
            h.opened().last(),
        )

        h.sendEncrypted(provision())
        assertEquals(listOf("pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"), h.statuses())
        assertEquals(
            Credentials("device-access", "device-refresh", "someone", "https://legacy-api.example", "https://api.example", ""),
            h.saved.single(),
        )
        assertEquals(SessionState.PROVISIONING, h.pairing.state)
        assertEquals(listOf(SetupEvent.Secured, SetupEvent.Confirmed, SetupEvent.SigningIn, SetupEvent.Completed), h.events)
    }

    @Test
    fun `setup commands need encryption`() {
        val h = Harness()
        h.send(action("wifi_scan"))
        h.send(provision())
        assertEquals(listOf("error_encryption_required", "error_encryption_required"), h.transport.messages)
    }

    @Test
    fun `plaintext is ignored once pairing starts`() {
        val h = Harness()
        h.send(hello())
        h.send(action("wifi_scan"))
        assertEquals(1, h.transport.messages.size)
    }

    @Test
    fun `device info is answered mid session and a new hello restarts`() {
        val h = Harness()
        h.pair()
        h.send(action("get_device_info"))
        assertEquals("device_info", (h.transport.messages.last() as Map<*, *>)["type"])
        assertTrue(h.pairing.confirmed)
        h.send(hello())
        assertEquals("pairing_ready", (h.transport.messages.last() as Map<*, *>)["type"])
        assertEquals(SessionState.WAIT_CLIENT_FINISHED, h.pairing.state)
    }

    @Test
    fun `bad hello gets a plaintext error`() {
        val h = Harness()
        h.send(hello(overrides = mapOf("pairing_policy" to "confirm_press")))
        assertEquals(listOf("error_pairing_invalid_hello"), h.transport.messages)
    }

    @Test
    fun `decrypt failure disconnects without a plaintext reply`() {
        val h = Harness()
        h.send(hello())
        h.send(clientFinishedRecord().put("counter", "1"))
        assertEquals(1, h.transport.messages.size) // just pairing_ready
        assertEquals(listOf(300L), h.transport.disconnects)
        assertEquals(SessionState.IDLE, h.pairing.state)
    }

    @Test
    fun `wrong first record means the session can never confirm`() {
        val h = Harness()
        h.send(hello())
        h.sendEncrypted(action("wifi_scan"))
        assertEquals(listOf("error_pairing_confirm_required"), h.statuses())
        h.sendEncrypted(action("pairing_client_finished"))
        assertEquals(listOf(300L), h.transport.disconnects)
        assertEquals(SessionState.IDLE, h.pairing.state)
    }

    @Test
    fun `provision requires device tokens`() {
        val changes = listOf("refresh_token" to "", "token_type" to "user", "access_token" to null, "password" to null)
        for ((key, value) in changes) {
            val h = Harness()
            h.pair()
            h.sendEncrypted(provision().put(key, value ?: JSONObject.NULL))
            assertEquals(key, "error_missing_credentials", h.statuses().last())
            assertEquals(SessionState.READY, h.pairing.state)
        }
    }

    @Test
    fun `offline device reports wifi failure and can retry`() {
        val h = Harness(online = false)
        h.pair()
        h.sendEncrypted(action("wifi_scan"))
        assertEquals(emptyList<Any>(), h.opened().last()["networks"])

        h.sendEncrypted(provision())
        assertEquals("wifi_failed", h.statuses().last())
        assertTrue(h.transport.disconnects.isEmpty())

        h.network.online = true
        h.sendEncrypted(provision())
        assertEquals("auth_ok", h.statuses().last())
    }

    @Test
    fun `rejected token reports auth failed and disconnects`() {
        val h = Harness(provision = { _, _ -> throw ProvisionFailed("auth_failed") })
        h.pair()
        h.sendEncrypted(provision())
        assertEquals("auth_failed", h.statuses().last())
        assertEquals(listOf(500L), h.transport.disconnects)
        assertFalse(SetupEvent.Completed in h.events)
        assertEquals(SetupEvent.Failed("auth_failed"), h.events.last())
    }

    @Test
    fun `an unexpected provisioning error is reported as auth failed`() {
        val h = Harness(provision = { _, _ -> throw IllegalStateException("boom") })
        h.pair()
        h.sendEncrypted(provision())
        assertEquals("auth_failed", h.statuses().last())
        h.sendEncrypted(provision()) // the in-progress flag was cleared
        assertEquals("auth_failed", h.statuses().last())
    }

    @Test
    fun `a failed save is reported as error_storage`() {
        val h = Harness(provision = { _, commit -> if (!commit { false }) throw ProvisionFailed("error_storage") })
        h.pair()
        h.sendEncrypted(provision())
        assertEquals("error_storage", h.statuses().last())
    }

    @Test
    fun `disconnect clears the session`() {
        val h = Harness()
        h.pair()
        h.controller.onDisconnect()
        assertEquals(SessionState.IDLE, h.pairing.state)
        assertEquals(SetupEvent.Reset, h.events.last())
        h.send(action("wifi_scan"))
        assertEquals("error_encryption_required", h.transport.messages.last())
    }

    @Test
    fun `garbage before pairing gets no reply`() {
        val h = Harness()
        for (raw in listOf("not json", "[1,2]", "{\"action\":1}x", "ÿ")) {
            h.controller.handleMessage(raw.toByteArray(Charsets.ISO_8859_1))
        }
        assertEquals(emptyList<Any>(), h.transport.messages)
    }

    @Test
    fun `credentials never print their tokens`() {
        val text = Credentials("secret-access", "secret-refresh", "someone", "", "https://api.example", "").toString()
        assertFalse(text.contains("secret"))
    }
}
