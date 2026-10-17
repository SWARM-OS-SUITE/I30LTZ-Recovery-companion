package com.example.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The shared-memory sync, tested the way it runs: two real Room databases (a phone and a peer), the phone's
 * own embedded HTTP server standing in for the peer, and the real client talking to it over localhost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemorySyncTest {
    private val token = "test-token-not-a-real-secret"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var phone: AppDatabase
    private lateinit var peer: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var server: MemorySyncServer
    private val ingested = CopyOnWriteArrayList<Int>()
    private val http = OkHttpClient()

    private class Cursors : MemoryCursors {
        val pulls = HashMap<String, String>()
        val pushes = HashMap<String, Long>()
        override fun pull(peer: String) = pulls[peer] ?: ""
        override fun setPull(peer: String, cursor: String) { pulls[peer] = cursor }
        override fun push(peer: String) = pushes[peer] ?: 0L
        override fun setPush(peer: String, seq: Long) { pushes[peer] = seq }
    }

    private val cursors = Cursors()
    private val base get() = "http://127.0.0.1:${server.boundPort}"
    private fun client(tok: String = token) = MemorySyncClient(phone.memoryDao(), http, { tok }, cursors)

    @Before fun setUp() {
        phone = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        peer = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MemorySyncServer(scope, peer.memoryDao(), { "peer-device" }, { token }, port = 0) { ingested += it }
        server.start()
        assertTrue("test server must start", server.isRunning)
    }

    @After fun tearDown() {
        server.stop(); scope.cancel(); phone.close(); peer.close()
    }

    private fun entry(device: String, key: String, content: String, n: Int = 0) =
        MemoryEntryEntity.create(device, "note", key, content).copy(id = "entry-$device-${n.toString().padStart(5, '0')}-${key.hashCode().toUInt()}")

    private fun phoneEntries(count: Int, prefix: String = "p") = (1..count).map { entry("phone-1", "$prefix$it", "phone note $it", it) }
    private fun peerEntries(count: Int, prefix: String = "q") = (1..count).map { entry("peer-device", "$prefix$it", "peer note $it", it) }

    private fun ids(db: AppDatabase) = runBlocking { db.memoryDao().since(0, 100_000).map { it.id }.toSet() }

    // ── The sync pass ─────────────────────────────────────────────────────────

    @Test fun phoneEntriesReachThePeerAndAreNotResentNextTime() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(3))
        val first = client().sync("peer", base)
        assertNull(first.lastError)
        assertEquals(3, first.pushed)
        assertEquals(ids(phone), ids(peer))

        val second = client().sync("peer", base)
        assertNull(second.lastError)
        assertEquals("nothing new to send", 0, second.pushed)
        assertEquals(0, second.pulled)
    }

    @Test fun peerEntriesArriveAndAreNotEchoedBack() = runBlocking {
        peer.memoryDao().insertAll(peerEntries(4))
        val result = client().sync("peer", base)
        assertNull(result.lastError)
        assertEquals(4, result.pulled)
        assertEquals("what the peer already has is not sent back", 0, result.pushed)
        assertEquals(ids(peer), ids(phone))
        assertTrue("the peer's server never had to ingest anything", ingested.isEmpty())
    }

    @Test fun twoWaySyncConvergesAndIsIdempotent() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(2))
        peer.memoryDao().insertAll(peerEntries(3))
        val a = client().sync("peer", base)
        assertEquals(3, a.pulled); assertEquals(2, a.pushed)
        assertEquals(5, ids(phone).size)
        assertEquals(ids(phone), ids(peer))
        val b = client().sync("peer", base)
        assertEquals(0, b.pulled); assertEquals(0, b.pushed)
    }

    @Test fun entriesCreatedAfterASyncAreSentOnTheNextOne() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(1))
        client().sync("peer", base)
        phone.memoryDao().insertAll(listOf(entry("phone-1", "later", "added afterwards", 99)))
        val result = client().sync("peer", base)
        assertEquals(1, result.pushed)
        assertEquals(ids(phone), ids(peer))
    }

    @Test fun anEntryGossipedFromAnotherDeviceIsForwardedToTheThirdParty() = runBlocking {
        // Another phone's note reached this phone earlier (not from this peer); this peer should get it too.
        phone.memoryDao().insertAll(listOf(entry("other-phone", "gossip", "from a third device", 7)))
        val result = client().sync("peer", base)
        assertEquals(1, result.pushed)
        assertTrue(ids(peer).any { it.startsWith("entry-other-phone") })
    }

    @Test fun longHistoriesArePulledInPages() = runBlocking {
        peer.memoryDao().insertAll(peerEntries(1_200))
        val result = client().sync("peer", base)
        assertNull(result.lastError)
        assertEquals(1_200, result.pulled)
        assertEquals(1_200, ids(phone).size)
    }

    @Test fun anInterruptedSyncResumesFromTheSavedCursor() = runBlocking {
        peer.memoryDao().insertAll(peerEntries(10))
        client().sync("peer", base)
        peer.memoryDao().insertAll(listOf(entry("peer-device", "extra", "late peer note", 500)))
        val result = client().sync("peer", base)
        assertEquals("only the new entry is pulled", 1, result.pulled)
    }

    // ── Failure handling ──────────────────────────────────────────────────────

    @Test fun aWrongTokenFailsCleanlyAndChangesNothing() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(2))
        peer.memoryDao().insertAll(peerEntries(2))
        val result = client("not-the-token").sync("peer", base)
        assertNotNull(result.lastError)
        assertTrue(result.lastError!!.contains("token", ignoreCase = true))
        assertEquals(2, ids(phone).size)
        assertEquals(2, ids(peer).size)
        assertTrue(cursors.pulls.isEmpty())
    }

    @Test fun anUnreachablePeerKeepsLocalNotesAndReportsTheError() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(2))
        val result = client().sync("gone", "http://127.0.0.1:1")
        assertNotNull(result.lastError)
        assertEquals(2, ids(phone).size)
        server.stop()
    }

    @Test fun onceThePeerIsBackNotesWrittenOfflineAreDelivered() = runBlocking {
        phone.memoryDao().insertAll(phoneEntries(2))
        assertNotNull(client().sync("down", "http://127.0.0.1:1").lastError)
        val result = client().sync("peer", base)
        assertNull(result.lastError)
        assertEquals(2, result.pushed)
    }

    @Test fun oneUnreadableEntryIsSkippedAndDoesNotStopTheSync() = runBlocking {
        // A peer that serves one good entry, one damaged one and one from a newer schema, then accepts pushes.
        val good = entry("peer-device", "ok", "readable", 1)
        val newer = good.toJson().put("v", 2).put("id", "entry-newer-schema-00001")
        val damaged = JSONObject().put("v", 1).put("id", "x")
        val posted = CopyOnWriteArrayList<String>()
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext(MemorySyncServer.SYNC_PATH) { ex ->
            val reply: JSONObject
            if (ex.requestMethod == "GET") {
                reply = JSONObject().put("device", "fake-peer").put("cursor", "3").put("more", false)
                    .put("entries", JSONArray().put(good.toJson()).put(damaged).put(newer))
            } else {
                posted += ex.requestBody.readBytes().decodeToString()
                reply = JSONObject().put("accepted", 1)
            }
            val bytes = reply.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        fake.start()
        try {
            phone.memoryDao().insertAll(phoneEntries(1))
            val result = client().sync("fake", "http://127.0.0.1:${fake.address.port}")
            assertNull("sync must not fail because of one bad entry", result.lastError)
            assertEquals(1, result.pulled)
            assertEquals(2, result.skipped)
            assertEquals("the phone's own note was still sent", 1, result.pushed)
            assertEquals(1, posted.size)
            assertEquals("cursor moved past the skipped entries", "3", cursors.pulls["http://127.0.0.1:${fake.address.port}"])
        } finally { fake.stop(0) }
    }

    @Test fun aPeerThatNeverAdvancesItsCursorIsStopped() = runBlocking {
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext(MemorySyncServer.SYNC_PATH) { ex ->
            val bytes = JSONObject().put("device", "stuck").put("cursor", "").put("more", true).put("entries", JSONArray())
                .toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        fake.start()
        try {
            val result = client().sync("stuck", "http://127.0.0.1:${fake.address.port}")
            assertNotNull("an endless pull loop must end with an error", result.lastError)
            assertTrue(result.lastError!!.contains("cursor"))
        } finally { fake.stop(0) }
    }

    // ── The embedded server ──────────────────────────────────────────────────

    private fun request(path: String, method: String = "GET", body: String? = null, headers: Map<String, String> = mapOf("X-Swarm-Auth" to token)): Pair<Int, JSONObject> {
        val b = Request.Builder().url("$base$path")
        headers.forEach { (k, v) -> b.header(k, v) }
        if (method == "POST") b.post((body ?: "").toRequestBody()) else b.get()
        http.newCall(b.build()).execute().use { return it.code to JSONObject(it.body!!.string()) }
    }

    @Test fun theServerDemandsTheToken() {
        assertEquals(401, request("/health", headers = emptyMap()).first)
        assertEquals(401, request("/health", headers = mapOf("X-Swarm-Auth" to "wrong")).first)
        assertEquals(200, request("/health").first)
        assertEquals("a Bearer header works too", 200, request("/health", headers = mapOf("Authorization" to "Bearer $token")).first)
    }

    @Test fun browsersAreRefused() {
        val (code, body) = request("/health", headers = mapOf("X-Swarm-Auth" to token, "Origin" to "http://evil.example"))
        assertEquals(403, code)
        assertTrue(body.getString("error").contains("browser"))
    }

    @Test fun unknownPathsAndBadCursorsAreRejected() {
        assertEquals(404, request("/api/nothing").first)
        assertEquals(400, request("${MemorySyncServer.SYNC_PATH}?cursor=not-a-number").first)
    }

    @Test fun aPushWithAnyInvalidEntryStoresNothing() {
        val good = entry("phone-1", "k", "fine", 1).toJson()
        val bad = JSONObject().put("v", 1).put("id", "no").put("device", "d").put("kind", "BAD KIND").put("ts", "nope").put("content", "x")
        val body = JSONObject().put("entries", JSONArray().put(good).put(bad)).toString()
        val (code, _) = request(MemorySyncServer.SYNC_PATH, "POST", body)
        assertEquals(400, code)
        assertTrue("all-or-nothing", ids(peer).isEmpty())
    }

    @Test fun duplicatePushesAreAcceptedOnlyOnce() {
        val body = JSONObject().put("entries", JSONArray().put(entry("phone-1", "k", "once", 1).toJson())).toString()
        assertEquals(1, request(MemorySyncServer.SYNC_PATH, "POST", body).second.getInt("accepted"))
        assertEquals(0, request(MemorySyncServer.SYNC_PATH, "POST", body).second.getInt("accepted"))
        assertEquals(1, ids(peer).size)
    }

    @Test fun postsNeedAContentLengthAndHaveASizeLimit() {
        fun raw(extra: String): String = Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("POST ${MemorySyncServer.SYNC_PATH} HTTP/1.1\r\nHost: x\r\nX-Swarm-Auth: $token\r\n$extra\r\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine()
        }
        assertTrue(raw("").contains("411"))
        assertTrue(raw("Content-Length: ${70 * 1024 * 1024}\r\n").contains("413"))
    }

    // ── Entry rules (same as the PC's Go server) ─────────────────────────────

    @Test fun entriesRoundTripThroughJson() {
        val e = entry("phone-1", "key", "content with \"quotes\" and\nnewlines", 1)
        assertEquals(e.copy(seq = 0), MemoryEntryEntity.fromJson(e.toJson())!!.copy(seq = 0))
    }

    @Test fun invalidEntriesAreRejected() {
        val ok = entry("phone-1", "k", "c", 1).toJson()
        fun mutate(f: (JSONObject) -> Unit) = MemoryEntryEntity.fromJson(JSONObject(ok.toString()).also(f))
        assertNotNull(mutate { })
        assertNull("wrong schema version", mutate { it.put("v", 2) })
        assertNull("bad id", mutate { it.put("id", "short") })
        assertNull("bad device", mutate { it.put("device", "has space") })
        assertNull("bad kind", mutate { it.put("kind", "Upper") })
        assertNull("bad timestamp", mutate { it.put("ts", "yesterday") })
        assertNull("key too long", mutate { it.put("key", "k".repeat(201)) })
        assertNull("content too big", mutate { it.put("content", "x".repeat(2 * 1024 * 1024 + 1)) })
    }

    @Test fun theNewestValueForAKeyWinsAndDeletesHideIt() = runBlocking {
        val dao = phone.memoryDao()
        val old = entry("phone-1", "color", "red", 1).copy(ts = "2026-10-01T10:00:00Z")
        val new = entry("phone-1", "color", "blue", 2).copy(ts = "2026-10-02T10:00:00Z")
        dao.insertAll(listOf(old, new))
        assertEquals(listOf("blue"), dao.observe("", "", 10).first().map { it.content })
        dao.insertAll(listOf(new.copy(id = "entry-tombstone-00001", ts = "2026-10-03T10:00:00Z", deleted = true)))
        assertTrue(dao.observe("", "", 10).first().isEmpty())
    }
}
