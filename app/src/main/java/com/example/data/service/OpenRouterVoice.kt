package com.example.data.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A voice problem, worded for the person. Never contains the API key. */
class VoiceException(message: String) : Exception(message)

/**
 * Text to speech through OpenRouter's OpenAI-compatible `POST /api/v1/audio/speech`, used with its ElevenLabs
 * models. The reply is raw audio (mp3). Spoken text goes to OpenRouter and the voice provider: callers must
 * only use this when the person switched it on.
 */
class OpenRouterVoice(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build(),
) {
    private val json = "application/json".toMediaType()

    /** Blocking. Returns mp3 bytes or throws [VoiceException]. */
    fun synthesize(apiKey: String, model: String, voice: String, text: String): ByteArray {
        if (apiKey.isBlank()) throw VoiceException("There is no OpenRouter API key. Add one under AI Lab.")
        val body = JSONObject().put("model", model).put("input", text).put("voice", voice).put("response_format", "mp3").toString()
        val request = Request.Builder().url("$baseUrl/audio/speech")
            .header("Authorization", "Bearer $apiKey").header("X-Title", "I30LTZ Companion")
            .post(body.toRequestBody(json)).build()
        try {
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val type = r.header("Content-Type").orEmpty()
                if (r.isSuccessful && type.startsWith("audio") && bytes.isNotEmpty()) return bytes
                throw VoiceException(explain(r.code, bytes, type, voice, model))
            }
        } catch (e: IOException) {
            throw VoiceException("Could not reach OpenRouter (${e.javaClass.simpleName}). Check the phone's connection.")
        }
    }

    private fun explain(code: Int, body: ByteArray, type: String, voice: String, model: String): String {
        val why = runCatching {
            val o = JSONObject(String(body, Charsets.UTF_8))
            val e = o.opt("error")
            (if (e is JSONObject) e.optString("message") else e?.toString()).orEmpty()
        }.getOrDefault("").take(200)
        return when (code) {
            401, 403 -> "OpenRouter rejected the API key. Check it under AI Lab."
            402 -> "Your OpenRouter account has no credit left for voice."
            429 -> "OpenRouter is limiting voice requests right now. Try again in a moment."
            400, 404, 422 -> "OpenRouter would not make that voice" + (if (why.isNotBlank()) ": $why" else " (voice '$voice' on $model may not be available)")
            in 500..599 -> "OpenRouter or the voice provider had a problem (HTTP $code). Try again shortly."
            200 -> "OpenRouter answered, but not with audio" + (if (why.isNotBlank()) ": $why" else if (type.isNotBlank()) " ($type)" else "")
            else -> "OpenRouter answered HTTP $code" + (if (why.isNotBlank()) ": $why" else "")
        }
    }

    /**
     * The voices OpenRouter currently lists for [model] (public endpoint, no key, no text sent), or null if that
     * could not be determined. Lets the app notice when a voice has been retired (ElevenLabs retires its
     * default voices after 2026-12-31).
     */
    fun supportedVoices(model: String): List<String>? = try {
        http.newCall(Request.Builder().url("$baseUrl/models?output_modalities=speech").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val data = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: return null
            (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull { it.optString("id") == model }
                ?.optJSONArray("supported_voices")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        }
    } catch (e: Exception) { null }

    companion object {
        /** One request per chunk keeps the first words quick and every request small. */
        const val CHUNK_CHARS = 400
        /** Spend cap per spoken reply (ElevenLabs bills per character). */
        const val MAX_CHARS = 1500

        private val CODE_FENCE = Regex("```[\\s\\S]*?```")
        private val URL = Regex("https?://\\S+")
        private val MARKUP = Regex("[*#_~`>|\\[\\]{}]")

        /** Emoji, pictographs, joiners and skin-tone marks: read out as noise or as their long names, so they are dropped. */
        private fun isPictograph(cp: Int): Boolean {
            val type = Character.getType(cp)
            return type == Character.OTHER_SYMBOL.toInt() || type == Character.MODIFIER_SYMBOL.toInt() ||
                cp == 0x200D || cp == 0xFE0F || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
        }

        private fun stripPictographs(s: String): String = buildString { s.codePoints().forEach { if (!isPictograph(it)) appendCodePoint(it) } }
        private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

        /** Turns a chat reply into speakable chunks: no markdown, emoji, links or code, at most [MAX_CHARS] in total. */
        fun prepare(raw: String): List<String> {
            var t = raw.replace(CODE_FENCE, " Code omitted. ").replace(URL, " link ").replace(MARKUP, "").let(::stripPictographs)
            t = t.replace(Regex("([^.!?:;\\n])[ \\t]*\\n+[ \\t]*"), "$1. ").replace(Regex("\\s*\\n+\\s*"), " ").replace(Regex("[ \\t]+"), " ").trim()
            if (!t.any { it.isLetterOrDigit() }) return emptyList() // nothing but punctuation would be read out as "period"
            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            var total = 0
            fun flush() { if (current.isNotBlank()) { chunks += current.toString().trim(); total += current.length }; current = StringBuilder() }
            loop@ for (sentence in SENTENCE_END.split(t)) {
                for (piece in splitLong(sentence)) {
                    if (current.length + piece.length + 1 > CHUNK_CHARS) flush()
                    if (total + current.length + piece.length > MAX_CHARS && (chunks.isNotEmpty() || current.isNotBlank())) { flush(); break@loop }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(piece)
                }
            }
            flush()
            return chunks
        }

        private fun splitLong(s: String): List<String> {
            if (s.length <= CHUNK_CHARS) return listOf(s)
            val out = mutableListOf<String>()
            var rest = s
            while (rest.length > CHUNK_CHARS) {
                val cut = rest.lastIndexOf(' ', CHUNK_CHARS).takeIf { it > CHUNK_CHARS / 2 } ?: CHUNK_CHARS
                out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
