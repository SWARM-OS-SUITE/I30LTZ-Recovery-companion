package com.example.data.nodes

import com.example.data.nodes.NodeClient.authFor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** A tmux session on a node, as listed by `GET /v1/terminal/sessions`. */
data class TerminalSessionInfo(val name: String, val windows: Int, val attached: Int)

/**
 * One phone-side terminal connection to a node's tmux session (`GET /v1/terminal`, WebSocket).
 * Single use: connect once, and create a new object to reconnect (tmux keeps the session alive,
 * so reconnecting puts you back exactly where you were).
 *
 * Output is a lossless ordered stream of terminal bytes; dropping any would corrupt the screen.
 */
internal class NodeTerminalSession(private val node: Node) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val session: String) : State
        data class Closed(val reason: String, val failed: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    /** Terminal output in order. Collect from one place only. */
    val output: Flow<ByteArray> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null

    // The agent pings every 25 s and we ping every 20 s, so a silent path is noticed quickly.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    fun connect(session: String, cols: Int, rows: Int) {
        require(SESSION_NAME.matches(session)) { "Session names are 1-32 letters, digits, _ or -" }
        _state.value = State.Connecting
        val url = "ws://${node.host}:${node.port}/v1/terminal?session=$session&cols=$cols&rows=$rows"
        val request = Request.Builder().url(url).authFor(node).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.Connected(session)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                channel.trySend(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(State.Closed(reason.ifBlank { "Session closed" }, failed = code != 1000))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(State.Closed(explain(t, response), failed = true))
            }
        })
    }

    private fun explain(t: Throwable, response: Response?): String {
        if (response == null) return NodeClient.explain(t, node.host, node.port)
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val why = NodeClient.errorText(body)
        return when (response.code) {
            401 -> "The node rejected the token. Pair this node again."
            404 -> why.ifBlank { "This node has no terminal service." }
            429 -> why.ifBlank { "Too many terminals are open on that node." }
            else -> why.ifBlank { "The node answered HTTP ${response.code}." }
        }
    }

    private fun finish(final: State) {
        if (_state.value !is State.Closed) _state.value = final
        channel.close()
    }

    /** Keystrokes / pasted text to the node. False if the connection is not up. */
    fun send(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

    fun send(text: String): Boolean = send(text.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) socket?.send("""{"type":"resize","cols":$cols,"rows":$rows}""")
    }

    /** Leaves the session; tmux keeps it running on the node. */
    fun close() {
        socket?.close(1000, "bye")
        finish(State.Closed("Disconnected", failed = false))
    }

    companion object {
        val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,32}")
    }
}
