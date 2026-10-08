package com.example.data.nodes

import java.io.BufferedReader

/**
 * Minimal Server-Sent Events reader (WHATWG "text/event-stream").
 * Handles `event:` and `data:` fields, multi-line data, comments (`: ping`)
 * and an optional single space after the colon. `id:`/`retry:` are ignored;
 * the caller reconnects on its own schedule.
 */
object SseParser {
    /** Reads until the stream ends. An event is delivered only if it carried data. */
    fun read(reader: BufferedReader, onEvent: (event: String, data: String) -> Unit) {
        var event = ""
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: return
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.toString().removeSuffix("\n"))
                    event = ""
                    data.setLength(0)
                }
                line.startsWith(":") -> Unit // comment / keep-alive
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon < 0) line else line.substring(0, colon)
                    var value = if (colon < 0) "" else line.substring(colon + 1)
                    if (value.startsWith(" ")) value = value.substring(1)
                    when (field) {
                        "event" -> event = value
                        "data" -> data.append(value).append('\n')
                    }
                }
            }
        }
    }
}
