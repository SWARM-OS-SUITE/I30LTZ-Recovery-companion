package com.example.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/** Where the engine remembers how far it has pulled from, and pushed to, each peer. */
interface MemoryCursors {
    fun pull(peer: String): String
    fun setPull(peer: String, cursor: String)
    fun push(peer: String): Long
    fun setPush(peer: String, seq: Long)
}

/** A peer answered with an HTTP error; [body] is its JSON explanation when it sent one. */
class MemoryHttpError(val code: Int, message: String, val body: JSONObject) : Exception(message)

/**
 * One pass of the shared-memory sync with one peer: pull what the peer has that we do not, then push what
 * we have that it does not. Pulled out of [MemorySyncEngine] (which needs Android services to run) so the
 * rules can be tested against a real in-process peer.
 *
 * Rules:
 *  - The cursor only moves past entries that were stored or knowingly skipped, so nothing is lost silently.
 *  - One unreadable entry is skipped and counted. It must never stop the pass: the old version aborted the
 *    whole sync, which also stopped this phone from sending its own notes.
 *  - Entries just pulled from a peer are not pushed straight back to it.
 */
class MemorySyncClient(
    private val dao: MemoryDao,
    private val http: OkHttpClient,
    private val token: () -> String,
    private val cursors: MemoryCursors,
) {
    private val json = "application/json".toMediaType()

    suspend fun sync(name: String, base: String): PeerStatus = withContext(Dispatchers.IO) {
        var pulled = 0
        var pushed = 0
        var skipped = 0
        val pulledIds = HashSet<String>()
        try {
            // Pull until the peer says there is no more.
            var cursor = cursors.pull(base)
            while (true) {
                val url = "$base${MemorySyncServer.SYNC_PATH}?cursor=${URLEncoder.encode(cursor, "UTF-8")}"
                val body = call(Request.Builder().url(url).get())
                val arr = body.getJSONArray("entries")
                require(body.getString("device").isNotBlank()) { "Missing peer identity" }
                val nextCursor = body.getString("cursor")
                val more = body.optBoolean("more", false)
                require(!more || nextCursor != cursor) { "Memory peer did not advance its cursor" }

                val entries = ArrayList<MemoryEntryEntity>(arr.length())
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i)?.let { MemoryEntryEntity.fromJson(it) }
                    if (entry == null) skipped++ else entries += entry
                }
                pulled += dao.insertAll(entries).count { it != -1L }
                entries.forEach { pulledIds += it.id }
                cursor = nextCursor
                cursors.setPull(base, cursor)
                if (!more) break
            }

            // Push everything the peer hasn't had from us, including gossip from other peers.
            var after = cursors.push(base)
            while (true) {
                val batch = dao.since(after, 200)
                if (batch.isEmpty()) break
                val outgoing = batch.filter { it.id !in pulledIds }
                if (outgoing.isNotEmpty()) {
                    val payload = JSONObject().put("entries", JSONArray().apply { outgoing.forEach { put(it.toJson()) } })
                    val ack = call(Request.Builder().url("$base${MemorySyncServer.SYNC_PATH}").post(payload.toString().toRequestBody(json)))
                    require(ack.getInt("accepted") in 0..outgoing.size) { "Invalid memory acknowledgment; cursor retained" }
                    pushed += outgoing.size
                }
                after = batch.last().seq
                cursors.setPush(base, after)
            }
            PeerStatus(name, base, lastOk = Instant.now().toString(), pulled = pulled, pushed = pushed, skipped = skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PeerStatus(name, base, lastError = e.message ?: e.javaClass.simpleName, pulled = pulled, pushed = pushed, skipped = skipped)
        }
    }

    /** One authenticated request. A non-2xx answer becomes a [MemoryHttpError] carrying the peer's explanation. */
    fun call(builder: Request.Builder): JSONObject {
        http.newCall(builder.header("X-Swarm-Auth", token()).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val obj = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                throw MemoryHttpError(resp.code, obj.optString("error", "HTTP ${resp.code}"), obj)
            }
            return JSONObject(text)
        }
    }
}
