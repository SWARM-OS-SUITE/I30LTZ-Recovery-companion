package com.example

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeTerminalSession
import com.example.ui.screens.NodeTerminalScreen
import com.example.ui.screens.XtermController
import com.example.ui.screens.XtermView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Runs the real terminal (xterm.js in a WebView) against a real `i30ltz-node --terminal` agent.
 * Needs instrumentation arguments agentHost, agentPort, agentToken; skipped without them.
 * From an emulator the host is 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
class TerminalWebViewTest {
    @get:Rule val compose = createComposeRule()
    private val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun node(): Node {
        val a = InstrumentationRegistry.getArguments()
        val host = a.getString("agentHost"); val port = a.getString("agentPort")?.toIntOrNull(); val token = a.getString("agentToken")
        assumeTrue("agentHost/agentPort/agentToken not given", host != null && port != null && token != null)
        return Node(name = "emulator-host", os = "Debian", backend = NodeBackend.AGENT, host = host!!, port = port!!, token = token!!)
    }

    private suspend fun controllerDump(c: XtermController): String = suspendCoroutine { k -> c.dump { k.resume(it) } }

    /** Saves what the terminal shows right now to a file (pull with adb) and the log. */
    private suspend fun snap(c: XtermController, stage: String) {
        val text = withTimeout(5000) { controllerDump(c) }
        File(instr.targetContext.getExternalFilesDir(null), "screen-$stage.txt").writeText(text)
        android.util.Log.i("TERMTEST", "[$stage] ${text.lines().filter { it.isNotBlank() }.joinToString(" | ")}")
    }

    private suspend fun waitScreen(c: XtermController, what: String, ms: Long = 10000, check: (String) -> Boolean) {
        var last = ""
        try {
            withTimeout(ms) { while (true) { last = controllerDump(c); if (check(last)) return@withTimeout; delay(100) } }
        } catch (e: Exception) { snap(c, "FAILED-" + what.replace(Regex("[^A-Za-z0-9]+"), "_")); throw AssertionError("Timed out waiting for $what. Screen was:\n$last") }
    }

    private fun findWebView(v: View): WebView? =
        if (v is WebView) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) } else null

    private fun webView(): WebView {
        var found: WebView? = null
        instr.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                ?.let { findWebView(it.window.decorView) }
        }
        return found ?: error("no WebView on screen")
    }

    @Test fun terminalRendersTypesPastesStopsCommandsMirrorsAndCopies() = runBlocking {
        val n = node()
        val c = XtermController()
        val ready = AtomicBoolean(false)
        val size = AtomicReference(80 to 24)
        val resizes = mutableListOf<Pair<Int, Int>>()
        val copied = AtomicReference("")
        c.onReady = { cols, rows -> size.set(cols to rows); ready.set(true) }
        c.onCopy = { copied.set(it) }
        compose.setContent { XtermView(c, Modifier.fillMaxSize()) }
        compose.waitUntil(20000) { ready.get() } // the bundled xterm.js loaded offline and the bridge works
        // The first size reported must be a real one: a tiny early size once gave a 10-column tmux window.
        assertTrue("initial terminal size was ${size.get()}", size.get().first >= 25 && size.get().second >= 8)

        val s = NodeTerminalSession(n)
        c.onInput = { s.send(it) }
        c.onResize = { cols, rows -> synchronized(resizes) { resizes += cols to rows }; s.resize(cols, rows) }
        val collector = launch(Dispatchers.IO) { s.output.collect { c.write(it) } }
        s.connect("emu", size.get().first, size.get().second)
        waitScreen(c, "a shell prompt") { it.contains("$") || it.contains("#") }

        snap(c, "1-connected")

        // Pasting puts text on the prompt line but must NOT run it (bash uses bracketed paste), so a pasted
        // command can be read before Enter is pressed.
        c.paste("echo pasted-\$((1+1))\n")
        waitScreen(c, "pasted text on the prompt line") { it.contains("echo pasted-") }
        assertTrue("a pasted newline must not execute", controllerDump(c).lines().none { it.trim() == "pasted-2" })
        snap(c, "2-pasted")
        c.type("\r")
        waitScreen(c, "pasted command after Enter") { it.lines().any { l -> l.trim() == "pasted-2" } }

        // Typing, including a non-ASCII word.
        c.type("echo \"web-\$((6*7))-päste\"\r")
        waitScreen(c, "echoed command output") { it.contains("web-42-päste") }
        snap(c, "3-typed")

        // Sticky Ctrl: Ctrl-C must interrupt a running command.
        c.type("sleep 300\r")
        waitScreen(c, "sleep started") { it.contains("sleep 300") }
        c.setCtrl(true)
        c.type("c")
        waitScreen(c, "^C after Ctrl-C") { it.contains("^C") }
        c.type("echo after-ctrl-c\r")
        waitScreen(c, "prompt back after Ctrl-C") { it.lines().any { l -> l.trim() == "after-ctrl-c" } }

        // A second client on the same tmux session (this stands in for the PC's own terminal).
        val pc = NodeTerminalSession(n)
        pc.connect("emu", 120, 40)
        delay(800)
        pc.send("echo from-the-pc\n")
        waitScreen(c, "text typed by the other client") { it.lines().any { l -> l.trim() == "from-the-pc" } }

        // Changing the font size refits the terminal and reports a new size to the node.
        val before = synchronized(resizes) { resizes.size }
        c.setFontSize(20)
        withTimeout(8000) { while (synchronized(resizes) { resizes.size } == before) delay(50) }

        // Touch-select a line and copy it.
        c.setSelectMode(true)
        val dump = controllerDump(c)
        val row = dump.lines().indexOfFirst { it.trim() == "from-the-pc" }
        assertTrue("row of output found in:\n$dump", row >= 0)
        val wv = webView()
        val loc = IntArray(2)
        val rows = dump.lines().size
        instr.runOnMainSync { wv.getLocationOnScreen(loc) }
        val y = loc[1] + (row + 0.5f) * wv.height / rows
        val x = loc[0] + wv.width / 3f
        val t0 = SystemClock.uptimeMillis()
        instr.sendPointerSync(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0))
        instr.sendPointerSync(MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, x, y, 0))
        delay(300)
        c.requestCopy()
        withTimeout(5000) { while (copied.get().isEmpty()) delay(50) }
        assertTrue("copied: ${copied.get()}", copied.get().contains("from-the-pc"))

        pc.close(); s.close(); collector.cancel()
    }

    @Test fun terminalScreenConnectsFromTheNodeListAndShowsTheSession() = runBlocking {
        val n = node()
        compose.setContent { NodeTerminalScreen(nodes = listOf(n), onBack = {}, onAddNode = {}, onLegacyShell = {}) }
        // The screen checks the node and enables CONNECT only when the agent really offers a terminal.
        compose.waitUntil(20000) {
            runCatching { compose.onNodeWithText("CONNECT").assertExists(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("CONNECT").performClick()
        compose.waitUntil(15000) {
            runCatching { compose.onNodeWithText("emulator-host · tmux 'main'").assertExists(); true }.getOrDefault(false)
        }
        kotlinx.coroutines.delay(2500) // let the shell prompt arrive and render before the screenshot
        // Save what the screen looks like, for a human to look at.
        val out = File(instr.targetContext.getExternalFilesDir(null), "terminal-screen.png")
        instr.uiAutomation.takeScreenshot()?.let { bmp -> out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue("screenshot saved", out.length() > 0)
    }
}
