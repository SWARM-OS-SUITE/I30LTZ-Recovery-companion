package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeStream
import com.example.data.nodes.PairOutcome
import com.example.data.phonenode.PhoneNode
import com.example.data.phonenode.PhoneNodeService
import com.example.ui.screens.PhoneNodeCard
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket

/** The real foreground service on a real Android, plus the card that controls it. */
@RunWith(AndroidJUnit4::class)
class PhoneNodeServiceTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instr get() = InstrumentationRegistry.getInstrumentation()

    @After fun off() {
        runCatching { PhoneNode.setEnabled(app, false) }
        PhoneNode.auth(app).devices().forEach { PhoneNode.auth(app).revoke(it.label) }
    }

    private fun waitFor(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
        throw AssertionError("Timed out waiting for $what; state=${PhoneNode.state.value}")
    }

    private fun portOpen() = runCatching { Socket("127.0.0.1", 7437).use { true } }.getOrDefault(false)

    private fun notificationShown() = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .activeNotifications.any { it.id == PhoneNodeService.NOTIFICATION_ID }

    @Test fun serviceStartsPairsServesRealTelemetryRevokesAndStops() = runBlocking {
        assertFalse("nothing may listen before the person switches it on", portOpen())
        PhoneNode.setEnabled(app, true)
        waitFor(15000, "the service to listen") { PhoneNode.state.value.running && portOpen() }
        assertTrue(PhoneNode.state.value.listening.any { it == "127.0.0.1:7437" })
        waitFor(12000, "the foreground notification") { notificationShown() }

        PhoneNode.showPairingCode(app)
        val code = PhoneNode.state.value.code!!
        val node = Node(name = "this phone", os = "Android", backend = NodeBackend.PHONE, host = "127.0.0.1", port = 7437)
        val bad = NodeClient.pair(NodeBackend.PHONE, "127.0.0.1", 7437, "AAAA-AAAA", "x")
        assertTrue("$bad", bad is PairOutcome.Failed)
        val paired = NodeClient.pair(NodeBackend.PHONE, "127.0.0.1", 7437, code, "instrumented test")
        assertTrue("$paired", paired is PairOutcome.Paired)
        val n = node.copy(token = (paired as PairOutcome.Paired).token)
        waitFor(3000, "the paired device to show on screen") { PhoneNode.state.value.paired.any { it.label == "instrumented test" } && PhoneNode.state.value.code == null }

        val probe = NodeClient.probe(n)
        assertTrue(probe.lines.toString(), probe.ok)
        val t = JSONObject(java.net.URL("http://127.0.0.1:7437/v1/telemetry").openConnection().let { c ->
            c.setRequestProperty("Authorization", "Bearer ${n.token}"); c.getInputStream().bufferedReader().readText() })
        assertTrue("real RAM", t.getLong("ram_total_mb") > 256 && t.getLong("ram_used_mb") in 1..t.getLong("ram_total_mb"))
        assertTrue("real storage", t.getDouble("storage_total_gb") > 1.0 && t.getDouble("storage_free_gb") >= 0.0)
        assertEquals("CPU is not readable by an app and must say so", -1, t.getInt("cpu_percent"))
        assertTrue(t.getInt("battery_percent") in -1..100)
        assertTrue(t.getString("hostname").isNotBlank() && t.getString("os").startsWith("Android"))

        val stream = NodeStream.probe(n, 8000)
        assertTrue(stream, stream.startsWith("Live stream works"))
        val err = runCatching { NodeClient.processes(n) }.exceptionOrNull()
        assertTrue("$err", err is UnsupportedOperationException)

        PhoneNode.revoke(app, "instrumented test")
        assertFalse("a revoked device must be refused at once", NodeClient.probe(n).ok)

        PhoneNode.setEnabled(app, false)
        waitFor(10000, "the port to close") { !portOpen() }
        waitFor(5000, "the notification to go") { !notificationShown() }
    }

    @Test fun theCardSwitchesItOnAndShowsAPairingCode() {
        compose.setContent { PhoneNodeCard() }
        compose.onNodeWithTag("phone_node_switch").performClick()
        waitFor(15000, "the card to say it is on") { PhoneNode.state.value.running }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("phone_node_show_code").assertExists(); true }.getOrDefault(false) }
        compose.onNodeWithTag("phone_node_show_code").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("phone_node_code").assertExists(); true }.getOrDefault(false) }
        val shown = PhoneNode.state.value.code!!
        assertTrue(shown, Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(shown))
        Thread.sleep(800)
        val out = File(instr.targetContext.getExternalFilesDir(null), "phone-node-card.png")
        instr.uiAutomation.takeScreenshot()?.let { b -> out.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(out.length() > 0)
    }
}
