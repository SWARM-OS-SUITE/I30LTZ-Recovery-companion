package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeStream
import com.example.data.nodes.PairOutcome
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CarbonSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.ElectricRed
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NvidiaGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** Light text on the dark surfaces; the theme's default field colors were nearly invisible. */
@Composable
internal fun readableFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedBorderColor = NvidiaGreen, unfocusedBorderColor = CarbonBorder,
    focusedLabelColor = NvidiaGreen, unfocusedLabelColor = TextMuted, cursorColor = NvidiaGreen,
)

private val OS_CHOICES = listOf("SWARM OS", "CachyOS", "Debian", "Recovery OS", "Other")

/** Manage the machines this app controls: add, pair, test and remove nodes. */
@Composable
fun NodesScreen(
    nodes: List<Node>,
    linkStates: Map<String, LinkState>,
    isPrimary: (Node) -> Boolean,
    onSave: (Node, Boolean) -> Unit,
    onDelete: (Node) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Node?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = NvidiaGreen) }
                Column {
                    Text("NODES", color = NvidiaGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("${nodes.size} machine${if (nodes.size == 1) "" else "s"} managed by this app", color = TextMuted, fontSize = 11.sp)
                }
            }
            Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen)) {
                Text("ADD NODE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (nodes.isEmpty()) {
            Text(
                "No nodes yet. Add each machine you want to watch or control: a SWARM OS / CachyOS box running LucyServer, " +
                    "a Linux machine running the I30LTZ node agent, or a Recovery OS box running the SSE bridge.",
                color = TextPrimary, fontSize = 13.sp,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PhoneNodeCard() }
            items(nodes, key = { it.id }) { node ->
                NodeCard(node, linkStates[node.id], isPrimary(node)) { editing = node }
            }
        }
    }

    if (adding || editing != null) {
        NodeEditorDialog(
            initial = editing,
            primaryNow = editing?.let(isPrimary) ?: false,
            onDismiss = { adding = false; editing = null },
            onSave = { node, primary -> onSave(node, primary); adding = false; editing = null },
            onDelete = editing?.let { n -> { onDelete(n); editing = null; adding = false } },
        )
    }
}

private fun linkText(node: Node, state: LinkState?): Pair<String, Color> = when {
    !node.isConfigured -> "Not paired: open it and add a token" to ElectricRed
    !node.backend.hasLiveStream -> "Polled every 30 s" to TextMuted
    !node.liveTelemetry -> "Live stream off; polled every 30 s" to TextMuted
    state == null || state == LinkState.Idle -> "Starting…" to TextMuted
    state == LinkState.Connecting -> "Connecting…" to ElectricCyan
    state == LinkState.Live -> "Live (SSE)" to NeonGreen
    state is LinkState.Retrying -> "Retrying in ${state.inSeconds}s: ${state.reason}" to ElectricRed
    else -> "" to TextMuted
}

