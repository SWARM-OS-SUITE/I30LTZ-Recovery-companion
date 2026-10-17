package com.example

import com.example.data.bridge.BridgeClient
import com.example.data.bridge.BridgeCommandStream
import com.example.data.bridge.ChatPiece
import com.example.data.bridge.Decision
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** Drives the real client and stream against a local fake of `sse_bridge.py` (same paths, auth and payloads). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeClientTest {
    private class Seen(val method: String, val path: String, val auth: String?, val body: String)

    private val servers = mutableListOf<HttpServer>()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { servers.forEach { it.stop(0) }; scopes.forEach { it.cancel() } }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun json(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Writes SSE [blocks] as a chunked response, then ends it. */
    private fun sse(ex: HttpExchange, blocks: List<String>, holdOpenMs: Long = 0) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            blocks.forEach { out.write(it.toByteArray()); out.flush() }
            if (holdOpenMs > 0) Thread.sleep(holdOpenMs)
        }
    }

    private fun fake(handler: (HttpExchange, Seen) -> Unit): Pair<Node, MutableList<Seen>> {
        val seen = mutableListOf<Seen>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val s = Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(seen) { seen += s }
            handler(ex, s)
        }
        server.start(); servers += server
        return bridgeNode(server.address.port) to seen
    }

    private fun bridgeNode(port: Int, token: String = "tok", backend: NodeBackend = NodeBackend.SSE_BRIDGE) =
        Node(name = "Recovery", os = "Recovery OS", backend = backend, host = "127.0.0.1", port = port, token = token)

    // --- queue a command ------------------------------------------------------------------------------

    @Test fun submitSendsTheCommandWithTheBearerTokenAndReturnsItsId() = runBlocking {
        val (node, seen) = fake { ex, _ -> json(ex, 200, """{"cmd_id":"c1","status":"pending"}""") }
        val r = BridgeClient.submit(node, "df -h", "disk check", false)
        assertEquals("c1", r.getOrThrow())
        assertEquals(1, seen.size)
        assertEquals("POST", seen[0].method); assertEquals("/api/command", seen[0].path); assertEquals("Bearer tok", seen[0].auth)
        val b = JSONObject(seen[0].body)
        assertEquals("df -h", b.getString("command")); assertEquals("disk check", b.getString("description")); assertFalse(b.getBoolean("dangerous"))
    }

    @Test fun submitSaysSoWhenTheTokenIsRejected() = runBlocking {
        val (node, _) = fake { ex, _ -> json(ex, 401, """{"detail":"Unauthorized"}""") }
        val e = BridgeClient.submit(node, "ls", "", false).exceptionOrNull()
        assertTrue(e!!.message!!.contains("rejected the token"))
    }

    @Test fun unpairedOrWrongKindOfNodeNeverTouchesTheNetwork() = runBlocking {
        val (node, seen) = fake { ex, _ -> json(ex, 200, "{}") }
        assertTrue(BridgeClient.submit(node.copy(token = ""), "ls", "", false).isFailure)
        assertTrue(BridgeClient.submit(node.copy(backend = NodeBackend.AGENT), "ls", "", false).isFailure)
        assertTrue(BridgeClient.decide(node.copy(token = ""), "c1", true) is Decision.Failed)
        assertTrue(BridgeClient.chat(node.copy(token = ""), "hi", true).toList().single() is ChatPiece.Error)
        assertEquals("a node that cannot be used must send nothing", 0, seen.size)
    }

    // --- approve / deny -------------------------------------------------------------------------------

    @Test fun approveRunsAndReturnsTheOutput() = runBlocking {
        val (node, seen) = fake { ex, _ -> json(ex, 200, """{"cmd_id":"c1","status":"executed","returncode":0,"stdout":"ok\n","stderr":""}""") }
        val d = BridgeClient.decide(node, "c1", approved = true)
        assertTrue(d is Decision.Executed)
        assertEquals("ok\n", (d as Decision.Executed).stdout)
        assertEquals("/api/command/decide", seen[0].path)
        val b = JSONObject(seen[0].body)
        assertEquals("c1", b.getString("cmd_id")); assertTrue(b.getBoolean("approved"))
    }

    @Test fun denySendsApprovedFalseAndNothingRuns() = runBlocking {
        val (node, seen) = fake { ex, _ -> json(ex, 200, """{"cmd_id":"c1","status":"denied"}""") }
        assertEquals(Decision.Denied, BridgeClient.decide(node, "c1", approved = false))
        assertFalse(JSONObject(seen[0].body).getBoolean("approved"))
    }

    @Test fun aCommandSomeoneElseAlreadyDecidedComesBackAsGone() = runBlocking {
        val (node, _) = fake { ex, _ -> json(ex, 404, """{"detail":"Command not found or already decided"}""") }
        val d = BridgeClient.decide(node, "c1", true) as Decision.Failed
        assertTrue(d.gone)
    }

    @Test fun anUnreachableNodeIsAFailureNotASuccess() = runBlocking {
        val port = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort } // closed again: nothing listens
        val d = BridgeClient.decide(bridgeNode(port), "c1", true)
        assertTrue(d is Decision.Failed)
        assertFalse((d as Decision.Failed).gone)
        assertTrue(BridgeClient.submit(bridgeNode(port), "ls", "", false).isFailure)
    }

    // --- chat ---------------------------------------------------------------------------------------

    @Test fun chatStreamsTokensThenDoneAndStopsReadingAfterDone() = runBlocking {
        val (node, seen) = fake { ex, _ ->
            sse(ex, listOf("data: {\"token\":\"Hel\"}\n\n", "data: {\"token\":\"lo\"}\n\n", "data: {\"done\": true}\n\n", "data: {\"token\":\"late\"}\n\n"))
        }
        val pieces = withTimeout(10_000) { BridgeClient.chat(node, "hi", false).toList() }
        assertEquals(listOf(ChatPiece.Token("Hel"), ChatPiece.Token("lo"), ChatPiece.Done), pieces)
        assertEquals("/api/chat", seen[0].path); assertEquals("Bearer tok", seen[0].auth)
        val b = JSONObject(seen[0].body)
        assertEquals("hi", b.getString("message")); assertFalse(b.getBoolean("include_telemetry"))
    }

    @Test fun chatReportsAServerError() = runBlocking {
        val (node, _) = fake { ex, _ -> json(ex, 500, """{"detail":"MAD IT is down"}""") }
        val pieces = withTimeout(10_000) { BridgeClient.chat(node, "hi", true).toList() }
        assertEquals(listOf<ChatPiece>(ChatPiece.Error("MAD IT is down")), pieces)
    }

    @Test fun chatReportsARejectedToken() = runBlocking {
        val (node, _) = fake { ex, _ -> json(ex, 401, """{"detail":"Unauthorized"}""") }
        val e = withTimeout(10_000) { BridgeClient.chat(node, "hi", true).toList() }.single() as ChatPiece.Error
        assertTrue(e.message.contains("rejected the token"))
    }

    // --- the live command list ----------------------------------------------------------------------

    private fun pending(id: String, cmd: String, dangerous: Boolean = false) =
        "event: command_pending\ndata: {\"id\":\"$id\",\"command\":\"$cmd\",\"description\":\"d\",\"source\":\"ai\",\"dangerous\":$dangerous,\"submitted_at\":\"t\",\"status\":\"pending\"}\n\n"

    private suspend fun <T> eventually(timeoutMs: Long = 10_000, probe: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { probe()?.let { return it }; delay(25) }
        throw AssertionError("condition not reached within ${timeoutMs} ms")
    }

    @Test fun theStreamCollectsPendingCommandsOnceEachAndIgnoresNoise() = runBlocking {
        val (node, seen) = fake { ex, _ ->
            sse(ex, listOf(
                ": ping\n\n",
                pending("a", "ls -l"),
                pending("a", "ls -l"),                                   // duplicate id is ignored
                "event: other\ndata: {\"id\":\"z\",\"command\":\"x\"}\n\n", // other event kinds are ignored
                "event: command_pending\ndata: not-json\n\n",            // unreadable is ignored
                pending("b", "rm -rf /tmp/x", dangerous = true),
            ), holdOpenMs = 3000)
        }
        val stream = BridgeCommandStream(node, newScope()); stream.start()
        val list = eventually { stream.commands.value.takeIf { it.size == 2 } }
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertTrue(list[1].dangerous); assertFalse(list[0].dangerous)
        assertEquals("/sse/commands", seen[0].path); assertEquals("Bearer tok", seen[0].auth)
        eventually { if (stream.state.value == LinkState.Live) true else null }
        stream.remove("a")
        assertEquals(listOf("b"), stream.commands.value.map { it.id })
        stream.stop()
        assertEquals(LinkState.Idle, stream.state.value)
    }

    @Test fun afterAReconnectTheListIsRebuiltFromWhatTheBridgeStillHolds() = runBlocking {
        var connection = 0
        val (node, _) = fake { ex, _ ->
            connection++
            if (connection == 1) sse(ex, listOf(pending("a", "ls"), pending("b", "pwd")))   // then the link drops
            else sse(ex, listOf(pending("b", "pwd")), holdOpenMs = 3000)                     // "a" was decided elsewhere
        }
        val stream = BridgeCommandStream(node, newScope()); stream.start()
        eventually { stream.commands.value.takeIf { it.size == 2 } }
        val after = eventually(15_000) { stream.commands.value.takeIf { connection >= 2 && it.map { c -> c.id } == listOf("b") } }
        assertEquals(listOf("b"), after.map { it.id })
        stream.stop()
    }

    @Test fun aRejectedTokenShowsAsRetryingWithAReasonAndNeverFillsTheList() = runBlocking {
        val (node, _) = fake { ex, _ -> json(ex, 401, """{"detail":"Unauthorized"}""") }
        val stream = BridgeCommandStream(node, newScope()); stream.start()
        val retry = eventually { stream.state.value as? LinkState.Retrying }
        assertTrue(retry.reason.contains("rejected the token"))
        assertTrue(stream.commands.value.isEmpty())
        stream.stop()
    }

    @Test fun anUnpairedOrWrongKindOfNodeNeverConnects() = runBlocking {
        val (node, seen) = fake { ex, _ -> sse(ex, listOf(pending("a", "ls")), holdOpenMs = 500) }
        BridgeCommandStream(node.copy(token = ""), newScope()).also { it.start() }
        BridgeCommandStream(node.copy(backend = NodeBackend.LUCYSERVER), newScope()).also { it.start() }
        delay(500)
        assertEquals(0, seen.size)
    }
}
