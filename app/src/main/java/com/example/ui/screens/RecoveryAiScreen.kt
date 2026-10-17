package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.bridge.PendingCommand
import com.example.data.nodes.KnownMachines
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.ui.BridgeViewModel
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CarbonSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.ElectricRed
import com.example.ui.theme.NvidiaGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

/**
 * Dr. I30LTZ chat and command approvals on a Recovery OS bridge node (CP-15, ported from the old `companion-app`).
 * Approving a command RUNS it on that node, so the full text is always shown and dangerous ones ask once more.
 */
@Composable
fun RecoveryAiScreen(nodes: List<Node>, vm: BridgeViewModel, onManageNodes: () -> Unit) {
    val bridgeNodes = remember(nodes) { nodes.filter { it.backend == NodeBackend.SSE_BRIDGE } }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val node = bridgeNodes.firstOrNull { it.id == selectedId } ?: bridgeNodes.firstOrNull()
    LaunchedEffect(node) { vm.select(node) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val commands by vm.commands.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RECOVERY AI", color = NvidiaGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Chat with Dr. I30LTZ and approve commands on a Recovery OS node", color = TextMuted, fontSize = 11.sp)

        if (node == null) {
            Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), border = BorderStroke(1.dp, CarbonBorder)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No Recovery OS node yet", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Add the machine running the Recovery OS bridge under Nodes (type: Recovery OS SSE bridge), pair it with a code, then come back.",
                        color = TextMuted, fontSize = 12.sp)
                    Button(onClick = onManageNodes, colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)) { Text("OPEN NODES") }
                }
            }
            return@Column
        }

        if (bridgeNodes.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bridgeNodes.forEach { n -> Pill(KnownMachines.displayName(n), n.id == node.id) { selectedId = n.id } }
            }
        }
        Text("${KnownMachines.displayName(node)} · ${node.host}:${node.port}", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        if (!node.isConfigured) {
            Text("Not paired: open Nodes and pair this node first.", color = ElectricRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onManageNodes, colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)) { Text("OPEN NODES") }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("CHAT", tab == 0) { tab = 0 }
            Pill("APPROVALS (${commands.size})", tab == 1) { tab = 1 }
        }
        if (tab == 1) {
            val (text, color) = linkLine(link)
            Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        if (tab == 0) ChatPane(node, vm) else ApprovalsPane(node, vm, commands)
    }
}

private fun linkLine(state: LinkState): Pair<String, Color> = when (state) {
    LinkState.Idle -> "Not connected" to TextMuted
    LinkState.Connecting -> "Connecting…" to TextMuted
    LinkState.Live -> "Live: new commands appear here at once" to NvidiaGreen
    is LinkState.Retrying -> "Reconnecting in ${state.inSeconds} s: ${state.reason}" to ElectricRed
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) ElectricPurple else CarbonSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, color = if (selected) Color.White else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

// --- chat ---------------------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ChatPane(node: Node, vm: BridgeViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val busy by vm.chatBusy.collectAsStateWithLifecycle()
    val lines = chats[node.id].orEmpty()
    var input by rememberSaveable { mutableStateOf("") }
    var telemetry by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lines.isEmpty()) item { Text("Ask Dr. I30LTZ about this machine.", color = TextMuted, fontSize = 12.sp) }
        items(lines) { line ->
            val mine = line.fromUser
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                Surface(
                    color = when { mine -> ElectricPurple; line.failed -> Color(0x33FF1744); else -> CarbonSurface },
                    shape = RoundedCornerShape(10.dp),
                    border = if (mine) null else BorderStroke(1.dp, if (line.failed) ElectricRed else CarbonBorder),
                ) {
                    Text(
                        line.text.ifEmpty { if (line.streaming) "…" else "" },
                        color = if (mine) Color.White else TextPrimary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = telemetry, onCheckedChange = { telemetry = it })
        Text("  Send this PC's live stats with my message", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.clearChat(node.id) }, enabled = lines.isNotEmpty() && !busy) { Text("CLEAR", fontSize = 11.sp) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(input, { input = it }, placeholder = { Text("Message Dr. I30LTZ") }, colors = readableFieldColors(),
            modifier = Modifier.weight(1f), maxLines = 4)
        if (busy) {
            Button(onClick = { vm.stopChat() }, colors = ButtonDefaults.buttonColors(containerColor = ElectricRed)) { Text("STOP") }
        } else {
            Button(
                onClick = { vm.sendChat(input, telemetry); input = "" },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen),
            ) { Text("SEND", color = Color.Black) }
        }
    }
}

