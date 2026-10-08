package com.example.data.nodes

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How the app talks to a machine. One app, three kinds of node. */
enum class NodeBackend(val label: String, val defaultPort: Int, val pairsWithCode: Boolean, val hasLiveStream: Boolean) {
    /** SWARM OS / CachyOS: LucyServer on :5000, `X-Swarm-Auth` header, token pasted in. */
    LUCYSERVER("LucyServer (SWARM OS / CachyOS)", 5000, pairsWithCode = false, hasLiveStream = false),

    /** `i30ltz-node` agent on any Linux box: Bearer token from a one-time pairing code, SSE at /v1/events. */
    AGENT("I30LTZ node agent", 7437, pairsWithCode = true, hasLiveStream = true),

    /** Recovery OS `sse_bridge.py`: Bearer token from a pairing code, SSE at /sse/telemetry. */
    SSE_BRIDGE("Recovery OS SSE bridge", 7300, pairsWithCode = true, hasLiveStream = true);

    companion object {
        fun parse(name: String?): NodeBackend? = entries.firstOrNull { it.name == name }
    }
}

/** One machine the app manages. The token is kept only in encrypted preferences. */
data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val os: String,
    val backend: NodeBackend,
    val host: String,
    val port: Int,
    val token: String = "",
    /** Keep an SSE connection open for live telemetry (only for backends that have one). */
    val liveTelemetry: Boolean = true,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && token.isNotBlank()
    val wantsLiveStream: Boolean get() = backend.hasLiveStream && liveTelemetry && isConfigured
    val baseUrl: String get() = "http://$host:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("os", os).put("backend", backend.name)
        .put("host", host).put("port", port).put("token", token).put("live", liveTelemetry)

    companion object {
        private val HOST = Regex("[A-Za-z0-9.-]+")

        /** Null when OK, otherwise what to tell the user. Hosts are names/IPv4 only: no scheme, port or path. */
        fun validate(name: String, host: String, port: Int?): String? = when {
            name.isBlank() -> "Give the node a name."
            !HOST.matches(host.trim()) -> "Use a hostname or IPv4 address without http://, a port or a path."
            port == null || port !in 1..65535 -> "Port must be 1 to 65535."
            else -> null
        }

        fun fromJson(o: JSONObject): Node? {
            val backend = NodeBackend.parse(o.optString("backend")) ?: return null
            val id = o.optString("id").ifBlank { return null }
            return Node(
                id = id, name = o.optString("name"), os = o.optString("os"), backend = backend,
                host = o.optString("host"), port = o.optInt("port", backend.defaultPort),
                token = o.optString("token"), liveTelemetry = o.optBoolean("live", true),
            )
        }

        fun listToJson(nodes: List<Node>): String = JSONArray().also { a -> nodes.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<Node> = runCatching {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
