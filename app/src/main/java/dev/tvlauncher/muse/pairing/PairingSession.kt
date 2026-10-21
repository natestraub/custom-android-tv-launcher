package dev.tvlauncher.muse.pairing

import dev.tvlauncher.muse.crypto.Hkdf
import dev.tvlauncher.muse.wire.MessageCodec
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A handshake step failed; [status] is the wire error to report. */
class PairingError(val status: String) : Exception(status)

enum class SessionState { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

/** `mobileTx` decrypts what the device receives; `mobileRx` encrypts what it sends. */
class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

/**
 * Device side of Muse Gadget BLE pairing, protocol v5, community mode (a port of the
 * SDK's pairing.py). `pairing_auth` is "none" and the policy is `confirm_app`, so a valid
 * client-finished record confirms the session without a physical button. Wire format,
 * transcript, key schedule and records match the reference firmware, so the stock Muse
 * app pairs with it unchanged.
 *
 * Community pairing hides setup secrets from passive listeners. It doesn't authenticate
 * the ECDH peer, so it can't stop an active man-in-the-middle.
 *
 * Thread-safe. Steps that advance the handshake return a nonzero generation; deferred
 * work holds on to it and checks [isCurrent], so an abandoned attempt can't act on a
 * newer one. [clock] is monotonic milliseconds.
 */
class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val generateKey: () -> KeyPair = P256::generateKeyPair,
    private val randomBytes: (Int) -> ByteArray = { ByteArray(it).also(RANDOM::nextBytes) },
) {
    private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
    private val lock = Any()
    private var generation = 0
    private var phase = SessionState.IDLE
    private var deadline = 0L
    private var rxKey: SecretKeySpec? = null
    private var txKey: SecretKeySpec? = null
    private var sessionId = ""
    private var rxCounter = 0L
    private var txCounter = 0L

    init {
        synchronized(lock) { resetLocked() }
    }

    /** Pairing fields for the `get_device_info` response. */
    fun deviceInfo(): Map<String, Any> = linkedMapOf(
        "device_id" to deviceId,
        "mac" to mac,
        "model" to PAIRING_MODEL,
        "pairing_protocol" to PAIRING_VERSION,
        "pairing_auth" to AUTH_COMMUNITY,
        "pairing_auth_epoch" to 0,
        "pairing_policy" to POLICY_APP,
    )

    val state: SessionState
        get() = synchronized(lock) {
            expireLocked()
            phase
        }

    val confirmed: Boolean
        get() = state.let { it == SessionState.READY || it == SessionState.PROVISIONING }

    fun isCurrent(generation: Int): Boolean = synchronized(lock) { generation != 0 && generation == this.generation }

    fun reset() = synchronized(lock) { resetLocked() }

    /** Starts a session from `pairing_client_hello`; returns `pairing_ready` in wire key order. */
    fun handleHello(message: JSONObject): Map<String, Any> {
        val version = message.opt("version")
        if (version !is Number || version.toDouble() != PAIRING_VERSION.toDouble() ||
            message.opt("pairing_auth") != AUTH_COMMUNITY || message.opt("pairing_policy") != POLICY_APP
        ) {
            throw PairingError(ERROR_INVALID_HELLO)
        }
        synchronized(lock) {
            resetLocked()
            val mobilePub: ByteArray
            val mobileNonce: ByteArray
            val peer: PublicKey
            try {
                mobilePub = B64Url.decode(message.opt("mobile_pub") as? String ?: "")
                mobileNonce = B64Url.decode(message.opt("mobile_nonce") as? String ?: "")
                require(mobileNonce.size == NONCE_BYTES) { "invalid hello nonce" }
                peer = P256.decodePublic(mobilePub)
            } catch (e: IllegalArgumentException) {
                resetLocked()
                throw PairingError(ERROR_INVALID_HELLO)
            } catch (e: GeneralSecurityException) {
                resetLocked()
                throw PairingError(ERROR_INVALID_HELLO)
            }

            val deviceKey = generateKey()
            val devicePub = P256.encodePublic(deviceKey.public)
            val deviceNonce = randomBytes(NONCE_BYTES)
            val transcript = buildTranscript(
                community = true,
                authEpoch = 0,
                policy = POLICY_APP,
                deviceId = deviceId,
                nodeId = nodeId,
                mac = mac,
                firmwareVersion = firmwareVersion,
                mobilePub = B64Url.encode(mobilePub),
                devicePub = B64Url.encode(devicePub),
                mobileNonce = B64Url.encode(mobileNonce),
                deviceNonce = B64Url.encode(deviceNonce),
            )
            val transcriptHash = sha256(transcript.toByteArray())
            val keys = deriveSessionKeys(P256.ecdh(deviceKey.private, peer), mobileNonce, deviceNonce, transcriptHash)

            rxKey = SecretKeySpec(keys.mobileTx, "AES")
            txKey = SecretKeySpec(keys.mobileRx, "AES")
            sessionId = B64Url.encode(keys.sessionId)
            rxCounter = 0
            txCounter = 0
            phase = SessionState.WAIT_CLIENT_FINISHED
            deadline = clock() + CLIENT_FINISHED_TIMEOUT_MS
            return linkedMapOf(
                "type" to "pairing_ready",
                "version" to PAIRING_VERSION,
                "device_id" to deviceId,
                "node_id" to nodeId,
                "mac" to mac,
                "model" to PAIRING_MODEL,
                "firmware_version" to firmwareVersion,
                "pairing_auth" to AUTH_COMMUNITY,
                "pairing_auth_epoch" to 0,
                "pairing_policy" to POLICY_APP,
                "device_pub" to B64Url.encode(devicePub),
                "device_nonce" to B64Url.encode(deviceNonce),
                "transcript_hash" to B64Url.encode(transcriptHash),
                "session_id" to sessionId,
            )
        }
    }

    /**
     * Opens one phone-to-device `pairing_encrypted` record. Any failure (wrong session,
     * skipped or replayed counter, bad tag, expiry) clears the session, as the firmware does.
     */
    fun decrypt(envelope: JSONObject): String = synchronized(lock) {
        if (expireLocked() || phase == SessionState.IDLE) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
        val plaintext = try {
            require(envelope.opt("session_id") == sessionId) { "wrong session" }
            val counter = envelope.opt("counter") as? String
            require(counter != null && DECIMAL.matches(counter) && BigInteger(counter) == BigInteger.valueOf(rxCounter)) {
                "unexpected counter"
            }
            val ciphertext = B64Url.decode(envelope.opt("ciphertext") as? String ?: "", B64Url.MAX_CIPHERTEXT_CHARS)
            val tag = B64Url.decode(envelope.opt("tag") as? String ?: "")
            require(tag.size == TAG_BYTES) { "invalid tag length" }
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, rxKey, GCMParameterSpec(TAG_BYTES * 8, recordNonce(TO_DEVICE, rxCounter)))
            cipher.updateAAD(recordAad(sessionId, TO_DEVICE, rxCounter))
            strictUtf8(cipher.doFinal(ciphertext + tag))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: CharacterCodingException) {
            null
        }
        if (plaintext == null) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
        rxCounter++
        plaintext
    }

    /**
     * Confirms the session after the first decrypted record, which must be exactly
     * `{"action":"pairing_client_finished"}`. Under `confirm_app` the app has already
     * collected consent. Returns the new generation, or 0 after clearing the session.
     */
    fun handleClientFinished(command: JSONObject): Int = synchronized(lock) {
        val ok = command.length() == 1 && command.opt("action") == "pairing_client_finished" &&
            !expireLocked() && phase == SessionState.WAIT_CLIENT_FINISHED && rxCounter == 1L
        if (!ok) {
            resetLocked()
            return 0
        }
        generation++
        phase = SessionState.READY
        deadline = clock() + CONFIRMED_TIMEOUT_MS
        generation
    }

    /** Enters provisioning from a confirmed session; returns its generation, or 0. */
    fun markProvisioning(): Int = synchronized(lock) {
        if (!expireLocked() && phase == SessionState.READY) {
            generation++
            phase = SessionState.PROVISIONING
            deadline = clock() + PROVISIONING_TIMEOUT_MS
        }
        if (phase == SessionState.PROVISIONING) generation else 0
    }

    fun extendProvisioning(generation: Int): Boolean = synchronized(lock) {
        provisioningLocked(generation).also { if (it) deadline = clock() + PROVISIONING_TIMEOUT_MS }
    }

    /** Runs [commit] under the pairing lock if the session is still valid. It must only persist local state. */
    fun commitProvisioning(generation: Int, commit: () -> Boolean): Boolean =
        synchronized(lock) { provisioningLocked(generation) && commit() }

    /**
     * Seals a device-to-phone record. Null when there is no live session, or when a nonzero
     * [generation] no longer matches (the work it belongs to is stale).
     */
    fun encryptJson(plaintext: String, generation: Int = 0): Map<String, String>? = synchronized(lock) {
        if ((generation != 0 && generation != this.generation) || expireLocked() || phase == SessionState.IDLE) {
            return null
        }
        val counter = txCounter
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, txKey, GCMParameterSpec(TAG_BYTES * 8, recordNonce(FROM_DEVICE, counter)))
        cipher.updateAAD(recordAad(sessionId, FROM_DEVICE, counter))
        val sealed = cipher.doFinal(plaintext.toByteArray())
        txCounter++
        linkedMapOf(
            "type" to "pairing_encrypted",
            "session_id" to sessionId,
            "counter" to counter.toString(),
            "ciphertext" to B64Url.encode(sealed.copyOfRange(0, sealed.size - TAG_BYTES)),
            "tag" to B64Url.encode(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)),
        )
    }

    fun encryptStatus(status: String, generation: Int = 0): Map<String, String>? {
        val message = linkedMapOf("type" to "status", "status" to status)
        // Apps read only type and status, so older ones ignore the token.
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") message["sdk_token"] = sdkToken
        return encryptJson(MessageCodec.compactJson(message), generation)
    }

    private fun provisioningLocked(generation: Int) =
        generation != 0 && generation == this.generation && !expireLocked() && phase == SessionState.PROVISIONING

    private fun resetLocked() {
        generation++
        clearLocked()
    }

    private fun clearLocked() {
        phase = SessionState.IDLE
        deadline = 0
        rxKey = null
        txKey = null
        sessionId = ""
        rxCounter = 0
        txCounter = 0
    }

    private fun expireLocked(): Boolean {
        if (phase == SessionState.IDLE || clock() <= deadline) return false
        // Drop the keys but keep the generation, so the owner of the expired session can
        // still recognise it and close the connection.
        clearLocked()
        return true
    }

    companion object {
        const val PAIRING_VERSION = 5
        const val PAIRING_MODEL = "hatch_link"
        const val PAIRING_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
        const val POLICY_BUTTON = "confirm_press"
        const val POLICY_APP = "confirm_app"
        const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
        const val AUTH_COMMUNITY = "none"
        const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
        const val ERROR_DECRYPT = "error_pairing_decrypt"

        private const val BUTTON_CONFIRM_TIMEOUT_S = 60
        private const val CLIENT_FINISHED_TIMEOUT_MS = 60_000L
        private const val CONFIRMED_TIMEOUT_MS = 120_000L
        private const val PROVISIONING_TIMEOUT_MS = 120_000L
        private const val RECORD_LABEL = "hatch-link ble setup v1"
        private const val SESSION_ID_LABEL = "hatch-link session id v1"
        private const val NONCE_BYTES = 16
        private const val SESSION_ID_BYTES = 16
        private const val TAG_BYTES = 16
        private const val TO_DEVICE = 0
        private const val FROM_DEVICE = 1
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val DECIMAL = Regex("[0-9]+")
        private val RANDOM = SecureRandom()

        /** The canonical v5 transcript; its SHA-256 is the `transcript_hash`. */
        fun buildTranscript(
            community: Boolean,
            authEpoch: Int,
            policy: String,
            deviceId: String,
            nodeId: String,
            mac: String,
            firmwareVersion: String,
            mobilePub: String,
            devicePub: String,
            mobileNonce: String,
            deviceNonce: String,
        ): String {
            val button = policy == POLICY_BUTTON
            require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: $policy" }
            require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid auth epoch: $authEpoch" }
            require(listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce).none { it.isEmpty() }) {
                "transcript fields must be non-empty"
            }
            return listOf(
                "hatch-link-pairing-v$PAIRING_VERSION",
                "version=$PAIRING_VERSION",
                "initiator_role=mobile",
                "responder_role=link",
                "device_id=$deviceId",
                "node_id=$nodeId",
                "mac=$mac",
                "model=$PAIRING_MODEL",
                "firmware_version=$firmwareVersion",
                "selected_cipher_suite=$PAIRING_SUITE",
                "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
                "pairing_auth_epoch=$authEpoch",
                "pairing_policy=$policy",
                "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
                "mobile_pub=$mobilePub",
                "device_pub=$devicePub",
                "mobile_nonce=$mobileNonce",
                "device_nonce=$deviceNonce",
            ).joinToString("\n")
        }

        fun deriveSessionKeys(ecdhSecret: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): SessionKeys {
            val salt = sha256(mobileNonce + deviceNonce + transcriptHash)
            val sessionSecret = Hkdf.derive(ecdhSecret, salt, RECORD_LABEL.toByteArray(), 32)
            return SessionKeys(
                mobileTx = Hkdf.expand(sessionSecret, "mobile->device".toByteArray(), 32),
                mobileRx = Hkdf.expand(sessionSecret, "device->mobile".toByteArray(), 32),
                sessionId = sha256(SESSION_ID_LABEL.toByteArray() + transcriptHash + ecdhSecret).copyOf(SESSION_ID_BYTES),
            )
        }

        private fun recordNonce(direction: Int, counter: Long): ByteArray =
            byteArrayOf(direction.toByte(), 0, 0, 0) + ByteBuffer.allocate(8).putLong(counter).array()

        private fun recordAad(sessionId: String, direction: Int, counter: Long): ByteArray =
            "$RECORD_LABEL|$sessionId|${if (direction == TO_DEVICE) "m2d" else "d2m"}|$counter".toByteArray()

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
