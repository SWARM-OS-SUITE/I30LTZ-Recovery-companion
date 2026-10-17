package com.example.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Keeps model files in one folder and downloads them safely.
 *
 * The rules that matter:
 *  - A download goes to `<name>.part` and resumes where it stopped (HTTP Range), so a dropped connection on
 *    a 2.6 GB file does not start over.
 *  - The finished file is hashed (SHA-256) *before* it gets its real name. The engine can only ever see a
 *    file that matched the published hash, or one the user explicitly verified after copying it in by hand.
 *  - Enough free space is checked up front, and a failed or cancelled download never deletes the bytes
 *    already fetched.
 */
class OfflineModelStore(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    sealed interface Status {
        data object Missing : Status
        data class Partial(val bytes: Long) : Status
        /** Right size but not yet hash-checked, e.g. copied in by hand. Needs [verify] before use. */
        data object Unverified : Status
        data object Ready : Status
    }

    enum class Phase { DOWNLOADING, VERIFYING }
    data class Progress(val phase: Phase, val doneBytes: Long, val totalBytes: Long) {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    private val busy = Mutex()

    fun file(model: OfflineModel) = File(dir, model.fileName)
    private fun part(model: OfflineModel) = File(dir, model.fileName + ".part")
    private fun marker(model: OfflineModel) = File(dir, model.fileName + ".sha256ok")

    fun status(model: OfflineModel): Status {
        val f = file(model)
        if (f.isFile && f.length() == model.sizeBytes) {
            return if (runCatching { marker(model).readText().trim() }.getOrNull() == model.sha256) Status.Ready else Status.Unverified
        }
        val p = part(model)
        return if (p.isFile && p.length() > 0) Status.Partial(p.length()) else Status.Missing
    }

    /** The installed, verified model file, or null. Only this is safe to hand to the engine. */
    fun readyFile(model: OfflineModel): File? = if (status(model) == Status.Ready) file(model) else null

    fun delete(model: OfflineModel) {
        file(model).delete(); part(model).delete(); marker(model).delete()
    }

    /** Hashes a file that was copied in by hand and, if it matches, marks it ready. */
    suspend fun verify(model: OfflineModel, onProgress: (Progress) -> Unit = {}): Result<File> = busy.withLockOrFail {
        catching {
            val f = file(model)
            if (!f.isFile || f.length() != model.sizeBytes) throw IOException("The file is missing or the wrong size.")
            val actual = sha256(f, model.sizeBytes) { onProgress(Progress(Phase.VERIFYING, it, model.sizeBytes)) }
            if (actual != model.sha256) throw IOException("Checksum does not match; this is not the expected model file.")
            marker(model).writeText(model.sha256)
            f
        }
    }

    /** Downloads (or resumes) and verifies [model]. Cancelling the calling coroutine keeps the partial bytes. */
    suspend fun download(model: OfflineModel, onProgress: (Progress) -> Unit): Result<File> = busy.withLockOrFail {
        catching {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                if (status(model) == Status.Ready) return@withContext file(model)
                val part = part(model)
                var have = if (part.isFile) part.length() else 0L
                if (have > model.sizeBytes) { part.delete(); have = 0 }

                val needed = model.sizeBytes - have + SAFETY_MARGIN
                val free = freeBytes(dir)
                if (free < needed) {
                    throw IOException("Not enough storage: needs ${mb(needed)} MB free, ${mb(free)} MB available.")
                }

                if (have < model.sizeBytes) fetch(model, part, have, onProgress)
                coroutineContext.ensureActive()

                val actual = sha256(part, model.sizeBytes) { onProgress(Progress(Phase.VERIFYING, it, model.sizeBytes)) }
                if (actual != model.sha256) {
                    part.delete()
                    throw IOException("The download was corrupted (checksum mismatch) and was discarded. Try again.")
                }
                val target = file(model)
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not save the model file.")
                marker(model).writeText(model.sha256)
                target
            }
        }
    }

    private suspend fun fetch(model: OfflineModel, part: File, have: Long, onProgress: (Progress) -> Unit) {
        val request = Request.Builder().url(model.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        client.newCall(request).execute().use { response ->
            var offset = have
            when (response.code) {
                206 -> {
                    val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (total != null && total != model.sizeBytes) throw IOException("The model on the server changed size; update the app.")
                }
                200 -> {
                    // The server ignored the Range header, so it is sending everything again from byte 0.
                    val length = response.body?.contentLength() ?: -1
                    if (length >= 0 && length != model.sizeBytes) throw IOException("The model on the server changed size; update the app.")
                    offset = 0
                }
                416 -> { part.delete(); throw IOException("The partial download was out of date and was discarded. Try again.") }
                else -> throw IOException("Download failed: HTTP ${response.code}.")
            }
            val body = response.body ?: throw IOException("Download failed: empty response.")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                val buffer = ByteArray(256 * 1024)
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        offset += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) {
                            lastReport = now
                            onProgress(Progress(Phase.DOWNLOADING, offset, model.sizeBytes))
                        }
                    }
                }
                onProgress(Progress(Phase.DOWNLOADING, offset, model.sizeBytes))
            }
            if (offset != model.sizeBytes) throw IOException("The connection dropped at ${mb(offset)} of ${mb(model.sizeBytes)} MB. Resume to continue.")
        }
    }

    private suspend fun sha256(file: File, total: Long, onBytes: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var done = 0L
        var lastReport = 0L
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > 200_000_000L) { lastReport = now; onBytes(done) }
            }
        }
        onBytes(total)
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> Mutex.withLockOrFail(block: suspend () -> Result<T>): Result<T> {
        if (!tryLock()) return Result.failure(IOException("Another model download is already running."))
        try { return block() } finally { unlock() }
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    /** Like runCatching, but a cancelled coroutine stays cancelled instead of turning into a "failed" result. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /** Room left over after the download so the phone does not run out of space entirely. */
        private const val SAFETY_MARGIN = 256L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
