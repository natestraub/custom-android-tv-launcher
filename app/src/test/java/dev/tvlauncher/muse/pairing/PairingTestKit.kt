package dev.tvlauncher.muse.pairing

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECPrivateKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared fixtures for the pairing tests (ported from the SDK's tests/test_pairing.py). */
object PairingTestKit {
    val VECTORS: List<JSONObject> = run {
        val text = PairingTestKit::class.java.getResource("/muse/link_pairing_v5.json")!!.readText()
        val array = JSONObject(text).getJSONArray("vectors")
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    val APP: JSONObject = VECTORS.first { it.getString("name") == "community_app_v5" }

    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    fun b64(data: ByteArray) = B64Url.encode(data)

    fun unb64(text: String) = B64Url.decode(text)

    class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke() = now
    }

    /** Device key pair from the vector's fixed scalar (public point taken from the vector). */
    fun vectorKeyPair(vector: JSONObject = APP): KeyPair {
        val params = P256.params
        val private = KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(vector.getString("device_private_scalar_hex"), 16), params))
        return KeyPair(P256.decodePublic(unb64(vector.getString("device_pub"))), private)
    }

    fun makeDevice(vector: JSONObject = APP, clock: FakeClock = FakeClock(), sdkToken: String? = null) =
        PairingSession(
            nodeId = vector.getString("node_id"),
            deviceId = vector.getString("device_id"),
            mac = vector.getString("mac"),
            firmwareVersion = vector.getString("firmware_version"),
            sdkToken = sdkToken,
            clock = clock,
            generateKey = { vectorKeyPair(vector) },
            randomBytes = { unb64(vector.getString("device_nonce")) },
        )

    fun hello(vector: JSONObject = APP, overrides: Map<String, Any?> = emptyMap()): JSONObject {
        val message = JSONObject()
            .put("action", "pairing_client_hello")
            .put("version", 5)
            .put("pairing_auth", "none")
            .put("pairing_policy", "confirm_app")
            .put("mobile_pub", vector.getString("mobile_pub"))
            .put("mobile_nonce", vector.getString("mobile_nonce"))
        for ((k, v) in overrides) message.put(k, v ?: JSONObject.NULL)
        return message
    }

    fun clientFinishedRecord(vector: JSONObject = APP): JSONObject = JSONObject()
        .put("action", "pairing_encrypted")
        .put("session_id", vector.getString("session_id"))
        .put("counter", "0")
        .put("ciphertext", vector.getString("client_finished_ciphertext"))
        .put("tag", vector.getString("client_finished_tag"))

    /** Mobile side of the record layer, written from the contract, not from the device code. */
    class Mobile(vector: JSONObject = APP) {
        private val tx = hex(vector.getString("mobile_tx_key_hex"))
        private val rx = hex(vector.getString("mobile_rx_key_hex"))
        val sessionId: String = vector.getString("session_id")
        var txCounter = 0L

        private fun nonce(direction: Int, counter: Long) =
            byteArrayOf(direction.toByte(), 0, 0, 0) + java.nio.ByteBuffer.allocate(8).putLong(counter).array()

        private fun aad(arrow: String, counter: Long) = "hatch-link ble setup v1|$sessionId|$arrow|$counter".toByteArray()

        fun seal(command: JSONObject, counter: Long? = null): JSONObject {
            val n = counter ?: txCounter++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(tx, "AES"), GCMParameterSpec(128, nonce(0, n)))
            cipher.updateAAD(aad("m2d", n))
            val sealed = cipher.doFinal(command.toString().toByteArray())
            return JSONObject()
                .put("action", "pairing_encrypted")
                .put("session_id", sessionId)
                .put("counter", n.toString())
                .put("ciphertext", b64(sealed.copyOfRange(0, sealed.size - 16)))
                .put("tag", b64(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }

        fun open(envelope: Map<String, Any?>): JSONObject {
            check(envelope["type"] == "pairing_encrypted")
            check(envelope["session_id"] == sessionId)
            val counter = (envelope["counter"] as String).toLong()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rx, "AES"), GCMParameterSpec(128, nonce(1, counter)))
            cipher.updateAAD(aad("d2m", counter))
            val plain = cipher.doFinal(unb64(envelope["ciphertext"] as String) + unb64(envelope["tag"] as String))
            return JSONObject(String(plain))
        }
    }

    fun confirmedDevice(clock: FakeClock = FakeClock()): Triple<PairingSession, Mobile, Int> {
        val device = makeDevice(clock = clock)
        device.handleHello(hello())
        val command = JSONObject(device.decrypt(clientFinishedRecord()))
        val generation = device.handleClientFinished(command)
        val mobile = Mobile()
        mobile.txCounter = 1
        return Triple(device, mobile, generation)
    }

    /** JSONObject → plain map for equality checks against expected maps. */
    fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k ->
        when (val v = get(k)) {
            JSONObject.NULL -> null
            is JSONObject -> v.toMap()
            is org.json.JSONArray -> (0 until v.length()).map { i -> (v.get(i) as? JSONObject)?.toMap() ?: v.get(i) }
            is Number -> v.toLong().takeIf { it.toDouble() == v.toDouble() } ?: v
            else -> v
        }
    }
}
