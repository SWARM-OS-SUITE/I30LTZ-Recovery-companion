package com.example.data.phonenode

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's side of the I30LTZ node API (v1): pairing, capabilities, telemetry and a live SSE stream,
 * so another I30LTZ Companion can list this phone as a node. Everything a phone cannot honestly do
 * (processes, kill, terminal, scans) is reported as unavailable instead of being faked.
 *
 * Only paired devices get data. The pairing code is created by [PhoneNodeAuth.newCode] from the phone's own
 * screen; no request can create one. Listens only on the addresses given to [listenOn].
 */
class PhoneNodeServer(
    private val auth: PhoneNodeAuth,
    private val telemetry: PhoneTelemetry,
    private val version: String,
    private val port: Int,
    private val maxConnections: Int = 16,
    private val maxStreams: Int = 4,
    private val streamEveryMs: Long = 2000,
    /** Short, secret-free lines about who connected or was turned away (shown on the phone). */
    private val log: (String) -> Unit = {},
) {
    private class Listener(val socket: ServerSocket, val thread: Thread)

    private val listeners = LinkedHashMap<String, Listener>()
    private val active = AtomicInteger(0)
    private val streams = AtomicInteger(0)
    @Volatile private var stopped = false
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "phone-node").apply { isDaemon = true } }
    private val rejectedAt = HashMap<String, Long>()

    /** Address to port currently bound. */
    fun bound(): Map<String, Int> = synchronized(listeners) { listeners.mapValues { it.value.socket.localPort } }

    /** Makes the set of listening addresses equal [desired]. Returns the addresses that could not be opened, with why. */
    fun listenOn(desired: Set<String>): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        synchronized(listeners) {
            if (stopped) return errors
            for (addr in listeners.keys.toList().filter { it !in desired }) listeners.remove(addr)?.socket?.close()
            for (addr in desired.filter { it !in listeners }) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName(addr), port))
                    val t = Thread({ acceptLoop(ss) }, "phone-node-accept-$addr").apply { isDaemon = true; start() }
                    listeners[addr] = Listener(ss, t)
                } catch (e: IOException) {
                    errors[addr] = e.message ?: "cannot listen"
                }
            }
        }
        return errors
    }

    fun stop() {
        stopped = true
        synchronized(listeners) { listeners.values.forEach { runCatching { it.socket.close() } }; listeners.clear() }
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped && !ss.isClosed) {
            val client = try { ss.accept() } catch (e: IOException) { return }
            if (active.incrementAndGet() > maxConnections) {
                active.decrementAndGet()
                runCatching { MiniHttp.writeJson(client.getOutputStream(), 503, err("too many connections")) }
                runCatching { client.close() }
                continue
            }
            try {
                pool.execute {
                    try { handle(client) } finally { active.decrementAndGet(); runCatching { client.close() } }
                }
            } catch (e: Exception) { // pool shut down
                active.decrementAndGet(); runCatching { client.close() }
            }
        }
    }

    private fun err(msg: String) = JSONObject().put("error", msg).toString()

    private fun handle(c: Socket) {
        c.soTimeout = 10_000
        val out = c.getOutputStream()
        val remote = (c.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
        try {
            val req = MiniHttp.readRequest(BufferedInputStream(c.getInputStream()))
            route(req, out, remote)
        } catch (e: HttpError) {
            runCatching { MiniHttp.writeJson(out, e.status, err(e.message)) }
        } catch (e: IOException) {
            // timeout or reset: nothing to say to a peer that has gone
        } catch (e: Exception) {
            runCatching { MiniHttp.writeJson(out, 500, err("internal error")) }
        }
    }

    private fun route(req: HttpRequest, out: OutputStream, remote: String) {
        if (req.method != "GET" && req.method != "POST") throw HttpError(405, "method not allowed")
        if (req.method == "POST" && req.path == "/v1/pair") return pair(req, out, remote)

        if (!auth.verify(req.bearer())) {
            noteRejected(remote, req.path)
            return MiniHttp.writeJson(out, 401, err("missing or unknown token; pair this device first"), mapOf("WWW-Authenticate" to "Bearer realm=\"i30ltz-phone\""))
        }
        when {
            req.method == "GET" && req.path == "/v1/capabilities" -> MiniHttp.writeJson(out, 200, capabilities())
            req.method == "GET" && req.path == "/v1/telemetry" -> MiniHttp.writeJson(out, 200, telemetryJson(telemetry.sample()))
            req.method == "GET" && req.path == "/v1/events" -> events(out)
            UNAVAILABLE.any { req.path == it || req.path.startsWith("$it/") } -> MiniHttp.writeJson(out, 404, err(
                "not available on a phone: Android does not let an app list or stop other apps, open a shell or scan other apps. Add a PC node for that."))
            else -> MiniHttp.writeJson(out, 404, err("not found"))
        }
    }

    private fun pair(req: HttpRequest, out: OutputStream, remote: String) {
        val body = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (e: Exception) {
            throw HttpError(400, "send JSON {\"code\":\"XXXX-XXXX\",\"label\":\"my phone\"}")
        }
        val label = body.optString("label")
        when (val r = auth.pair(body.optString("code"), label)) {
            is PhoneNodeAuth.Pairing.Ok -> {
                log("paired \"${label.take(64).ifBlank { "device" }}\" from $remote")
                MiniHttp.writeJson(out, 200, JSONObject().put("token", r.token).put("hostname", telemetry.sample().hostname).put("api", API).toString())
            }
            PhoneNodeAuth.Pairing.Locked -> {
                log("pairing attempt from $remote refused: too many wrong codes (locked for a minute)")
                MiniHttp.writeJson(out, 429, err("too many wrong codes; pairing is locked for a minute"), mapOf("Retry-After" to "60"))
            }
            PhoneNodeAuth.Pairing.BadCode -> {
                log("pairing attempt from $remote with a wrong or expired code")
                MiniHttp.writeJson(out, 401, err("pairing code is wrong or expired"))
            }
        }
    }

    /** Logs a turned-away request at most once a minute per caller and path. */
    private fun noteRejected(remote: String, path: String) {
        val key = "$remote $path"
        val now = System.currentTimeMillis()
        val fresh = synchronized(rejectedAt) {
            val last = rejectedAt[key]
            if (last == null || now - last > 60_000) {
                if (rejectedAt.size > 500) rejectedAt.clear()
                rejectedAt[key] = now; true
            } else false
        }
        if (fresh) log("$remote asked for $path without a valid token (pair it first)")
    }

    private fun capabilities(): String {
        val s = telemetry.sample()
        return JSONObject()
            .put("agent", "i30ltz-phone").put("version", version).put("api", API)
            .put("hostname", s.hostname).put("os", s.os)
            .put("features", JSONObject().put("telemetry", true).put("events", true).put("processes", false).put("kill", false)
                .put("scan", false).put("optimize", false).put("terminal", false).put("jobs", false))
            .toString()
    }

    private fun telemetryJson(s: PhoneSample): String {
        val j = JSONObject()
            .put("hostname", s.hostname).put("os", s.os)
            .put("cpu_percent", -1) // Android does not let an app read CPU load
            .put("ram_percent", if (s.ramTotalMb > 0) Math.round(s.ramUsedMb * 1000.0 / s.ramTotalMb) / 10.0 else -1)
            .put("ram_total_mb", s.ramTotalMb).put("ram_used_mb", s.ramUsedMb)
            .put("storage_total_gb", Math.round(s.storageTotalGb * 10) / 10.0).put("storage_free_gb", Math.round(s.storageFreeGb * 10) / 10.0)
            .put("load1", -1).put("uptime_seconds", s.uptimeSeconds)
            .put("battery_percent", s.batteryPercent).put("charging", s.charging)
            .put("time", Instant.now().toString())
        if (s.tempC != null) j.put("temp_c", s.tempC)
        return j.toString()
    }

    private fun events(out: OutputStream) {
        if (streams.incrementAndGet() > maxStreams) {
            streams.decrementAndGet()
            return MiniHttp.writeJson(out, 503, err("too many live streams"))
        }
        try {
            MiniHttp.writeHead(out, 200, linkedMapOf("Content-Type" to "text/event-stream", "Cache-Control" to "no-cache", "X-Accel-Buffering" to "no"))
            var sinceBeat = 0L
            while (!stopped) {
                out.write("event: telemetry\ndata: ${telemetryJson(telemetry.sample())}\n\n".toByteArray())
                out.flush()
                Thread.sleep(streamEveryMs)
                sinceBeat += streamEveryMs
                if (sinceBeat >= 15_000) { out.write(": ping\n\n".toByteArray()); out.flush(); sinceBeat = 0 }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            streams.decrementAndGet()
        }
    }

    companion object {
        const val API = 1
        const val DEFAULT_PORT = 7437
        private val UNAVAILABLE = listOf("/v1/processes", "/v1/terminal", "/v1/scan", "/v1/quarantine")
    }
}
