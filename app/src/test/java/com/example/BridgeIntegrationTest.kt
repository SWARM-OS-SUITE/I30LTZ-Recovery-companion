package com.example

import com.example.data.bridge.BridgeClient
import com.example.data.bridge.BridgeCommandStream
import com.example.data.bridge.ChatPiece
import com.example.data.bridge.Decision
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs the app's bridge client against the REAL `sse_bridge.py` (FastAPI), with a tiny in-process stand-in for MAD IT.
 * Opt-in: set I30LTZ_BRIDGE_PY (a Python with fastapi, uvicorn, sse-starlette, httpx, psutil) and I30LTZ_BRIDGE_SCRIPT
 * (path to sse_bridge.py); without them these tests are skipped.
 *
 * Safe by construction: the bridge listens on 127.0.0.1 only, uses a random token made for this run and a temp data dir,
 * and the only command ever approved is `echo`. The deny test proves a denied command is never executed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeIntegrationTest {
    private var bridge: Process? = null
    private var madit: HttpServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var data: File
    private lateinit var node: Node
    private val maditSaw = mutableListOf<String>()
    private val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    @Before fun startBridge() {
        val py = System.getenv("I30LTZ_BRIDGE_PY")
        val script = System.getenv("I30LTZ_BRIDGE_SCRIPT")
        assumeTrue("I30LTZ_BRIDGE_PY / I30LTZ_BRIDGE_SCRIPT not set", !py.isNullOrBlank() && !script.isNullOrBlank() && File(py!!).canExecute() && File(script!!).isFile)
        data = Files.createTempDirectory("i30ltz-bridge-it").toFile()

        madit = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                if (ex.requestURI.path == "/health") {
                    ex.sendResponseHeaders(200, -1); ex.close(); return@createContext
                }
                synchronized(maditSaw) { maditSaw += ex.requestBody.readBytes().toString(Charsets.UTF_8) }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    listOf("data: {\"token\":\"Hello \"}\n\n", "data: {\"token\":\"from MAD IT\"}\n\n").forEach { out.write(it.toByteArray()); out.flush() }
                }
            }
            start()
        }

        val port = ServerSocket(0).use { it.localPort }
        bridge = ProcessBuilder(listOf(py!!, script!!)).directory(data).redirectErrorStream(true)
            .redirectOutput(File(data, "bridge.log")).also { b ->
                b.environment().apply {
                    put("SSE_HOST", "127.0.0.1"); put("SSE_PORT", port.toString())
                    put("MADIT_URL", "http://127.0.0.1:${madit!!.address.port}")
                    put("I30LTZ_AUTH_TOKEN", token); put("I30LTZ_DATA_DIR", data.absolutePath)
                }
            }.start()
        node = Node(name = "Real bridge", os = "Recovery OS", backend = NodeBackend.SSE_BRIDGE, host = "127.0.0.1", port = port, token = token)
        runBlocking {
            withTimeout(30_000) { while (!NodeClient.probe(node).ok) delay(250) }
        }
    }

    @After fun stopBridge() {
        scope.cancel()
        bridge?.let { it.destroy(); if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
        madit?.stop(0)
        if (::data.isInitialized) data.deleteRecursively()
    }

    private suspend fun <T> eventually(timeoutMs: Long = 15_000, probe: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { probe()?.let { return it }; delay(50) }
        throw AssertionError("condition not reached within $timeoutMs ms")
    }

    @Test fun theRealBridgeAcceptsTheTokenAndRejectsAWrongOne() = runBlocking {
        val good = NodeClient.probe(node)
        assertTrue(good.lines.joinToString("\n"), good.ok)
        assertTrue(good.lines.joinToString("\n").contains("Dr. I30LTZ (MAD IT) online: yes"))
        val bad = NodeClient.probe(node.copy(token = "wrong-token"))
        assertFalse(bad.ok)
        assertTrue(bad.lines.joinToString().contains("rejected the token"))
    }

    @Test fun queueThenApproveRunsTheCommandAndReturnsItsOutput() = runBlocking {
        val stream = BridgeCommandStream(node, scope).also { it.start() }
        val id = BridgeClient.submit(node, "echo bridge-e2e-ok", "integration test", false).getOrThrow()
        val shown = eventually { stream.commands.value.firstOrNull { it.id == id } }
        assertEquals("echo bridge-e2e-ok", shown.command); assertEquals("integration test", shown.description)
        assertEquals("android", shown.source); assertFalse(shown.dangerous)

        val d = BridgeClient.decide(node, id, approved = true) as Decision.Executed
        assertEquals(0, d.returnCode); assertTrue(d.succeeded)
        assertEquals("bridge-e2e-ok\n", d.stdout)

        // The same command cannot be decided twice: the bridge forgot it, and the app must say so (and drop it).
        val again = BridgeClient.decide(node, id, approved = true) as Decision.Failed
        assertTrue(again.gone)
        stream.stop()
    }

    @Test fun aDeniedCommandIsNeverExecuted() = runBlocking {
        val canary = File(data, "must-not-exist")
        val stream = BridgeCommandStream(node, scope).also { it.start() }
        val id = BridgeClient.submit(node, "touch ${canary.absolutePath}", "dangerous canary", true).getOrThrow()
        val shown = eventually { stream.commands.value.firstOrNull { it.id == id } }
        assertTrue("the dangerous flag must survive the round trip", shown.dangerous)

        assertEquals(Decision.Denied, BridgeClient.decide(node, id, approved = false))
        delay(1000) // long enough for any wrongly-started command to have created the file
        assertFalse("a denied command must not run", canary.exists())
        assertTrue((BridgeClient.decide(node, id, approved = true) as Decision.Failed).gone)
        assertFalse("and approving it afterwards must not run it either", canary.exists())
        stream.stop()
    }

    @Test fun aFailingCommandIsReportedWithItsExitCode() = runBlocking {
        val id = BridgeClient.submit(node, "echo oops >&2; exit 3", "", false).getOrThrow()
        val d = BridgeClient.decide(node, id, approved = true) as Decision.Executed
        assertEquals(3, d.returnCode); assertFalse(d.succeeded); assertEquals("oops\n", d.stderr)
    }

    @Test fun anUnknownCommandIdIsGone() = runBlocking {
        assertTrue((BridgeClient.decide(node, "does-not-exist", approved = true) as Decision.Failed).gone)
    }

    @Test fun aWrongTokenCannotQueueDecideOrWatch() = runBlocking {
        val wrong = node.copy(token = "not-the-token")
        assertTrue(BridgeClient.submit(wrong, "echo no", "", false).exceptionOrNull()!!.message!!.contains("rejected the token"))
        val d = BridgeClient.decide(wrong, "x", approved = true) as Decision.Failed
        assertFalse("a rejected token is not 'gone'", d.gone)
        val stream = BridgeCommandStream(wrong, scope).also { it.start() }
        assertTrue(eventually { stream.state.value as? LinkState.Retrying }.reason.contains("rejected the token"))
        stream.stop()
    }

    @Test fun chatGoesThroughTheRealBridgeToMadItAndStreamsTheReplyBack() = runBlocking {
        val pieces = withTimeout(30_000) { BridgeClient.chat(node, "what is wrong with my disk?", true).toList() }
        assertEquals("Hello from MAD IT", pieces.filterIsInstance<ChatPiece.Token>().joinToString("") { it.text })
        assertEquals(ChatPiece.Done, pieces.last())
        val sent = synchronized(maditSaw) { maditSaw.single() }
        val o = JSONObject(sent)
        assertEquals("what is wrong with my disk?", o.getString("message")); assertTrue(o.getBoolean("include_telemetry"))
        assertNotNull(pieces)
    }
}
