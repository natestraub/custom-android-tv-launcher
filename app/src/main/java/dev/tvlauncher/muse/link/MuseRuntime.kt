package dev.tvlauncher.muse.link

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.tvlauncher.BuildConfig
import dev.tvlauncher.muse.MusePairing
import dev.tvlauncher.muse.MuseSecrets
import dev.tvlauncher.muse.chat.SideChat
import dev.tvlauncher.muse.net.MuseApi
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The launcher's one Muse link. [MuseLinkService] owns its lifetime; the chat overlay
 * reads [state] and talks through [session]. Listeners run on the main thread.
 */
object MuseRuntime {
    private const val TAG = "MuseLink"
    private const val RELISTEN_DELAY_MS = 5_000L
    private const val MAX_LISTEN_ATTEMPTS = 20
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { Thread(it, "muse-commands").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(LinkState) -> Unit>()
    private val chatListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val commandListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val chatLock = Any()
    private var chatEvents: ChatSubscription? = null
    private var chatEventsSession: LinkSession? = null
    private var chatEventsScoped = false
    private var link: MuseLink? = null

    @Volatile var state: LinkState = LinkState.NotPaired
        private set

    /** The session registered with the Muse, if any. */
    val session: LinkSession? get() = link?.session?.takeIf { it.isRegistered }

    @Synchronized
    fun start(context: Context) {
        link?.let {
            it.wake()
            return
        }
        val app = context.applicationContext
        val secrets = MuseSecrets(app)
        val identity = secrets.identity()
        val commands = TvCommands(app)
        val userAgent = MuseApi.userAgent(Build.MODEL)
        val device = DeviceDescription(identity.nodeId, displayName(app), BuildConfig.VERSION_NAME, TvCommands.SPECS)
        val link = MuseLink(
            store = SecretsStore(secrets),
            nodeId = identity.nodeId,
            sdkToken = { secrets.sdkToken },
            api = { apiUrlV2 -> MuseApi(apiUrlV2, userAgent) },
            newSession = { host, vm, onRegistered ->
                lateinit var session: LinkSession
                session = LinkSession(
                    noiseHost = host,
                    vmId = vm.linkId,
                    vmAuthToken = vm.authToken,
                    device = device,
                    runCommand = { command, params, timeoutMs ->
                        commands.run(command, params, timeoutMs).also { result ->
                            if (result["ok"] == true) main.post { commandListeners.forEach { it(command) } }
                        }
                    },
                    connect = LinkSession.webSocketConnector(userAgent),
                    onRegistered = {
                        onRegistered()
                        keepChatEvents(app, session)
                    },
                    log = ::log,
                )
                session
            },
            onState = ::publish,
            log = ::log,
        )
        this.link = link
        publish(LinkState.Connecting)
        thread(name = "muse-link", isDaemon = true) { link.run() }
    }

    @Synchronized
    fun stop() {
        link?.stop()
        link = null
        publish(LinkState.NotPaired)
    }

    /** Retry now instead of waiting out a backoff. */
    fun wake() = link?.wake()

    /** Adds [listener] and tells it the current state right away. */
    fun addListener(listener: (LinkState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (LinkState) -> Unit) {
        listeners -= listener
    }

    /** Chat events for the overlay; called on the link's reader thread, so hand them off. */
    fun addChatListener(listener: (JSONObject) -> Unit) {
        chatListeners += listener
    }

    fun removeChatListener(listener: (JSONObject) -> Unit) {
        chatListeners -= listener
    }

    /** The name of each TV command Muse ran successfully, on the main thread. */
    fun addCommandListener(listener: (String) -> Unit) {
        commandListeners += listener
    }

    fun removeCommandListener(listener: (String) -> Unit) {
        commandListeners -= listener
    }

    /**
     * Opens [session]'s one chat subscription unless it's open; blocks, so background threads
     * only. One, because Muse delivers this TV's commands as `client.invoke` events to the
     * newest subscription only (the session answers them), and the overlay's events share it.
     * Until the TV's side chat exists (its first message creates it) this listens account-wide.
     */
    fun ensureChatEvents(context: Context, session: LinkSession) {
        synchronized(chatLock) {
            if (chatEventsSession === session && chatEvents?.isOpen == true) return
            chatEvents?.close()
            val listener = fanOut(context, session)
            chatEvents = try {
                session.subscribeChat(SideChat.id(context), listener).also { chatEventsScoped = true }
            } catch (e: HttpStreamError) {
                if (e.status != 404) throw e
                session.subscribeChat(null, listener).also { chatEventsScoped = false }
            }
            chatEventsSession = session
        }
        log("listening for Muse chat events and commands")
    }

    /** Swaps an account-wide subscription for the side chat's own once it exists. */
    fun narrowChatEvents(context: Context, session: LinkSession) {
        synchronized(chatLock) {
            if (chatEventsScoped || chatEventsSession !== session) return
            try {
                val scoped = session.subscribeChat(SideChat.id(context), fanOut(context, session))
                chatEvents?.close()
                chatEvents = scoped
                chatEventsScoped = true
            } catch (e: Exception) {
                log("side chat events still unavailable: $e")
                return
            }
        }
        log("listening to the TV's side chat")
    }

    private fun fanOut(context: Context, session: LinkSession) = object : ChatSubscription.Listener {
        override fun onEvent(event: JSONObject) {
            chatListeners.forEach { it(event) }
        }

        override fun onEnd(error: Exception?) {
            // Runs on the reader thread, which ensureChatEvents may be waiting on: don't lock here.
            background.execute {
                synchronized(chatLock) {
                    if (chatEvents?.isOpen != true) {
                        chatEvents = null
                        chatEventsSession = null
                    }
                }
                log("chat events ended${error?.let { ": $it" } ?: ""}")
                keepChatEvents(context, session, delayMs = RELISTEN_DELAY_MS)
            }
        }
    }

    /** Keeps the chat subscription up for as long as [session] is registered. */
    private fun keepChatEvents(context: Context, session: LinkSession, attempt: Int = 0, delayMs: Long = 0) {
        main.postDelayed({
            background.execute {
                if (!session.isRegistered || attempt > MAX_LISTEN_ATTEMPTS) return@execute
                try {
                    ensureChatEvents(context, session)
                } catch (e: Exception) {
                    log("can't listen for Muse commands: $e")
                    keepChatEvents(context, session, attempt + 1, RELISTEN_DELAY_MS)
                }
            }
        }, delayMs)
    }

    private fun publish(state: LinkState) {
        this.state = state
        main.post { listeners.forEach { it(state) } }
    }

    private fun log(message: String) = Log.i(TAG, message)

    /** What the Muse app lists the TV as: the name set in the TV's settings. */
    private fun displayName(context: Context) =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.ifEmpty { null } ?: Build.MODEL

    private class SecretsStore(private val secrets: MuseSecrets) : PairingStore {
        override fun load(): MusePairing? = secrets.pairing

        override fun save(pairing: MusePairing) = secrets.savePairing(pairing)

        override fun delete() {
            secrets.savePairing(null)
        }
    }
}
