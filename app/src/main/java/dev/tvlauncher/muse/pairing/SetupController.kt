package dev.tvlauncher.muse.pairing

import dev.tvlauncher.muse.wire.MessageCodec
import dev.tvlauncher.muse.wire.parseJsonObject
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Where setup replies go: the BLE GATT server. */
interface SetupTransport {
    /** Queues one message for the phone: chunk-framed when [framed], else a single raw packet. */
    fun send(message: ByteArray, framed: Boolean)

    fun disconnect(delayMillis: Long)
}

interface SetupNetwork {
    fun isOnline(): Boolean

    /** The single Wi-Fi entry offered to the phone; the TV is already online, so it's the only one. */
    fun currentConnectionEntry(): Map<String, Any>
}

/** Device tokens from `provision_v2`. [toString] leaves the tokens out so they can't reach a log. */
data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
) {
    override fun toString() = "Credentials(username=$username, apiUrlV2=$apiUrlV2, noiseHost=$noiseHost)"
}

/** Setup could not finish; [status] is what the app is told. */
class ProvisionFailed(val status: String) : Exception(status)

/** Setup progress, for the pairing screen. */
sealed interface SetupEvent {
    /** Hello accepted: keys agreed, waiting for the app to confirm. */
    object Secured : SetupEvent

    /** The app confirmed; it sends the account tokens once a network is chosen. */
    object Confirmed : SetupEvent

    /** Tokens received; checking them with Muse. */
    object SigningIn : SetupEvent

    object Completed : SetupEvent

    /** Provisioning failed; [status] is what the app was told (e.g. `auth_failed`). */
    data class Failed(val status: String) : SetupEvent

    /** The phone disconnected; the session is gone and it has to start over. */
    object Reset : SetupEvent
}

/**
 * The setup commands behind the GATT characteristics (a port of the SDK's ble_setup.py).
 * The GATT server reassembles writes into [onMessage] and frames what [SetupTransport.send]
 * gets. Messages run one at a time on [worker] in arrival order, and every send happens
 * under one lock so encrypted record counters reach the phone in order.
 *
 * [provision] runs on [provisioner]. It must verify the credentials and persist them
 * through its commit callback (which runs under the pairing lock), throwing
 * [ProvisionFailed] on failure.
 */
