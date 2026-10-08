package com.example.data.nodes

import com.example.data.nodes.NodeClient.authFor
import com.example.data.repository.RemoteTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Live : LinkState
    data class Retrying(val reason: String, val inSeconds: Int) : LinkState
}

/**
 * One Server-Sent Events connection to a node's live telemetry, with reconnect
 * (2 s doubling to 30 s). Agent nodes stream `GET /v1/events`; Recovery bridge
 * nodes stream `GET /sse/telemetry`. LucyServer has no stream, so it is never started.
 */
internal class NodeStream(private val node: Node, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _telemetry = MutableSharedFlow<RemoteTelemetry>(replay = 1, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val telemetry: SharedFlow<RemoteTelemetry> = _telemetry.asSharedFlow()

    private var job: Job? = null

    // Servers send a keep-alive at least every 15 s, so 45 s of silence means the link is dead.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    fun start() {
        if (!node.backend.hasLiveStream || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = LinkState.Idle
    }

    private fun request(): Request {
        val path = if (node.backend == NodeBackend.AGENT || node.backend == NodeBackend.PHONE) "/v1/events" else "/sse/telemetry"
        return Request.Builder().url(node.baseUrl + path).authFor(node)
            .header("Accept", "text/event-stream").header("Cache-Control", "no-cache").build()
    }

    private fun onEvent(event: String, data: String) {
        if (event != "telemetry" && event != "message") return
        val parsed = runCatching {
            val o = JSONObject(data)
            if (node.backend == NodeBackend.AGENT || node.backend == NodeBackend.PHONE) RemoteTelemetry.parseAgent(o) else RemoteTelemetry.parseBridge(o)
        }.getOrNull() ?: return
        _telemetry.tryEmit(parsed)
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

    companion object {
        /** For the Test button: open a stream and say what happened within [timeoutMs]. */
        suspend fun probe(node: Node, timeoutMs: Long = 7000): String = coroutineScope {
            val stream = NodeStream(node, this)
            stream.start()
            val outcome = withTimeoutOrNull(timeoutMs) {
                merge(
                    stream.telemetry.map { t ->
                        "Live stream works: first update received (CPU ${if (t.cpu < 0) "n/a" else "${t.cpu}%"}, RAM ${t.ram}%)."
                    },
                    stream.state.filterIsInstance<LinkState.Retrying>().map { "Live stream failed: ${it.reason}" },
                ).first()
            }
            stream.stop()
            outcome ?: "No live update within ${timeoutMs / 1000} s."
        }
    }
}
