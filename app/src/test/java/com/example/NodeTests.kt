package com.example

import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.QuarantinedFile
import com.example.data.nodes.ScanInfo
import com.example.data.nodes.ScanView
import com.example.data.nodes.SseParser
import com.example.data.repository.RemoteTelemetry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

private fun parseSse(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    SseParser.read(BufferedReader(StringReader(text))) { event, data -> out += event to data }
    return out
}

class SseParserTest {
    @Test fun namedAndDefaultEvents() {
        val got = parseSse("event: telemetry\ndata: {\"a\":1}\n\ndata: plain\n\n")
        assertEquals(listOf("telemetry" to "{\"a\":1}", "message" to "plain"), got)
    }

    @Test fun commentsAndKeepAlivesAreIgnored() {
        assertEquals(listOf("message" to "x"), parseSse(": ping\n\n: another\ndata: x\n\n"))
    }

    @Test fun multiLineDataIsJoinedWithNewlines() {
        assertEquals(listOf("message" to "one\ntwo"), parseSse("data: one\ndata: two\n\n"))
    }

    @Test fun spaceAfterColonIsOptionalAndOnlyOneIsStripped() {
        assertEquals(listOf("message" to "a", "message" to " b"), parseSse("data:a\n\ndata:  b\n\n"))
    }

    @Test fun eventNameDoesNotLeakIntoTheNextEvent() {
        assertEquals(listOf("telemetry" to "1", "message" to "2"), parseSse("event: telemetry\ndata: 1\n\ndata: 2\n\n"))
    }

    @Test fun eventWithoutDataIsNotDelivered() {
        assertTrue(parseSse("event: telemetry\n\n").isEmpty())
    }

    @Test fun halfReceivedEventAtEndOfStreamIsDropped() {
        assertEquals(listOf("message" to "done"), parseSse("data: done\n\ndata: cut off"))
    }