class SetupController(
    private val pairing: PairingSession,
    private val identity: GadgetIdentity,
    private val version: String,
    private val transport: SetupTransport,
    private val network: SetupNetwork,
    private val provision: (Credentials, commit: (save: () -> Boolean) -> Boolean) -> Unit,
    private val worker: Executor,
    private val provisioner: Executor,
    private val onEvent: (SetupEvent) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var provisioning = false

    /** One reassembled write from the phone; any thread. */
    fun onMessage(raw: ByteArray) {
        try {
            worker.execute {
                try {
                    handleMessage(raw)
                } catch (e: Exception) {
                    log("setup command failed: $e")
                }
            }
        } catch (e: RejectedExecutionException) {
            log("setup closed; dropped a message") // a write raced the pairing window closing
        }
    }

    fun onDisconnect() {
        log("BLE client disconnected; clearing pairing session")
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
        onEvent(SetupEvent.Reset)
    }

    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val command = parseJsonObject(raw)
        if (command == null) {
            log("invalid command JSON (${raw.size} bytes)")
            sendStatus("error_invalid_command")
            return
        }
        val action = command.opt("action") as? String ?: ""
        log("RX action: ${action.ifEmpty { "?" }.take(64)}${if (decrypted) " (encrypted)" else ""}")
        val blocked = synchronized(stateLock) { plaintextBlocked }
        when {
            !decrypted && action == "pairing_client_hello" -> handleHello(command)
            !decrypted && action == "pairing_encrypted" -> handleRecord(command)
            // Public metadata, fine in plaintext at any point. Apps re-read it when they
            // restart a handshake on the same connection.
            action == "get_device_info" -> sendJson(deviceInfo())
            !decrypted && blocked -> log("plaintext command ignored after pairing started")
            !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
            decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> handleWifiScan()
            decrypted && action == "provision_v2" -> handleProvision(command)
            else -> sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
        put("type", "device_info")
        put("node_id", identity.nodeId)
        put("version", version)
        putAll(pairing.deviceInfo())
        put("build_sha", "")
        put("network_ready", network.isOnline())
    }

    private fun handleHello(command: JSONObject) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingError) {
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        sendJson(ready)
        onEvent(SetupEvent.Secured)
    }

    private fun handleRecord(envelope: JSONObject) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingError) {
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        handleMessage(plaintext.toByteArray(), decrypted = true)
    }

    private fun handleClientFinished(command: JSONObject) {
        val generation = pairing.handleClientFinished(command)
        if (generation == 0) {
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        log("pairing confirmed (app consent)")
        sendStatus("pairing_confirmed", generation)
        onEvent(SetupEvent.Confirmed)
    }

    private fun handleWifiScan() {
        val networks = if (network.isOnline()) listOf(network.currentConnectionEntry()) else emptyList()
        sendEncryptedJson(linkedMapOf("type" to "wifi_scan_result", "networks" to networks))
    }

    private fun handleProvision(command: JSONObject) {
        fun text(key: String) = command.opt(key) as? String ?: ""
        if (command.opt("ssid") !is String || command.opt("password") !is String ||
            text("access_token").isEmpty() || text("refresh_token").isEmpty() || text("token_type") != "device"
        ) {
            sendStatus("error_missing_credentials")
            return
        }
        var generation = 0
        val refusal = synchronized(stateLock) {
            if (provisioning) {
                "error_operation_in_progress"
            } else {
                generation = pairing.markProvisioning()
                provisioning = generation != 0
                if (provisioning) null else "error_pairing_confirm_required"
            }
        }
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // The Wi-Fi fields are dropped on purpose: the TV only sets up when it's already online.
        val credentials = Credentials(
            accessToken = text("access_token"),
            refreshToken = text("refresh_token"),
            username = text("username"),
            apiUrl = text("api_url"),
            apiUrlV2 = text("api_url_v2"),
            noiseHost = text("noise_host"),
        )
        onEvent(SetupEvent.SigningIn)
        provisioner.execute { runProvision(credentials, generation) }
    }

    private fun runProvision(credentials: Credentials, generation: Int) {
        try {
            sendStatus("wifi_connecting", generation)
            if (!network.isOnline()) {
                // Stay in provisioning so the app can retry, as the firmware does.
                pairing.extendProvisioning(generation)
                sendStatus("wifi_failed", generation)
                onEvent(SetupEvent.Failed("wifi_failed"))
                return
            }
            sendStatus("wifi_connected", generation)
            val failure = try {
                provision(credentials) { save -> pairing.commitProvisioning(generation, save) }
                null
            } catch (e: ProvisionFailed) {
                e.status
            } catch (e: Exception) {
                // The SDK's thread would just die here; telling the app lets the user retry.
                log("provisioning crashed: ${e.javaClass.simpleName}")
                "auth_failed"
            }
            if (failure != null) {
                log("provisioning failed: $failure")
                sendStatus(failure, generation)
                transport.disconnect(DISCONNECT_AFTER_PROVISION_FAILURE_MS)
                onEvent(SetupEvent.Failed(failure))
                return
            }
            sendStatus("auth_ok", generation)
            log("setup complete")
            onEvent(SetupEvent.Completed)
        } finally {
            synchronized(stateLock) { provisioning = false }
        }
    }

    private fun sendStatus(status: String, generation: Int = 0): Unit = synchronized(txLock) {
        val envelope = pairing.encryptStatus(status, generation)
        if (envelope != null) {
            transport.send(MessageCodec.compactJson(envelope).toByteArray(), framed = true)
            log("TX status (encrypted #${envelope["counter"]}): $status")
            return
        }
        val blocked = synchronized(stateLock) { plaintextBlocked }
        if (generation != 0 || blocked || status !in PLAINTEXT_STATUSES) {
            log("TX status suppressed: $status")
            return
        }
        transport.send(status.toByteArray(), framed = false)
        log("TX status: $status")
    }

    private fun sendJson(message: Map<String, Any?>): Unit = synchronized(txLock) {
        transport.send(MessageCodec.compactJson(message).toByteArray(), framed = true)
        log("TX ${message["type"]}")
    }

    private fun sendEncryptedJson(message: Map<String, Any?>, generation: Int = 0): Unit = synchronized(txLock) {
        val envelope = pairing.encryptJson(MessageCodec.compactJson(message), generation)
        if (envelope == null) {
            log("TX ${message["type"]} suppressed: no session")
            return
        }
        transport.send(MessageCodec.compactJson(envelope).toByteArray(), framed = true)
        log("TX ${message["type"]} (encrypted #${envelope["counter"]})")
    }

    companion object {
        val SENSITIVE_ACTIONS = setOf(
            "provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth",
        )
        val PLAINTEXT_STATUSES = setOf(
            "error_encryption_required",
            "error_pairing_invalid_hello",
            "error_pairing_unavailable",
            "error_pairing_decrypt",
        )
        const val DISCONNECT_AFTER_ERROR_MS = 300L
        const val DISCONNECT_AFTER_PROVISION_FAILURE_MS = 500L
    }
}
