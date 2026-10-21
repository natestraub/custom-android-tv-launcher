package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.MusePairing
import dev.tvlauncher.muse.net.DeviceApi
import dev.tvlauncher.muse.net.DeviceTokens
import dev.tvlauncher.muse.net.FetchVmsResult
import dev.tvlauncher.muse.net.MuseVm
import dev.tvlauncher.muse.net.RefreshResult
import dev.tvlauncher.muse.net.WebSocketUpgradeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** The service loop (the SDK's service.py) against fake storage, API and sessions. */
class MuseLinkTest {
    private val now = 1_760_000_000L

    private fun pairing(savedAt: Long = now, hint: String = "mgst_AAAAAAA") = MusePairing(
        accessToken = "access",
        refreshToken = "hatch_refresh:refresh",
        username = "someone",
        apiUrl = "",
        apiUrlV2 = "https://api.example",
        noiseHost = "",
        accessTokenSavedAt = savedAt,
        sdkTokenHint = hint,
    )

    private class Store(var pairing: MusePairing?) : PairingStore {
        val saved = CopyOnWriteArrayList<MusePairing>()
        var deleted = 0

        override fun load() = pairing

        override fun save(pairing: MusePairing): Boolean {
            this.pairing = pairing
            saved += pairing
            return true
        }

        override fun delete() {
            pairing = null
            deleted++
        }
    }

    private class Api : DeviceApi {
        val fetches = CopyOnWriteArrayList<String>()
        val refreshes = CopyOnWriteArrayList<Triple<String, String, String?>>()
        var fetchResults = ArrayDeque<FetchVmsResult>()
        var refreshResult: RefreshResult = RefreshResult.Refreshed(DeviceTokens("access2", "refresh2"))

        override fun fetchVms(accessToken: String): FetchVmsResult {
            fetches += accessToken
            return fetchResults.removeFirstOrNull() ?: FetchVmsResult.Vms(listOf(VM), 200)
        }

        override fun refreshDeviceToken(deviceId: String, refreshToken: String, sdkToken: String?): RefreshResult {
            refreshes += Triple(deviceId, refreshToken, sdkToken)
            return refreshResult
        }
    }

    private class Harness(
        store: PairingStore,
        api: DeviceApi,
        private val maxSleeps: Int,
        sdkToken: String? = "mgst_AAAAAAAtoken",
        connect: (String, Map<String, String>) -> LinkSocket = { _, _ -> throw IOException("down") },
        now: Long,
    ) {
        val sleeps = CopyOnWriteArrayList<Long>()
        val states = CopyOnWriteArrayList<LinkState>()
        val logs = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<Pair<String, MuseVm>>()
        lateinit var link: MuseLink

        init {
            link = MuseLink(
                store = store,
                nodeId = "homelink-abcdef",
                sdkToken = { sdkToken },
                api = { api },
                newSession = { host, vm, onRegistered ->
                    sessions += host to vm
                    LinkSession(host, vm.linkId, vm.authToken, DESCRIPTION, { _, _, _ -> mapOf("ok" to true) }, connect, onRegistered)
                },
                wallSeconds = { now },
                onState = { states += it },
                log = { logs += it },
                sleeper = { ms ->
                    sleeps += ms
                    if (sleeps.size >= maxSleeps) link.stop()
                },
            )
        }

        fun run() = link.run()
    }

    @Test
    fun `without a pairing it waits and polls`() {
        val h = Harness(Store(null), Api(), maxSleeps = 2, now = now)
        h.run()
        assertEquals(listOf(MuseLink.UNPAIRED_POLL_MS, MuseLink.UNPAIRED_POLL_MS), h.sleeps)
        assertEquals(LinkState.NotPaired, h.states.last())
    }

    @Test
    fun `a fresh token connects to the preferred vm on the default noise host`() {
        val api = Api()
        api.fetchResults += FetchVmsResult.Vms(listOf(VM.copy(id = "other", isDefault = false), VM), 200)
        val h = Harness(Store(pairing()), api, maxSleeps = 1, now = now)
        h.run()
        assertTrue(api.refreshes.isEmpty())
        assertEquals(listOf("access"), api.fetches)
        assertEquals(MuseLink.DEFAULT_NOISE_HOST to VM, h.sessions.single())
        assertEquals(listOf(2_000L), h.sleeps) // the session couldn't connect: backoff
        assertTrue(h.states.contains(LinkState.Connecting))
    }

    @Test
    fun `failures back off exponentially`() {
        val h = Harness(Store(pairing()), Api(), maxSleeps = 7, now = now)
        h.run()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), h.sleeps)
    }

    @Test
    fun `an upgrade refusal keeps retries at least 15 s apart`() {
        val h = Harness(Store(pairing()), Api(), maxSleeps = 2, now = now, connect = { _, _ -> throw WebSocketUpgradeException(401) })
        h.run()
        assertEquals(listOf(15_000L, 15_000L), h.sleeps)
    }

    @Test
    fun `a stale token is rotated and saved before connecting`() {
        val store = Store(pairing(savedAt = now - MuseLink.TOKEN_REFRESH_AGE_S))
        val api = Api()
        val h = Harness(store, api, maxSleeps = 1, now = now)
        h.run()
        assertEquals(listOf(Triple("homelink-abcdef", "hatch_refresh:refresh", "mgst_AAAAAAAtoken")), api.refreshes)
        assertEquals("access2" to "refresh2", store.saved.single().accessToken to store.saved.single().refreshToken)
        assertEquals(now, store.saved.single().accessTokenSavedAt)
        assertEquals(listOf("access2"), api.fetches)
    }

    @Test
    fun `a revoked pairing is deleted`() {
        val store = Store(pairing(savedAt = 0))
        val api = Api().apply { refreshResult = RefreshResult.PairingRevoked }
        val h = Harness(store, api, maxSleeps = 2, now = now)
        h.run()
        assertEquals(1, store.deleted)
        assertNull(store.pairing)
        assertEquals(LinkState.NotPaired, h.states.last())
    }

    @Test
    fun `a failed due refresh keeps using the old token`() {
        val store = Store(pairing(savedAt = 0))
        val api = Api().apply { refreshResult = RefreshResult.Failed(null, "IOException") }
        val h = Harness(store, api, maxSleeps = 1, now = now)
        h.run()
        assertEquals(listOf("access"), api.fetches)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a rejected access token forces a refresh`() {
        val store = Store(pairing())
        val api = Api()
        api.fetchResults += FetchVmsResult.TokenRejected
        val h = Harness(store, api, maxSleeps = 1, now = now)
        h.run()
        assertEquals(1, api.refreshes.size)
        assertEquals(listOf("access", "access2"), api.fetches)
    }

    @Test
    fun `a failed forced refresh waits out the retry interval`() {
        val api = Api().apply { refreshResult = RefreshResult.Failed(500, "HTTP 500") }
        api.fetchResults += FetchVmsResult.TokenRejected
        val h = Harness(Store(pairing()), api, maxSleeps = 1, now = now)
        h.run()
        assertEquals(listOf(MuseLink.TOKEN_RETRY_MS), h.sleeps)
    }

    @Test
    fun `no vm means backoff`() {
        val api = Api()
        api.fetchResults += FetchVmsResult.Vms(emptyList(), 200)
        api.fetchResults += FetchVmsResult.Failed(null, "IOException")
        val h = Harness(Store(pairing()), api, maxSleeps = 2, now = now)
        h.run()
        assertEquals(listOf(2_000L, 4_000L), h.sleeps)
        assertTrue(h.sessions.isEmpty())
        assertTrue(h.states.last() is LinkState.Offline)
    }

    @Test
    fun `the muse removing the device deletes the pairing`() {
        val store = Store(pairing())
        val vm = FakeVm()
        val h = Harness(store, Api(), maxSleeps = 1, now = now, connect = { _, _ -> vm.pipe.device })
        val loop = thread { h.run() }
        vm.register()
        vm.sendMessage(mapOf("type" to "evt", "event" to "link.unpaired"))
        loop.join(5_000)
        assertEquals(1, store.deleted)
        assertTrue(h.states.contains(LinkState.Connected))
        assertEquals(LinkState.NotPaired, h.states.last())
    }

    @Test
    fun `a token changed since pairing is reported once`() {
        val h = Harness(Store(pairing(hint = "mgst_BBBBBBB")), Api(), maxSleeps = 2, now = now)
        h.run()
        assertEquals(1, h.logs.count { it.startsWith("paired with SDK token mgst_BBBBBBB, not this one") })
    }

    @Test
    fun `stop ends a waiting loop`() {
        val link = MuseLink(Store(null), "homelink-abcdef", { null }, { Api() }, { _, _, _ -> error("no session") })
        val loop = thread { link.run() }
        Thread.sleep(50)
        link.stop()
        loop.join(2_000)
        assertTrue(!loop.isAlive)
    }

    companion object {
        val VM = MuseVm(url = "wss://vm.example", authToken = "vm-bearer", name = "vm-name", id = "vm-1", isDefault = true)
        val DESCRIPTION = DeviceDescription("homelink-abcdef", "tv", "0.1.0", emptyMap())
    }
}
