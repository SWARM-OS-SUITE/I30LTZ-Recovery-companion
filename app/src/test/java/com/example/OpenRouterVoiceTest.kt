package com.example

import com.example.data.service.OpenRouterVoice
import com.example.data.service.VoiceException
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenRouterVoiceTest {
    private class Seen(val method: String, val path: String, val auth: String?, val title: String?, val body: String)

    private val servers = mutableListOf<HttpServer>()
    @After fun stop() { servers.forEach { it.stop(0) } }

    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6)

    /** A fake OpenRouter. [reply] decides status, content type and body for each request. */
    private fun fake(reply: (Seen) -> Triple<Int, String, ByteArray>): Pair<String, MutableList<Seen>> {
        val seen = mutableListOf<Seen>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val s = Seen(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("X-Title"), body)
            synchronized(seen) { seen += s }
            val (code, type, bytes) = reply(s)
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start(); servers += server
        return "http://127.0.0.1:${server.address.port}/api/v1" to seen
    }

    private fun err(json: String, code: Int) = Triple(code, "application/json", json.toByteArray())

    private fun failure(base: String, key: String = "fake-key-SECRET-123"): String {
        try { OpenRouterVoice(base).synthesize(key, "elevenlabs/eleven-turbo-v2.5", "bill", "hi"); fail("expected a VoiceException") }
        catch (e: VoiceException) { assertFalse("the API key must never appear in a message", e.message!!.contains("SECRET")); return e.message!! }
        return ""
    }

    @Test fun sendsExactlyTheRequestOpenRouterDocumentsAndReturnsTheAudio() {
        val (base, seen) = fake { Triple(200, "audio/mpeg", mp3) }
        val out = OpenRouterVoice(base).synthesize("fake-key-SECRET-123", "elevenlabs/eleven-turbo-v2.5", "bill", "Good evening.")
        assertTrue(out.contentEquals(mp3))
        val s = seen.single()
        assertEquals("POST", s.method); assertEquals("/api/v1/audio/speech", s.path)
        assertEquals("Bearer fake-key-SECRET-123", s.auth); assertEquals("I30LTZ Companion", s.title)
        val j = JSONObject(s.body)
        assertEquals(setOf("model", "input", "voice", "response_format"), j.keys().asSequence().toSet())
        assertEquals("elevenlabs/eleven-turbo-v2.5", j.getString("model")); assertEquals("bill", j.getString("voice"))
        assertEquals("Good evening.", j.getString("input")); assertEquals("mp3", j.getString("response_format"))
    }

    @Test fun everyFailureIsExplainedInPlainWordsWithoutLeakingTheKey() {
        assertTrue(failure(fake { err("""{"error":{"message":"bad key"}}""", 401) }.first).contains("rejected the API key"))
        assertTrue(failure(fake { err("{}", 403) }.first).contains("rejected the API key"))
        assertTrue(failure(fake { err("""{"error":{"message":"insufficient credits"}}""", 402) }.first).contains("no credit"))
        assertTrue(failure(fake { err("{}", 429) }.first).contains("limiting voice requests"))
        assertTrue(failure(fake { err("""{"error":{"message":"voice 'bill' is not supported for this model"}}""", 400) }.first).contains("voice 'bill' is not supported"))
        assertTrue(failure(fake { err("not json at all", 404) }.first).contains("may not be available"))
        assertTrue(failure(fake { err("{}", 503) }.first).contains("HTTP 503"))
        assertTrue("a JSON answer with status 200 is not audio", failure(fake { err("""{"error":{"message":"provider failed"}}""", 200) }.first).contains("not with audio"))
        assertTrue("an empty 200 is not audio", failure(fake { Triple(200, "audio/mpeg", ByteArray(0)) }.first).contains("not with audio"))
        assertTrue(failure("http://127.0.0.1:1/api/v1").contains("Could not reach OpenRouter"))
    }

    @Test fun noKeyMeansNoRequest() {
        val (base, seen) = fake { Triple(200, "audio/mpeg", mp3) }
        try { OpenRouterVoice(base).synthesize("  ", "m", "bill", "hi"); fail() } catch (e: VoiceException) { assertTrue(e.message!!.contains("no OpenRouter API key")) }
        assertTrue(seen.isEmpty())
    }

    @Test fun readsTheLiveVoiceListWithoutAKeyOrAnyText() {
        val models = """{"data":[{"id":"elevenlabs/eleven-v3","supported_voices":["george","bill","daniel"]},{"id":"other/tts","supported_voices":["x"]}]}"""
        val (base, seen) = fake { Triple(200, "application/json", models.toByteArray()) }
        val v = OpenRouterVoice(base)
        assertEquals(listOf("george", "bill", "daniel"), v.supportedVoices("elevenlabs/eleven-v3"))
        assertNull("a model OpenRouter does not list", v.supportedVoices("elevenlabs/not-a-model"))
        assertTrue(seen.all { it.method == "GET" && it.auth == null && it.path.startsWith("/api/v1/models?output_modalities=speech") && it.body.isEmpty() })
        assertNull(OpenRouterVoice(fake { err("{}", 500) }.first).supportedVoices("elevenlabs/eleven-v3"))
        assertNull(OpenRouterVoice("http://127.0.0.1:1/api/v1").supportedVoices("elevenlabs/eleven-v3"))
    }

    // ── what gets spoken ──────────────────────────────────────────────────────

    @Test fun markdownEmojiLinksAndCodeAreNotReadOut() {
        val spoken = OpenRouterVoice.prepare("## Status ⚡\n**All good** 👨‍🔬 — see https://example.com/a?b=1 now.\n```kotlin\nval x = 1\n```\nDone.").joinToString(" ")
        assertFalse(spoken.contains("#") || spoken.contains("*") || spoken.contains("http") || spoken.contains("val x") || spoken.contains("⚡") || spoken.contains("👨"))
        assertTrue(spoken, spoken.contains("All good") && spoken.contains("link") && spoken.contains("Code omitted") && spoken.contains("Done."))
        assertTrue("accented letters survive", OpenRouterVoice.prepare("Café résumé.").single().contains("Café résumé"))
    }

    @Test fun nothingSpeakableMeansNoRequests() {
        assertTrue(OpenRouterVoice.prepare("").isEmpty()); assertTrue(OpenRouterVoice.prepare("   \n  ").isEmpty()); assertTrue(OpenRouterVoice.prepare("⚡👍🌿").isEmpty())
    }

    @Test fun longRepliesAreSplitAtSentencesAndCappedToControlCost() {
        val sentence = "The system is running within normal limits and nothing needs your attention right now."
        val chunks = OpenRouterVoice.prepare((1..60).joinToString(" ") { sentence })
        assertTrue(chunks.all { it.length <= OpenRouterVoice.CHUNK_CHARS })
        assertTrue("capped at ${OpenRouterVoice.MAX_CHARS} characters: ${chunks.sumOf { it.length }}", chunks.sumOf { it.length } <= OpenRouterVoice.MAX_CHARS)
        assertTrue("never cuts a sentence in half", chunks.all { it.endsWith(".") })
        assertTrue(chunks.size > 1)
    }

    @Test fun oneEndlessSentenceIsSplitAtSpacesNotInTheMiddleOfAWord() {
        val long = (1..300).joinToString(" ") { "word$it" } + "."
        val chunks = OpenRouterVoice.prepare(long)
        assertTrue(chunks.all { it.length <= OpenRouterVoice.CHUNK_CHARS })
        assertTrue(chunks.joinToString(" ").split(" ").all { it.startsWith("word") })
        assertNotNull(chunks.firstOrNull())
    }

    @Test fun shortRepliesAreOneRequest() {
        assertEquals(listOf("All systems are fine."), OpenRouterVoice.prepare("All systems are fine."))
    }
}
