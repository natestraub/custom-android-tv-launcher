package dev.tvlauncher.muse.pairing

import dev.tvlauncher.muse.pairing.PairingTestKit.APP
import dev.tvlauncher.muse.pairing.PairingTestKit.FakeClock
import dev.tvlauncher.muse.pairing.PairingTestKit.Mobile
import dev.tvlauncher.muse.pairing.PairingTestKit.VECTORS
import dev.tvlauncher.muse.pairing.PairingTestKit.b64
import dev.tvlauncher.muse.pairing.PairingTestKit.clientFinishedRecord
import dev.tvlauncher.muse.pairing.PairingTestKit.confirmedDevice
import dev.tvlauncher.muse.pairing.PairingTestKit.hello
import dev.tvlauncher.muse.pairing.PairingTestKit.hex
import dev.tvlauncher.muse.pairing.PairingTestKit.makeDevice
import dev.tvlauncher.muse.pairing.PairingTestKit.toMap
import dev.tvlauncher.muse.pairing.PairingTestKit.unb64
import dev.tvlauncher.muse.wire.MessageCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Port of the SDK's tests/test_pairing.py. */
class PairingSessionTest {
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    // --- Vector conformance ---

    @Test
    fun `transcript matches every vector`() {
        for (v in VECTORS) {
            val transcript = PairingSession.buildTranscript(
                community = v.getBoolean("community"),
                authEpoch = v.getInt("pairing_auth_epoch"),
                policy = v.getString("pairing_policy"),
                deviceId = v.getString("device_id"),
                nodeId = v.getString("node_id"),
                mac = v.getString("mac"),
                firmwareVersion = v.getString("firmware_version"),
                mobilePub = v.getString("mobile_pub"),
                devicePub = v.getString("device_pub"),
                mobileNonce = v.getString("mobile_nonce"),
                deviceNonce = v.getString("device_nonce"),
            )
            assertEquals(v.getString("name"), v.getString("transcript"), transcript)
            assertEquals(v.getString("transcript_hash"), b64(sha256(transcript)))
        }
    }

    @Test
    fun `key schedule matches every vector`() {
        for (v in VECTORS) {
            val keys = PairingSession.deriveSessionKeys(
                hex(v.getString("ecdh_secret_hex")),
                unb64(v.getString("mobile_nonce")),
                unb64(v.getString("device_nonce")),
                unb64(v.getString("transcript_hash")),
            )
            assertEquals(v.getString("mobile_tx_key_hex"), keys.mobileTx.joinToString("") { "%02x".format(it) })
            assertEquals(v.getString("mobile_rx_key_hex"), keys.mobileRx.joinToString("") { "%02x".format(it) })
            assertEquals(v.getString("session_id"), b64(keys.sessionId))
        }
    }

    @Test
    fun `ecdh with the vector scalars gives the vector secret`() {
        val pair = PairingTestKit.vectorKeyPair()
        val secret = P256.ecdh(pair.private, P256.decodePublic(unb64(APP.getString("mobile_pub"))))
        assertEquals(APP.getString("ecdh_secret_hex"), secret.joinToString("") { "%02x".format(it) })
        assertEquals(APP.getString("device_pub"), b64(P256.encodePublic(pair.public)))
    }

