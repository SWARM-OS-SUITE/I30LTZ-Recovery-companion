package com.example.data.nodes

import org.json.JSONArray
import org.json.JSONObject

data class ScanFinding(val path: String, val signature: String)

/** What the node's scanner is and how fresh its signatures are. [ageDays] is -1 when unknown. */
data class ScanInfo(
    val available: Boolean, val version: String, val signatureDb: String,
    val ageDays: Int, val stale: Boolean, val runningId: String, val reason: String,
) {
    companion object {
        fun fromJson(o: JSONObject) = ScanInfo(
            available = o.optBoolean("available"), version = o.optString("version"), signatureDb = o.optString("signature_version"),
            ageDays = o.optInt("signature_age_days", -1), stale = o.optBoolean("signatures_stale"),
            runningId = o.optString("running_scan"), reason = o.optString("reason"),
        )
    }
}

/** One scan, running or finished. */
data class ScanView(
    val id: String, val state: String, val scope: String, val started: String,
    val scanned: Int, val skipped: Int, val current: String, val errors: Int,
    val errorSamples: List<String>, val findings: List<ScanFinding>, val findingsTruncated: Boolean,
    val message: String, val engineVersion: String,
) {
    val running: Boolean get() = state == "running"

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"

        fun fromJson(o: JSONObject): ScanView {
            fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            val f = o.optJSONArray("findings")
            return ScanView(
                id = o.getString("id"), state = o.optString("state"), scope = o.optString("scope"), started = o.optString("started"),
                scanned = o.optInt("scanned_files"), skipped = o.optInt("skipped_files"), current = o.optString("current"),
                errors = o.optInt("errors"), errorSamples = strings(o.optJSONArray("error_samples")),
                findings = if (f == null) emptyList() else (0 until f.length()).map { f.getJSONObject(it) }
                    .map { ScanFinding(it.getString("path"), it.optString("signature")) },
                findingsTruncated = o.optBoolean("findings_truncated"), message = o.optString("message"),
                engineVersion = o.optString("engine_version"),
            )
        }
    }
}

data class QuarantinedFile(val id: String, val original: String, val signature: String, val sha256: String, val size: Long, val at: String) {
    companion object {
        fun fromJson(o: JSONObject) = QuarantinedFile(
            id = o.getString("id"), original = o.optString("original_path"), signature = o.optString("signature"),
            sha256 = o.optString("sha256"), size = o.optLong("size"), at = o.optString("quarantined_at"),
        )
    }
}

/** Everything the Security screen shows for one node. */
data class NodeSecurity(
    val info: ScanInfo? = null,
    val scan: ScanView? = null,
    val quarantine: List<QuarantinedFile> = emptyList(),
    /** Last problem or result sentence from an action; empty when there is nothing to say. */
    val message: String = "",
    val busy: Boolean = false,
)
