package dev.tvlauncher.muse.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tvlauncher.BuildConfig
import dev.tvlauncher.R
import dev.tvlauncher.muse.link.ChatResult
import dev.tvlauncher.muse.link.LinkSession
import dev.tvlauncher.muse.link.MuseRuntime
import dev.tvlauncher.muse.voice.MuseVoicePlayer
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * One question at a time to Muse, from the TV's own side chat, with the answer streamed
 * back. Main-thread only: network calls run on a background thread and post back.
 */
class ChatController(private val context: Context, private val view: View) {
    interface View {
        fun showQuestion(text: String)

        /** Muse's latest status line, e.g. "Searching the web". */
        fun showStatus(text: String?)

        /** The answer so far; [done] once Muse is finished. */
        fun showReply(text: String, done: Boolean)

        fun showError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "muse-chat") }
    private val scope = ReplayScope()
    private var turn: MuseTurn? = null
    private var voice: MuseVoicePlayer? = null
    private var turnStarted = 0L
    private var closed = false

    /** Events come from the link's one chat subscription (see [MuseRuntime.ensureChatEvents]). */
    private val onEvent: (JSONObject) -> Unit = { event -> main.post { if (!closed) onChatEvent(event) } }

    init {
        MuseRuntime.addChatListener(onEvent)
    }

    val sideChatId: String get() = SideChat.id(context)

    val busy: Boolean get() = turn != null

    private val tick = object : Runnable {
        override fun run() {
            val current = turn ?: return
            val now = SystemClock.elapsedRealtime()
            val hasText = current.allReplies.any { it.text.isNotBlank() }
            when {
                current.acknowledged && hasText && current.complete(now) -> finish()
                current.acknowledged && current.settled(now) -> finish()
                now - maxOf(turnStarted, current.lastActivity) > SILENCE_TIMEOUT_MS -> fail(context.getString(R.string.muse_chat_no_answer))
                else -> main.postDelayed(this, TICK_MS)
            }
        }
    }

    /**
     * Sends [question]; the answer arrives on [view]. A [spoken] question is also answered out
     * loud in Muse's voice when the build enables it (BuildConfig.MUSE_VOICE_REPLIES).
     */
    fun ask(question: String, spoken: Boolean = false) {
        val text = question.trim()
        if (text.isEmpty()) return
        val voiced = spoken && BuildConfig.MUSE_VOICE_REPLIES
        send(text, voiced) { session, chat -> session.sendChat(text, chat, if (voiced) VOICE else "text") }
    }

    /** Sends a spoken question as a WAV voice note; [label] stands in for its words on screen. */
    fun askVoice(wav: ByteArray, label: String) {
        val voiced = BuildConfig.MUSE_VOICE_REPLIES
        send(label, voiced) { session, chat -> session.sendVoice(wav, chat, outputModality = if (voiced) VOICE else "text") }
    }

    private fun send(shown: String, spoken: Boolean, post: (LinkSession, String) -> ChatResult) {
        if (busy || closed) return
        voice?.stop()
        voice = if (spoken) MuseVoicePlayer() else null
        val chat = sideChatId
        val newTurn = MuseTurn(chat, ownsChat = true, replayScope = scope, clock = SystemClock::elapsedRealtime)
        turn = newTurn
        turnStarted = SystemClock.elapsedRealtime()
        view.showQuestion(shown)
        view.showStatus(context.getString(R.string.muse_chat_sending))
        io.execute {
            val outcome = runCatching {
                val session = MuseRuntime.session ?: throw IllegalStateException(context.getString(R.string.muse_chat_not_connected))
                val listening = try {
                    MuseRuntime.ensureChatEvents(context, session)
                    true
                } catch (e: Exception) {
                    // Without the event stream the question still goes; the answer shows in the app.
                    Log.w(TAG, "no chat events: $e")
                    false
                }
                var used = chat
                var result = post(session, chat)
                if (result.status == HTTP_NOT_FOUND) {
                    // Muse doesn't know this side chat (it belonged to an earlier pairing): start a new one.
                    used = SideChat.reset(context)
                    Log.i(TAG, "side chat not found; starting a new one")
                    result = post(session, used)
                }
                Triple(result, listening, used)
            }
            main.post {
                if (turn !== newTurn) return@post
                val used = outcome.getOrNull()?.third ?: chat
                val sent = if (used == chat) newTurn else MuseTurn(used, ownsChat = true, replayScope = scope, clock = SystemClock::elapsedRealtime).also { turn = it }
                onSent(sent, outcome.map { it.first to it.second })
            }
        }
        main.postDelayed(tick, TICK_MS)
    }

    fun close() {
        closed = true
        voice?.stop()
        voice = null
        turn = null
        main.removeCallbacks(tick)
        MuseRuntime.removeChatListener(onEvent)
        io.shutdown()
    }

    private fun onSent(sent: MuseTurn, outcome: Result<Pair<ChatResult, Boolean>>) {
        val (result, listening) = outcome.getOrElse {
            Log.w(TAG, "send failed: $it") // messages carry statuses, never tokens or bodies
            val notConnected = it is IllegalStateException
            return fail(if (notConnected) it.message.orEmpty() else context.getString(R.string.muse_chat_send_failed))
        }
        val response = result.response as? JSONObject
        Log.i(TAG, "chat ack: HTTP ${result.status} ${describeAck(response)}")
        if (!result.ok || response == null) return fail(context.getString(R.string.muse_chat_send_failed))
        if (!listening) {
            turn = null
            main.removeCallbacks(tick)
            view.showStatus(null)
            return view.showReply(context.getString(R.string.muse_chat_sent_without_events), done = false)
        }
        val events = try {
            sent.acknowledge(response)
        } catch (e: MuseTurnException) {
            // Muse didn't confirm the side chat as ours: follow the reply as on a shared chat.
            Log.w(TAG, "ack: ${e.message}; following it as a shared chat")
            val shared = MuseTurn(sent.sessionId, clock = SystemClock::elapsedRealtime)
            turn = shared
            runCatching { shared.acknowledge(response) }.getOrElse { return fail(context.getString(R.string.muse_chat_send_failed)) }
        }
        view.showStatus(null)
        render(events)
        // The first message created the side chat; follow its own event stream from now on.
        MuseRuntime.session?.let { session -> io.execute { MuseRuntime.narrowChatEvents(context, session) } }
    }

    private fun onChatEvent(event: JSONObject) {
        // Ids and names only; enable with `adb shell setprop log.tag.MuseChat DEBUG`.
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, describeEvent(event))
        val current = turn
        if (current == null) {
            runCatching { scope.observeIdle(event, sideChatId) }
            return
        }
        val events = try {
            current.feed(event)
        } catch (e: MuseTurnException) {
            return fail(context.getString(R.string.muse_chat_send_failed))
        }
        render(events)
    }

    private fun render(events: List<TurnEvent>) {
        val current = turn ?: return
        for (event in events) {
            if (event is Reply && event.bound) voice?.onReply(event.messageId, event.done)
            if (event is AgentStatus) view.showStatus(event.activityText?.takeIf { it.isNotBlank() })
        }
        if (events.any { it is Reply }) view.showReply(answerText(current), done = false)
    }

    private fun finish() {
        val current = turn ?: return
        turn = null
        main.removeCallbacks(tick)
        current.retire()
        view.showStatus(null)
        val answer = answerText(current)
        if (answer.isBlank()) view.showError(context.getString(R.string.muse_chat_empty_answer)) else view.showReply(answer, done = true)
    }

    private fun fail(message: String) {
        turn?.retire()
        turn = null
        main.removeCallbacks(tick)
        view.showStatus(null)
        view.showError(message)
    }

    /** Muse can answer in several messages; show them in order. */
    private fun answerText(turn: MuseTurn) = turn.allReplies.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Ids and names only, never text: what an event is about, for the log. */
    private fun describeEvent(event: JSONObject): String {
        val payload = event.optJSONObject("payload") ?: JSONObject()
        fun id(o: JSONObject, key: String) = (o.opt(key) as? String)?.take(40) ?: "-"
        val session = (payload.opt("session_id") ?: event.opt("session_id")) as? String
        return "event ${id(event, "event")} chat=${if (session == sideChatId) "tv" else session?.take(12) ?: "-"} " +
            "msg=${id(payload, "message_id")} parent=${id(payload, "reply_to_message_id")} role=${id(payload, "role")} " +
            "task=${id(payload, "task_id")} status=${id(payload, "status")}/${id(payload, "activity_code")}"
    }

    private fun describeAck(response: JSONObject?): String {
        val result = response?.optJSONObject("result") ?: response ?: return "(no body)"
        val session = result.opt("session_id") as? String
        return "chat=${if (session == sideChatId) "tv" else session?.take(12) ?: "-"} thread=${result.opt("is_thread")} " +
            "msg=${(result.opt("message_id") as? String)?.take(40) ?: "-"}"
    }

    private companion object {
        const val TAG = "MuseChat"
        const val VOICE = "voice"
        const val HTTP_NOT_FOUND = 404
        const val TICK_MS = 250L
        /** Give up when Muse has said nothing at all for this long. */
        const val SILENCE_TIMEOUT_MS = 90_000L
    }
}
