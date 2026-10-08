package com.example.data.nodes

import com.example.data.repository.RemoteTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class NodeProcess(val pid: Int, val name: String, val memoryMb: Int)

sealed interface PairOutcome {
    data class Paired(val token: String, val hostname: String) : PairOutcome
    data class Failed(val message: String) : PairOutcome
}

/** Result of testing a node: [lines] is what to show the user, [ok] means reachable AND authorized. */
data class Probe(val ok: Boolean, val lines: List<String>)

/** Calls to one node, whichever kind it is. Blocking network work runs on Dispatchers.IO. */
internal object NodeClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    internal fun Request.Builder.authFor(node: Node): Request.Builder = when (node.backend) {
        NodeBackend.LUCYSERVER -> header("X-Swarm-Auth", node.token)
        else -> header("Authorization", "Bearer ${node.token}")
    }

    /** Turns a failed call into something the person can act on. */
    fun explain(e: Throwable, host: String, port: Int): String = when {
        e is ConnectException && e.message.orEmpty().contains("ECONNREFUSED") ->
            "Reached $host, but nothing is listening on port $port. Start the service on that node, and make sure it listens on its Tailscale address, not only 127.0.0.1."
        e is SocketTimeoutException || e is NoRouteToHostException || e.message.orEmpty().contains("timeout", ignoreCase = true) ->
            "No route to $host:$port. Turn on Tailscale on this phone and check that the node is online."
        e is UnknownHostException -> "Can't resolve \"$host\". Use the node's Tailscale IP (100.x.y.z)."
        else -> "Connection to $host:$port failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun get(node: Node, path: String): Request =
        Request.Builder().url(node.baseUrl + path).authFor(node).build()

    /** The node's own explanation from `{"error":..}` (agent) or `{"detail":..}` (bridge), if it sent one. */
    internal fun errorText(body: String): String =
        runCatching { JSONObject(body) }.getOrNull()
            ?.let { o -> o.optString("error").ifBlank { o.optString("detail") } }.orEmpty()

    private inline fun <T> call(node: Node, request: Request, parse: (String) -> T): T =
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            // LucyServer answers 403 for a bad token; on agent/bridge nodes 403 means "I refuse to do that".
            val badToken = r.code == 401 || (r.code == 403 && node.backend == NodeBackend.LUCYSERVER)
            when {
                badToken -> throw IllegalStateException("The node rejected the token (HTTP ${r.code}).")
                !r.isSuccessful -> throw IllegalStateException(
                    errorText(body).ifBlank { "${node.host}:${node.port} answered HTTP ${r.code}: ${body.take(200)}" })
                else -> parse(body)
            }
        }

    suspend fun probe(node: Node): Probe = withContext(Dispatchers.IO) {
        val path = when (node.backend) {
            NodeBackend.LUCYSERVER -> "/api/system/stats"
            NodeBackend.AGENT -> "/v1/capabilities"
            NodeBackend.SSE_BRIDGE -> "/api/status"
        }
        try {
            call(node, get(node, path)) { body -> Probe(true, describe(node, body)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Probe(false, listOf("Reached ${node.host}:${node.port}.", e.message.orEmpty()))
        } catch (e: Exception) {
            Probe(false, listOf(explain(e, node.host, node.port)))
        }
    }

    private fun describe(node: Node, body: String): List<String> = runCatching {
        val o = JSONObject(body)
        when (node.backend) {
            NodeBackend.AGENT -> {
                val f = o.getJSONObject("features")
                val on = f.keys().asSequence().filter { f.getBoolean(it) }.sorted().toList()
                val off = f.keys().asSequence().filter { !f.getBoolean(it) }.sorted().toList()
                buildList {
                    add("Token accepted. Agent ${o.optString("version")} on ${o.optString("hostname")} (${o.optString("os")}).")
                    add("Works: ${on.joinToString(", ").ifEmpty { "nothing yet" }}.")
                    if (off.isNotEmpty()) add("Not built on this node yet: ${off.joinToString(", ")}.")
                }
            }
            NodeBackend.SSE_BRIDGE -> listOf(
                "Token accepted. Recovery bridge on ${o.optString("hostname")} (${o.optString("os_version")}).",
                "Dr. I30LTZ (MAD IT) online: ${if (o.optBoolean("madit_online")) "yes" else "no"}. Pending approvals: ${o.optInt("pending_cmd_count")}.",
                "Works: telemetry, command approvals, chat. Not available: process list/kill, scan, optimize.")
            NodeBackend.LUCYSERVER -> listOf("Token accepted. LucyServer answered.")
        }
    }.getOrElse { listOf("Token accepted, but the answer was not in the expected format.") }

    /** Trades a one-time code (shown on the node) for a token. */
    suspend fun pair(backend: NodeBackend, host: String, port: Int, code: String, label: String): PairOutcome =
        withContext(Dispatchers.IO) {
            require(backend.pairsWithCode)
            val path = if (backend == NodeBackend.AGENT) "/v1/pair" else "/api/pair"
            val body = JSONObject().put("code", code.trim()).put("label", label).toString().toRequestBody(json)
            try {
                http.newCall(Request.Builder().url("http://$host:$port$path").post(body).build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    val o = runCatching { JSONObject(text) }.getOrNull()
                    if (r.isSuccessful && o != null && o.optString("token").isNotBlank()) {
                        PairOutcome.Paired(o.getString("token"), o.optString("hostname"))
                    } else {
                        PairOutcome.Failed(errorText(text).ifBlank { "The node answered HTTP ${r.code}." })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairOutcome.Failed(explain(e, host, port))
            }
        }

    suspend fun telemetry(node: Node): RemoteTelemetry = withContext(Dispatchers.IO) {
        when (node.backend) {
            NodeBackend.LUCYSERVER -> call(node, get(node, "/api/system/stats")) { RemoteTelemetry.parse(JSONObject(it)) }
            NodeBackend.AGENT -> call(node, get(node, "/v1/telemetry")) { RemoteTelemetry.parseAgent(JSONObject(it)) }
            NodeBackend.SSE_BRIDGE -> call(node, get(node, "/api/status")) { RemoteTelemetry.parseBridge(JSONObject(it)) }
        }
    }

    suspend fun processes(node: Node): List<NodeProcess> = withContext(Dispatchers.IO) {
        when (node.backend) {
            NodeBackend.LUCYSERVER -> call(node, get(node, "/api/system/processes")) { body ->
                JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                    .map { NodeProcess(it.getInt("pid"), it.getString("name"), it.getDouble("memoryUsageMb").toInt()) } }
            }
            NodeBackend.AGENT -> call(node, get(node, "/v1/processes?limit=100")) { body ->
                JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                    .map { NodeProcess(it.getInt("pid"), it.getString("name"), it.getDouble("memory_mb").toInt()) } }
            }
            NodeBackend.SSE_BRIDGE -> throw UnsupportedOperationException("The Recovery bridge has no process list yet.")
        }
    }

    /** Returns a sentence for the user; throws if the node refused. */
    suspend fun kill(node: Node, pid: Int): String = withContext(Dispatchers.IO) {
        val empty = ByteArray(0).toRequestBody(json)
        when (node.backend) {
            NodeBackend.LUCYSERVER -> {
                val body = JSONObject().put("pid", pid).toString().toRequestBody(json)
                call(node, Request.Builder().url(node.baseUrl + "/api/system/process/kill").authFor(node).post(body).build()) {
                    check(JSONObject(it).getBoolean("success")) { "Node refused termination" }
                }
            }
            NodeBackend.AGENT -> call(node, Request.Builder().url(node.baseUrl + "/v1/processes/$pid/kill").authFor(node).post(empty).build()) { }
            NodeBackend.SSE_BRIDGE -> throw UnsupportedOperationException("The Recovery bridge cannot stop processes yet.")
        }
        "${node.name} accepted the stop request for pid $pid."
    }

    /** tmux sessions on an agent node (the ones a phone can attach to). Throws with the node's reason if unavailable. */
    suspend fun terminalSessions(node: Node): List<TerminalSessionInfo> = withContext(Dispatchers.IO) {
        call(node, get(node, "/v1/terminal/sessions")) { body ->
            JSONArray(body).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .map { TerminalSessionInfo(it.getString("name"), it.optInt("windows"), it.optInt("attached")) }
            }
        }
    }

    // ── Malware scanning (agent nodes) ───────────────────────────────────────────────

    private fun jsonPost(node: Node, path: String, body: String = "{}") =
        Request.Builder().url(node.baseUrl + path).authFor(node).post(body.toRequestBody(json)).build()

    /** Scanner status plus recent scans (without findings). */
    suspend fun scanStatus(node: Node): Pair<ScanInfo, List<ScanView>> = withContext(Dispatchers.IO) {
        call(node, get(node, "/v1/scan")) { body ->
            val o = JSONObject(body)
            val scans = o.optJSONArray("scans")
            ScanInfo.fromJson(o.getJSONObject("engine")) to
                (if (scans == null) emptyList() else (0 until scans.length()).map { ScanView.fromJson(scans.getJSONObject(it)) })
        }
    }

    /** Starts a scan; [scope] is quick, home, full or path. Returns the scan id. */
    suspend fun startScan(node: Node, scope: String, path: String = ""): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("scope", scope).apply { if (path.isNotBlank()) put("path", path) }.toString()
        call(node, jsonPost(node, "/v1/scan", body)) { JSONObject(it).getString("id") }
    }

    suspend fun getScan(node: Node, id: String): ScanView = withContext(Dispatchers.IO) {
        call(node, get(node, "/v1/scan/$id")) { ScanView.fromJson(JSONObject(it)) }
    }

    suspend fun cancelScan(node: Node, id: String) = withContext(Dispatchers.IO) {
        call(node, jsonPost(node, "/v1/scan/$id/cancel")) { }
    }

    /** Moves one finding into quarantine. The node re-scans it first and refuses anything that is not a finding. */
    suspend fun quarantineFile(node: Node, scanId: String, path: String): QuarantinedFile = withContext(Dispatchers.IO) {
        call(node, jsonPost(node, "/v1/scan/$scanId/quarantine", JSONObject().put("path", path).toString())) {
            QuarantinedFile.fromJson(JSONObject(it))
        }
    }

    suspend fun quarantineList(node: Node): List<QuarantinedFile> = withContext(Dispatchers.IO) {
        call(node, get(node, "/v1/quarantine")) { body ->
            JSONArray(body).let { a -> (0 until a.length()).map { QuarantinedFile.fromJson(a.getJSONObject(it)) } }
        }
    }

    suspend fun quarantineRestore(node: Node, id: String): QuarantinedFile = withContext(Dispatchers.IO) {
        call(node, jsonPost(node, "/v1/quarantine/$id/restore")) { QuarantinedFile.fromJson(JSONObject(it)) }
    }

    suspend fun quarantineDelete(node: Node, id: String) = withContext(Dispatchers.IO) {
        call(node, Request.Builder().url(node.baseUrl + "/v1/quarantine/$id").authFor(node).delete().build()) { }
    }

    /** Which features an agent node really has; null for other node types or if unreachable. */
    suspend fun agentFeatures(node: Node): Map<String, Boolean>? = withContext(Dispatchers.IO) {
        if (node.backend != NodeBackend.AGENT) return@withContext null
        runCatching {
            call(node, get(node, "/v1/capabilities")) { body ->
                val f = JSONObject(body).getJSONObject("features")
                f.keys().asSequence().associateWith { f.getBoolean(it) }
            }
        }.getOrNull()
    }
}
