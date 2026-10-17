package com.example.data.bridge

import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeClient.authFor
import com.example.data.nodes.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Command approvals and chat with a Recovery OS bridge node (`sse_bridge.py`). Same node list, same encrypted token,
 * same error wording as every other node call. Nothing here runs anything by itself: [decide] with `approved = true`
 * is the one call that makes the node execute a command, and the UI only sends it after a person taps APPROVE.
 */
internal object BridgeClient {
    private val json = "application/json".toMediaType()

    // Approving can run a command for up to 30 s on the node, so the whole call may take longer than a normal one.
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    // Chat streams for as long as MAD IT keeps talking (the bridge allows it 120 s between lines).
    private val streaming = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(130, TimeUnit.SECONDS).build()

    /** Null when [node] can be used, otherwise what to tell the person. */
    fun problemWith(node: Node): String? = when {
        node.backend != NodeBackend.SSE_BRIDGE -> "Chat and approvals need a Recovery OS bridge node; ${node.name} is a different kind."
        !node.isConfigured -> "${node.name} is not paired yet. Open Nodes and pair it first."
        else -> null
    }

    private fun post(node: Node, path: String, body: String): Request =
        Request.Builder().url(node.baseUrl + path).authFor(node).post(body.toRequestBody(json)).build()

    suspend fun submit(node: Node, command: String, description: String, dangerous: Boolean): Result<String> {
        problemWith(node)?.let { return Result.failure(IllegalStateException(it)) }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(post(node, "/api/command", BridgeWire.submitBody(command, description, dangerous))).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.code == 401 || r.code == 403 -> Result.failure(IllegalStateException("The node rejected the token (HTTP ${r.code})."))
                        !r.isSuccessful -> Result.failure(IllegalStateException(BridgeWire.errorText(text).ifBlank { "The node answered HTTP ${r.code}." }))
                        else -> BridgeWire.submittedId(text)?.let { Result.success(it) }
                            ?: Result.failure(IllegalStateException("The node did not return a command id."))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException(NodeClient.explain(e, node.host, node.port)))
            }
        }
    }

    /** `approved = true` makes the node RUN the command. Call it only from an explicit tap. */
    suspend fun decide(node: Node, id: String, approved: Boolean): Decision {
        problemWith(node)?.let { return Decision.Failed(it) }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(post(node, "/api/command/decide", BridgeWire.decideBody(id, approved))).execute().use { r ->
                    BridgeWire.decision(r.code, r.body?.string().orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Decision.Failed(NodeClient.explain(e, node.host, node.port))
            }
        }
    }

    /**
     * Sends one chat message and streams the reply. The flow ends when the reply is done or the connection closes;
     * cancelling the collector cancels the HTTP call. Problems arrive as [ChatPiece.Error].
     */
    fun chat(node: Node, message: String, includeTelemetry: Boolean): Flow<ChatPiece> = callbackFlow {
        val problem = problemWith(node)
        if (problem != null) {
            trySend(ChatPiece.Error(problem))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val call = streaming.newCall(
            Request.Builder().url(node.baseUrl + "/api/chat").authFor(node)
                .header("Accept", "text/event-stream")
                .post(BridgeWire.chatBody(message, includeTelemetry).toRequestBody(json)).build())
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) {
                        val text = r.body?.string().orEmpty()
                        trySend(ChatPiece.Error(
                            if (r.code == 401 || r.code == 403) "The node rejected the token (HTTP ${r.code})."
                            else BridgeWire.errorText(text).ifBlank { "The node answered HTTP ${r.code}." }))
                    } else {
                        var finished = false
                        r.body?.charStream()?.buffered()?.use { reader ->
                            SseParser.read(reader) { _, data ->
                                if (finished) return@read
                                when (val piece = BridgeWire.chatPiece(data)) {
                                    ChatPiece.Ignore -> Unit
                                    ChatPiece.Done -> { finished = true; trySend(piece) }
                                    else -> trySend(piece)
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!call.isCanceled()) trySend(ChatPiece.Error(NodeClient.explain(e, node.host, node.port)))
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }
}
