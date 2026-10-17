package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.bridge.BridgeClient
import com.example.data.bridge.BridgeCommandStream
import com.example.data.bridge.ChatPiece
import com.example.data.bridge.Decision
import com.example.data.bridge.PendingCommand
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Chat and command approvals with one Recovery OS bridge node at a time (CP-15).
 *
 * Kept apart from [MainViewModel] on purpose: that one's `init` order has already crashed the app once (CP-22), and
 * this feature needs no state from it beyond the node list the screen passes in.
 */
class BridgeViewModel : ViewModel() {
    data class ChatLine(val fromUser: Boolean, val text: String, val streaming: Boolean = false, val failed: Boolean = false)

    /** What came of a decision, kept so the person can see what a command printed after it ran. */
    data class Outcome(val nodeName: String, val command: String, val ok: Boolean, val summary: String, val output: String = "")

    private var current: Node? = null
    private var stream: BridgeCommandStream? = null
    private var collector: Job? = null
    private var chatJob: Job? = null

    private val _commands = MutableStateFlow<List<PendingCommand>>(emptyList())
    val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatLine>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatLine>>> = _chats.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    private val _outcomes = MutableStateFlow<List<Outcome>>(emptyList())
    val outcomes: StateFlow<List<Outcome>> = _outcomes.asStateFlow()

    private val _deciding = MutableStateFlow<Set<String>>(emptySet())
    val deciding: StateFlow<Set<String>> = _deciding.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Point the screen at [node] (null = none). Safe to call on every recomposition: unchanged nodes are ignored. */
    fun select(node: Node?) {
        if (node == current) return
        stream?.stop()
        collector?.cancel()
        stream = null
        current = node
        _commands.value = emptyList()
        _link.value = LinkState.Idle
        if (node == null || node.backend != NodeBackend.SSE_BRIDGE || !node.isConfigured) return
        val s = BridgeCommandStream(node, viewModelScope).also { stream = it; it.start() }
        collector = viewModelScope.launch {
            launch { s.commands.collect { _commands.value = it } }
            launch { s.state.collect { _link.value = it } }
        }
    }

    /** Queue a command on the node. It does NOT run: it waits in the list for a person to approve it. */
    fun submit(command: String, description: String, dangerous: Boolean) {
        val node = current ?: return
        val text = command.trim()
        if (text.isEmpty()) { _notice.value = "Type a command first."; return }
        viewModelScope.launch {
            _notice.value = BridgeClient.submit(node, text, description.trim(), dangerous)
                .fold({ "Queued on ${node.name}. It waits below until you approve or deny it." }, { it.message ?: "Could not queue the command." })
        }
    }

    /**
     * Approve or deny one pending command. `approved = true` makes the node RUN it, so the screen calls this only from
     * an explicit tap (with a confirmation for dangerous commands). A command already being decided is ignored.
     */
    fun decide(cmd: PendingCommand, approved: Boolean) {
        val node = current ?: return
        val s = stream
        if (cmd.id in _deciding.value) return
        _deciding.update { it + cmd.id }
        viewModelScope.launch {
            try {
                when (val d = BridgeClient.decide(node, cmd.id, approved)) {
                    is Decision.Executed -> {
                        s?.remove(cmd.id)
                        addOutcome(node, cmd, d.succeeded, "Ran, exit code ${d.returnCode ?: "unknown"}",
                            listOf(d.stdout, d.stderr).filter { it.isNotBlank() }.joinToString("\n"))
                    }
                    Decision.Denied -> { s?.remove(cmd.id); addOutcome(node, cmd, true, "Denied. Nothing was run.") }
                    Decision.Timeout -> { s?.remove(cmd.id); addOutcome(node, cmd, false, "Stopped after 30 s on the node (timeout).") }
                    is Decision.Failed -> {
                        if (d.gone) s?.remove(cmd.id)
                        addOutcome(node, cmd, false, d.message)
                    }
                }
            } finally {
                _deciding.update { it - cmd.id }
            }
        }
    }

    private fun addOutcome(node: Node, cmd: PendingCommand, ok: Boolean, summary: String, output: String = "") {
        _outcomes.update { (listOf(Outcome(node.name, cmd.command, ok, summary, output)) + it).take(MAX_OUTCOMES) }
    }

    fun clearNotice() { _notice.value = null }

    fun clearOutcomes() { _outcomes.value = emptyList() }

    // --- chat -----------------------------------------------------------------------------------------------

    private fun updateChat(nodeId: String, change: (List<ChatLine>) -> List<ChatLine>) =
        _chats.update { m -> m + (nodeId to change(m[nodeId].orEmpty()).takeLast(MAX_CHAT_LINES)) }

    private fun updateLast(nodeId: String, change: (ChatLine) -> ChatLine) =
        updateChat(nodeId) { list -> if (list.isEmpty()) list else list.dropLast(1) + change(list.last()) }

    /** Send [text] to Dr. I30LTZ (MAD IT) on the selected node and stream the reply into the history. */
    fun sendChat(text: String, includeTelemetry: Boolean) {
        val node = current ?: return
        val message = text.trim()
        if (message.isEmpty() || _chatBusy.value) return
        updateChat(node.id) { it + ChatLine(fromUser = true, text = message) + ChatLine(fromUser = false, text = "", streaming = true) }
        _chatBusy.value = true
        chatJob = viewModelScope.launch {
            try {
                BridgeClient.chat(node, message, includeTelemetry).collect { piece ->
                    when (piece) {
                        is ChatPiece.Token -> updateLast(node.id) { it.copy(text = it.text + piece.text) }
                        is ChatPiece.Error -> updateLast(node.id) {
                            it.copy(text = if (it.text.isBlank()) piece.message else it.text + "\n" + piece.message, failed = true)
                        }
                        ChatPiece.Done, ChatPiece.Ignore -> Unit
                    }
                }
            } finally {
                updateLast(node.id) { it.copy(streaming = false, text = if (it.text.isBlank() && !it.failed) "(no reply)" else it.text) }
                _chatBusy.value = false
            }
        }
    }

    fun stopChat() { chatJob?.cancel() }

    fun clearChat(nodeId: String) { _chats.update { it - nodeId } }

    override fun onCleared() {
        chatJob?.cancel()
        stream?.stop()
    }

    private companion object {
        const val MAX_CHAT_LINES = 200
        const val MAX_OUTCOMES = 20
    }
}
