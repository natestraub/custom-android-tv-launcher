package dev.tvlauncher.muse.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the SDK's tests/test_muse_turn.py (minus follow-ups, which the TV doesn't send). */
class MuseTurnTest {
    private var now = 1_000_000L
    private val clock = { now }

    private fun event(name: String, messageId: String? = null, parent: String? = null, session: String = "chat", vararg payload: Pair<String, Any?>): JSONObject {
        val data = JSONObject().put("session_id", session)
        for ((k, v) in payload) data.put(k, v ?: JSONObject.NULL)
        if (messageId != null) data.put("message_id", messageId)
        if (parent != null) data.put("reply_to_message_id", parent)
        return JSONObject().put("type", "event").put("event", name).put("payload", data)
    }

    private fun json(text: String) = JSONObject(text)

    private fun sharedTurn(ignoreParentlessUntil: Long = 0) = MuseTurn("chat", ignoreParentlessUntil = ignoreParentlessUntil, clock = clock).also {
        assertEquals(emptyList<TurnEvent>(), it.acknowledge(json("""{"result":{"message_id":"u1","session_id":"chat"}}""")))
    }

    private fun sideAck(userId: String) = json("""{"result":{"message_id":"$userId","session_id":"side","is_thread":true}}""")

    private fun sideTurn(scope: ReplayScope? = null, userId: String = "u1") = MuseTurn("side", ownsChat = true, replayScope = scope, clock = clock).also {
        assertEquals(emptyList<TurnEvent>(), it.acknowledge(sideAck(userId)))
    }

