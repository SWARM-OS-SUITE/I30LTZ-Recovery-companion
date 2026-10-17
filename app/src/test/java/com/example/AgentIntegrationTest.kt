package com.example

import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeSecurityController
import com.example.data.nodes.NodeStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
        // Started from a background shell, this JVM can have SIGINT/SIGQUIT *ignored*, and every child inherits that: Ctrl-C
        // inside the terminal then cannot stop anything (sudo answers "Sorry, try again"). Reset both before exec'ing the agent
        // so the tests behave the same however they were launched.
        val python = File("/usr/bin/python3").takeIf { it.canExecute() }?.path
        val resetSignals = if (python == null) emptyList() else listOf(python, "-c",
            "import os,signal,sys; signal.signal(signal.SIGINT, signal.SIG_DFL); signal.signal(signal.SIGQUIT, signal.SIG_DFL); os.execv(sys.argv[1], sys.argv[1:])")
        agent = ProcessBuilder(resetSignals + listOf(bin, "serve", "--terminal", "--listen", "127.0.0.1", "--port", port.toString(), "--config-dir", cfg.absolutePath) + scanArgs)
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
        System.getenv("I30LTZ_AGENT_LOG_DIR")?.takeIf { it.isNotBlank() && ::cfg.isInitialized }?.let { dir ->
            runCatching { File(cfg, "agent.log").copyTo(File(dir, "agent-${System.nanoTime()}.log"), overwrite = true) }
        }
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

    // ── sudo and paste, through the real agent and a real tmux ────────────────────────────────

    /** Raw terminal bytes, decoded as a whole so a multi-byte character split across chunks still reads right. */
    private class RawScreen {
        private val buf = java.io.ByteArrayOutputStream()
        @Synchronized fun add(b: ByteArray) { buf.write(b) }
        @Synchronized fun text(): String = String(buf.toByteArray(), Charsets.UTF_8)
        fun has(s: String) = text().contains(s)
    }

    private suspend fun waitScreen(screen: RawScreen, what: String, ms: Long = 10_000, check: (String) -> Boolean) {
        try {
            withTimeout(ms) { while (!check(screen.text())) kotlinx.coroutines.delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            System.getenv("I30LTZ_AGENT_LOG_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
                runCatching { File(dir, "screen-${System.nanoTime()}.txt").writeText("waiting for: $what\n" + screen.text().replace("\u001b", "<ESC>")) }
            }
            throw AssertionError("timed out waiting for $what. Terminal output so far (last 1500 chars):\n" + screen.text().takeLast(1500).replace("\u001b", "<ESC>"))
        }
    }

    private suspend fun <T> withTerminal(name: String, body: suspend (NodeTerminalSession, RawScreen) -> T): T = coroutineScope {
        val screen = RawScreen()
        val s = NodeTerminalSession(pairedNode())
        val collector = launch(Dispatchers.IO) { s.output.collect { screen.add(it) } }
        try {
            s.connect(name, 100, 30)
            waitUntil("connected") { s.state.value is NodeTerminalSession.State.Connected }
            body(s, screen)
        } finally {
            s.close(); collector.cancel()
        }
    }

    @Test fun realSudoPromptsInTheTerminalNeverEchoesTypingAndCtrlCCancels() = runBlocking {
        withTerminal("sudo1") { s, screen ->
            // A node started with NoNewPrivileges would make sudo fail with a confusing message. It must not be set.
            s.send("grep NoNewPrivs /proc/self/status\n")
            waitScreen(screen, "the NoNewPrivs line") { Regex("NoNewPrivs:(?:\\s|\u001b\\[\\d*C)+0").containsMatchIn(it) }

            s.send("sudo -k; sudo -p \"\$(printf 'SUDO-PW\\076 ')\" true\n")   // prompt is built at run time: the echoed command line cannot match it
            waitScreen(screen, "the real sudo password prompt") { it.contains("SUDO-PW> ") }
            s.send("hunter2-SECRET")                                // typed at the prompt, no Enter
            kotlinx.coroutines.delay(600)
            assertFalse("sudo turned echo off, so what is typed must never come back", screen.has("hunter2-SECRET"))

            s.send(byteArrayOf(0x03))                                // Ctrl-C, the way the CTRL button + C sends it
            // tmux's status line names the running program: wait until it is bash again, i.e. sudo really ended.
            waitScreen(screen, "sudo to end after Ctrl-C") { it.lastIndexOf("0:bash*") > it.lastIndexOf("0:sudo*") }
            // sudo restores the terminal with TCSAFLUSH (it discards typed-ahead input) and bash then redraws its prompt: wait for
            // that, as a person would, instead of typing in the same instant.
            waitScreen(screen, "the shell prompt after sudo ended") { it.substringAfterLast("sudo: a password is required", "").contains("$ ") }
            kotlinx.coroutines.delay(500)
            s.send("echo alive-\$((2+3))\n")
            waitScreen(screen, "the shell after cancelling sudo") { it.contains("alive-5") }
            assertFalse(screen.has("hunter2-SECRET"))
        }
    }

    @Test fun aPasswordPromptReceivesEveryCharacterAndNeverEchoesIt() = runBlocking {
        val password = "pä55 wörd ✓ 日本 end"                            // spaces, accents, a symbol, CJK
        val expectedBytes = password.toByteArray(Charsets.UTF_8)
        val expectedSum = java.security.MessageDigest.getInstance("SHA-256").digest(expectedBytes).joinToString("") { "%02x".format(it) }.take(16)
        withTerminal("pw1") { s, screen ->
            // `read -s` switches echo off exactly as sudo's prompt does; this proves the whole path without a real password.
            s.send("bash -c 'IFS= read -rs -p \"\$(printf \"PW\\076 \")\" p; echo; echo \"bytes=\$(printf %s \"\$p\" | wc -c) sum=\$(printf %s \"\$p\" | sha256sum | cut -c1-16)\"'\n")
            waitScreen(screen, "the password prompt") { it.contains("PW> ") }
            s.send(password + "\r")                                      // one write, ending in the CR the Enter key sends
            waitScreen(screen, "the byte count and hash of what the prompt received") { it.contains("bytes=${expectedBytes.size} sum=$expectedSum") }
            assertFalse("the password must not be echoed. Terminal:\n" + screen.text().takeLast(1500), screen.has("wörd"))
            assertFalse(screen.has("日本"))
        }
    }

    @Test fun aLargePasteArrivesBitForBit() = runBlocking {
        // ~200 KB: 2,000 lines of 100 characters, some non-ASCII, like pasting a big script or log into a prompt.
        val text = buildString { for (i in 0 until 2000) append("line ${"%04d".format(i)} ").append("päste-✓-".repeat(8)).append("x".repeat(100 - 14 - 8 * 8)).append('\n') }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        withTerminal("paste1") { s, screen ->
            s.send("F=\$(mktemp); cat > \$F\n")
            kotlinx.coroutines.delay(500)
            assertTrue(s.send(bytes))                                    // the whole paste in one write, as the app does
            s.send(byteArrayOf(0x04))                                    // Ctrl-D at the start of a line: end of input
            kotlinx.coroutines.delay(300)
            s.send("echo HASH=\$(sha256sum < \$F | cut -c1-64) SIZE=\$(wc -c < \$F); rm -f \$F\n")
            waitScreen(screen, "the hash of what arrived", 60_000) { it.contains("HASH=$expected SIZE=${bytes.size}") }
        }
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
