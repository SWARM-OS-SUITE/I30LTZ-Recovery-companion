package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.ui.theme.TextPrimary
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.nodes.Node
import com.example.data.nodes.NodeSecurity

@Composable
fun AntivirusScreen(
    threatScore: Int, isScanning: Boolean, onRunScan: () -> Unit, report: String = "No audit run yet.",
    nodes: List<Node> = emptyList(), security: Map<String, NodeSecurity> = emptyMap(), actions: SecurityActions? = null,
) {
    val context = LocalContext.current
    // The theme's default text color is dark; this screen sits on a dark surface.
    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Security", style = MaterialTheme.typography.headlineSmall)
        if (actions != null) NodeSecuritySection(nodes, security, actions)
        Text("This phone: settings review", style = MaterialTheme.typography.titleLarge)
        Text("Reads Android's security patch, screen lock and USB debugging setting. This is a settings review, not a malware scan: Android does not let an app scan other apps, so it cannot say the phone is clean.")
        Button(onClick=onRunScan, enabled=!isScanning, modifier=Modifier.fillMaxWidth().testTag("run_antivirus_scan_button")) { Text(if(isScanning) "Reading device configuration…" else "Run device audit") }
        SelectionContainer { Text(report) }
        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}) { Text("Open Android security settings") }
        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))}) { Text("Open privacy settings") }
        Text("Authorize ADB only for computers you trust. Remote PC currently uses HTTP/WebSocket with an access token; protect it with your private Tailscale network. The app does not verify tunnel encryption and its Room database is not application-encrypted.")
    }
    }
}