// --- approvals ----------------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ApprovalsPane(node: Node, vm: BridgeViewModel, commands: List<PendingCommand>) {
    val deciding by vm.deciding.collectAsStateWithLifecycle()
    val outcomes by vm.outcomes.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var dangerous by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PendingCommand?>(null) }
    val where = KnownMachines.displayName(node)

    confirm?.let { cmd ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = CarbonSurface,
            title = { Text("Run a dangerous command?", color = ElectricRed, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This marked-dangerous command will run on $where as soon as you confirm:", color = TextPrimary, fontSize = 13.sp)
                    Text(cmd.command, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.decide(cmd, true); confirm = null }) { Text("RUN IT", color = ElectricRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL") } },
        )
    }

    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${commands.size} waiting for approval", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showQueue = !showQueue }) { Text(if (showQueue) "HIDE" else "QUEUE A COMMAND", fontSize = 11.sp) }
            }
        }
        if (showQueue) item {
            Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), border = BorderStroke(1.dp, CarbonBorder)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Queuing does not run anything. The command waits here until you approve it.", color = TextMuted, fontSize = 11.sp)
                    OutlinedTextField(command, { command = it }, label = { Text("Command") }, singleLine = true, colors = readableFieldColors(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("Why (optional)") }, singleLine = true, colors = readableFieldColors(),
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(dangerous, { dangerous = it })
                        Text("Mark as dangerous (asks again before running)", color = TextMuted, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { vm.submit(command, description, dangerous); command = ""; description = ""; dangerous = false },
                        enabled = command.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("QUEUE") }
                }
            }
        }
        notice?.let { n ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = ElectricCyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearNotice() }) { Text("OK", fontSize = 11.sp) }
                }
            }
        }
        if (commands.isEmpty()) item { Text("Nothing is waiting. Dr. I30LTZ's proposals and anything you queue show up here.", color = TextMuted, fontSize = 12.sp) }
        items(commands, key = { it.id }) { cmd ->
            CommandCard(cmd, where, busy = cmd.id in deciding,
                onDeny = { vm.decide(cmd, false) },
                onApprove = { if (cmd.dangerous) confirm = cmd else vm.decide(cmd, true) })
        }
        if (outcomes.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RESULTS", color = NvidiaGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearOutcomes() }) { Text("CLEAR", fontSize = 11.sp) }
                }
            }
            items(outcomes) { o ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                    border = BorderStroke(1.dp, if (o.ok) CarbonBorder else ElectricRed),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(o.command, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("${o.nodeName}: ${o.summary}", color = if (o.ok) NvidiaGreen else ElectricRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (o.output.isNotBlank()) {
                            Text(o.output.take(1500), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                modifier = Modifier.background(Color(0xFF0A0C10)).padding(6.dp).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCard(cmd: PendingCommand, where: String, busy: Boolean, onDeny: () -> Unit, onApprove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = BorderStroke(if (cmd.dangerous) 2.dp else 1.dp, if (cmd.dangerous) ElectricRed else CarbonBorder),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("from ${cmd.source}", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (cmd.dangerous) Text("DANGEROUS", color = ElectricRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(cmd.command, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.background(Color(0xFF0A0C10)).padding(8.dp).fillMaxWidth())
            if (cmd.description.isNotBlank()) Text(cmd.description, color = TextMuted, fontSize = 12.sp)
            Text("Approving runs it on $where.", color = TextMuted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDeny, enabled = !busy) { Text("DENY", color = ElectricRed, fontWeight = FontWeight.Bold) }
                Button(onClick = onApprove, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen)) {
                    Text(if (busy) "WORKING…" else "APPROVE", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
