package com.example.data.repository

import org.json.JSONObject
import kotlin.math.roundToInt

internal data class RemoteTelemetry(val cpu: Int, val ram: Int, val storage: Int) {
    companion object {
        fun parse(json: JSONObject): RemoteTelemetry {
            val cpu = json.getDouble("cpuUsagePercentage")
            val total = json.getDouble("totalMemoryMb")
            val used = json.getDouble("usedMemoryMb")
            require(cpu.isFinite() && cpu in 0.0..100.0 && total.isFinite() && total > 0 && used.isFinite() && used in 0.0..total) { "Invalid telemetry response" }
            val storage = json.optDouble("storageFreeGb", Double.NaN)
            return RemoteTelemetry(cpu.roundToInt(), (used / total * 100).roundToInt(),
                if (storage.isFinite() && storage >= 0) storage.toInt() else -1)
        }

        /** `i30ltz-node` /v1/telemetry and its /v1/events payloads. CPU is -1 until the agent has two samples. */
        fun parseAgent(json: JSONObject): RemoteTelemetry {
            val ram = json.optDouble("ram_percent", -1.0)
            require(ram.isFinite() && ram in 0.0..100.0) { "Invalid telemetry response" }
            val cpu = json.optDouble("cpu_percent", -1.0)
            val free = json.optDouble("storage_free_gb", -1.0)
            return RemoteTelemetry(
                if (cpu.isFinite() && cpu in 0.0..100.0) cpu.roundToInt() else -1,
                ram.roundToInt(),
                if (free.isFinite() && free >= 0) free.toInt() else -1)
        }

        /** Recovery OS bridge: /api/status and the /sse/telemetry stream (`cpu_pct`, `mem_pct`, `disks`). */
        fun parseBridge(json: JSONObject): RemoteTelemetry {
            val cpu = json.optDouble("cpu_pct", Double.NaN)
            val mem = json.optDouble("mem_pct", Double.NaN)
            require(mem.isFinite() && mem in 0.0..100.0) { "Invalid telemetry response" }
            val disks = json.optJSONArray("disks")
            var free = -1
            if (disks != null && disks.length() > 0) {
                val rows = (0 until disks.length()).map { disks.getJSONObject(it) }
                val row = rows.firstOrNull { it.optString("mount") == "/" } ?: rows.first()
                val gb = row.optDouble("free_gb", Double.NaN)
                if (gb.isFinite() && gb >= 0) free = gb.toInt()
            }
            return RemoteTelemetry(if (cpu.isFinite() && cpu in 0.0..100.0) cpu.roundToInt() else -1, mem.roundToInt(), free)
        }
    }
}
