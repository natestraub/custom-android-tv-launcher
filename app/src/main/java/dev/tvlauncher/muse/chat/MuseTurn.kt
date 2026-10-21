package dev.tvlauncher.muse.chat

import org.json.JSONArray
import org.json.JSONObject

/** Muse's public status for the request; either field null when absent. */
data class AgentStatus(val activityCode: String?, val activityText: String?) : TurnEvent

/**
 * One assistant message answering the request, as it stands after the latest event. [text]
 * is the best current text: streamed deltas, replaced by Muse's final text once it sends one.
 * [bound] means it's proven to answer this request (own chat, or a parent chain to it).
 */
data class Reply(
    val messageId: String,
    val text: String = "",
    val streamed: String = "",
    val done: Boolean = false,
    val bound: Boolean = false,
) : TurnEvent

/** Muse reported the request's task as finished. */
object TaskFinished : TurnEvent {
    override fun toString() = "TaskFinished"
}

sealed interface TurnEvent

/** Muse's events broke a turn's bounds or its acknowledgement was unusable. */
class MuseTurnException(message: String) : IllegalStateException(message)

/**
 * Chat ids retired by earlier turns, bounded to [limit]. Shared across the turns of a chat
 * the TV owns, so replays of earlier turns never reach a later one.
 */
class ReplayScope(private val limit: Int = 2048) {
    val ids = HashSet<Pair<String, String>>()
    private val order = ArrayDeque<Pair<String, String>>()

    fun retire(identifiers: Iterable<Pair<String, String>>) {
        for (id in identifiers) if (ids.add(id)) order.addLast(id)
        while (order.size > limit) ids.remove(order.removeFirst())
    }

    /** Events seen on [sessionId] while no turn was open belong to no later turn. */
    fun observeIdle(event: JSONObject, sessionId: String?) {
        val payload = event.opt("payload") as? JSONObject ?: return
        if (event.opt("type") == "event" && !sessionId.isNullOrEmpty() &&
            (truthy(payload.opt("session_id")) ?: truthy(event.opt("session_id"))) == sessionId &&
            event.opt("event") in STATUS_EVENTS + MESSAGE_EVENTS
        ) {
            retire(eventIds(event))
        }
    }

    companion object {
        fun eventIds(event: JSONObject, includeParents: Boolean = true): Set<Pair<String, String>> {
            val payload = event.opt("payload") as? JSONObject ?: return emptySet()
            val result = HashSet<Pair<String, String>>()
            val keys = listOf("message_id", "id", "task_id") + if (includeParents) listOf("reply_to_message_id", "parent_message_id") else emptyList()
            for (key in keys) {
                val value = nonEmptyString(payload.opt(key)) ?: continue
                if (value.length > MAX_ID_CHARS) throw MuseTurnException("Muse returned an oversized chat identifier")
                result += (if (key == "task_id") "task" else "message") to value
            }
            nonEmptyString(event.opt("message_id"))?.let {
                if (it.length > MAX_ID_CHARS) throw MuseTurnException("Muse returned an oversized chat identifier")
                result += "message" to it
            }
            return result
        }
    }
}

/**
 * Muse's work on one request, followed through the chat event stream (a port of the SDK's
 * muse_turn.py, without follow-ups). Create it before sending, feed it every event from the
 * subscription, then [acknowledge] it with the send's response; events that arrive first are
 * held. Events from other chats, other requests and earlier turns are dropped. [complete]
 * says when Muse is done. [ownsChat]: the TV owns [sessionId], so every event on it belongs
 * to this turn. Not thread-safe; [clock] is monotonic milliseconds.
 */
