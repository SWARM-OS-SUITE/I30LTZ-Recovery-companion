package com.example.data.phonenode

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** An HTTP problem to answer with [status]. */
class HttpError(val status: Int, override val message: String) : Exception(message)

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /** The token from "Authorization: Bearer <token>", or "". */
    fun bearer(): String {
        val h = headers["authorization"] ?: return ""
        return if (h.length > 7 && h.substring(0, 7).equals("bearer ", ignoreCase = true)) h.substring(7).trim() else ""
    }
}

/**
 * A deliberately small, strict HTTP/1.1 reader and writer (one request per connection). Hard limits on
 * header and body size, no chunked bodies, no keep-alive: this listens on a phone, so it stays simple.
 */
object MiniHttp {
    const val MAX_HEAD = 8 * 1024
    const val MAX_BODY = 4 * 1024
    private const val MAX_TARGET = 2048

    fun readRequest(input: InputStream): HttpRequest {
        val head = ByteArrayOutputStream()
        var tail = 0 // last four bytes, to spot the blank line that ends the header
        while (true) {
            val b = input.read()
            if (b < 0) throw HttpError(400, "the connection closed before the request was complete")
            head.write(b)
            if (head.size() > MAX_HEAD) throw HttpError(431, "request header too large")
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "malformed request line")
        val method = parts[0]
        val target = parts[1]
        if (method.isEmpty() || method.length > 7 || !method.all { it in 'A'..'Z' }) throw HttpError(400, "malformed method")
        if (!target.startsWith("/") || target.length > MAX_TARGET) throw HttpError(400, "malformed request target")

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "content-length" && headers[name] != null && headers[name] != value) throw HttpError(400, "conflicting Content-Length")
            headers[name] = value
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(501, "chunked request bodies are not supported")

        val len = headers["content-length"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw HttpError(400, "bad Content-Length") } ?: 0
        if (len > MAX_BODY) throw HttpError(413, "request body too large")
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) throw HttpError(400, "the body ended early")
            read += n
        }

        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        return HttpRequest(method, path, if (q < 0) emptyMap() else parseQuery(target.substring(q + 1)), headers, body)
    }

    private fun parseQuery(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in s.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            try { out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") } catch (_: IllegalArgumentException) { }
        }
        return out
    }

    private val REASONS = mapOf(
        200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed",
        413 to "Payload Too Large", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        500 to "Internal Server Error", 501 to "Not Implemented", 503 to "Service Unavailable",
    )

    fun writeHead(out: OutputStream, status: Int, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun writeJson(out: OutputStream, status: Int, json: String, extra: Map<String, String> = emptyMap()) {
        val body = json.toByteArray(Charsets.UTF_8)
        writeHead(out, status, linkedMapOf("Content-Type" to "application/json", "Cache-Control" to "no-store", "Content-Length" to body.size.toString()) + extra)
        out.write(body)
        out.flush()
    }
}
