package com.example.data.bridge

import org.json.JSONObject

/**
 * The Recovery OS bridge's command-approval and chat wire format (`mad-dr-ai/sse-server/sse_bridge.py`).
 * Pure functions, so the format is testable without a network. Ported from the old `companion-app` (CP-15).
 */

/** A command waiting for a person to approve or deny it on the node. Approving RUNS it on that node. */
data class PendingCommand(
    val id: String,
    val command: String,
    val description: String = "",
    val source: String = "android",
    val dangerous: Boolean = false,
    val submittedAt: String = "",
)

/** One piece of a streamed chat reply. */
sealed interface ChatPiece {
    data class Token(val text: String) : ChatPiece
    data object Done : ChatPiece
    data class Error(val message: String) : ChatPiece
    data object Ignore : ChatPiece
}

/** What happened when a command was approved or denied. */
sealed interface Decision {
    data class Executed(val returnCode: Int?, val stdout: String, val stderr: String) : Decision {
        val succeeded: Boolean get() = returnCode == 0
    }
    data object Denied : Decision
    data object Timeout : Decision
    /** [gone] means the node no longer has that command (someone else decided it, or the bridge restarted). */
    data class Failed(val message: String, val gone: Boolean = false) : Decision
}

object BridgeWire {
    /** `event: command_pending` data. Null for anything that is not a usable command. */
    fun commandFromJson(data: String): PendingCommand? = runCatching {
        val o = JSONObject(data)
        val id = o.optString("id")
        val command = o.optString("command")
        if (id.isBlank() || command.isBlank()) null
        else PendingCommand(
            id = id, command = command,
            description = o.optString("description"),
            source = o.optString("source").ifBlank { "android" },
            // Fail closed: a command that does not say whether it is dangerous gets the extra confirmation.
            dangerous = o.optBoolean("dangerous", true),
            submittedAt = o.optString("submitted_at"),
        )
    }.getOrNull()

    fun submitBody(command: String, description: String, dangerous: Boolean): String =
        JSONObject().put("command", command).put("description", description)
            .put("source", "android").put("dangerous", dangerous).toString()

    fun decideBody(id: String, approved: Boolean): String =
        JSONObject().put("cmd_id", id).put("approved", approved).toString()

    fun chatBody(message: String, includeTelemetry: Boolean): String =
        JSONObject().put("message", message).put("include_telemetry", includeTelemetry).toString()

    /**
     * One `data:` payload of the chat stream. The bridge relays MAD IT's lines and ends with `{"done": true}`.
     * JSON objects carry the text in `token` (the bridge's own format); `content`/`response` are also accepted so a
     * differently shaped MAD IT still shows its reply. Anything that is not JSON is shown as plain text.
     */
    fun chatPiece(data: String): ChatPiece {
        val text = data.trim()
        if (text.isEmpty()) return ChatPiece.Ignore
        if (!text.startsWith("{")) return ChatPiece.Token(data)
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return ChatPiece.Token(data)
        if (o.optBoolean("done", false)) return ChatPiece.Done
        for (key in TEXT_KEYS) {
            val v = o.opt(key)
            if (v is String) return if (v.isEmpty()) ChatPiece.Ignore else ChatPiece.Token(v)
        }
        return ChatPiece.Ignore
    }

    private val TEXT_KEYS = listOf("token", "content", "response")

    /** The reply to `POST /api/command/decide`. [code] is the HTTP status. */
    fun decision(code: Int, body: String): Decision {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val detail = o?.optString("detail").orEmpty()
        return when {
            code == 404 -> Decision.Failed("That command was already decided or is no longer pending.", gone = true)
            code == 401 || code == 403 -> Decision.Failed("The node rejected the token (HTTP $code).")
            code !in 200..299 -> Decision.Failed(detail.ifBlank { "The node answered HTTP $code." })
            o == null -> Decision.Failed("The node sent a reply this app cannot read.")
            else -> when (o.optString("status")) {
                "executed" -> Decision.Executed(
                    returnCode = if (o.has("returncode") && !o.isNull("returncode")) o.optInt("returncode") else null,
                    stdout = o.optString("stdout"), stderr = o.optString("stderr"))
                "denied" -> Decision.Denied
                "timeout" -> Decision.Timeout
                "error" -> Decision.Failed(o.optString("error").ifBlank { "The command failed to start." })
                else -> Decision.Failed("Unexpected reply from the node: ${o.optString("status").ifBlank { "no status" }}.")
            }
        }
    }

    /** The id from `POST /api/command` (`{"cmd_id": "...", "status": "pending"}`), or null. */
    fun submittedId(body: String): String? =
        runCatching { JSONObject(body).optString("cmd_id").ifBlank { null } }.getOrNull()

    /** The node's own explanation of an error, from `{"detail": ...}` (bridge) or `{"error": ...}`. */
    fun errorText(body: String): String =
        runCatching { JSONObject(body) }.getOrNull()
            ?.let { o -> o.optString("detail").ifBlank { o.optString("error") } }.orEmpty()
}
