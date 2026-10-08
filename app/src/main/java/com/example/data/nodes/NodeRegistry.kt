package com.example.data.nodes

import android.content.Context
import com.example.data.config.SwarmConnection
import com.example.data.config.securePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's list of nodes, stored as one JSON blob in encrypted preferences
 * (tokens included). A handful of nodes does not need a database table, and
 * this keeps the Room schema untouched: a schema bump here would fall through
 * to destructive migration and wipe tasks and chat.
 */
class NodeRegistry private constructor(context: Context) {
    private val prefs = securePreferences(context.applicationContext, PREFS)
    private val _nodes = MutableStateFlow(load())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()

    private fun load(): List<Node> = prefs.getString(KEY_NODES, null)?.let(Node::listFromJson).orEmpty()

    private fun save(list: List<Node>) {
        check(prefs.edit().putString(KEY_NODES, Node.listToJson(list)).commit()) { "Could not save nodes" }
        _nodes.value = list
    }

    fun get(id: String): Node? = _nodes.value.firstOrNull { it.id == id }

    /** Telemetry rows and process control find their credentials by the address stored on the row. */
    fun findByHost(host: String): Node? = _nodes.value.firstOrNull { it.host.equals(host, ignoreCase = true) }

    @Synchronized
    fun upsert(node: Node) {
        val list = _nodes.value
        save(if (list.any { it.id == node.id }) list.map { if (it.id == node.id) node else it } else list + node)
    }

    @Synchronized
    fun remove(id: String) = save(_nodes.value.filterNot { it.id == id })

    /**
     * One-time move of the single "Remote PC" connection into the node list, so
     * a phone that was already set up keeps working. Does nothing if that
     * connection never had a host and token saved (the old built-in defaults are gone).
     */
    @Synchronized
    fun importLegacyConnectionOnce(connection: SwarmConnection) {
        if (prefs.getBoolean(KEY_LEGACY_DONE, false)) return
        if (connection.isUsable && findByHost(connection.host) == null) {
            upsert(Node(name = connection.host, os = "Other", backend = NodeBackend.LUCYSERVER,
                host = connection.host, port = connection.port, token = connection.authToken))
        }
        check(prefs.edit().putBoolean(KEY_LEGACY_DONE, true).commit())
    }

    companion object {
        private const val PREFS = "i30ltz_nodes"
        private const val KEY_NODES = "nodes"
        private const val KEY_LEGACY_DONE = "legacy_connection_imported"

        @Volatile private var instance: NodeRegistry? = null
        fun get(context: Context): NodeRegistry =
            instance ?: synchronized(this) { instance ?: NodeRegistry(context).also { instance = it } }
    }
}
