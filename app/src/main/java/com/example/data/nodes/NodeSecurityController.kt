package com.example.data.nodes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Scans, quarantine and progress for every node, in one place so a running scan keeps being followed
 * when you leave the screen. Nothing here changes a file on a node except [quarantine], [restore] and
 * [delete], and those only run when the person taps them.
 */
internal class NodeSecurityController(private val scope: CoroutineScope, private val pollEveryMs: Long = 1500) {
    private val _state = MutableStateFlow<Map<String, NodeSecurity>>(emptyMap())
    val state: StateFlow<Map<String, NodeSecurity>> = _state.asStateFlow()
    private val polls = HashMap<String, Job>()

    private fun update(nodeId: String, change: NodeSecurity.() -> NodeSecurity) {
        _state.update { m -> m + (nodeId to (m[nodeId] ?: NodeSecurity()).change()) }
    }

    private fun describe(e: Exception, node: Node): String =
        if (e is IllegalStateException || e is UnsupportedOperationException) e.message.orEmpty() else NodeClient.explain(e, node.host, node.port)

    /** Runs [block] with the busy flag set and any failure turned into a message. */
    private fun act(node: Node, block: suspend () -> String) {
        scope.launch {
            update(node.id) { copy(busy = true) }
            val msg = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { describe(e, node) }
            update(node.id) { copy(busy = false, message = msg) }
        }
    }

    private suspend fun refreshQuarantine(node: Node) {
        val items = NodeClient.quarantineList(node)
        update(node.id) { copy(quarantine = items) }
    }

    /** Reads the scanner status, the latest scan and the quarantine list. */
    fun refresh(node: Node) = act(node) {
        val (info, recent) = NodeClient.scanStatus(node)
        val quarantine = if (info.available) NodeClient.quarantineList(node) else emptyList()
        val target = info.runningId.ifBlank { recent.firstOrNull()?.id }
        val view = target?.let { NodeClient.getScan(node, it) } ?: _state.value[node.id]?.scan
        update(node.id) { copy(info = info, scan = view, quarantine = quarantine) }
        if (view?.running == true) watch(node, view.id)
        ""
    }

    fun start(node: Node, scanScope: String, path: String = "") = act(node) {
        val id = NodeClient.startScan(node, scanScope, path)
        watch(node, id)
        ""
    }

    /** Quick-scan every node that can scan. */
    fun startAll(nodes: List<Node>, scanScope: String = "quick") = nodes.forEach { start(it, scanScope) }

    fun cancel(node: Node) {
        val id = _state.value[node.id]?.scan?.takeIf { it.running }?.id ?: return
        act(node) { NodeClient.cancelScan(node, id); "" }
    }

    /** Follows a scan until it ends. Gives up (with a message) after repeated failures. */
    private fun watch(node: Node, id: String) {
        polls[node.id]?.cancel()
        polls[node.id] = scope.launch {
            var failures = 0
            while (true) {
                try {
                    val view = NodeClient.getScan(node, id)
                    failures = 0
                    update(node.id) { copy(scan = view) }
                    if (!view.running) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= 4) {
                        update(node.id) { copy(message = "Lost contact with ${node.name} while scanning: ${describe(e, node)}") }
                        break
                    }
                }
                delay(pollEveryMs)
            }
            runCatching { NodeClient.quarantineList(node) }.onSuccess { q -> update(node.id) { copy(quarantine = q) } }
        }
    }

    fun quarantine(node: Node, finding: ScanFinding) {
        val scanId = _state.value[node.id]?.scan?.id ?: return
        act(node) {
            val item = NodeClient.quarantineFile(node, scanId, finding.path)
            refreshQuarantine(node)
            "Quarantined ${item.original} (${item.signature}). It can be restored from the list below."
        }
    }

    fun restore(node: Node, item: QuarantinedFile) = act(node) {
        NodeClient.quarantineRestore(node, item.id)
        refreshQuarantine(node)
        "Restored ${item.original}."
    }

    fun delete(node: Node, item: QuarantinedFile) = act(node) {
        NodeClient.quarantineDelete(node, item.id)
        refreshQuarantine(node)
        "Permanently deleted the quarantined copy of ${item.original}."
    }

    fun stopAll() {
        polls.values.forEach { it.cancel() }
        polls.clear()
    }
}