    @Test fun windowsLineEndingsWork() {
        assertEquals(listOf("message" to "x"), parseSse("data: x\r\n\r\n"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NodeModelTest {
    @Test fun nodeSurvivesJsonRoundTripIncludingToken() {
        val n = Node(name = "CachyOS", os = "CachyOS", backend = NodeBackend.LUCYSERVER, host = "100.82.1.19", port = 5000, token = "abc", liveTelemetry = false)
        val back = Node.listFromJson(Node.listToJson(listOf(n))).single()
        assertEquals(n, back)
    }

    @Test fun corruptOrUnknownEntriesAreSkippedNotFatal() {
        assertEquals(emptyList<Node>(), Node.listFromJson("not json"))
        val good = Node(name = "a", os = "x", backend = NodeBackend.AGENT, host = "h", port = 7437).toJson()
        val bad = JSONObject(good.toString()).put("backend", "FUTURE_KIND")
        assertEquals(1, Node.listFromJson("[${bad},${good}]").size)
    }

    @Test fun validationRejectsUrlsPortsAndBlankNames() {
        assertNull(Node.validate("n", "100.82.1.19", 7437))
        assertNull(Node.validate("n", "laptop.tail1234.ts.net", 1))
        assertNotNull(Node.validate("", "h", 1))
        assertNotNull(Node.validate("n", "http://h", 7437))
        assertNotNull(Node.validate("n", "h:7437", 7437))
        assertNotNull(Node.validate("n", "h/path", 7437))
        assertNotNull(Node.validate("n", "h", 0))
        assertNotNull(Node.validate("n", "h", 65536))
        assertNotNull(Node.validate("n", "h", null))
    }

    @Test fun onlyConfiguredStreamingBackendsWantALiveStream() {
        fun node(b: NodeBackend, token: String = "t", live: Boolean = true) = Node(name = "n", os = "o", backend = b, host = "h", port = 1, token = token, liveTelemetry = live)
        assertTrue(node(NodeBackend.AGENT).wantsLiveStream)
        assertTrue(node(NodeBackend.SSE_BRIDGE).wantsLiveStream)
        assertEquals(false, node(NodeBackend.LUCYSERVER).wantsLiveStream) // no stream exists
        assertEquals(false, node(NodeBackend.AGENT, token = "").wantsLiveStream) // not paired yet
        assertEquals(false, node(NodeBackend.AGENT, live = false).wantsLiveStream)
    }

    @Test fun agentTelemetryKeepsUnknownCpuAsMinusOne() {
        val t = RemoteTelemetry.parseAgent(JSONObject("""{"cpu_percent":-1,"ram_percent":52.4,"storage_free_gb":245.2}"""))
        assertEquals(-1, t.cpu); assertEquals(52, t.ram); assertEquals(245, t.storage)
        val full = RemoteTelemetry.parseAgent(JSONObject("""{"cpu_percent":23.6,"ram_percent":99.5}"""))
        assertEquals(24, full.cpu); assertEquals(100, full.ram); assertEquals(-1, full.storage)
        assertTrue(runCatching { RemoteTelemetry.parseAgent(JSONObject("{}")) }.isFailure)
        assertTrue(runCatching { RemoteTelemetry.parseAgent(JSONObject("""{"ram_percent":140}""")) }.isFailure)
    }

    @Test fun bridgeTelemetryPicksRootDiskAndToleratesMissingParts() {
        val t = RemoteTelemetry.parseBridge(JSONObject("""{"cpu_pct":12.4,"mem_pct":40.0,
            "disks":[{"mount":"/boot","free_gb":0.3},{"mount":"/","free_gb":88.7}]}"""))
        assertEquals(12, t.cpu); assertEquals(40, t.ram); assertEquals(88, t.storage)
        val noDisks = RemoteTelemetry.parseBridge(JSONObject("""{"mem_pct":10}"""))
        assertEquals(-1, noDisks.cpu); assertEquals(-1, noDisks.storage)
        assertTrue(runCatching { RemoteTelemetry.parseBridge(JSONObject("""{"cpu_pct":5}""")) }.isFailure)
    }

    @Test fun scanResultsParseIncludingFindingsErrorsAndMissingFields() {
        val v = ScanView.fromJson(JSONObject("""{"id":"20261007-231455-ab12cd","state":"done","scope":"path","started":"2026-10-07T23:14:55Z",
            "scanned_files":120,"skipped_files":3,"errors":2,"error_samples":["/root/x: Access denied","/y: Access denied"],
            "findings":[{"path":"/tmp/eicar.com","signature":"Eicar-Test-Signature"}],"findings_truncated":true,"engine_version":"1.4.3"}"""))
        assertEquals(120, v.scanned); assertEquals(2, v.errors); assertEquals(2, v.errorSamples.size)
        assertEquals("/tmp/eicar.com", v.findings.single().path); assertTrue(v.findingsTruncated); assertEquals(false, v.running)
        val minimal = ScanView.fromJson(JSONObject("""{"id":"x","state":"running"}"""))
        assertTrue(minimal.running); assertEquals(0, minimal.scanned); assertTrue(minimal.findings.isEmpty())
    }

    @Test fun scannerInfoKeepsUnknownSignatureAgeUnknown() {
        val ok = ScanInfo.fromJson(JSONObject("""{"available":true,"version":"1.4.3","signature_version":"28146","signature_age_days":9,"signatures_stale":true}"""))
        assertTrue(ok.available); assertTrue(ok.stale); assertEquals(9, ok.ageDays)
        val unknown = ScanInfo.fromJson(JSONObject("""{"available":true,"version":"1.4.3"}"""))
        assertEquals(-1, unknown.ageDays); assertEquals(false, unknown.stale) // unknown is not "fresh" and not "stale"
        val missing = ScanInfo.fromJson(JSONObject("""{"available":false,"reason":"no scanner"}"""))
        assertEquals(false, missing.available); assertEquals("no scanner", missing.reason)
    }

    @Test fun quarantinedFilesParse() {
        val q = QuarantinedFile.fromJson(JSONObject("""{"id":"20261007-231455-ab12cd","original_path":"/tmp/a","signature":"S","sha256":"ff","size":68,"quarantined_at":"t"}"""))
        assertEquals("/tmp/a", q.original); assertEquals(68L, q.size)
    }
}
