package com.example

import com.example.data.phonenode.HttpError
import com.example.data.phonenode.MiniHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MiniHttpTest {
    private fun read(text: String) = MiniHttp.readRequest(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    private fun status(text: String): Int = try { read(text); 0 } catch (e: HttpError) { e.status }

    @Test fun parsesMethodPathQueryHeadersAndBody() {
        val r = read("POST /v1/pair?x=a%20b&flag&y=2 HTTP/1.1\r\nHost: h\r\nAuthorization: Bearer abc123\r\nContent-Length: 5\r\nX-Mixed-CASE: v\r\n\r\nhello")
        assertEquals("POST", r.method); assertEquals("/v1/pair", r.path)
        assertEquals(mapOf("x" to "a b", "flag" to "", "y" to "2"), r.query)
        assertEquals("hello", String(r.body)); assertEquals("abc123", r.bearer()); assertEquals("v", r.headers["x-mixed-case"])
    }

    @Test fun bearerIsCaseInsensitiveAndStrict() {
        assertEquals("t", read("GET / HTTP/1.1\r\nauthorization: bearer t\r\n\r\n").bearer())
        assertEquals("", read("GET / HTTP/1.1\r\nAuthorization: Basic t\r\n\r\n").bearer())
        assertEquals("", read("GET / HTTP/1.1\r\n\r\n").bearer())
    }

    @Test fun refusesMalformedRequests() {
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: x\r\n")) // never ended
        assertEquals(400, status("GET /\r\n\r\n"))
        assertEquals(400, status("GET / SPDY/3\r\n\r\n"))
        assertEquals(400, status("get / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET v1/telemetry HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nno-colon-header\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
    }

    @Test fun enforcesSizeLimits() {
        assertEquals(431, status("GET / HTTP/1.1\r\nX: " + "a".repeat(MiniHttp.MAX_HEAD) + "\r\n\r\n"))
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: ${MiniHttp.MAX_BODY + 1}\r\n\r\n"))
        assertEquals(400, status("GET /" + "a".repeat(3000) + " HTTP/1.1\r\n\r\n"))
        assertEquals(0, status("POST / HTTP/1.1\r\nContent-Length: ${MiniHttp.MAX_BODY}\r\n\r\n" + "x".repeat(MiniHttp.MAX_BODY)))
    }

    @Test fun refusesChunkedBodiesInsteadOfMisreadingThem() {
        assertEquals(501, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
    }

    @Test fun writesAWellFormedJsonResponseAndCannotBeUsedForHeaderInjection() {
        val out = ByteArrayOutputStream()
        MiniHttp.writeJson(out, 401, """{"error":"é"}""", mapOf("WWW-Authenticate" to "Bearer\r\nSet-Cookie: evil=1"))
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(text.contains("Content-Length: ${"""{"error":"é"}""".toByteArray().size}\r\n"))
        assertTrue(text.contains("Connection: close\r\n\r\n"))
        assertFalse("a CR/LF in a header value must not start a new header", text.contains("\r\nSet-Cookie:"))
    }

}
