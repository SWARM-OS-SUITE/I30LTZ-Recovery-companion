package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeSecurity
import com.example.data.nodes.QuarantinedFile
import com.example.data.nodes.ScanFinding
import com.example.data.nodes.ScanView
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricRed
import com.example.ui.theme.LightningYellow
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NvidiaGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

/** What the Security screen can do; each call acts on one node. */
class SecurityActions(
    val refresh: (Node) -> Unit,
    val scan: (Node, scope: String, path: String) -> Unit,
    val cancel: (Node) -> Unit,
    val quarantine: (Node, ScanFinding) -> Unit,
    val restore: (Node, QuarantinedFile) -> Unit,
    val delete: (Node, QuarantinedFile) -> Unit,
    val scanAll: () -> Unit,
)

/**
 * Real malware scanning per node (ClamAV on each machine, through the node agent).
 * Wording is deliberately careful: a scan that matched nothing says what it covered, never "secure".
 */
@Composable
fun NodeSecuritySection(nodes: List<Node>, security: Map<String, NodeSecurity>, actions: SecurityActions) {
    val scannable = nodes.filter { it.backend == NodeBackend.AGENT && it.isConfigured }
    LaunchedEffect(scannable.map { it.id }) { scannable.forEach(actions.refresh) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Malware scan of your nodes", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Text(
            "Each node scans itself with ClamAV through its I30LTZ node agent. Scanning only reports: nothing is deleted or moved " +
                "unless you tap Quarantine. A scan is only as good as its signatures, and only covers files the agent may read.",
            fontSize = 13.sp,
        )
        if (nodes.isEmpty()) Text("No nodes yet. Add one under Nodes.", color = TextMuted)
        if (scannable.size > 1) {
            Button(onClick = actions.scanAll, modifier = Modifier.fillMaxWidth().testTag("scan_all")) { Text("Quick-scan all ${scannable.size} nodes") }
        }
        nodes.forEach { node ->
            when {
                !node.isConfigured -> InfoCard(node, "Not paired yet. Pair it under Nodes first.")
                node.backend == NodeBackend.PHONE -> InfoCard(
                    node, "This is a phone. Android does not let one app scan another, so there is no malware scan for it. " +
                        "Its telemetry (RAM, storage, battery) is on the Monitor tab.")
                node.backend != NodeBackend.AGENT -> InfoCard(
                    node, "This node runs ${node.backend.label}, which has no malware scanner. Install the I30LTZ node agent on it " +
                        "(with ClamAV) and add it as an agent node to scan it.")
                else -> NodeScanCard(node, security[node.id] ?: NodeSecurity(), actions)
            }
        }
    }
}

@Composable
private fun InfoCard(node: Node, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), border = BorderStroke(1.dp, CarbonBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(node.name, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(text, color = TextMuted, fontSize = 12.sp)
        }
    }
}

private fun ageText(days: Int) = when (days) { -1 -> "age unknown"; 0 -> "updated today"; 1 -> "1 day old"; else -> "$days days old" }

