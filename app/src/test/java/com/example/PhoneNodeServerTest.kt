package com.example

import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeStream
import com.example.data.nodes.PairOutcome
import com.example.data.phonenode.PhoneNodeAuth
import com.example.data.phonenode.PhoneNodeServer
import com.example.data.phonenode.PhoneSample
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** The phone node server on real sockets, plus the app's own client talking to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneNodeServerTest {
    private class Mem : PhoneNodeAuth.Store {
        var saved: List<PhoneNodeAuth.Device> = emptyList()
        override fun load() = saved
        override fun save(devices: List<PhoneNodeAuth.Device>) { saved = devices.toList() }
    }

    private val sample = PhoneSample("test-phone", "Android 16 (Test)", 8000, 2000, 128.0, 64.5, 3600, 87, true, 31.5)
    private val servers = mutableListOf<PhoneNodeServer>()
    private val logs = CopyOnWriteArrayList<String>()

    private class Fixture(val server: PhoneNodeServer, val auth: PhoneNodeAuth, val port: Int)

    private fun fixture(maxConnections: Int = 16, maxStreams: Int = 4): Fixture {
        val auth = PhoneNodeAuth(Mem())
        val server = PhoneNodeServer(auth, { sample }, "test", 0, maxConnections, maxStreams, streamEveryMs = 100, log = { logs.add(it) })
        assertTrue(server.listenOn(setOf("127.0.0.1")).isEmpty())
        servers += server
        return Fixture(server, auth, server.bound().getValue("127.0.0.1"))
    }

    @After fun stop() { servers.forEach { it.stop() } }

    /** One raw HTTP exchange: returns status code and body. */
    private fun http(port: Int, request: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1)); s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = text.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            return status to text.substringAfter("\r\n\r\n", "")
        }
    }

    private fun get(port: Int, path: String, token: String = "") =
        http(port, "GET $path HTTP/1.1\r\nHost: x\r\n" + (if (token.isNotEmpty()) "Authorization: Bearer $token\r\n" else "") + "\r\n")

    private fun post(port: Int, path: String, body: String, token: String = "") =
        http(port, "POST $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.toByteArray().size}\r\n" +
            (if (token.isNotEmpty()) "Authorization: Bearer $token\r\n" else "") + "\r\n$body")

    private fun pairToken(f: Fixture, label: String = "test phone"): String {
        val (code, body) = post(f.port, "/v1/pair", """{"code":"${f.auth.newCode().code}","label":"$label"}""")
        assertEquals(body, 200, code)
        return JSONObject(body).getString("token")
    }

    @Test fun nothingIsServedWithoutAPairedToken() {
        val f = fixture()
        for (p in listOf("/v1/capabilities", "/v1/telemetry", "/v1/events", "/v1/processes", "/anything")) {
            val (code, body) = get(f.port, p)
            assertEquals("$p", 401, code)
            assertFalse("a refusal must not leak data: $body", body.contains("test-phone"))
        }
        assertEquals(401, get(f.port, "/v1/telemetry", "not-a-token").first)
    }

    @Test fun pairingThenCapabilitiesAndTelemetryAreHonest() {
        val f = fixture()
        val token = pairToken(f)
        val caps = JSONObject(get(f.port, "/v1/capabilities", token).second)
        assertEquals("i30ltz-phone", caps.getString("agent")); assertEquals(1, caps.getInt("api")); assertEquals("test-phone", caps.getString("hostname"))
        val feat = caps.getJSONObject("features")
        assertTrue(feat.getBoolean("telemetry") && feat.getBoolean("events"))
        for (k in listOf("processes", "kill", "scan", "optimize", "terminal", "jobs")) assertFalse("$k must be reported as unavailable on a phone", feat.getBoolean(k))

        val t = JSONObject(get(f.port, "/v1/telemetry", token).second)
        assertEquals(-1, t.getInt("cpu_percent")) // Android gives apps no CPU load: never invented
        assertEquals(25.0, t.getDouble("ram_percent"), 0.01)
        assertEquals(64.5, t.getDouble("storage_free_gb"), 0.01)
        assertEquals(87, t.getInt("battery_percent")); assertTrue(t.getBoolean("charging")); assertEquals(31.5, t.getDouble("temp_c"), 0.01)
    }

    @Test fun wrongCodesAreRefusedThenLockedAndNeverLogged() {
        val f = fixture()
        f.auth.newCode()
        val codes = (1..5).map { post(f.port, "/v1/pair", """{"code":"ZZZZ-9999","label":"x"}""").first }
        assertEquals(listOf(401, 401, 401, 401, 429), codes)
        assertEquals(400, post(f.port, "/v1/pair", "not json").first)
        val text = logs.joinToString("\n")
        assertTrue(text, text.contains("wrong or expired code")); assertFalse(text.contains("ZZZZ-9999"))
    }

    @Test fun thingsAPhoneCannotDoSayWhyInsteadOfPretending() {
        val f = fixture()
        val token = pairToken(f)
        for ((method, path) in listOf("GET" to "/v1/processes", "POST" to "/v1/processes/1/kill", "GET" to "/v1/terminal", "GET" to "/v1/scan", "POST" to "/v1/scan", "GET" to "/v1/quarantine")) {
            val (code, body) = http(f.port, "$method $path HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nAuthorization: Bearer $token\r\n\r\n")
            assertEquals("$method $path", 404, code)
            assertTrue(body, body.contains("not available on a phone"))
        }
        assertEquals(404, get(f.port, "/v1/nonsense", token).first)
    }

    @Test fun noRequestCanCreateAPairingCode() {
        val f = fixture()
        val token = pairToken(f)
        for (p in listOf("/v1/pairing/new", "/v1/pairing-code", "/api/pairing-code")) {
            assertTrue(p, get(f.port, p, token).first == 404 && post(f.port, p, "{}", token).first == 404)
        }
        assertEquals("no code may exist unless the phone's own screen made one", null, f.auth.currentCode())
    }

    @Test fun malformedAndOversizedRequestsGetPlainErrors() {
        val f = fixture()
        assertEquals(431, http(f.port, "GET / HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n\r\n").first)
        assertEquals(413, http(f.port, "POST /v1/pair HTTP/1.1\r\nContent-Length: 99999\r\n\r\n").first)
        assertEquals(400, http(f.port, "nonsense\r\n\r\n").first)
        assertEquals(405, http(f.port, "PUT /v1/telemetry HTTP/1.1\r\nHost: x\r\n\r\n").first)
        assertEquals(501, http(f.port, "POST /v1/pair HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").first)
        // and the server is still fine afterwards
        assertEquals(401, get(f.port, "/v1/telemetry").first)
    }

    @Test fun liveStreamDeliversTelemetryEvents() {
        val f = fixture()
        val token = pairToken(f)
        Socket("127.0.0.1", f.port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("GET /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
            val reader = s.getInputStream().bufferedReader()
            assertTrue(reader.readLine().contains("200"))
            val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            assertTrue(head.toString(), head.any { it.equals("Content-Type: text/event-stream", true) })
            val events = mutableListOf<String>()
            while (events.size < 2) { val l = reader.readLine() ?: break; if (l.startsWith("data: ")) events += l.removePrefix("data: ") }
            assertEquals(2, events.size)
            assertEquals("test-phone", JSONObject(events[0]).getString("hostname"))
        }
    }

    @Test fun tooManyConnectionsAndStreamsAreTurnedAway() {
        val f = fixture(maxConnections = 2, maxStreams = 1)
        val idle = (1..2).map { Socket("127.0.0.1", f.port) } // hold two slots without sending anything
        try {
            Thread.sleep(200)
            assertEquals("the third connection is refused", 503, get(f.port, "/v1/telemetry").first)
        } finally { idle.forEach { it.close() } }
        Thread.sleep(300)

        val g = fixture(maxStreams = 1)
        val token = pairToken(g)
        val first = Socket("127.0.0.1", g.port).also { it.getOutputStream().write("GET /v1/events HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray()) }
        try {
            first.soTimeout = 5000; first.getInputStream().bufferedReader().readLine()
            assertEquals("a second live stream is refused", 503, get(g.port, "/v1/events", token).first)
        } finally { first.close() }
    }

    @Test fun listeningAddressesFollowTheRequestedSet() {
        val f = fixture()
        val extra = f.server.listenOn(setOf("127.0.0.1", "127.0.0.2"))
        assertTrue(extra.toString(), extra.isEmpty())
        assertEquals(setOf("127.0.0.1", "127.0.0.2"), f.server.bound().keys)
        assertEquals(401, get(f.port, "/v1/telemetry").first)
        f.server.listenOn(setOf("127.0.0.2"))
        assertEquals(setOf("127.0.0.2"), f.server.bound().keys)
        assertTrue("the removed address must stop answering", runCatching { Socket("127.0.0.1", f.port).close() }.isFailure)
        val bad = f.server.listenOn(setOf("127.0.0.2", "203.0.113.77")) // not an address this machine has
        assertTrue(bad.containsKey("203.0.113.77")); assertTrue("good addresses keep working", f.server.bound().containsKey("127.0.0.2"))
    }

    // ── the app's own client against this server ──────────────────────────────

    @Test fun theAppsOwnClientPairsProbesReadsTelemetryAndStreams() = runBlocking {
        val f = fixture()
        val node = Node(name = "other phone", os = "Android", backend = NodeBackend.PHONE, host = "127.0.0.1", port = f.port)

        val bad = NodeClient.pair(NodeBackend.PHONE, "127.0.0.1", f.port, "AAAA-AAAA", "app")
        assertTrue("$bad", bad is PairOutcome.Failed && bad.message.contains("wrong or expired"))

        val paired = NodeClient.pair(NodeBackend.PHONE, "127.0.0.1", f.port, f.auth.newCode().code, "I30LTZ Companion (test)")
        assertTrue("$paired", paired is PairOutcome.Paired)
        assertEquals("test-phone", (paired as PairOutcome.Paired).hostname)
        val n = node.copy(token = paired.token)

        val probe = NodeClient.probe(n)
        assertTrue(probe.lines.toString(), probe.ok)
        assertTrue(probe.lines.joinToString(), probe.lines.any { it.contains("Phone test-phone") })
        assertTrue(probe.lines.joinToString(), probe.lines.any { it.contains("Not possible on a phone") })
        assertFalse(NodeClient.probe(n.copy(token = "wrong")).ok)

        val t = NodeClient.telemetry(n)
        assertEquals(-1, t.cpu); assertEquals(25, t.ram); assertEquals(64, t.storage); assertEquals(87, t.battery)

        val err = runCatching { NodeClient.processes(n) }.exceptionOrNull()
        assertTrue("$err", err is UnsupportedOperationException && err.message!!.contains("phone"))

        val stream = NodeStream.probe(n, 6000)
        assertTrue(stream, stream.startsWith("Live stream works"))
        assertNotNull(logs.firstOrNull { it.startsWith("paired") })
    }
}
