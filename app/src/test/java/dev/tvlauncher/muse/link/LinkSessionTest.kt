package dev.tvlauncher.muse.link

import dev.tvlauncher.muse.net.WebSocketUpgradeException
import dev.tvlauncher.muse.wire.ApplicationRequest
import dev.tvlauncher.muse.wire.ApplicationResponse
import dev.tvlauncher.muse.wire.BodyChunk
import dev.tvlauncher.muse.wire.Reset
import dev.tvlauncher.muse.wire.ResetCode
import dev.tvlauncher.muse.wire.ServiceFrame
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Port of the SDK's tests/test_link_client.py, against an in-memory VM. */
class LinkSessionTest {
    private val threads = Executors.newCachedThreadPool()
    private val device = DeviceDescription(
        nodeId = "homelink-abcdef",
        displayName = "tv",
        version = "0.1.0",
        commands = mapOf("tv.home" to mapOf("description" to "home", "required" to emptyMap<String, Any>(), "optional" to emptyMap<String, Any>())),
    )
    private val connects = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val logs = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        threads.shutdownNow()
    }

    private fun session(
        vm: FakeVm,
        runCommand: (String, JSONObject, Long?) -> Map<String, Any?> = { _, _, _ -> mapOf("ok" to true) },
    ) = LinkSession(
        noiseHost = "gw.example",
        vmId = "vm 1&x",
        vmAuthToken = "tok",
        device = device,
        runCommand = runCommand,
        connect = { url, headers ->
            connects += url to headers
            vm.pipe.device
        },
        log = { logs += it },
    )

    private fun LinkSession.start(): Future<Outcome> = threads.submit<Outcome> { run() }

    private fun <T> Future<T>.result(): T = get(5, TimeUnit.SECONDS)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `register invoke result and unpair`() {
        val vm = FakeVm()
        val calls = CopyOnWriteArrayList<Triple<String, String, Long?>>()
        val session = session(vm) { command, params, timeout ->
            calls += Triple(command, params.toString(), timeout)
            mapOf("ok" to true, "payload" to mapOf("stdout" to "hi\n", "exit_code" to 0))
        }
        val outcome = session.start()

        vm.handshake()
        val request = vm.acceptControlStream().kind as ApplicationRequest
        assertEquals(Triple("POST", "/link-control", false), Triple(request.verb, request.path, request.endBody))

        val register = vm.nextMessage()
        assertEquals("link.register", register.getString("method"))
        val params = register.getJSONObject("params")
        assertEquals("homelink-abcdef", params.getString("node_id"))
        assertEquals("linux" to "homehub", params.getString("platform") to params.getString("device_family"))
        assertTrue(params.getJSONObject("commands_v2").has("tv.home"))
        assertFalse(params.getJSONObject("commands_v2").has("device.ota"))
        vm.sendMessage(mapOf("type" to "res", "id" to register.getString("id"), "ok" to true))

        vm.sendMessage(
            mapOf("method" to "link.invoke", "id" to "inv-1", "command" to "tv.home", "params" to mapOf("x" to "y"), "timeout_ms" to 5000),
        )
        val result = vm.nextMessage()
        assertEquals("link.result", result.getString("method"))
        assertEquals("inv-1", result.getString("id"))
        assertTrue(result.getBoolean("ok"))
        assertEquals(0, result.getJSONObject("payload").getInt("exit_code"))
        assertEquals(listOf(Triple("tv.home", """{"x":"y"}""", 5000L)), calls)
        assertNotNull(session.registeredAt)

        vm.sendMessage(mapOf("type" to "evt", "event" to "link.unpaired"))
        assertEquals(Outcome.UNPAIRED, outcome.result())
        assertEquals("wss://gw.example/v1/noise?vm_id=vm%201%26x" to mapOf("Authorization" to "Bearer tok"), connects.single())
    }

    @Test
    fun `forbidden control stream`() {
        val vm = FakeVm()
        val outcome = session(vm).start()
        vm.handshake()
        vm.acceptControlStream(status = 403)
        assertEquals(Outcome.FORBIDDEN, outcome.result())
    }

    @Test
    fun `other control stream errors just close`() {
        val vm = FakeVm()
        val outcome = session(vm).start()
        vm.handshake()
        vm.acceptControlStream(status = 500)
        assertEquals(Outcome.CLOSED, outcome.result())
    }

    @Test
    fun `stop ends the session`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.handshake()
        vm.acceptControlStream()
        vm.nextMessage()
        session.stop()
        assertEquals(Outcome.STOPPED, outcome.result())
    }

    @Test
    fun `register rejection never reports ready`() {
        for (rejection in listOf<Pair<String, Any>>("error" to "unauthorized", "ok" to false)) {
            val vm = FakeVm()
            val session = session(vm)
            val outcome = session.start()
            vm.handshake()
            vm.acceptControlStream()
            val register = vm.nextMessage()
            vm.sendMessage(mapOf("type" to "res", "id" to register.getString("id"), rejection))
            assertEquals(Outcome.FORBIDDEN, outcome.result())
            assertNull(session.registeredAt)
            assertFalse(session.isRegistered)
        }
    }

    @Test
    fun `upgrade refusals map to auth outcomes`() {
        for ((status, expected) in listOf(401 to Outcome.AUTH_REJECTED, 403 to Outcome.FORBIDDEN, 502 to Outcome.CLOSED)) {
            val session = LinkSession("h", "vm", "tok", device, { _, _, _ -> emptyMap() }, { _, _ -> throw WebSocketUpgradeException(status) })
            assertEquals(expected, session.run())
        }
        val down = LinkSession("h", "vm", "tok", device, { _, _, _ -> emptyMap() }, { _, _ -> throw IOException("no route") })
        assertEquals(Outcome.CLOSED, down.run())
    }

    @Test
    fun `the vm hanging up closes the session`() {
        val vm = FakeVm()
        val outcome = session(vm).start()
        vm.register()
        vm.pipe.drop()
        assertEquals(Outcome.CLOSED, outcome.result())
    }

    @Test
    fun `a reset or end of the control stream closes the session`() {
        val vm = FakeVm()
        val outcome = session(vm).start()
        vm.register()
        vm.sendFrame(ServiceFrame(vm.controlStreamId, Reset(ResetCode.INTERNAL_ERROR, "bye")))
        assertEquals(Outcome.CLOSED, outcome.result())

        val vm2 = FakeVm()
        val outcome2 = session(vm2).start()
        vm2.register()
        vm2.sendFrame(ServiceFrame(vm2.controlStreamId, BodyChunk(endBody = true)))
        assertEquals(Outcome.CLOSED, outcome2.result())
    }

    @Test
    fun `text frames and unknown streams are ignored`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        vm.pipe.toDevice.put("hello") // a text frame
        vm.sendFrame(ServiceFrame(99, ApplicationResponse(status = 200, endBody = true)))
        vm.sendMessage(mapOf("method" to "link.invoke", "id" to "alive", "command" to "tv.home"))
        assertEquals("alive", vm.nextMessage().getString("id"))
        session.stop()
        assertEquals(Outcome.STOPPED, outcome.result())
    }

    @Test
    fun `a failing command answers ok false and the session goes on`() {
        val vm = FakeVm()
        val session = session(vm) { command, _, _ -> if (command == "boom") throw IllegalStateException("x") else mapOf("ok" to true) }
        val outcome = session.start()
        vm.register()
        vm.sendMessage(mapOf("method" to "link.invoke", "id" to "1", "command" to "boom"))
        val failed = vm.nextMessage()
        assertEquals("1", failed.getString("id"))
        assertFalse(failed.getBoolean("ok"))
        vm.sendMessage(mapOf("method" to "link.invoke", "command" to "tv.home")) // no id: ignored
        vm.sendMessage(mapOf("method" to "link.invoke", "id" to "2", "command" to "tv.home"))
        assertEquals("2", vm.nextMessage().getString("id"))
        session.stop()
        assertEquals(Outcome.STOPPED, outcome.result())
    }

    @Test
    fun `each invoke logs how it ended but not what it ran`() {
        val vm = FakeVm()
        val results = LinkedBlockingQueue(
            listOf(
                mapOf("ok" to true, "payload" to mapOf("stdout" to "", "exit_code" to 3, "timed_out" to false)),
                mapOf("ok" to false, "error" to "no such file: /home/pi/secret"),
            ),
        )
        val session = session(vm) { _, _, _ -> results.take() }
        val outcome = session.start()
        vm.register()
        for ((id, command) in listOf("inv-1" to "system.run", "inv-2" to "file.read\nforged")) {
            vm.sendMessage(mapOf("method" to "link.invoke", "id" to id, "command" to command, "params" to mapOf("command" to "cat /home/pi/secret")))
            vm.nextMessage()
        }
        session.stop()
        outcome.result()
        assertTrue(logs.any { it.startsWith("system.run ok, exit 3 in ") && it.endsWith(" ms") })
        assertTrue(logs.any { it.startsWith("file.read?forged failed in ") })
        assertFalse(logs.any { "\n" in it })
        assertFalse(logs.any { "secret" in it })
    }

    @Test
    fun `send chat posts a device attributed message on the same session`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }

        val reply = threads.submit<ChatResult> { session.sendChat("porch light on", "side-1") }
        val request = vm.nextFrame()
        val app = request.kind as ApplicationRequest
        assertEquals(Triple("POST", "/chat/stream", true), Triple(app.verb, app.path, app.endBody))
        assertEquals(
            """{"message":"porch light on","output_modality":"text","device_id":"homelink-abcdef","session_id":"side-1"}""",
            String(app.body),
        )
        val headers = app.headers.associate { it.key.lowercase() to it.value }
        assertEquals("application/json", headers["content-type"])
        assertEquals("musegadget", headers["x-app-id"])
        assertTrue(headers["x-request-id"]!!.isNotEmpty())
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200, body = """{"accepted":true}""".toByteArray(), endBody = true)))
        val result = reply.result()
        assertTrue(result.ok)
        assertEquals(200, result.status)
        assertEquals(true, (result.response as JSONObject).getBoolean("accepted"))
        session.stop()
        assertEquals(Outcome.STOPPED, outcome.result())
    }

    @Test
    fun `a byte stream GET is readable while it arrives and reset when closed early`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }

        val stream = session.openByteStream("/api/voice/tts-stream?message_id=m%201", "audio/mpeg")
        val request = vm.nextFrame()
        val app = request.kind as ApplicationRequest
        assertEquals(Triple("GET", "/api/voice/tts-stream?message_id=m%201", true), Triple(app.verb, app.path, app.endBody))
        assertEquals("audio/mpeg", app.headers.associate { it.key.lowercase() to it.value }["accept"])
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200, body = byteArrayOf(1, 2))))
        assertEquals(200, stream.awaitStatus(2000))
        vm.sendFrame(ServiceFrame(request.streamId, BodyChunk(byteArrayOf(3))))
        val buffer = ByteArray(8)
        waitFor { stream.readAt(0, buffer, 0, 8, 2000) == 3 }
        stream.close()
        assertEquals(Reset::class, vm.nextFrame().kind!!::class)
        session.stop()
        assertEquals(Outcome.STOPPED, outcome.result())
    }

    @Test
    fun `a voice note uploads in bounded chunks`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }
        val audio = ByteArray(640_044) { (it % 251).toByte() }
        val reply = threads.submit<ChatResult> { session.sendVoice(audio, "robot-chat") }
        val request = vm.nextFrame()
        val app = request.kind as ApplicationRequest
        assertEquals(Triple("POST", "/chat/stream", false), Triple(app.verb, app.path, app.endBody))
        val body = java.io.ByteArrayOutputStream()
        var chunks = 0
        while (true) {
            val frame = vm.nextFrame()
            val chunk = frame.kind as BodyChunk
            assertEquals(request.streamId, frame.streamId)
            assertTrue(chunk.data.size <= 16384)
            body.write(chunk.data)
            chunks++
            if (chunk.endBody) break
        }
        assertTrue(chunks > 40)
        val uploaded = JSONObject(body.toString())
        assertEquals("robot-chat", uploaded.getString("session_id"))
        assertEquals("homelink-abcdef", uploaded.getString("device_id"))
        assertFalse(uploaded.has("output_modality"))
        val item = uploaded.getJSONArray("items").getJSONObject(0)
        assertEquals("audio/wav", item.getString("mime_type"))
        assertTrue(audio.contentEquals(Base64.getDecoder().decode(item.getString("data_base64"))))
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200, body = """{"result":{"message_id":"user-1"}}""".toByteArray(), endBody = true)))
        assertEquals("user-1", (reply.result().response as JSONObject).getJSONObject("result").getString("message_id"))
        session.stop()
        outcome.result()
    }

    @Test
    fun `subscription is ready on headers and parses split utf8 events`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }
        val events = LinkedBlockingQueue<JSONObject>()
        val ends = LinkedBlockingQueue<Any>()
        val listener = object : ChatSubscription.Listener {
            override fun onEvent(event: JSONObject) = events.put(event)

            override fun onEnd(error: Exception?) = ends.put(error ?: "clean")
        }
        val subscribed = threads.submit<ChatSubscription> { session.subscribeChat("robot-chat", listener) }
        val request = vm.nextFrame()
        val app = request.kind as ApplicationRequest
        assertEquals("/chat/subscribe", app.path)
        assertEquals("""{"session_id":"robot-chat"}""", String(app.body))
        assertEquals("application/x-ndjson", app.headers.associate { it.key.lowercase() to it.value }["accept"])
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200)))
        subscribed.result()

        val event = """{"type":"event","seq":1,"event":"delta.text_append","payload":{"text":"héllo"}}""" + "\n"
        val bytes = event.toByteArray()
        val split = bytes.indexOf(0xC3.toByte()) + 1 // inside the two-byte é
        vm.sendFrame(ServiceFrame(request.streamId, BodyChunk(bytes.copyOfRange(0, split))))
        vm.sendFrame(ServiceFrame(request.streamId, BodyChunk(bytes.copyOfRange(split, bytes.size) + """{"type":"ack"}""".toByteArray() + "\n\n".toByteArray())))
        assertEquals("héllo", events.poll(5, TimeUnit.SECONDS)!!.getJSONObject("payload").getString("text"))

        vm.sendFrame(ServiceFrame(request.streamId, BodyChunk("""{"type":"event","seq":2,"event":"delta.message_done"}""".toByteArray(), endBody = true)))
        assertEquals(2, events.poll(5, TimeUnit.SECONDS)!!.getInt("seq"))
        assertEquals("clean", ends.poll(5, TimeUnit.SECONDS))
        assertTrue(events.isEmpty()) // the ack was filtered out
        session.stop()
        outcome.result()
    }

    @Test
    fun `an oversized chat event cancels only the subscription`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }
        val ends = LinkedBlockingQueue<Any>()
        val listener = object : ChatSubscription.Listener {
            override fun onEvent(event: JSONObject) = Unit

            override fun onEnd(error: Exception?) = ends.put(error ?: "clean")
        }
        val subscribed = threads.submit<ChatSubscription> { session.subscribeChat(null, listener) }
        val request = vm.nextFrame()
        assertEquals("{}", String((request.kind as ApplicationRequest).body))
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200)))
        subscribed.result()
        repeat(5) { vm.sendFrame(ServiceFrame(request.streamId, BodyChunk(ByteArray(65536) { 'x'.code.toByte() }))) }
        val reset = vm.nextFrame()
        assertEquals(request.streamId, reset.streamId)
        assertTrue(reset.kind is Reset)
        assertTrue(ends.poll(5, TimeUnit.SECONDS) is Exception)
        // The control stream still works.
        vm.sendMessage(mapOf("method" to "link.invoke", "id" to "alive", "command" to "tv.home"))
        assertEquals("alive", vm.nextMessage().getString("id"))
        session.stop()
        outcome.result()
    }

    @Test
    fun `a refused subscription throws with the status and cancels nothing twice`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }
        val listener = object : ChatSubscription.Listener {
            override fun onEvent(event: JSONObject) = Unit

            override fun onEnd(error: Exception?) = Unit
        }
        val subscribed = threads.submit<ChatSubscription> { session.subscribeChat(null, listener) }
        val request = vm.nextFrame()
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 403, body = "private".toByteArray())))
        val error = runCatching { subscribed.result() }.exceptionOrNull()?.cause
        assertTrue(error is HttpStreamError)
        assertEquals(403, (error as HttpStreamError).status)
        assertFalse(error.message!!.contains("private"))
        assertTrue(vm.nextFrame().kind is Reset)
        session.stop()
        outcome.result()
    }

    @Test
    fun `client invoke events on a chat subscription are run and answered with link result`() {
        val vm = FakeVm()
        val calls = CopyOnWriteArrayList<Triple<String, String, Long?>>()
        val session = session(vm) { command, params, timeout ->
            calls += Triple(command, params.toString(), timeout)
            mapOf("ok" to true, "payload" to mapOf("shown" to true))
        }
        val outcome = session.start()
        vm.register()
        waitFor { session.isRegistered }
        val passedOn = LinkedBlockingQueue<JSONObject>()
        val listener = object : ChatSubscription.Listener {
            override fun onEvent(event: JSONObject) = passedOn.put(event)

            override fun onEnd(error: Exception?) = Unit
        }
        val subscribed = threads.submit<ChatSubscription> { session.subscribeChat("tv-chat", listener) }
        val request = vm.nextFrame()
        vm.sendFrame(ServiceFrame(request.streamId, ApplicationResponse(status = 200)))
        subscribed.result()

        fun invoke(command: String, id: String): String {
            val payload = JSONObject()
                .put("command_id", command)
                .put("invoke_id", id)
                .put("params_json", JSONObject().put("text", "Hello").toString())
                .put("timeout_ms", 30000)
            return JSONObject().put("type", "event").put("event", "client.invoke").put("ts_ms", 1).put("payload", payload).toString() + "\n"
        }
        val status = JSONObject().put("type", "event").put("event", "agent.status").put("payload", JSONObject()).toString() + "\n"
        val events = invoke("tv.home", "inv-9") + invoke("tv.home", "inv-9") + invoke("not.ours", "inv-10") + status
        vm.sendFrame(ServiceFrame(request.streamId, BodyChunk(events.toByteArray())))

        val result = vm.nextMessage()
        assertEquals("link.result", result.getString("method"))
        assertEquals("inv-9", result.getString("id"))
        assertTrue(result.getBoolean("ok"))
        assertEquals("agent.status", passedOn.poll(5, TimeUnit.SECONDS)!!.getString("event")) // invokes aren't passed on
        assertEquals(listOf(Triple("tv.home", JSONObject().put("text", "Hello").toString(), 30000L)), calls) // once, ours only
        assertTrue(passedOn.isEmpty())
        session.stop()
        outcome.result()
    }

    @Test
    fun `a registered reply is followed by a heartbeat`() {
        val vm = FakeVm()
        val session = session(vm)
        val outcome = session.start()
        vm.handshake()
        vm.acceptControlStream()
        val register = vm.nextMessage()
        vm.sendMessage(mapOf("type" to "res", "id" to register.getString("id"), "result" to mapOf("status" to "registered")))
        assertEquals("link.heartbeat", vm.nextMessage().getString("method"))
        session.stop()
        outcome.result()
    }

    @Test
    fun `chat needs a live session`() {
        val session = session(FakeVm())
        assertTrue(runCatching { session.sendChat("hi") }.exceptionOrNull() is IOException)
    }

    @Test
    fun `vm id is escaped like encodeURIComponent`() {
        assertEquals("wss://h/v1/noise?vm_id=a-b_c.d!~*'()%3F%26%3D", noiseUrl("h", "a-b_c.d!~*'()?&="))
        assertEquals("wss://h/v1/noise?vm_id=caf%C3%A9", noiseUrl("h", "café"))
    }

    @Test
    fun `describe result and printable`() {
        assertEquals("ok", describeResult(mapOf("ok" to true, "payload" to mapOf("pump" to "on"))))
        assertEquals("ok", describeResult(mapOf("ok" to true)))
        assertEquals("ok, exit 0", describeResult(mapOf("ok" to true, "payload" to mapOf("exit_code" to 0, "timed_out" to false))))
        assertEquals("ok, exit -9, timed out", describeResult(mapOf("ok" to true, "payload" to mapOf("exit_code" to -9, "timed_out" to true))))
        assertEquals("failed", describeResult(mapOf("ok" to false, "error" to "no such file: /home/pi/secret")))
        assertEquals("failed", describeResult(mapOf("ok" to false)))
        assertEquals("system.run", printable("system.run"))
        assertEquals("a?b?[2Jc", printable("a\nb\u001b[2Jc"))
    }
}
