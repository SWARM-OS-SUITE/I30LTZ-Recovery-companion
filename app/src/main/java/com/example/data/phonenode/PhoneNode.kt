package com.example.data.phonenode

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.example.data.config.securePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * "This phone as a node": the setting, the pairing state and what the screen shows. The server itself
 * runs in [PhoneNodeService]; everything the person controls (on/off, pairing code, revoke) goes through here,
 * so there is no network path to any of it.
 */
object PhoneNode {
    private const val PREFS = "i30ltz_phone_node"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAIRED = "paired"
    private const val MAX_EVENTS = 20

    data class UiState(
        val enabled: Boolean = false,
        val running: Boolean = false,
        /** "100.82.1.17:7437", "127.0.0.1:7437" ... what the server is bound to right now. */
        val listening: List<String> = emptyList(),
        val tailnet: List<String> = emptyList(),
        val port: Int = PhoneNodeServer.DEFAULT_PORT,
        val message: String = "",
        val paired: List<PhoneNodeAuth.Device> = emptyList(),
        val code: String? = null,
        val codeExpiresAt: Long = 0,
        /** Secret-free lines: who paired, who was turned away. Newest last. */
        val events: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var auth: PhoneNodeAuth? = null

    private fun prefs(context: Context): SharedPreferences = securePreferences(context.applicationContext, PREFS)

    private class SecureStore(private val prefs: SharedPreferences) : PhoneNodeAuth.Store {
        override fun load(): List<PhoneNodeAuth.Device> = runCatching {
            val a = JSONArray(prefs.getString(KEY_PAIRED, "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { PhoneNodeAuth.Device(it.getString("label"), it.getString("hash"), it.optLong("created")) }
        }.getOrDefault(emptyList())

        override fun save(devices: List<PhoneNodeAuth.Device>) {
            val a = JSONArray().also { arr -> devices.forEach { arr.put(JSONObject().put("label", it.label).put("hash", it.hash).put("created", it.createdAt)) } }
            check(prefs.edit().putString(KEY_PAIRED, a.toString()).commit()) { "Could not save the paired devices" }
        }
    }

    @Synchronized
    fun auth(context: Context): PhoneNodeAuth = auth ?: PhoneNodeAuth(SecureStore(prefs(context))).also { auth = it }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Loads the saved setting and paired devices into the screen state. Cheap; safe to call often. */
    fun load(context: Context) {
        val enabled = isEnabled(context)
        _state.update { it.copy(enabled = enabled, paired = auth(context).devices()) }
    }

    fun setEnabled(context: Context, on: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ENABLED, on).commit()) { "Could not save the setting" }
        _state.update { it.copy(enabled = on, message = if (on) "Starting…" else "", code = if (on) it.code else null) }
        sync(context)
    }

    /** Starts or stops the service to match the saved setting (also called at boot and after an app update). */
    fun sync(context: Context) {
        val ctx = context.applicationContext
        if (isEnabled(ctx)) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PhoneNodeService::class.java))
        } else {
            ctx.stopService(Intent(ctx, PhoneNodeService::class.java))
            _state.update { it.copy(running = false, listening = emptyList(), message = "") }
        }
    }

    /** Makes a one-time pairing code for the person to read off this screen. */
    fun showPairingCode(context: Context) {
        val issued = auth(context).newCode()
        _state.update { it.copy(code = issued.code, codeExpiresAt = issued.expiresAt) }
    }

    fun revoke(context: Context, label: String) {
        auth(context).revoke(label)
        _state.update { it.copy(paired = auth(context).devices()) }
    }

    // ── called by the service ────────────────────────────────────────────────────────────────

    internal fun report(running: Boolean, listening: List<String>, tailnet: List<String>, message: String) {
        _state.update { it.copy(running = running, listening = listening, tailnet = tailnet, message = message) }
    }

    internal fun event(context: Context, line: String) {
        val stamp = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
        val paired = line.startsWith("paired")
        _state.update {
            it.copy(
                events = (it.events + "$stamp  $line").takeLast(MAX_EVENTS),
                paired = if (paired) auth(context).devices() else it.paired,
                code = if (paired) null else it.code, // the code is spent once someone pairs with it
            )
        }
    }
}