    @Test
    fun `events before the acknowledgement are held and returned in order`() {
        val turn = MuseTurn("chat", clock = clock)
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.text_append", "r1", "u1", "chat", "text" to "Hel")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.text_append", "r1", "u1", "chat", "text" to "lo")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Hello.")))
        assertEquals(
            listOf(Reply("r1", "Hel", "Hel", false, true), Reply("r1", "Hello", "Hello", false, true), Reply("r1", "Hello.", "Hello", true, true)),
            turn.acknowledge(json("""{"result":{"message_id":"u1"}}""")),
        )
        assertEquals(listOf(Reply("r1", "Hello.", "Hello", true, true)), turn.allReplies)
    }

    @Test
    fun `acknowledgement teaches an unknown session`() {
        val turn = MuseTurn(clock = clock)
        turn.acknowledge(json("""{"message_id":"u1","session_id":"learned"}"""))
        assertEquals("learned", turn.sessionId)
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "r1", "u1", "other", "content" to "No")))
        assertEquals(listOf(Reply("r1", "Yes", "", true, true)), turn.feed(event("delta.message_done", "r1", "u1", "learned", "content" to "Yes")))
    }

    @Test
    fun `other chats requests and event kinds are dropped`() {
        val turn = sharedTurn()
        val raws = listOf(
            event("delta.message_done", "r1", "u1", "phone-chat", "content" to "Other chat"),
            event("delta.message_done", "r2", "someone-else", "chat", "content" to "Other request"),
            event("message.assistant", "u1", "u1", "chat", "role" to "user", "content" to "My own question"),
            event("message.tool", "t1", "u1", "chat", "content" to "Tool output"),
            json("""{"type":"ack","payload":{"message_id":"r3"}}"""),
            json("""{"type":"event","event":"delta.message_done","payload":null}"""),
        )
        for (raw in raws) assertEquals(emptyList<TurnEvent>(), turn.feed(raw))
        assertTrue(turn.allReplies.isEmpty())
    }

    @Test
    fun `parentless reply on a shared chat is read but not bound`() {
        val turn = sharedTurn()
        assertEquals(listOf(Reply("r1", "Hi", "", true, false)), turn.feed(event("delta.message_done", "r1", null, "chat", "content" to "Hi")))
        assertEquals(listOf(Reply("r2", "More", "", true, false)), turn.feed(event("delta.message_done", "r2", "r1", "chat", "content" to "More")))
    }

    @Test
    fun `parentless replies inside the ignore window stay ignored`() {
        val turn = sharedTurn(ignoreParentlessUntil = now + 60_000)
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "stop-answer", null, "chat", "content" to "Stopped.")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "stop-answer", "u1", "chat", "content" to "Stopped.")))
        assertEquals(listOf(Reply("r1", "Answer", "", true, true)), turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Answer")))
        assertEquals(listOf("r1"), turn.allReplies.map { it.messageId })
    }

    @Test
    fun `reply first seen answering another request stays dropped`() {
        val turn = sharedTurn()
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.text_append", "r2", "someone-else", "chat", "text" to "Other ")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.text_append", "r2", null, "chat", "text" to "answer")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "r2", "r2", "chat", "content" to "Other answer")))
        assertEquals(listOf(Reply("r1", "Mine", "", true, true)), turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Mine")))
        assertEquals(listOf("r1"), turn.allReplies.map { it.messageId })
    }

    @Test
    fun `reply and status text lose control characters`() {
        val turn = sharedTurn()
        assertEquals(
            listOf(AgentStatus("working", "Look[2Jing")),
            turn.feed(event("agent.status", null, "u1", "chat", "activity_code" to "working", "activity_text" to "Look\u001b[2Jing\r")),
        )
        assertEquals(
            listOf(Reply("r1", "Red [31malert\n", "Red [31malert\n", false, true)),
            turn.feed(event("delta.text_append", "r1", "u1", "chat", "text" to "Red \u001b[31malert\u0007\n")),
        )
        assertEquals(
            listOf(Reply("r1", "Done.\tOK\n", "Red [31malert\n", true, true)),
            turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Done\u009b\u0000.\tOK\n")),
        )
    }

    @Test
    fun `replays of an earlier turn never reach the next one`() {
        val scope = ReplayScope()
        val first = sideTurn(scope)
        first.feed(event("task.status", null, null, "side", "task_id" to "t1", "status" to "running"))
        first.feed(event("delta.message_done", "r1", null, "side", "content" to "First answer"))
        first.retire()
        val second = sideTurn(scope, userId = "u2")
        assertEquals(emptyList<TurnEvent>(), second.feed(event("delta.message_done", "r1", null, "side", "content" to "First answer")))
        assertEquals(emptyList<TurnEvent>(), second.feed(event("task.status", null, null, "side", "task_id" to "t1", "status" to "completed")))
        assertEquals(emptyList<TurnEvent>(), second.feed(event("delta.message_done", "r9", "u1", "side", "content" to "Late")))
        assertEquals(listOf(Reply("r2", "Second answer", "", true, true)), second.feed(event("delta.message_done", "r2", null, "side", "content" to "Second answer")))
        assertFalse(second.taskFinished)
    }

    @Test
    fun `retired requests and idle events are replays`() {
        val scope = ReplayScope()
        scope.retire(MuseTurn.acknowledgedMessageIds(json("""{"result":{"message_id":"stop-1"}}""")).map { "message" to it })
        scope.observeIdle(event("agent.status", "w1", null, "side", "activity_code" to "working"), "side")
        val turn = sideTurn(scope, userId = "u2")
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("delta.message_done", "r1", "stop-1", "side", "content" to "Stopped")))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("agent.status", "w1", null, "side", "activity_code" to "working")))
        assertFalse(turn.busy)
    }

    @Test
    fun `linked status is reported and unlinked status only marks busy`() {
        val turn = sharedTurn()
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("agent.status", null, null, "chat", "activity_code" to "working", "activity_text" to "Searching web")))
        assertTrue(turn.busy)
        assertEquals(
            listOf(AgentStatus("dancing", "Searching web")),
            turn.feed(event("agent.status", null, "u1", "chat", "activity_code" to "dancing", "activity_text" to "Searching web")),
        )
        assertEquals("other", turn.activityCode)
        assertEquals(listOf(AgentStatus("idle", null)), turn.feed(event("agent.status", null, "u1", "chat", "activity_code" to "idle", "activity_text" to 7)))
        assertFalse(turn.busy)
        assertEquals("idle", turn.activityCode)
    }

    @Test
    fun `side chat status needs no parent`() {
        val turn = sideTurn()
        assertEquals(
            listOf(AgentStatus("researching", "Comparing options")),
            turn.feed(event("agent.status", null, null, "side", "activity_code" to "researching", "activity_text" to "Comparing options")),
        )
    }

    @Test
    fun `task finished after the reply completes at once`() {
        val turn = sharedTurn()
        assertEquals(listOf(Reply("r1", bound = true)), turn.feed(event("delta.message_start", "r1", "u1")))
        assertFalse(turn.complete(turn.lastActivity + 10_000))
        turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Answer"))
        assertFalse(turn.complete(turn.lastActivity + 2_900))
        assertTrue(turn.complete(turn.lastActivity + 3_010))
        assertEquals(listOf(TaskFinished), turn.feed(event("task.status", null, "u1", "chat", "status" to "completed")))
        assertTrue(turn.complete(turn.lastActivity))
    }

    @Test
    fun `task finished before the reply waits for late text`() {
        val turn = sharedTurn()
        assertEquals(listOf(TaskFinished), turn.feed(event("task.status", null, "u1", "chat", "status" to "completed")))
        assertFalse(turn.settled(turn.lastActivity + 2_900))
        assertTrue(turn.settled(turn.lastActivity + 3_010))
        assertEquals(listOf(Reply("r1", bound = true)), turn.feed(event("delta.message_start", "r1", "u1")))
        assertFalse(turn.settled(turn.lastActivity + 10_000) || turn.complete(turn.lastActivity + 10_000))
        turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to " "))
        assertFalse(turn.settled(turn.lastActivity + 900))
        assertTrue(turn.settled(turn.lastActivity + 1_010))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("task.status", null, "u1", "chat", "status" to "completed")))
    }

    @Test
    fun `busy agent holds the turn open past the quiet period`() {
        val turn = sharedTurn()
        turn.feed(event("delta.message_done", "r1", "u1", "chat", "content" to "Answer"))
        turn.feed(event("agent.status", null, "u1", "chat", "activity_code" to "working"))
        assertFalse(turn.complete(turn.lastActivity + 60_000))
        turn.feed(event("agent.status", null, "u1", "chat", "activity_code" to "idle"))
        assertTrue(turn.complete(turn.lastActivity + 3_010))
    }

    @Test
    fun `side chat turn finishes when its last running task does`() {
        val turn = sideTurn()
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("task.status", null, null, "side", "task_id" to "startup", "status" to "completed")))
        turn.feed(event("task.status", null, null, "side", "task_id" to "root", "status" to "running"))
        turn.feed(event("task.status", null, null, "side", "task_id" to "sub", "status" to "running"))
        assertEquals(emptyList<TurnEvent>(), turn.feed(event("task.status", null, null, "side", "task_id" to "sub", "status" to "cancelled")))
        assertTrue(turn.busy && !turn.taskFinished)
        assertEquals(listOf(TaskFinished), turn.feed(event("task.status", null, null, "side", "task_id" to "root", "status" to "completed")))
        assertTrue(!turn.busy && turn.complete(turn.lastActivity))
    }

    @Test
    fun `display text waits for readiness and text after done is ignored`() {
        val turn = sharedTurn()
        assertEquals(
            listOf(Reply("r1", "Draft", "", false, true)),
            turn.feed(event("message.assistant", "r1", "u1", "chat", "content" to "Draft", "display_text_ready" to false)),
        )
        assertEquals(listOf(Reply("r1", "Final", "", true, true)), turn.feed(event("message.assistant", "r1", "u1", "chat", "display_text" to "Final")))
        assertEquals(listOf(Reply("r1", "Final", "", true, true)), turn.feed(event("delta.text_append", "r1", "u1", "chat", "text" to " extra")))
    }

    @Test
    fun `completed message reads its own text from a structured transcript`() {
        val transcript = json(
            """{"messages":[{"id":"r0","role":"assistant","content":[{"type":"text","text":"Prior answer"}]},""" +
                """{"id":"r1","role":"assistant","content":[{"type":"text","text":"Current answer"}]}]}""",
        )
        val turn = sharedTurn()
        assertEquals(listOf(Reply("r1", "Current answer", "", true, true)), turn.feed(event("delta.message_done", "r1", "u1", "chat", "transcript" to transcript)))
    }

    @Test
    fun `abandoned reply counts as done`() {
        val turn = sharedTurn()
        turn.feed(event("delta.text_append", "r1", "u1", "chat", "text" to "{broken"))
        turn.feed(event("task.status", null, "u1", "chat", "status" to "completed"))
        assertFalse(turn.complete(turn.lastActivity + 10_000))
        turn.abandonReply("r1")
        assertEquals(listOf(Reply("r1", "{broken", "{broken", true, true)), turn.allReplies)
        assertTrue(turn.complete(turn.lastActivity))
    }

    @Test
    fun `unusable acknowledgement is rejected`() {
        val cases = listOf(
            Triple("side", true, """{"result":{"message_id":"u1","session_id":"chat","is_thread":true}}"""),
            Triple("side", true, """{"result":{"message_id":"u1","session_id":"side","is_thread":false}}"""),
            Triple("chat", false, """{"message_id":""}"""),
            Triple("chat", false, """{"result":"nope"}"""),
        )
        for ((session, owns, response) in cases) {
            assertThrows(response, MuseTurnException::class.java) { MuseTurn(session, ownsChat = owns, clock = clock).acknowledge(json(response)) }
        }
    }

    @Test
    fun `held events and reply text are bounded`() {
        val turn = MuseTurn("chat", clock = clock)
        repeat(64) { turn.feed(event("delta.message_start", "r1", "u1")) }
        assertThrows(MuseTurnException::class.java) { turn.feed(event("delta.message_start", "r1", "u1")) }
        assertThrows(MuseTurnException::class.java) { sharedTurn().feed(event("delta.text_append", "r1", "u1", "chat", "text" to "é".repeat(33000))) }
    }

    @Test
    fun `transcript reads only assistant text`() {
        val transcript = json(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"Question"}]},""" +
                """{"role":"tool","content":[{"type":"text","text":"Private tool data"}]},""" +
                """{"role":"assistant","content":[{"type":"text","text":"Hello."},{"type":"image","text":"Do not read this"},""" +
                """{"type":"text","text":"Goodbye."}]}]}""",
        )
        assertEquals("Hello.\nGoodbye.", MuseTurn.transcriptText(transcript))
    }
}
