package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.MusePairing
import dev.tvlauncher.muse.net.Backoff
import dev.tvlauncher.muse.net.DeviceApi
import dev.tvlauncher.muse.net.FetchVmsResult
import dev.tvlauncher.muse.net.MuseVm
import dev.tvlauncher.muse.net.RefreshResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the pairing lives (encrypted [dev.tvlauncher.muse.MuseSecrets] on the TV). */
interface PairingStore {
    fun load(): MusePairing?

    fun save(pairing: MusePairing): Boolean

    fun delete()
}

/** The link as the UI sees it. */
sealed interface LinkState {
    object NotPaired : LinkState

    object Connecting : LinkState

    /** Registered with the Muse: it lists the TV, can invoke its commands and take its messages. */
    object Connected : LinkState

    /** Between attempts; the next one starts in [retryInMs]. */
    data class Offline(val retryInMs: Long) : LinkState
}

/**
 * Keeps a paired TV connected to its Muse (a port of the SDK's service.py). Each round
 * fetches the leased VMs with the device token (which also hands out a fresh per-VM
 * bearer), connects to the default VM and serves until the session ends. Failures back off
 * exponentially, and a session that stayed up a while resets the backoff. The device token
 * is rotated before it expires, and at once if the API rejects it.
 *
 * [run] blocks its thread until [stop].
 */
class MuseLink(
    private val store: PairingStore,
    /** The identity's node id (`homelink-…`), which is also the refresh call's device id. */
    private val nodeId: String,
    private val sdkToken: () -> String?,
    private val api: (apiUrlV2: String) -> DeviceApi,
    private val newSession: (noiseHost: String, vm: MuseVm, onRegistered: () -> Unit) -> LinkSession,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onState: (LinkState) -> Unit = {},
    private val log: (String) -> Unit = {},
    sleeper: ((Long) -> Unit)? = null,
) {
    private val lock = ReentrantLock()
    private val wakeUp = lock.newCondition()
    private val sleep: (Long) -> Unit = sleeper ?: ::waitUnlessWoken
    private var woken = false
    private var lastRefreshAttempt: Long? = null
    private var sdkTokenChangeWarned = false

    @Volatile private var stopped = false

    /** The live session, registered or not. */
    @Volatile var session: LinkSession? = null
        private set

    fun run() {
        val backoff = Backoff()
        while (!stopped) {
            val stored = store.load()
            if (stored == null) {
                log("not paired")
                onState(LinkState.NotPaired)
                sleep(UNPAIRED_POLL_MS)
                continue
            }
            warnIfSdkTokenChanged(stored)
            val pairing = maybeRefresh(stored)
            if (pairing == null) {
                sleep(TOKEN_RETRY_MS)
                continue
            }
            val vm = when (val result = api(pairing.apiUrlV2).fetchVms(pairing.accessToken)) {
                FetchVmsResult.TokenRejected -> {
                    log("device token rejected by the API; refreshing")
                    if (maybeRefresh(pairing, force = true) == null) sleep(TOKEN_RETRY_MS)
                    continue
                }
                is FetchVmsResult.Failed -> null.also { log("fetch_vms failed: ${result.status ?: "no response"} ${result.detail}") }
                is FetchVmsResult.Vms -> result.preferred.also {
                    log("vms: " + result.vms.joinToString { vm -> "${vm.name.take(24)}/${vm.id.take(8)}${if (vm.isDefault) "*" else ""}" })
                }
            }
            if (vm == null) {
                pause(backoff.nextDelayMs())
                continue
            }
            val (outcome, lastedMs) = runSession(vm, pairing)
            if (outcome == Outcome.STOPPED) return
            if (outcome == Outcome.UNPAIRED) {
                store.delete()
                log("pairing removed by the Muse; pair again")
                continue
            }
            pause(backoff.afterSession(lastedMs, outcome == Outcome.AUTH_REJECTED || outcome == Outcome.FORBIDDEN))
        }
    }

    /** Ends [run]; any thread. */
    fun stop() {
        stopped = true
        session?.stop()
        wake()
    }

    /** Cuts a wait short, e.g. right after pairing or when the network comes back. */
    fun wake() = lock.withLock {
        woken = true
        wakeUp.signalAll()
    }

    private fun runSession(vm: MuseVm, pairing: MusePairing): Pair<Outcome, Long> {
        val session = newSession(pairing.noiseHost.ifEmpty { DEFAULT_NOISE_HOST }, vm) { onState(LinkState.Connected) }
        log("connecting to ${vm.name.ifEmpty { vm.id }}")
        onState(LinkState.Connecting)
        val started = clock()
        this.session = session
        if (stopped) session.stop() // stop() may have run before the session was visible to it
        val outcome = try {
            session.run()
        } catch (e: Exception) {
            log("session failed: ${e.javaClass.simpleName}")
            Outcome.CLOSED
        } finally {
            this.session = null
        }
        val lasted = session.registeredAt?.let { clock() - it } ?: 0L
        log("session ended: ${outcome.name.lowercase()} after ${(clock() - started) / 1000}s")
        return outcome to lasted
    }

    private fun pause(delayMs: Long) {
        log("reconnecting in ${delayMs / 1000}s")
        onState(LinkState.Offline(delayMs))
        sleep(delayMs)
    }

    /**
     * The pairing with fresh tokens. Null only when a due refresh failed and the old token
     * shouldn't be used yet; the pairing is deleted when the Muse revoked it.
     */
    private fun maybeRefresh(pairing: MusePairing, force: Boolean = false): MusePairing? {
        if (!force && wallSeconds() - pairing.accessTokenSavedAt < TOKEN_REFRESH_AGE_S) return pairing
        val last = lastRefreshAttempt
        if (!force && last != null && clock() - last < TOKEN_RETRY_MS) return pairing
        lastRefreshAttempt = clock()
        return when (val result = api(pairing.apiUrlV2).refreshDeviceToken(nodeId, pairing.refreshToken, sdkToken())) {
            is RefreshResult.Refreshed -> {
                val rotated = pairing.copy(
                    accessToken = result.tokens.accessToken,
                    refreshToken = result.tokens.refreshToken,
                    accessTokenSavedAt = wallSeconds(),
                )
                if (store.save(rotated)) log("device token rotated") else log("device token rotated but not saved")
                rotated
            }
            RefreshResult.PairingRevoked -> {
                store.delete()
                log("pairing revoked; pair again")
                null
            }
            is RefreshResult.Failed -> {
                log("token refresh failed: ${result.status ?: "no response"} ${result.detail}")
                // Transient: keep using the current token while it still works.
                if (force) null else pairing
            }
        }
    }

    /** Muse records the SDK token only at pairing, so a token set later needs a new pairing. */
    private fun warnIfSdkTokenChanged(pairing: MusePairing) {
        if (sdkTokenChangeWarned || pairing.sdkTokenHint == MusePairing.sdkTokenHint(sdkToken())) return
        sdkTokenChangeWarned = true
        log("paired with SDK token ${pairing.sdkTokenHint}, not this one; pair again to use it")
    }

    private fun waitUnlessWoken(ms: Long) {
        lock.withLock {
            if (!woken && !stopped) wakeUp.await(ms, TimeUnit.MILLISECONDS)
            woken = false
        }
    }

    companion object {
        const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
        const val UNPAIRED_POLL_MS = 30_000L

        /** Device access tokens live about 4 hours; rotate at 3. */
        const val TOKEN_REFRESH_AGE_S = 3 * 3600L
        const val TOKEN_RETRY_MS = 300_000L
    }
}