class MuseTurn(
    sessionId: String? = null,
    val ownsChat: Boolean = false,
    private val replayScope: ReplayScope? = null,
    private val ignoreParentlessUntil: Long = 0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var sessionId: String? = sessionId
        private set
    var acknowledged = false
        private set
    var busy = false
        private set
    var taskFinished = false
        private set
    var activityCode: String? = null
        private set
    var lastActivity = clock()
        private set

    private var userIds: Set<String> = emptySet()
    private val replies = LinkedHashMap<String, Reply>()
    private var pending = ArrayList<JSONObject>()
    private var pendingTextBytes = 0
    private val observedIds = HashSet<Pair<String, String>>()
    private val runningTaskIds = HashSet<String>()
    private val ignoredMessages = LinkedHashSet<String>()

    /** Every assistant message bound to the request, in arrival order. */
    val allReplies: List<Reply> get() = replies.values.toList()

    /** Binds the turn to the user message in Muse's acknowledgement; returns the held events. */
    fun acknowledge(response: JSONObject): List<TurnEvent> {
        val ids = acknowledgedMessageIds(response)
        if (ownsChat && ids.any { it.length > MAX_ID_CHARS }) throw MuseTurnException("Muse returned an oversized chat identifier")
        val result = ackResult(response)
        userIds = ids
        val ackSession = result.opt("session_id")
        if (ownsChat && (sessionId.isNullOrEmpty() || ackSession != sessionId || result.opt("is_thread") != true)) {
            throw MuseTurnException("Muse did not acknowledge the TV's dedicated side chat")
        }
        if (sessionId == null && ackSession is String) sessionId = ackSession
        acknowledged = true
        val held = pending
        pending = ArrayList()
        pendingTextBytes = 0
        return held.flatMap(::feed)
    }

    /** Reads one raw chat event; returns what it means for this request, often nothing. */
    fun feed(event: JSONObject): List<TurnEvent> {
        if (event.opt("type") != "event") return emptyList()
        val payload = event.opt("payload") as? JSONObject ?: return emptyList()
        val eventSession = truthy(payload.opt("session_id")) ?: truthy(event.opt("session_id"))
        if (ownsChat) {
            if (sessionId.isNullOrEmpty() || eventSession != sessionId) return emptyList()
            val identifiers = ReplayScope.eventIds(event)
            replayScope?.let { scope ->
                val own = ReplayScope.eventIds(event, includeParents = false)
                if (own.any { it in scope.ids }) return emptyList()
                val authorizedParents = userIds.map { "message" to it }.toSet()
                if (acknowledged && (identifiers - own - authorizedParents).any { it in scope.ids }) return emptyList()
            }
            observedIds += identifiers
            if (observedIds.size > MAX_OBSERVED_IDS) throw MuseTurnException("Muse returned too many activity identifiers for one turn")
        }
        if (eventSession != null && !sessionId.isNullOrEmpty() && eventSession != sessionId) return emptyList()
        if (!acknowledged) {
            if (pending.size >= MAX_PENDING_EVENTS) throw MuseTurnException("too many chat events before Muse acknowledged the turn")
            pendingTextBytes += listOf("text", "display_text", "content", "transcript").sumOf {
                (payload.opt(it) as? String)?.toByteArray()?.size ?: 0
            }
            if (pendingTextBytes > MAX_TEXT_BYTES) throw MuseTurnException("Muse returned more than 64 KiB of pending reply text")
            pending += event
            return emptyList()
        }
        val scoped = ownsChat && !sessionId.isNullOrEmpty() && eventSession == sessionId
        return when (val name = event.opt("event")) {
            in STATUS_EVENTS -> status(name as String, payload, scoped)
            in MESSAGE_EVENTS -> message(name as String, event, payload, scoped)
            else -> emptyList()
        }
    }

    /** Stop waiting for one reply (one the TV can't read): it counts as done. */
    fun abandonReply(messageId: String) {
        replies[messageId]?.let { replies[messageId] = it.copy(done = true) }
    }

    /** Muse is done answering: every reply done, and Muse idle and finished or quiet. */
    fun complete(now: Long): Boolean =
        !busy && replies.values.all { it.done } && (taskFinished || now - lastActivity >= REPLY_QUIET_MS)

    /** The task finished, every reply is done, and no late text arrived within the grace period. */
    fun settled(now: Long): Boolean =
        taskFinished && !busy && replies.values.all { it.done } &&
            now - lastActivity >= (if (replies.isNotEmpty()) EMPTY_REPLY_GRACE_MS else REPLY_QUIET_MS)

    /** Retires every id this turn saw into its replay scope, once the turn is over. */
    fun retire() {
        val scope = replayScope ?: return
        if (!ownsChat) return
        scope.retire(observedIds)
        scope.retire(userIds.map { "message" to it })
        for (event in pending) scope.retire(ReplayScope.eventIds(event))
    }

    private fun status(name: String, payload: JSONObject, scoped: Boolean): List<TurnEvent> {
        val activity = payload.opt("activity_code") as? String
        val status = payload.opt("status") as? String
        val parent = parentOf(payload)
        val reply = (payload.opt("message_id") as? String)?.let(replies::get)
        val explicitlyLinked = (parent != null && parent in userIds) || reply?.bound == true
        val terminal = status != null && status in TERMINAL_TASK_STATUSES
        val wasFinished = taskFinished
        val taskId = payload.opt("task_id") as? String
        if (ownsChat && name == "task.status") {
            if (terminal && (taskId == null || taskId !in runningTaskIds) && !explicitlyLinked) return emptyList()
            if (status == "running" && !taskId.isNullOrEmpty()) runningTaskIds += taskId
            if (terminal && taskId != null) runningTaskIds -= taskId
        }
        if (name == "task.status" && status != null) taskFinished = terminal && !(ownsChat && runningTaskIds.isNotEmpty())
        if (activity != null) {
            busy = activity !in setOf("", "online", "idle") && activity !in TERMINAL_TASK_STATUSES
            if (name == "agent.status") activityCode = if (activity in ACTIVITY_CODES) activity else "other"
        } else if (status != null) {
            busy = status !in setOf("", "idle") && !terminal
        }
        if (ownsChat && runningTaskIds.isNotEmpty()) {
            taskFinished = false
            busy = true
        } else if (name == "task.status" && taskFinished) {
            busy = false
        }
        lastActivity = clock()
        if (name == "agent.status" && (scoped || explicitlyLinked) && !taskFinished) {
            return listOf(AgentStatus(activity, (payload.opt("activity_text") as? String)?.let(::stripControls)))
        }
        return if (taskFinished && !wasFinished) listOf(TaskFinished) else emptyList()
    }

    private fun message(name: String, event: JSONObject, payload: JSONObject, scoped: Boolean): List<TurnEvent> {
        val messageId = (truthy(payload.opt("message_id")) ?: truthy(event.opt("message_id")) ?: truthy(payload.opt("id"))) as? String
        if (messageId.isNullOrEmpty() || payload.opt("role") == "user") return emptyList()
        val parent = parentOf(payload)
        var reply = replies[messageId]
        if (parent != null && parent !in userIds && parent !in replies && !(scoped && parent == messageId)) {
            if (reply == null) ignore(messageId)
            return emptyList()
        }
        if (reply == null) {
            if (messageId in ignoredMessages || (parent == null && clock() < ignoreParentlessUntil)) {
                ignore(messageId)
                return emptyList()
            }
            if (replies.size >= MAX_MESSAGES) throw MuseTurnException("Muse returned too many messages for one request")
            reply = Reply(messageId)
        }
        var bound = reply.bound || scoped
        if (parent != null) bound = scoped || parent in userIds || replies[parent]?.bound == true
        var text = reply.text
        var streamed = reply.streamed
        var done = reply.done
        lastActivity = clock()
        val delta = payload.opt("text")
        if (name == "delta.text_append" && delta is String && delta.isNotEmpty() && !done) {
            val clean = stripControls(delta)
            text += clean
            streamed += clean
        }
        if (name == "delta.message_done" || name == "message.assistant") {
            var completed: Any? = payload.opt("display_text")
            if (completed !is String) {
                val transcript = payload.opt("transcript")
                val fromTranscript = transcript as? String ?: transcriptText(transcript, messageId)
                completed = if (fromTranscript.isEmpty() && transcript !is String) payload.opt("content") else fromTranscript
            }
            if (completed is String) text = stripControls(completed)
            if (name == "delta.message_done" || payload.opt("display_text_ready") != false) done = true
        }
        val updated = Reply(messageId, text, streamed, done, bound)
        replies[messageId] = updated
        if (text.toByteArray().size > MAX_TEXT_BYTES || streamed.toByteArray().size > MAX_TEXT_BYTES) {
            throw MuseTurnException("Muse returned more than 64 KiB of reply text")
        }
        return listOf(updated)
    }

    private fun ignore(messageId: String) {
        ignoredMessages.remove(messageId)
        ignoredMessages.add(messageId)
        if (ignoredMessages.size > MAX_IGNORED_MESSAGES) ignoredMessages.remove(ignoredMessages.first())
    }

    companion object {
        /** Silence after which a turn without a terminal task status counts as answered. */
        const val REPLY_QUIET_MS = 3_000L

        /** Wait after a terminal task with blank replies, for reordered text to arrive. */
        const val EMPTY_REPLY_GRACE_MS = 1_000L
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_MESSAGES = 32
        private const val MAX_OBSERVED_IDS = 256
        private const val MAX_IGNORED_MESSAGES = 256

        private fun parentOf(payload: JSONObject): String? =
            nonEmptyString(payload.opt("reply_to_message_id")) ?: nonEmptyString(payload.opt("parent_message_id"))

        /** The user message ids an acknowledgement names (`result.message_id` / `reply_to_message_id`). */
        fun acknowledgedMessageIds(response: JSONObject): Set<String> {
            val result = ackResult(response)
            val ids = listOf("message_id", "reply_to_message_id").mapNotNull { nonEmptyString(result.opt(it)) }.toSet()
            if (ids.isEmpty()) throw MuseTurnException("Muse chat acknowledgement omitted the user message ID")
            return ids
        }

        private fun ackResult(response: JSONObject): JSONObject =
            if (response.has("result")) {
                response.opt("result") as? JSONObject ?: throw MuseTurnException("Muse returned an invalid chat acknowledgement")
            } else {
                response
            }

        /** Only assistant text content from Muse's structured transcript. */
        fun transcriptText(value: Any?, messageId: String? = null): String {
            if (value is String) return value
            val messages = (value as? JSONObject)?.opt("messages") as? JSONArray ?: return ""
            val assistants = (0 until messages.length()).mapNotNull { messages.opt(it) as? JSONObject }
                .filter { it.opt("role") == "assistant" && (messageId == null || it.opt("id") == messageId) }
            val content = assistants.lastOrNull()?.opt("content") as? JSONArray ?: return ""
            return (0 until content.length()).mapNotNull { content.opt(it) as? JSONObject }
                .filter { it.opt("type") == "text" }
                .mapNotNull { it.opt("text") as? String }
                .joinToString("\n")
        }
    }
}

internal val STATUS_EVENTS = listOf("agent.status", "task.status")
internal val MESSAGE_EVENTS = listOf("delta.message_start", "delta.text_append", "delta.message_done", "message.assistant")
private val ACTIVITY_CODES = setOf(
    "working", "responding", "idle", "online", "thinking", "researching",
    "planning", "listening", "speaking", "processing", "generating", "executing",
)
private val TERMINAL_TASK_STATUSES = setOf("completed", "failed", "errored", "cancelled", "canceled")
private const val MAX_ID_CHARS = 512

/** C0 and C1 controls except tab and newline, so reply text can't drive a terminal or the UI. */
private val CONTROL_CHARACTERS = Regex("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x9f]")

internal fun stripControls(text: String) = CONTROL_CHARACTERS.replace(text, "")

/** A JSON value if Python would call it truthy, else null. */
private fun truthy(value: Any?): Any? = when (value) {
    null, JSONObject.NULL, false, "" -> null
    is Number -> value.takeIf { it.toDouble() != 0.0 }
    else -> value
}

private fun nonEmptyString(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }
