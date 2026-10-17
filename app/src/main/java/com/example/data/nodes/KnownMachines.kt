package com.example.data.nodes

/**
 * Machines on the owner's tailnet that the app recognises without typing their address.
 *
 * Names and addresses only. A token is a secret and never belongs in the code (CP-05 removed the old compiled-in
 * host and token on purpose): a known machine starts as a "Not paired" node and is paired from the Nodes screen.
 */
data class KnownMachine(
    val name: String,
    val os: String,
    val host: String,
    val backend: NodeBackend = NodeBackend.AGENT,
)

object KnownMachines {
    /** The Debian PC. Tailscale address 100.82.1.19 (`tailscale0` on `debian`), confirmed by the owner 2026-10-09. */
    val DEBIAN = KnownMachine(name = "Debian", os = "Debian", host = "100.82.1.19")

    val all: List<KnownMachine> = listOf(DEBIAN)

    fun forHost(host: String): KnownMachine? = all.firstOrNull { it.host.equals(host.trim(), ignoreCase = true) }

    /** "Debian (100.82.1.19)" for a known address, the address itself otherwise. */
    fun label(host: String): String = forHost(host)?.let { "${it.name} (${it.host})" } ?: host

    /** What to show as a node's name: a node named only after its address takes the known machine's name. */
    fun displayName(node: Node): String =
        if (node.name.isBlank() || node.name == node.host) forHost(node.host)?.name ?: node.name.ifBlank { node.host }
        else node.name

    fun toNode(machine: KnownMachine): Node =
        Node(name = machine.name, os = machine.os, backend = machine.backend, host = machine.host, port = machine.backend.defaultPort)

    /** Known machines the list does not have yet (matched by address), as unpaired nodes with no token. */
    fun missingFrom(existing: List<Node>): List<Node> =
        all.filter { k -> existing.none { it.host.equals(k.host, ignoreCase = true) } }.map(::toNode)
}
