package dev.tvlauncher.muse

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tvlauncher.BuildConfig
import dev.tvlauncher.R
import dev.tvlauncher.muse.ble.MuseGattServer
import dev.tvlauncher.muse.link.MuseLinkService
import dev.tvlauncher.muse.net.FetchVmsResult
import dev.tvlauncher.muse.net.MuseApi
import dev.tvlauncher.muse.pairing.Credentials
import dev.tvlauncher.muse.pairing.PairingSession
import dev.tvlauncher.muse.pairing.ProvisionFailed
import dev.tvlauncher.muse.pairing.SetupController
import dev.tvlauncher.muse.pairing.SetupEvent
import dev.tvlauncher.muse.pairing.SetupNetwork
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

/**
 * Pairing for real: opens a 10-minute Bluetooth window as a Muse gadget, runs the setup
 * protocol with the Muse app, checks the device tokens it hands over with Muse and saves
 * them. The walkthrough gets states on the main thread.
 */
class MusePairingFlow(private val context: Context, private val secrets: MuseSecrets) : PairingFlow {
    private val main = Handler(Looper.getMainLooper())
    private var run: Run? = null

    override fun start(onState: (PairingState) -> Unit) {
        cancel()
        val token = secrets.sdkToken ?: return onState(PairingState.Failed(context.getString(R.string.muse_error_no_token)))
        run = Run(onState, token).also { it.start() }
    }

    override fun cancel() {
        run?.close()
        run = null
    }

    /** One pairing window. Callbacks from a closed window are ignored. */
    private inner class Run(private val onState: (PairingState) -> Unit, private val sdkToken: String) : MuseGattServer.Listener {
        private val identity = secrets.identity()
        private val deadline = System.currentTimeMillis() + WINDOW_MS
        private val pairing = PairingSession(
            nodeId = identity.nodeId,
            deviceId = identity.deviceId,
            mac = identity.mac,
            firmwareVersion = BuildConfig.VERSION_NAME,
            sdkToken = sdkToken,
        )
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "muse-setup") }
        private val provisioner = Executors.newSingleThreadExecutor { Thread(it, "muse-provision") }
        private val server = MuseGattServer(context, identity.bleName, this)
        private val controller = SetupController(
            pairing = pairing,
            identity = identity,
            version = BuildConfig.VERSION_NAME,
            transport = server,
            network = Reachability,
            provision = ::provision,
            worker = worker,
            provisioner = provisioner,
            onEvent = { event -> main.post { onEvent(event) } },
            log = { Log.i(TAG, it) },
        )
        private var step = PairingStep.WAITING_FOR_PHONE
        private var finished = false
        private var shutDown = false
        private val timeout = Runnable { fail(context.getString(R.string.muse_error_timeout)) }

        fun start() {
            show()
            server.start()
            main.postDelayed(timeout, WINDOW_MS)
        }

        /** The walkthrough closed: stop now, and keep a sign-in still in flight from saving. */
        fun close() {
            val hadFinished = finished
            finished = true
            main.removeCallbacks(timeout)
            pairing.reset()
            if (!hadFinished) shutDown()
        }

        override fun onOpened(error: String?) {
            if (error != null) main.post { fail(context.getString(R.string.muse_error_bluetooth, error)) }
        }

        override fun onConnected() {
            main.post { advance(PairingStep.SECURING) }
        }

        override fun onMessage(message: ByteArray) = controller.onMessage(message)

        override fun onDisconnected() = controller.onDisconnect()

        private fun onEvent(event: SetupEvent) {
            when (event) {
                SetupEvent.Secured, SetupEvent.Confirmed -> advance(PairingStep.CONFIRMING)
                SetupEvent.SigningIn -> advance(PairingStep.SIGNING_IN)
                SetupEvent.Completed -> {
                    MuseLinkService.start(context) // connect to Muse right away so the app lists the TV
                    finish(PairingState.Done)
                }
                is SetupEvent.Failed -> fail(failureMessage(event.status))
                // The phone left; it can connect again while the window is open.
                SetupEvent.Reset -> if (step != PairingStep.WAITING_FOR_PHONE) setStep(PairingStep.WAITING_FOR_PHONE)
            }
        }

        private fun advance(to: PairingStep) {
            if (to.ordinal > step.ordinal) setStep(to)
        }

        private fun setStep(to: PairingStep) {
            if (finished) return
            step = to
            show()
        }

        private fun show() {
            if (!finished) onState(PairingState.InProgress(identity.bleName, deadline, step))
        }

        private fun fail(reason: String) = finish(PairingState.Failed(reason))

        private fun finish(state: PairingState) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            onState(state)
            // Give the last notifications and the app's own disconnect time to go out.
            main.postDelayed(::shutDown, SHUTDOWN_DELAY_MS)
        }

        private fun shutDown() {
            if (shutDown) return
            shutDown = true
            server.stop()
            worker.shutdown()
            provisioner.shutdown()
        }

        private fun failureMessage(status: String): String = when (status) {
            "auth_failed" -> context.getString(R.string.muse_error_auth)
            "wifi_failed" -> context.getString(R.string.muse_error_offline)
            "error_storage" -> context.getString(R.string.muse_error_storage)
            else -> context.getString(R.string.muse_error_other, status)
        }

        /** The SDK's `_verify_and_save`: the tokens must fetch at least one VM before they're kept. */
        private fun provision(credentials: Credentials, commit: (save: () -> Boolean) -> Boolean) {
            val apiUrl = credentials.apiUrl.takeIf { it.startsWith("https://") }.orEmpty()
            val apiUrlV2 = credentials.apiUrlV2.takeIf { it.startsWith("https://") }.orEmpty()
            val check = MuseApi(apiUrlV2, MuseApi.userAgent(Build.MODEL)).fetchVms(credentials.accessToken)
            if (check !is FetchVmsResult.Vms || check.vms.isEmpty()) {
                Log.w(TAG, "device token check failed: $check")
                throw ProvisionFailed("auth_failed")
            }
            val record = MusePairing(
                accessToken = credentials.accessToken,
                refreshToken = credentials.refreshToken,
                username = credentials.username,
                apiUrl = apiUrl,
                apiUrlV2 = apiUrlV2,
                noiseHost = credentials.noiseHost,
                accessTokenSavedAt = System.currentTimeMillis() / 1000,
                sdkTokenHint = MusePairing.sdkTokenHint(sdkToken),
            )
            if (!commit { secrets.savePairing(record) }) throw ProvisionFailed("error_storage")
            Log.i(TAG, "paired as ${credentials.username.ifEmpty { "?" }}")
        }
    }

    /** Online means the Muse API takes a TCP connection, as upstream checks. A success is reused briefly. */
    private object Reachability : SetupNetwork {
        private val host = URI(MuseApi.API_BASE).host
        @Volatile private var onlineAt = 0L

        override fun isOnline(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (onlineAt != 0L && now - onlineAt < ONLINE_REUSE_MS) return true
            val online = try {
                Socket().use { it.connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS) }
                true
            } catch (e: IOException) {
                false
            }
            onlineAt = if (online) now else 0L
            return online
        }

        // Marked open so the app skips the password field; the TV ignores the Wi-Fi details anyway.
        override fun currentConnectionEntry(): Map<String, Any> =
            linkedMapOf("ssid" to "Use current connection", "rssi" to -40, "secure" to false)
    }

    private companion object {
        const val TAG = "MusePairing"
        const val WINDOW_MS = 10 * 60_000L
        const val SHUTDOWN_DELAY_MS = 1_500L
        const val ONLINE_REUSE_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
