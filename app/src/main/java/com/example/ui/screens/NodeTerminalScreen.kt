package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeTerminalSession
import com.example.data.nodes.TerminalSessionInfo
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val ESC = "\u001b"

/** Keys a phone keyboard lacks. Each entry: label, bytes sent. */
private val EXTRA_KEYS = listOf(
    "ESC" to ESC, "TAB" to "\t", "↑" to "$ESC[A", "↓" to "$ESC[B", "←" to "$ESC[D", "→" to "$ESC[C",
    "PgUp" to "$ESC[5~", "PgDn" to "$ESC[6~", "SCROLL" to "\u0002[", // Ctrl-b [ : tmux copy mode, then PgUp/PgDn
    "^C" to "\u0003", "^D" to "\u0004", "|" to "|", "~" to "~", "/" to "/", "-" to "-",
)

/**
 * Terminal that shows the same tmux session as the node's own screen, and lets you type into it.
 * What you type here appears there and the other way round, because both are tmux clients.
 */
@Composable
fun NodeTerminalScreen(
    nodes: List<Node>,
    onBack: () -> Unit,
    onAddNode: () -> Unit,
    onLegacyShell: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agents = nodes.filter { it.backend == NodeBackend.AGENT && it.isConfigured }
    val controller = remember { XtermController() }

    var selectedId by remember { mutableStateOf<String?>(null) }
    val node = agents.firstOrNull { it.id == selectedId } ?: agents.firstOrNull()

    var note by remember { mutableStateOf("") }              // why a node can't be used, or empty
    var sessions by remember { mutableStateOf(emptyList<TerminalSessionInfo>()) }
    var sessionName by remember { mutableStateOf("main") }
    var ready by remember { mutableStateOf(false) }
    var cols by remember { mutableIntStateOf(80) }
    var rows by remember { mutableIntStateOf(24) }
    var session by remember { mutableStateOf<NodeTerminalSession?>(null) }
    var collectors by remember { mutableStateOf(emptyList<Job>()) }
    var status by remember { mutableStateOf("Not connected") }
    var failed by remember { mutableStateOf(false) }
    var ctrlOn by remember { mutableStateOf(false) }
    var selectOn by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(13) }

    fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun disconnect() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        session?.close()
        session = null
    }

    fun connect() {
        val n = node ?: return
        if (!NodeTerminalSession.SESSION_NAME.matches(sessionName)) {
            status = "Session names are 1-32 letters, digits, _ or -"; failed = true; return
        }
        disconnect()
        val s = NodeTerminalSession(n)
        session = s
        controller.reset()
        s.connect(sessionName, cols, rows)
        val outputJob = scope.launch { s.output.collect { controller.write(it) } }
        val stateJob = scope.launch {
            s.state.collect { st ->
                when (st) {
                    is NodeTerminalSession.State.Idle -> {}
                    is NodeTerminalSession.State.Connecting -> { status = "Connecting to ${n.name}…"; failed = false }
                    is NodeTerminalSession.State.Connected -> { status = "${n.name} · tmux '${st.session}'"; failed = false }
                    is NodeTerminalSession.State.Closed -> { status = st.reason; failed = st.failed; if (session === s) session = null }
                }
            }
        }
        collectors = listOf(outputJob, stateJob)
    }

    // Installed from the start, so a size reported before CONNECT is not lost and typing always reaches the live session.
    SideEffect {
        controller.onReady = { c, r -> cols = c; rows = r; ready = true }
        controller.onResize = { c, r -> cols = c; rows = r; session?.resize(c, r) }
        controller.onInput = { session?.send(it) }
        controller.onCtrlConsumed = { ctrlOn = false }
    }
    controller.onCopy = { text ->
        if (text.isBlank()) Toast.makeText(context, "Nothing selected. Turn SELECT on and touch the lines to copy.", Toast.LENGTH_SHORT).show()
        else {
            clipboard().setPrimaryClip(ClipData.newPlainText("terminal", text.trimEnd()))
            Toast.makeText(context, "Copied ${text.trimEnd().lines().size} line(s)", Toast.LENGTH_SHORT).show()
        }
    }

    // Check the chosen node and list its tmux sessions whenever the choice changes.
    LaunchedEffect(node?.id) {
        sessions = emptyList(); note = ""
        val n = node ?: return@LaunchedEffect
        val features = NodeClient.agentFeatures(n)
        when {
            features == null -> note = "Could not reach ${n.name}. Open Nodes and press TEST."
            features["terminal"] != true -> note = "The terminal is off on ${n.name}. On that machine run:  i30ltz-node serve --terminal  (needs tmux)"
            else -> runCatching { NodeClient.terminalSessions(n) }.onSuccess { sessions = it }.onFailure { note = it.message.orEmpty() }
        }
    }

    DisposableEffect(Unit) { onDispose { collectors.forEach { it.cancel() }; session?.close() } }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = NvidiaGreen) }
                Column {
                    Text("TERMINAL", color = NvidiaGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(status, color = if (failed) ElectricRed else if (session != null) NeonGreen else TextMuted, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onLegacyShell) { Text("LucyServer shell", fontSize = 10.sp) }
        }

        if (agents.isEmpty()) {
            Text(
                "No agent node is paired yet. Add the I30LTZ node agent of a machine under Nodes; then its tmux session can be mirrored here.",
                color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
            Button(onClick = onAddNode, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen), modifier = Modifier.padding(8.dp)) {
                Text("ADD A NODE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                agents.forEach { n -> Pill(n.name, node?.id == n.id) { if (n.id != node?.id) { disconnect(); selectedId = n.id } } }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(sessionName, { sessionName = it.take(32) }, singleLine = true, label = { Text("tmux session") },
                    colors = readableFieldColors(), modifier = Modifier.weight(1f))
                if (session == null) {
                    Button(enabled = ready && note.isEmpty(), onClick = ::connect, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen)) {
                        Text(if (failed) "RECONNECT" else "CONNECT", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Button(onClick = ::disconnect, colors = ButtonDefaults.buttonColors(containerColor = ElectricRed)) {
                        Text("LEAVE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            if (session == null && sessions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sessions.forEach { s -> Pill("${s.name} · ${s.attached} on", sessionName == s.name) { sessionName = s.name } }
                }
            }
            if (note.isNotEmpty()) Text(note, color = ElectricRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        // The terminal stays composed so it is ready before the first connect.
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
            XtermView(controller, Modifier.fillMaxSize())
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("CTRL", ctrlOn) { ctrlOn = !ctrlOn; controller.setCtrl(ctrlOn) }
            EXTRA_KEYS.forEach { (label, bytes) -> Pill(label, false) { session?.send(bytes) } }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill("PASTE", false) {
                val text = clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isEmpty()) Toast.makeText(context, "The clipboard is empty", Toast.LENGTH_SHORT).show() else controller.paste(text)
            }
            Pill("COPY", false) { controller.requestCopy() }
            Pill("SELECT", selectOn) { selectOn = !selectOn; controller.setSelectMode(selectOn) }
            Pill("A−", false) { fontSize = (fontSize - 1).coerceAtLeast(8); controller.setFontSize(fontSize) }
            Pill("A+", false) { fontSize = (fontSize + 1).coerceAtMost(28); controller.setFontSize(fontSize) }
            Pill("KEYBOARD", false) { controller.showKeyboard() }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) ElectricPurple else CarbonSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else CarbonBorder),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, color = if (selected) Color.White else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}
