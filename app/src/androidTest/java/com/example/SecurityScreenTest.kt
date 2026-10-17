package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeSecurityController
import com.example.ui.screens.AntivirusScreen
import com.example.ui.screens.SecurityActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real Security screen against a real `i30ltz-node` with real ClamAV. Needs instrumentation arguments
 * agentHost, agentPort, agentToken and scanPath (a folder on the node's machine holding bad.bin that its
 * signature database matches, and ok.txt). From an emulator the host is 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
class SecurityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instr get() = InstrumentationRegistry.getInstrumentation()

    private fun shot(name: String) {
        val out = File(instr.targetContext.getExternalFilesDir(null), "$name.png")
        instr.uiAutomation.takeScreenshot()?.let { bmp -> out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun waitFor(ms: Long, what: String, check: () -> Boolean) {
        try { compose.waitUntil(ms) { check() } } catch (e: Exception) { shot("FAILED-" + what.replace(Regex("[^A-Za-z0-9]+"), "_")); throw AssertionError("Timed out waiting for $what") }
    }

    private fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag).assertExists(); true }.getOrDefault(false)
    private fun hasText(t: String) = runCatching { compose.onNodeWithText(t, substring = true).assertExists(); true }.getOrDefault(false)

    @Test fun scanFindQuarantineAndRestoreThroughTheScreen() {
        val a = InstrumentationRegistry.getArguments()
        val host = a.getString("agentHost"); val port = a.getString("agentPort")?.toIntOrNull(); val token = a.getString("agentToken"); val path = a.getString("scanPath")
        assumeTrue("agent arguments not given", host != null && port != null && token != null && path != null)
        val node = Node(name = "emulator-host", os = "Debian", backend = NodeBackend.AGENT, host = host!!, port = port!!, token = token!!)

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val ctl = NodeSecurityController(scope, pollEveryMs = 300)
        compose.setContent {
            val sec by ctl.state.collectAsState()
            AntivirusScreen(
                threatScore = -1, isScanning = false, onRunScan = {},
                nodes = listOf(node), security = sec,
                actions = SecurityActions(
                    refresh = { ctl.refresh(it) }, scan = { n, s, p -> ctl.start(n, s, p) }, cancel = { ctl.cancel(it) },
                    quarantine = { n, f -> ctl.quarantine(n, f) }, restore = { n, q -> ctl.restore(n, q) },
                    delete = { n, q -> ctl.delete(n, q) }, scanAll = {},
                ),
            )
        }
        try {
            // 1. The node reports its engine and signature freshness (loaded automatically).
            waitFor(20000, "engine line") { hasText("ClamAV 1.") }

            // 2. Scan the folder.
            compose.onNodeWithTag("scan_path_field").performScrollTo().performTextInput(path!!)
            compose.onNodeWithTag("scan_path_button").performScrollTo().performClick()
            waitFor(30000, "scan finding") { hasText("matched a malware signature") }
            compose.onNodeWithTag("finding_quarantine").performScrollTo().assertIsDisplayed()
            shot("security-1-finding")

            // 3. Quarantine only after the confirmation dialog.
            compose.onNodeWithTag("finding_quarantine").performScrollTo().performClick()
            waitFor(5000, "confirm dialog") { shown("quarantine_confirm") }
            compose.onNodeWithTag("quarantine_confirm").performClick()
            waitFor(20000, "quarantine done") { shown("quarantine_restore") }
            shot("security-2-quarantined")

            // 4. Restore it.
            compose.onNodeWithTag("quarantine_restore").performScrollTo().performClick()
            waitFor(20000, "restored message") { hasText("Restored") }
            shot("security-3-restored")
            assertTrue(hasText("Restored"))
        } finally { scope.cancel() }
    }
}