@Composable
private fun NodeScanCard(node: Node, sec: NodeSecurity, actions: SecurityActions) {
    val info = sec.info
    val scan = sec.scan
    val running = scan?.running == true
    var path by remember { mutableStateOf("") }
    var confirmQuarantine by remember { mutableStateOf<ScanFinding?>(null) }
    var confirmDelete by remember { mutableStateOf<QuarantinedFile?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), border = BorderStroke(1.dp, CarbonBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(node.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    Text("${node.os.ifBlank { "Other" }} · ${node.host}", color = TextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { actions.refresh(node) }, enabled = !sec.busy) { Text("REFRESH", fontSize = 11.sp) }
            }

            when {
                info == null -> Text(if (sec.busy) "Contacting ${node.name}…" else "Status not read yet.", color = TextMuted, fontSize = 12.sp)
                !info.available -> Text(
                    info.reason.ifBlank { "ClamAV is not available on this node." } + " Install ClamAV there (apt install clamav), then press REFRESH.",
                    color = ElectricRed, fontSize = 12.sp, modifier = Modifier.testTag("scan_unavailable"),
                )
                else -> {
                    Text("ClamAV ${info.version} · signatures ${info.signatureDb.ifBlank { "?" }} · ${ageText(info.ageDays)}",
                        color = if (info.stale) LightningYellow else TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (info.stale) Text("The signatures are old, so this scan would miss recent malware. Update them on the node (freshclam).",
                        color = LightningYellow, fontSize = 12.sp)
                }
            }

            if (info?.available == true) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanButton("QUICK", "scan_quick", !running && !sec.busy) { actions.scan(node, "quick", "") }
                    ScanButton("HOME", "scan_home", !running && !sec.busy) { actions.scan(node, "home", "") }
                    ScanButton("FULL", "scan_full", !running && !sec.busy) { actions.scan(node, "full", "") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(path, { path = it }, singleLine = true, label = { Text("Folder or drive path on the node") },
                        colors = readableFieldColors(), modifier = Modifier.weight(1f).testTag("scan_path_field"))
                    ScanButton("SCAN", "scan_path_button", !running && !sec.busy && path.startsWith("/")) { actions.scan(node, "path", path.trim()) }
                }
                Text("QUICK: Downloads, temp folders, user programs. HOME: your whole home folder. FULL: the whole system disk " +
                    "(not other drives; use the path box for those). Run the agent as root to include system files.", color = TextMuted, fontSize = 11.sp)
            }

            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Scanning… ${scan?.scanned ?: 0} files checked", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.testTag("scan_progress"))
                scan?.current?.takeIf { it.isNotBlank() }?.let { Text(it, color = TextMuted, fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace) }
                OutlinedButton(onClick = { actions.cancel(node) }, modifier = Modifier.testTag("scan_cancel")) { Text("CANCEL SCAN") }
            } else if (scan != null) {
                ScanResult(scan, info?.ageDays ?: -1, sec.quarantine, onQuarantine = { confirmQuarantine = it })
            }

            if (sec.message.isNotBlank()) Text(sec.message, color = if (sec.message.startsWith("Quarantined") || sec.message.startsWith("Restored") || sec.message.startsWith("Permanently")) NeonGreen else ElectricRed,
                fontSize = 12.sp, modifier = Modifier.testTag("scan_message"))

            if (sec.quarantine.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Quarantine (${sec.quarantine.size})", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                sec.quarantine.forEach { q ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(q.original, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                        Text("${q.signature} · ${q.size} bytes · sha256 ${q.sha256.take(12)}…", fontSize = 10.sp, color = TextMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { actions.restore(node, q) }, enabled = !sec.busy, modifier = Modifier.testTag("quarantine_restore")) { Text("RESTORE", fontSize = 11.sp) }
                            OutlinedButton(onClick = { confirmDelete = q }, enabled = !sec.busy, modifier = Modifier.testTag("quarantine_delete")) { Text("DELETE FOR GOOD", fontSize = 11.sp, color = ElectricRed) }
                        }
                    }
                }
            }
        }
    }

    confirmQuarantine?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmQuarantine = null }, containerColor = CarbonSurface,
            title = { Text("Quarantine this file?") },
            text = { Text("${f.path}\n\nDetected as ${f.signature}.\n\nThe node checks it again, then moves it to a private quarantine folder. You can restore it later. " +
                "If this is a file you know is safe, cancel: signatures can be wrong.") },
            confirmButton = { Button(onClick = { actions.quarantine(node, f); confirmQuarantine = null }, modifier = Modifier.testTag("quarantine_confirm")) { Text("QUARANTINE") } },
            dismissButton = { TextButton(onClick = { confirmQuarantine = null }) { Text("CANCEL") } },
        )
    }
    confirmDelete?.let { q ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = CarbonSurface,
            title = { Text("Delete for good?") },
            text = { Text("${q.original}\n\nThe quarantined copy is erased from the node and cannot be restored.") },
            confirmButton = { Button(onClick = { actions.delete(node, q); confirmDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = ElectricRed)) { Text("DELETE") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun ScanButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen), modifier = Modifier.testTag(tag)) {
        Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/** Outcome of a finished scan, worded so that "nothing found" is never presented as "safe". */
@Composable
private fun ScanResult(scan: ScanView, signatureAgeDays: Int, quarantine: List<QuarantinedFile>, onQuarantine: (ScanFinding) -> Unit) {
    val quarantinedPaths = quarantine.map { it.original }.toSet()
    val open = scan.findings.filter { it.path !in quarantinedPaths }
    val (headline, color) = when {
        scan.state == ScanView.FAILED -> "The scan did not run: ${scan.message.ifBlank { "unknown reason" }}" to ElectricRed
        scan.state == ScanView.CANCELLED -> "Stopped after ${scan.scanned} files. This was not a complete scan." to LightningYellow
        scan.findings.isNotEmpty() -> "${scan.findings.size}${if (scan.findingsTruncated) "+" else ""} file(s) matched a malware signature" to ElectricRed
        scan.errors > 0 -> "No signature matched in ${scan.scanned} files, but ${scan.errors} file(s) could not be checked" to LightningYellow
        else -> "No signature matched in ${scan.scanned} files." to NeonGreen
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(headline, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.testTag("scan_headline"))
        if (scan.state == ScanView.DONE && scan.findings.isEmpty()) {
            Text("That is not a guarantee: it covers what the scanner could read, with signatures ${ageText(signatureAgeDays)}.", color = TextMuted, fontSize = 11.sp)
        }
        if (scan.errors > 0) {
            Text("Not checked (${scan.errors}): " + scan.errorSamples.take(3).joinToString("; ") + if (scan.errors > 3) "…" else "", color = LightningYellow, fontSize = 11.sp)
        }
        scan.findings.forEach { f ->
            val handled = f.path in quarantinedPaths
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(f.signature, color = if (handled) NeonGreen else ElectricRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(f.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                if (handled) Text("Quarantined", color = NeonGreen, fontSize = 11.sp)
                else OutlinedButton(onClick = { onQuarantine(f) }, modifier = Modifier.testTag("finding_quarantine")) { Text("QUARANTINE…", fontSize = 11.sp) }
            }
        }
        if (open.isEmpty() && scan.findings.isNotEmpty()) Text("All findings are in quarantine.", color = NeonGreen, fontSize = 11.sp)
    }
}