    @Test
    fun `transcript rejects app policy for official devices`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingSession.buildTranscript(
                community = false, authEpoch = 1, policy = "confirm_app",
                deviceId = "d", nodeId = "n", mac = "m", firmwareVersion = "1",
                mobilePub = "a", devicePub = "b", mobileNonce = "c", deviceNonce = "e",
            )
        }
    }

    @Test
    fun `device info advertises community app confirmation`() {
        assertEquals(
            mapOf(
                "device_id" to APP.getString("device_id"),
                "mac" to APP.getString("mac"),
                "model" to "hatch_link",
                "pairing_protocol" to 5,
                "pairing_auth" to "none",
                "pairing_auth_epoch" to 0,
                "pairing_policy" to "confirm_app",
            ),
            makeDevice().deviceInfo(),
        )
    }

    @Test
    fun `hello produces the vector pairing_ready`() {
        val ready = makeDevice().handleHello(hello())
        assertEquals(
            mapOf(
                "type" to "pairing_ready",
                "version" to 5,
                "device_id" to APP.getString("device_id"),
                "node_id" to APP.getString("node_id"),
                "mac" to APP.getString("mac"),
                "model" to "hatch_link",
                "firmware_version" to APP.getString("firmware_version"),
                "pairing_auth" to "none",
                "pairing_auth_epoch" to 0,
                "pairing_policy" to "confirm_app",
                "device_pub" to APP.getString("device_pub"),
                "device_nonce" to APP.getString("device_nonce"),
                "transcript_hash" to APP.getString("transcript_hash"),
                "session_id" to APP.getString("session_id"),
            ),
            ready,
        )
        // Key order matches the firmware's wire output.
        assertEquals(
            "type,version,device_id,node_id,mac,model,firmware_version,pairing_auth,pairing_auth_epoch," +
                "pairing_policy,device_pub,device_nonce,transcript_hash,session_id",
            ready.keys.joinToString(","),
        )
    }

    @Test
    fun `full app confirmed handshake`() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(SessionState.WAIT_CLIENT_FINISHED, device.state)

        val plaintext = device.decrypt(clientFinishedRecord())
        assertEquals(APP.getString("client_finished_plaintext"), plaintext)

        val generation = device.handleClientFinished(JSONObject(plaintext))
        assertTrue(generation != 0)
        assertTrue(device.confirmed)

        val mobile = Mobile()
        val confirmed = mobile.open(device.encryptStatus("pairing_confirmed", generation)!!)
        assertEquals(mapOf("type" to "status", "status" to "pairing_confirmed"), confirmed.toMap())

        val scan = device.decrypt(mobile.seal(JSONObject().put("action", "wifi_scan"), counter = 1))
        assertEquals(mapOf("action" to "wifi_scan"), JSONObject(scan).toMap())
    }

    @Test
    fun `pairing_confirmed carries the sdk token`() {
        val token = "mgst_" + "A".repeat(43)
        val device = makeDevice(sdkToken = token)
        device.handleHello(hello())
        val generation = device.handleClientFinished(JSONObject(device.decrypt(clientFinishedRecord())))
        val mobile = Mobile()
        assertEquals(
            mapOf("type" to "status", "status" to "pairing_confirmed", "sdk_token" to token),
            mobile.open(device.encryptStatus("pairing_confirmed", generation)!!).toMap(),
        )
        assertEquals(
            mapOf("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", generation)!!).toMap(),
        )
    }

    @Test
    fun `device record is byte exact with the SDK`() {
        // extra_vectors.json › pairing_device_record: pairing_confirmed sealed with community_app_v5 keys.
        val record = JSONObject(javaClass.getResource("/muse/extra_vectors.json")!!.readText())
            .getJSONObject("pairing_device_record")
        val token = JSONObject(record.getString("plaintext")).getString("sdk_token")
        val device = makeDevice(sdkToken = token)
        device.handleHello(hello())
        val generation = device.handleClientFinished(JSONObject(device.decrypt(clientFinishedRecord())))
        val envelope = device.encryptStatus("pairing_confirmed", generation)!!
        // Same keys, nonce and AAD, so an equal ciphertext means an identical plaintext.
        val expected = record.getJSONObject("envelope")
        val wireOrder = listOf("type", "session_id", "counter", "ciphertext", "tag")
        assertEquals(wireOrder, envelope.keys.toList())
        assertEquals(wireOrder.map { expected.getString(it) }, wireOrder.map { envelope[it] })
        assertEquals(
            """{"type":"pairing_encrypted","session_id":"${expected.getString("session_id")}","counter":"0",""" +
                """"ciphertext":"${expected.getString("ciphertext")}","tag":"${expected.getString("tag")}"}""",
            MessageCodec.compactJson(envelope),
        )
    }

    @Test
    fun `device records count up from zero`() {
        val (device, mobile, generation) = confirmedDevice()
        val first = device.encryptStatus("pairing_confirmed", generation)!!
        val second = device.encryptJson("""{"type":"wifi_scan_result","networks":[]}""", generation)!!
        assertEquals("0" to "1", first["counter"] to second["counter"])
        assertEquals(mapOf("type" to "wifi_scan_result", "networks" to emptyList<Any>()), mobile.open(second).toMap())
    }

    // --- Hello validation ---

    @Test
    fun `hello rejects invalid input`() {
        val cases: List<Map<String, Any?>> = listOf(
            mapOf("version" to 4),
            mapOf("version" to "5"),
            mapOf("version" to true),
            mapOf("pairing_auth" to "fleet_ecdsa_p256_v1"),
            mapOf("pairing_policy" to "confirm_press"),
            mapOf("pairing_policy" to null),
            mapOf("mobile_pub" to ""),
            mapOf("mobile_pub" to b64(byteArrayOf(4) + ByteArray(64))),
            mapOf("mobile_pub" to b64(byteArrayOf(2) + ByteArray(32))),
            mapOf("mobile_pub" to APP.getString("mobile_pub") + "="),
            mapOf("mobile_nonce" to b64(ByteArray(15))),
            mapOf("mobile_nonce" to 7),
        )
        for (overrides in cases) {
            val device = makeDevice()
            val err = assertThrows(overrides.toString(), PairingError::class.java) { device.handleHello(hello(overrides = overrides)) }
            assertEquals("error_pairing_invalid_hello", err.status)
            assertEquals(SessionState.IDLE, device.state)
        }
    }

    @Test
    fun `version 5 as a JSON float is accepted like the firmware`() {
        makeDevice().handleHello(hello(overrides = mapOf("version" to 5.0)))
    }

    @Test
    fun `new hello replaces the previous session`() {
        val (device, _, generation) = confirmedDevice()
        device.handleHello(hello())
        assertFalse(device.isCurrent(generation))
        assertEquals(SessionState.WAIT_CLIENT_FINISHED, device.state)
    }

    // --- Record layer ---

    private fun expectDecryptFailure(device: PairingSession, envelope: JSONObject) {
        val err = assertThrows(PairingError::class.java) { device.decrypt(envelope) }
        assertEquals("error_pairing_decrypt", err.status)
        assertEquals(SessionState.IDLE, device.state)
    }

    private val wifiScan get() = JSONObject().put("action", "wifi_scan")

    @Test
    fun `replayed record clears the session`() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(wifiScan)
        device.decrypt(record)
        expectDecryptFailure(device, record)
    }

    @Test
    fun `skipped counter clears the session`() {
        val (device, mobile, _) = confirmedDevice()
        expectDecryptFailure(device, mobile.seal(wifiScan, counter = 2))
    }

    @Test
    fun `malformed counter clears the session`() {
        for (counter in listOf<Any>("-1", "+1", " 1", "1.0", "", "18446744073709551616", 1)) {
            val (device, mobile, _) = confirmedDevice()
            val record = mobile.seal(wifiScan).put("counter", counter)
            expectDecryptFailure(device, record)
        }
    }

    @Test
    fun `tampered ciphertext clears the session`() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(wifiScan)
        val raw = unb64(record.getString("ciphertext"))
        raw[0] = (raw[0].toInt() xor 1).toByte()
        expectDecryptFailure(device, record.put("ciphertext", b64(raw)))
    }

    @Test
    fun `wrong session id clears the session`() {
        val (device, mobile, _) = confirmedDevice()
        expectDecryptFailure(device, mobile.seal(wifiScan).put("session_id", b64(ByteArray(16))))
    }

    @Test
    fun `short tag clears the session`() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(wifiScan)
        expectDecryptFailure(device, record.put("tag", record.getString("tag").dropLast(2)))
    }

    @Test
    fun `decrypt without session fails`() {
        expectDecryptFailure(makeDevice(), clientFinishedRecord())
    }

    // --- Client finished ---

    @Test
    fun `first record must be exact client finished`() {
        val commands = listOf(
            JSONObject().put("action", "wifi_scan"),
            JSONObject().put("action", "pairing_client_finished").put("extra", true),
            JSONObject().put("action", "provision_v2").put("ssid", "x"),
        )
        for (command in commands) {
            val device = makeDevice()
            device.handleHello(hello())
            val plaintext = device.decrypt(Mobile().seal(command))
            assertEquals(0, device.handleClientFinished(JSONObject(plaintext)))
            assertEquals(SessionState.IDLE, device.state)
        }
    }

    @Test
    fun `client finished only counts as the first record`() {
        val (device, mobile, _) = confirmedDevice()
        val plaintext = device.decrypt(mobile.seal(JSONObject().put("action", "pairing_client_finished")))
        assertEquals(0, device.handleClientFinished(JSONObject(plaintext)))
        assertEquals(SessionState.IDLE, device.state)
    }

    // --- Timeouts and generations ---

    @Test
    fun `client finished times out after 60 seconds`() {
        val clock = FakeClock()
        val device = makeDevice(clock = clock)
        device.handleHello(hello())
        clock.now += 60_500
        expectDecryptFailure(device, clientFinishedRecord())
    }

    @Test
    fun `confirmed session expires after 120 seconds`() {
        val clock = FakeClock()
        val (device, _, generation) = confirmedDevice(clock)
        clock.now += 119_000
        assertTrue(device.confirmed)
        clock.now += 2_000
        assertFalse(device.confirmed)
        assertNull(device.encryptStatus("wifi_connected"))
        assertTrue(device.isCurrent(generation))
    }

    @Test
    fun `stale generation cannot encrypt`() {
        val (device, mobile, generation) = confirmedDevice()
        val provisioning = device.markProvisioning()
        assertTrue(provisioning != 0)
        assertNotEquals(generation, provisioning)
        assertNull(device.encryptStatus("wifi_connecting", generation))
        assertEquals(
            mapOf("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", provisioning)!!).toMap(),
        )
    }

    @Test
    fun `provisioning requires confirmation`() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(0, device.markProvisioning())
    }

    @Test
    fun `commit runs only for the current provisioning session`() {
        val clock = FakeClock()
        val (device, _, _) = confirmedDevice(clock)
        val generation = device.markProvisioning()
        val committed = mutableListOf<Int>()
        assertFalse(device.commitProvisioning(generation + 1) { committed.add(1); true })
        assertTrue(device.commitProvisioning(generation) { committed.add(2); true })
        assertEquals(listOf(2), committed)

        clock.now += 119_000
        assertTrue(device.extendProvisioning(generation))
        clock.now += 119_000
        assertTrue(device.commitProvisioning(generation) { true })
        clock.now += 2_000
        assertFalse(device.commitProvisioning(generation) { true })
    }
}
