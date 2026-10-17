package com.example.data.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Drives the real downloader against a local HTTP server that can misbehave on purpose. */
class OfflineModelStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val payload = ByteArray(3 * 1024 * 1024 + 123) { (it * 31 + it / 7).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val lastRange = AtomicReference<String?>(null)
    @Volatile private var honourRange = true
    @Volatile private var served: ByteArray = payload
    /** If set, the server promises the full length but hangs up after this many bytes. */
    @Volatile private var dropAfter: Int = -1
    /** If > 0, the server writes in small slices with a pause, so a test can cancel mid-download. */
    @Volatile private var slowChunk: Int = 0

    private lateinit var model: OfflineModel
    private lateinit var dir: File

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.bin") { ex ->
            requests.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range")
            lastRange.set(range)
            val data = served
            var from = 0
            var status = 200
            if (range != null && honourRange) {
                from = range.removePrefix("bytes=").substringBefore('-').toInt()
                if (from >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                status = 206
                ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            }
            val length = data.size - from
            ex.sendResponseHeaders(status, length.toLong())
            val out = ex.responseBody
            try {
                var sent = 0
                val limit = if (dropAfter >= 0) minOf(dropAfter, length) else length
                while (sent < limit) {
                    val n = minOf(if (slowChunk > 0) slowChunk else 64 * 1024, limit - sent)
                    out.write(data, from + sent, n)
                    out.flush()
                    sent += n
                    if (slowChunk > 0) Thread.sleep(25)
                }
            } finally {
                ex.close() // closing early (dropAfter) leaves the client with a short body
            }
        }
        server.start()
        dir = temp.newFolder("models")
        model = OfflineModel(
            id = "test", displayName = "Test", tagline = "", fileName = "test.litertlm",
            url = "http://127.0.0.1:${server.address.port}/m.bin", sizeBytes = payload.size.toLong(), sha256 = sha(payload),
            minRamBytes = 1, comfortableRamBytes = 2, license = "test", contextTokens = 1024, usesQwenThinkSwitch = false,
        )
    }

    @After fun stop() = server.stop(0)

    private fun store(free: Long = Long.MAX_VALUE / 2) = OfflineModelStore(dir, freeBytes = { free })

    @Test fun downloadsAndVerifiesAFreshModel() = runBlocking {
        val s = store()
        assertEquals(OfflineModelStore.Status.Missing, s.status(model))
        val seen = mutableListOf<OfflineModelStore.Progress>()
        val file = s.download(model) { seen += it }.getOrThrow()
        assertArrayEquals(payload, file.readBytes())
        assertEquals(OfflineModelStore.Status.Ready, s.status(model))
        assertEquals(file, s.readyFile(model))
        assertFalse("no .part left behind", File(dir, "test.litertlm.part").exists())
        assertTrue(seen.any { it.phase == OfflineModelStore.Phase.DOWNLOADING })
        assertTrue(seen.any { it.phase == OfflineModelStore.Phase.VERIFYING })
        assertEquals(1f, seen.last().fraction, 0.0001f)
    }

    @Test fun anAlreadyInstalledModelIsNotDownloadedAgain() = runBlocking {
        val s = store()
        s.download(model) {}.getOrThrow()
        val before = requests.get()
        s.download(model) {}.getOrThrow()
        assertEquals(before, requests.get())
    }

    @Test fun resumesFromAPartialFileWithARangeRequest() = runBlocking {
        val cut = payload.size * 2 / 5
        File(dir, "test.litertlm.part").writeBytes(payload.copyOf(cut))
        val s = store()
        assertEquals(OfflineModelStore.Status.Partial(cut.toLong()), s.status(model))
        val file = s.download(model) {}.getOrThrow()
        assertEquals("bytes=$cut-", lastRange.get())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun aServerThatIgnoresRangeStillProducesACorrectFile() = runBlocking {
        honourRange = false
        File(dir, "test.litertlm.part").writeBytes(payload.copyOf(1000))
        val file = store().download(model) {}.getOrThrow()
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun aCorruptDownloadIsDiscardedAndNeverBecomesReady() = runBlocking {
        served = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val s = store()
        val result = s.download(model) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("checksum", ignoreCase = true))
        assertFalse(File(dir, "test.litertlm").exists())
        assertFalse(File(dir, "test.litertlm.part").exists())
        assertNull(s.readyFile(model))
    }

    @Test fun aDroppedConnectionKeepsTheBytesAndTheNextTryResumes() = runBlocking {
        dropAfter = payload.size / 3
        val s = store()
        val first = s.download(model) {}
        assertTrue(first.isFailure)
        val partial = (s.status(model) as OfflineModelStore.Status.Partial).bytes
        assertTrue("some bytes kept", partial in 1 until payload.size)
        assertNull(s.readyFile(model))

        dropAfter = -1
        val file = s.download(model) {}.getOrThrow()
        assertEquals("bytes=$partial-", lastRange.get())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun notEnoughStorageFailsBeforeAnyNetworkTraffic() = runBlocking {
        val result = store(free = 1024 * 1024).download(model) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("storage", ignoreCase = true))
        assertEquals(0, requests.get())
    }

    @Test fun aModelThatChangedSizeOnTheServerIsRefused() = runBlocking {
        served = payload.copyOf(payload.size - 5)
        val result = store().download(model) {}
        assertTrue(result.isFailure)
        assertNull(store().readyFile(model))
    }

    @Test fun aHandCopiedFileMustBeVerifiedBeforeItCanBeUsed() = runBlocking {
        val s = store()
        File(dir, "test.litertlm").writeBytes(payload)
        assertEquals(OfflineModelStore.Status.Unverified, s.status(model))
        assertNull("unverified files are never handed to the engine", s.readyFile(model))
        s.verify(model).getOrThrow()
        assertEquals(OfflineModelStore.Status.Ready, s.status(model))
    }

    @Test fun aWrongFileOfTheRightSizeFailsVerification() = runBlocking {
        val s = store()
        File(dir, "test.litertlm").writeBytes(ByteArray(payload.size) { 7 })
        assertTrue(s.verify(model).isFailure)
        assertNull(s.readyFile(model))
    }

    @Test fun cancellingKeepsTheBytesForALaterResume() = runBlocking {
        slowChunk = 16 * 1024
        val s = store()
        val firstProgress = AtomicReference<Long?>(null)
        val job = async(Dispatchers.IO) {
            s.download(model) { if (it.doneBytes > 100_000) firstProgress.compareAndSet(null, it.doneBytes) }
        }
        withTimeout(20_000) { while (firstProgress.get() == null) kotlinx.coroutines.delay(20) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(File(dir, "test.litertlm").exists())
        assertTrue("partial kept", File(dir, "test.litertlm.part").length() > 0)

        slowChunk = 0
        val file = s.download(model) {}.getOrThrow()
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun deleteRemovesEveryTraceOfAModel() = runBlocking {
        val s = store()
        s.download(model) {}.getOrThrow()
        s.delete(model)
        assertEquals(OfflineModelStore.Status.Missing, s.status(model))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