@Composable
private fun NodeCard(node: Node, state: LinkState?, primary: Boolean, onEdit: () -> Unit) {
    val (status, color) = linkText(node, state)
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = BorderStroke(1.dp, CarbonBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(node.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (primary) Text("PRIMARY", color = ElectricPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("${node.os.ifBlank { "Other" }} · ${node.backend.label}", color = TextMuted, fontSize = 11.sp)
            Text("${node.host}:${node.port}", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(status, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) ElectricPurple else CarbonSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, color = if (selected) Color.White else TextMuted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

private fun pairingHint(backend: NodeBackend): String = when (backend) {
    NodeBackend.AGENT -> "On the node run:  i30ltz-node pair"
    NodeBackend.SSE_BRIDGE -> "On the node run:  curl -X POST http://127.0.0.1:7300/api/pairing-code"
    NodeBackend.PHONE -> "On the other phone: Nodes → This phone as a node → switch on → SHOW PAIRING CODE"
    NodeBackend.LUCYSERVER -> ""
}

@Composable
private fun NodeEditorDialog(
    initial: Node?,
    primaryNow: Boolean,
    onDismiss: () -> Unit,
    onSave: (Node, Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var os by remember { mutableStateOf(initial?.os ?: "Other") }
    var backend by remember { mutableStateOf(initial?.backend ?: NodeBackend.AGENT) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: backend.defaultPort).toString()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(initial?.liveTelemetry ?: true) }
    var primary by remember { mutableStateOf(primaryNow) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    val id = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }

    fun candidate(): Node? {
        val p = port.toIntOrNull()
        Node.validate(name.ifBlank { host }, host, p)?.let { lines = listOf(it); return null }
        return Node(id = id, name = name.ifBlank { host.trim() }, os = os, backend = backend,
            host = host.trim(), port = p!!, token = token.trim(), liveTelemetry = live)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CarbonSurface,
        title = { Text(if (initial == null) "Add node" else "Edit node", color = NvidiaGreen, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = readableFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Operating system", color = TextMuted, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OS_CHOICES.forEach { Choice(it, os == it) { os = it } }
                }
                Text("How it connects", color = TextMuted, fontSize = 11.sp)
                NodeBackend.entries.forEach { b ->
                    Choice(b.label, backend == b) {
                        // Only swap the port if it still holds the previous type's default.
                        if (port == backend.defaultPort.toString()) port = b.defaultPort.toString()
                        backend = b
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Host (Tailscale IP or name)") }, singleLine = true, colors = readableFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = readableFieldColors(), modifier = Modifier.fillMaxWidth())

                if (backend.pairsWithCode) {
                    Text(pairingHint(backend), color = ElectricCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it.uppercase().take(9) }, label = { Text("Pairing code") }, singleLine = true, colors = readableFieldColors(), modifier = Modifier.weight(1f))
                        Button(enabled = !busy && code.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen), onClick = {
                            val p = port.toIntOrNull()
                            Node.validate("x", host, p)?.let { lines = listOf(it); return@Button }
                            busy = true; lines = listOf("Pairing…")
                            scope.launch {
                                when (val r = NodeClient.pair(backend, host.trim(), p!!, code, "I30LTZ Companion (${Build.MODEL})")) {
                                    is PairOutcome.Paired -> {
                                        token = r.token; code = ""
                                        if (name.isBlank()) name = r.hostname.ifBlank { host.trim() }
                                        lines = listOf("Paired with ${r.hostname.ifBlank { host.trim() }}. Now press TEST, then SAVE.")
                                    }
                                    is PairOutcome.Failed -> lines = listOf("Pairing failed: ${r.message}")
                                }
                                busy = false
                            }
                        }) { Text("PAIR", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                OutlinedTextField(token, { token = it }, label = { Text(if (backend.pairsWithCode) "Token (filled by pairing)" else "Access token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = readableFieldColors(), modifier = Modifier.fillMaxWidth())

                if (backend.hasLiveStream) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = live, onCheckedChange = { live = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Live telemetry over SSE", color = TextPrimary, fontSize = 12.sp)
                    }
                }
                if (backend == NodeBackend.LUCYSERVER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = primary, onCheckedChange = { primary = it })
                        Text("Use for terminal, Coder and shared memory", color = TextPrimary, fontSize = 12.sp)
                    }
                }
                lines.forEach { Text(it, color = if (it.startsWith("Live stream failed") || it.contains("rejected") || it.contains("failed", true)) ElectricRed else TextPrimary, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple), onClick = {
                        val node = candidate() ?: return@Button
                        busy = true; lines = listOf("Testing ${node.host}:${node.port}…")
                        scope.launch {
                            val probe = NodeClient.probe(node)
                            lines = probe.lines
                            if (probe.ok && node.backend.hasLiveStream && node.liveTelemetry) {
                                lines = lines + "Opening the live stream…"
                                lines = lines.dropLast(1) + NodeStream.probe(node)
                            }
                            busy = false
                        }
                    }) { Text("TEST", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    if (onDelete != null) {
                        TextButton(enabled = !busy, onClick = onDelete) { Text("DELETE", color = ElectricRed, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen), onClick = {
                candidate()?.let { onSave(it, primary && backend == NodeBackend.LUCYSERVER) }
            }) { Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("CANCEL") } },
    )
}

