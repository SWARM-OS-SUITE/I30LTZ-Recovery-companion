package com.example.data.bridge

import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeClient.authFor
import com.example.data.nodes.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The bridge's `GET /sse/commands`: every command waiting for approval, with reconnect (2 s doubling to 30 s).
 *
 * The bridge sends each pending command once per connection and never announces that one was decided. So the list
 * is rebuilt from scratch after every (re)connect, and the caller drops an entry with [remove] once it has decided it.
 */
internal class BridgeCommandStream(private val node: Node, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _commands = MutableStateFlow<List<PendingCommand>>(emptyList())
    val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()

    private var job: Job? = null

    // The bridge pings at least every 15 s, so 45 s of silence means the link is dead.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    fun start() {
        if (node.backend != NodeBackend.SSE_BRIDGE || !node.isConfigured || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = LinkState.Idle
    }

    fun remove(id: String) = _commands.update { list -> list.filterNot { it.id == id } }

    private fun request(): Request =
        Request.Builder().url(node.baseUrl + "/sse/commands").authFor(node)
            .header("Accept", "text/event-stream").header("Cache-Control", "no-cache").build()

    private fun onEvent(event: String, data: String) {
        if (event != "command_pending") return
        val cmd = BridgeWire.commandFromJson(data) ?: return
        _commands.update { list -> if (list.any { it.id == cmd.id }) list else list + cmd }
    }

    private suspend fun loop() {
        var backoff = 2
        while (currentCoroutineContext().isActive) {
            _state.value = LinkState.Connecting
            var reason = "The connection closed"
            try {
                val call = client.newCall(request())
                coroutineScope {
                    // Reading is blocking; cancelling the HTTP call is what unblocks it when the stream is stopped.
                    val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
                    try {
                        call.execute().use { r ->
                            if (!r.isSuccessful) {
                                reason = if (r.code == 401 || r.code == 403) "The node rejected the token (HTTP ${r.code})" else "HTTP ${r.code}"
                            } else {
                                _state.value = LinkState.Live
                                backoff = 2
                                _commands.value = emptyList() // the bridge re-sends everything still pending
                                r.body?.charStream()?.buffered()?.use { SseParser.read(it, ::onEvent) }
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = NodeClient.explain(e, node.host, node.port)
            }
            for (left in backoff downTo 1) {
                _state.value = LinkState.Retrying(reason, left)
                delay(1000)
            }
            backoff = (backoff * 2).coerceAtMost(30)
        }
    }
}
