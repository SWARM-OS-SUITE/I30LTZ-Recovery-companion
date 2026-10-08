package com.example

import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeSecurityController
import com.example.data.nodes.NodeStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import com.example.data.nodes.NodeTerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.example.data.nodes.PairOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the app's node client against the real `i30ltz-node` binary.
 * Opt-in: set I30LTZ_NODE_BIN to the built binary (30-source/i30ltz-node/dist/i30ltz-node);
 * without it these tests are skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentIntegrationTest {
    private var agent: Process? = null
    private lateinit var bin: String
    private lateinit var cfg: File
    private lateinit var tmuxDir: File
    private var port = 0
    private val extraAgents = mutableListOf<Process>()
    private val clamscan: String? = System.getenv("I30LTZ_CLAMSCAN")?.takeIf { it.isNotBlank() && File(it).canExecute() }
    private val marker = "I30LTZ-NOT-REAL-MALWARE-android-marker-5d2e"
    private val toDelete = mutableListOf<File>()

    @Before fun startAgent() {
        val path = System.getenv("I30LTZ_NODE_BIN")
        assumeTrue("I30LTZ_NODE_BIN not set", !path.isNullOrBlank() && File(path).canExecute())
        bin = path!!
        port = ServerSocket(0).use { it.localPort }
        cfg = Files.createTempDirectory("i30ltz-node-it").toFile()
        tmuxDir = Files.createTempDirectory("i30ltz-tmux-it").toFile() // private tmux server, never the real one
        val scanArgs = clamscan?.let { listOf("--clamscan", it, "--clam-db", signatureDir().absolutePath) } ?: listOf("--clamscan", "/nonexistent/clamscan")
        agent = ProcessBuilder(listOf(bin, "serve", "--terminal", "--listen", "127.0.0.1", "--port", port.toString(), "--config-dir", cfg.absolutePath) + scanArgs)
            .redirectErrorStream(true).redirectOutput(File(cfg, "agent.log"))
            .also { it.environment()["TMUX_TMPDIR"] = tmuxDir.absolutePath; it.environment().remove("TMUX") }.start()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(50)
        }
        error("agent did not start: ${File(cfg, "agent.log").readText()}")
    }

    /** A signature database that knows exactly one thing: the marker text. */
    private fun signatureDir(): File {
        val dir = Files.createTempDirectory("i30ltz-sigs").toFile().also { toDelete += it }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(marker.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "marker.hdb").writeText("$md5:${marker.length}:Android.Test.Marker\n")
        return dir
    }

    @After fun stopAgent() {
        toDelete.forEach { it.deleteRecursively() }
        if (::tmuxDir.isInitialized) {
            runCatching { tmux("kill-server").waitFor(3, TimeUnit.SECONDS) }
            tmuxDir.deleteRecursively()
        }
        extraAgents.forEach { it.destroy() }
        agent?.destroy(); agent?.waitFor(3, TimeUnit.SECONDS)
        if (::cfg.isInitialized) cfg.deleteRecursively()
    }

    private fun tmux(vararg args: String): Process =
        ProcessBuilder("tmux", *args).redirectErrorStream(true)
            .also { it.environment()["TMUX_TMPDIR"] = tmuxDir.absolutePath; it.environment().remove("TMUX") }.start()

    private fun tmuxOut(vararg args: String): String = tmux(*args).let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

    private fun waitForPort(p: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", p).close() }.isSuccess) return
            Thread.sleep(50)
        }
        error("nothing listening on $p")
    }

    private fun newCode(): String {
        val p = ProcessBuilder(bin, "pair", "--port", port.toString()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return Regex("Pairing code: ([A-Z0-9-]+)").find(out)?.groupValues?.get(1) ?: error("no code in: $out")
    }

    private fun node(token: String) = Node(name = "it", os = "Debian", backend = NodeBackend.AGENT, host = "127.0.0.1", port = port, token = token)

    private suspend fun pairedNode(): Node {
        val r = NodeClient.pair(NodeBackend.AGENT, "127.0.0.1", port, newCode(), "integration test")
        return node((r as PairOutcome.Paired).token)
    }

    @Test fun pairingProbeTelemetryProcessesAndKill() = runBlocking {
        val code = newCode()
        val paired = NodeClient.pair(NodeBackend.AGENT, "127.0.0.1", port, code, "integration test")
        assertTrue("pair: $paired", paired is PairOutcome.Paired)
        val n = node((paired as PairOutcome.Paired).token)

        // A code works once.
        val again = NodeClient.pair(NodeBackend.AGENT, "127.0.0.1", port, code, "x")
        assertTrue("reuse: $again", again is PairOutcome.Failed && again.message.contains("wrong or expired"))

        val probe = NodeClient.probe(n)
        assertTrue(probe.lines.toString(), probe.ok)
        assertTrue(probe.lines.joinToString(), probe.lines.any { it.startsWith("Works:") && "kill" in it })
        assertTrue(probe.lines.joinToString(), probe.lines.any { it.contains("Not built on this node yet") && "optimize" in it && "jobs" in it && "scan" !in it })

        val bad = NodeClient.probe(node("wrong-token"))
        assertFalse(bad.ok)
        assertTrue(bad.lines.joinToString(), bad.lines.any { it.contains("rejected the token") })

        val t = NodeClient.telemetry(n)
        assertTrue("ram ${t.ram}", t.ram in 0..100)

        val procs = NodeClient.processes(n)
        assertTrue(procs.isNotEmpty())

        // `exec` keeps the shell's pid, so the pid we print is the sleeping process itself.
        val child = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 60").start()
        try {
            val childPid = child.inputStream.bufferedReader().readLine().trim().toInt()
            val msg = NodeClient.kill(n, childPid)
            assertTrue(msg, msg.contains("accepted"))
            assertTrue("child should die", child.waitFor(5, TimeUnit.SECONDS))
        } finally { child.destroyForcibly() }

        // The agent refuses protected targets, and the app shows the agent's reason, not "bad token".
        val refused = runCatching { NodeClient.kill(n, 1) }.exceptionOrNull()
        assertTrue("pid 1: $refused", refused?.message.orEmpty().contains("refusing"))
        assertEquals(false, refused?.message.orEmpty().contains("rejected the token"))
    }

    @Test fun liveStreamDeliversTelemetry() = runBlocking {
        val n = pairedNode()
        val result = NodeStream.probe(n, 8000)
        assertTrue(result, result.startsWith("Live stream works"))
    }

    @Test fun liveStreamWithBadTokenReportsWhy() = runBlocking {
        val result = NodeStream.probe(node("wrong-token"), 5000)
        assertTrue(result, result.startsWith("Live stream failed") && result.contains("rejected"))
    }

    /** Collects a session's output so a test can wait for text to appear. */
    private class Screen {
        private val text = StringBuffer()
        fun add(b: ByteArray) { text.append(String(b, Charsets.UTF_8)) }
        fun has(s: String) = text.contains(s)
        override fun toString() = text.toString()
    }

    private suspend fun waitUntil(what: String, ms: Long = 6000, check: suspend () -> Boolean) {
        withTimeout(ms) { while (!check()) kotlinx.coroutines.delay(25) }
    }

    @Test fun terminalMirrorsThePcAndTheSessionSurvivesLeaving() = runBlocking {
        val n = pairedNode()
        val screen = Screen()
        val s = NodeTerminalSession(n)
        val collector = launch(Dispatchers.IO) { s.output.collect { screen.add(it) } }
        s.connect("itest", 100, 30)
        waitUntil("connected") { s.state.value is NodeTerminalSession.State.Connected }

        assertTrue(s.send("echo kt-\$((6*7))\n"))
        waitUntil("typed command") { screen.has("kt-42") }

        // The node's own user is just another tmux client typing into the same session.
        tmux("send-keys", "-t", "itest", "echo from-the-pc", "Enter").waitFor()
        waitUntil("pc text") { screen.has("from-the-pc") }

        s.resize(120, 40)
        waitUntil("resize") { tmuxOut("list-clients", "-t", "itest", "-F", "#{client_width}x#{client_height}").trim() == "120x40" }

        val listed = NodeClient.terminalSessions(n)
        assertEquals(listOf("itest"), listed.map { it.name })
        assertEquals(1, listed.single().attached)

        s.close()
        collector.cancel()
        waitUntil("detached") { NodeClient.terminalSessions(n).singleOrNull()?.attached == 0 }
        assertEquals(false, s.send("x")) // sending on a closed session reports failure, not a crash
    }

    @Test fun terminalWithABadTokenSaysSo() = runBlocking {
        val s = NodeTerminalSession(node("wrong-token"))
        s.connect("main", 80, 24)
        waitUntil("closed") { s.state.value is NodeTerminalSession.State.Closed }
        val closed = s.state.value as NodeTerminalSession.State.Closed
        assertTrue(closed.reason, closed.failed && closed.reason.contains("rejected the token"))
    }

    @Test fun terminalIsRefusedWithAClearReasonWhenTheAgentHasItOff() = runBlocking {
        val plainPort = ServerSocket(0).use { it.localPort }
        val plainCfg = Files.createTempDirectory("i30ltz-node-it-plain").toFile()
        extraAgents += ProcessBuilder(bin, "serve", "--listen", "127.0.0.1", "--port", plainPort.toString(), "--config-dir", plainCfg.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(plainCfg, "agent.log")).start()
        waitForPort(plainPort)
        try {
            val code = ProcessBuilder(bin, "pair", "--port", plainPort.toString()).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().let { Regex("Pairing code: ([A-Z0-9-]+)").find(it)!!.groupValues[1] }
            val token = (NodeClient.pair(NodeBackend.AGENT, "127.0.0.1", plainPort, code, "t") as PairOutcome.Paired).token
            val n = node(token).copy(port = plainPort)
            assertEquals(false, NodeClient.agentFeatures(n)?.get("terminal"))
            val s = NodeTerminalSession(n)
            s.connect("main", 80, 24)
            waitUntil("closed") { s.state.value is NodeTerminalSession.State.Closed }
            assertTrue((s.state.value as NodeTerminalSession.State.Closed).reason, (s.state.value as NodeTerminalSession.State.Closed).reason.contains("--terminal"))
        } finally { plainCfg.deleteRecursively() }
    }

    // ── malware scanning, with the real agent and the real ClamAV ──────────────────────────

    private fun scanTarget(): File = Files.createTempDirectory("i30ltz-scan-target").toFile().also {
        toDelete += it
        File(it, "ok.txt").writeText("perfectly fine")
        File(it, "bad.bin").writeText(marker)
    }

    @Test fun scanFeatureIsOnlyAdvertisedWhenTheScannerExists() = runBlocking {
        val n = pairedNode()
        assertEquals(clamscan != null, NodeClient.agentFeatures(n)?.get("scan"))
        val (info, _) = NodeClient.scanStatus(n)
        assertEquals(clamscan != null, info.available)
        if (clamscan == null) {
            assertTrue(info.reason, info.reason.contains("ClamAV"))
            val err = runCatching { NodeClient.startScan(n, "home") }.exceptionOrNull()
            assertTrue("$err", err?.message.orEmpty().contains("ClamAV"))
        }
    }

    @Test fun controllerScansQuarantinesRestoresAndDeletesForReal() = runBlocking {
        assumeTrue("I30LTZ_CLAMSCAN not set", clamscan != null)
        val n = pairedNode()
        val dir = scanTarget()
        val bad = File(dir, "bad.bin")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val ctl = NodeSecurityController(scope, pollEveryMs = 100)
            suspend fun until(what: String, check: (com.example.data.nodes.NodeSecurity) -> Boolean) =
                waitUntil(what, 20000) { ctl.state.value[n.id]?.let(check) == true }

            ctl.refresh(n)
            until("scanner status") { it.info?.available == true && !it.busy }
            assertTrue(ctl.state.value[n.id]!!.info!!.version.isNotBlank())

            ctl.start(n, "path", dir.absolutePath)
            until("scan finished") { it.scan?.state == "done" && !it.busy }
            val scan = ctl.state.value[n.id]!!.scan!!
            assertEquals(listOf(bad.absolutePath), scan.findings.map { it.path })
            assertTrue(scan.findings.single().signature, scan.findings.single().signature.startsWith("Android.Test.Marker"))
            assertEquals(2, scan.scanned)
            assertTrue("a scan alone must not touch files", bad.exists())

            ctl.quarantine(n, scan.findings.single())
            until("quarantined") { it.quarantine.size == 1 && !it.busy }
            assertTrue(ctl.state.value[n.id]!!.message, ctl.state.value[n.id]!!.message.startsWith("Quarantined"))
            assertEquals(false, bad.exists())
            assertTrue("other files stay", File(dir, "ok.txt").exists())

            ctl.restore(n, ctl.state.value[n.id]!!.quarantine.single())
            until("restored") { it.quarantine.isEmpty() && !it.busy }
            assertEquals(marker, bad.readText())

            ctl.quarantine(n, scan.findings.single())
            until("quarantined again") { it.quarantine.size == 1 && !it.busy }
            ctl.delete(n, ctl.state.value[n.id]!!.quarantine.single())
            until("deleted") { it.quarantine.isEmpty() && !it.busy }
            assertEquals(false, bad.exists())

            ctl.start(n, "path", "/no/such/folder/anywhere")
            until("refusal") { it.message.isNotBlank() && !it.busy }
            assertTrue(ctl.state.value[n.id]!!.message, ctl.state.value[n.id]!!.message.contains("cannot read"))
        } finally { scope.cancel() }
    }

    @Test fun quarantineRefusesFilesThatAreNotFindings() = runBlocking {
        assumeTrue("I30LTZ_CLAMSCAN not set", clamscan != null)
        val n = pairedNode()
        val dir = scanTarget()
        val id = NodeClient.startScan(n, "path", dir.absolutePath)
        withTimeout(20000) { while (NodeClient.getScan(n, id).running) delay(100) }
        val err = runCatching { NodeClient.quarantineFile(n, id, File(dir, "ok.txt").absolutePath) }.exceptionOrNull()
        assertTrue("$err", err?.message.orEmpty().contains("not one of this scan's findings"))
        assertTrue(File(dir, "ok.txt").exists())
        assertEquals(emptyList<Any>(), NodeClient.quarantineList(n))
    }

    @Test fun cancellingAScanIsReportedAsCancelledNotComplete() = runBlocking {
        assumeTrue("I30LTZ_CLAMSCAN not set", clamscan != null)
        val n = pairedNode()
        val big = Files.createTempDirectory("i30ltz-many").toFile().also { toDelete += it }
        repeat(3000) { File(big, "f$it.txt").writeText("file number $it") }
        val id = NodeClient.startScan(n, "path", big.absolutePath)
        NodeClient.cancelScan(n, id)
        val v = withTimeout(10000) { var x = NodeClient.getScan(n, id); while (x.running) { delay(50); x = NodeClient.getScan(n, id) }; x }
        assertEquals("cancelled", v.state)
    }
}
