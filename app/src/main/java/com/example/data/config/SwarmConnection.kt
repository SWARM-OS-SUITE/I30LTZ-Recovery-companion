package com.example.data.config

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for how the companion app reaches LucyServer:
 * host, port and the X-Swarm-Auth token. Used by RemotePcScreen,
 * InteractiveTerminalScreen and AppRepository's phone-to-PC bridge call —
 * previously each of those hardcoded its own copy of the IP and token.
 */
class SwarmConnection(context: Context) {
    private val prefs: SharedPreferences =
        securePreferences(context.applicationContext, PREFS_NAME)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** lucille-core companion socket (HITL approvals); same host and token. */
    var hitlPort: Int
        get() = prefs.getInt(KEY_HITL_PORT, DEFAULT_HITL_PORT)
        set(value) = prefs.edit().putInt(KEY_HITL_PORT, value).apply()

    /** Keep the approval link open in the background (foreground service). */
    var hitlAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HITL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HITL_ENABLED, value).apply()

    fun httpBase(): String = "http://$host:$port"
    fun wsBase(): String = "ws://$host:$port"

    val isUsable: Boolean
        get() = host.isNotBlank() && authToken.isNotBlank()

    /** OkHttp client for the terminal WebSocket: pings keep an idle shell alive through Tailscale/NAT. */
    fun terminalClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Turns a failed call into something actionable. Every terminal failure on
     * 2026-09-16 looked like a bare "Failed to connect", with three different
     * causes: server bound to loopback, phone off the tailnet, wrong token.
     */
    fun explain(t: Throwable?, httpCode: Int? = null): String {
        val target = "$host:$port"
        return when {
            httpCode == 401 || httpCode == 403 ->
                "LucyServer at $target rejected the auth token. Save this node’s access token under Nodes."
            t is ConnectException && t.message.orEmpty().contains("ECONNREFUSED") ->
                "Reached $host, but nothing is listening on port $port. Start the service on that node, and make sure it listens on its Tailscale address, not only 127.0.0.1."
            t is SocketTimeoutException || t is NoRouteToHostException || t?.message.orEmpty().contains("timeout", ignoreCase = true) ->
                "No route to $target. Turn on Tailscale on this phone, and check that the node is online (tailscale status)."
            t is UnknownHostException -> "Can't resolve \"$host\". Use the node's Tailscale IP (100.x.y.z)."
            httpCode != null -> "LucyServer at $target answered HTTP $httpCode."
            else -> "Connection to $target failed: ${t?.message ?: "unknown error"}"
        }
    }

    companion object {
        private const val PREFS_NAME = "swarm_connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_HITL_PORT = "hitl_port"
        private const val KEY_HITL_ENABLED = "hitl_enabled"
        const val DEFAULT_HITL_PORT = 9091

        // No default host or token on purpose: a token compiled into the app is
        // public to anyone who has the APK. Nodes are added (and paired) under Nodes.
        const val DEFAULT_PORT = 5000
    }
}
