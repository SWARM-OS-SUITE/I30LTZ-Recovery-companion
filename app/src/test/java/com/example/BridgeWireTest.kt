package com.example

import com.example.data.bridge.BridgeWire
import com.example.data.bridge.ChatPiece
import com.example.data.bridge.Decision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeWireTest {
    // --- command_pending events ---------------------------------------------------------------------------

    @Test fun parsesACommandTheBridgeSent() {
        val c = BridgeWire.commandFromJson(
            """{"id":"ab12","command":"systemctl status ssh","description":"check ssh","source":"ai","dangerous":true,"submitted_at":"2026-10-09T20:00:00","status":"pending"}""")
        assertNotNull(c)
        assertEquals("ab12", c!!.id)
        assertEquals("systemctl status ssh", c.command)
        assertEquals("check ssh", c.description)
        assertEquals("ai", c.source)
        assertTrue(c.dangerous)
        assertEquals("2026-10-09T20:00:00", c.submittedAt)
    }

    @Test fun missingFieldsTakeSafeDefaults() {
        val c = BridgeWire.commandFromJson("""{"id":"x","command":"ls"}""")!!
        assertEquals("", c.description)
        assertEquals("android", c.source)
        assertTrue("no danger flag means fail closed: the extra confirmation applies", c.dangerous)
        assertFalse(BridgeWire.commandFromJson("""{"id":"y","command":"ls","dangerous":false}""")!!.dangerous)
    }

    @Test fun unusableCommandsAreDropped() {
        assertNull(BridgeWire.commandFromJson("""{"command":"ls"}"""))
        assertNull(BridgeWire.commandFromJson("""{"id":"x"}"""))
        assertNull(BridgeWire.commandFromJson("""{"id":"x","command":"   "}"""))
        assertNull(BridgeWire.commandFromJson("not json"))
        assertNull(BridgeWire.commandFromJson(""))
    }

    // --- request bodies: field names must match sse_bridge.py exactly -----------------------------------

    @Test fun submitBodyUsesTheBridgesFieldNames() {
        val o = JSONObject(BridgeWire.submitBody("rm -rf /tmp/x", "clean up", true))
        assertEquals("rm -rf /tmp/x", o.getString("command"))
        assertEquals("clean up", o.getString("description"))
        assertEquals("android", o.getString("source"))
        assertTrue(o.getBoolean("dangerous"))
        assertEquals(4, o.length())
    }

    @Test fun decideBodyUsesCmdId() {
        val o = JSONObject(BridgeWire.decideBody("ab12", false))
        assertEquals("ab12", o.getString("cmd_id"))
        assertFalse(o.getBoolean("approved"))
        assertEquals(2, o.length())
    }

    @Test fun chatBodyCarriesTheTelemetryChoice() {
        val on = JSONObject(BridgeWire.chatBody("hi", true))
        assertEquals("hi", on.getString("message")); assertTrue(on.getBoolean("include_telemetry"))
        assertFalse(JSONObject(BridgeWire.chatBody("hi", false)).getBoolean("include_telemetry"))
    }

    @Test fun quotesAndNewlinesSurviveTheTripThroughJson() {
        val tricky = "echo \"a\"\n\tb \\ c ü 🙂"
        assertEquals(tricky, JSONObject(BridgeWire.submitBody(tricky, "", false)).getString("command"))
        assertEquals(tricky, JSONObject(BridgeWire.chatBody(tricky, true)).getString("message"))
    }

    // --- chat stream ------------------------------------------------------------------------------------

    @Test fun chatTokensDoneAndNoise() {
        assertEquals(ChatPiece.Token("Hel"), BridgeWire.chatPiece("""{"token":"Hel"}"""))
        assertEquals(ChatPiece.Token(" lo\n"), BridgeWire.chatPiece("""{"token":" lo\n"}"""))
        assertEquals(ChatPiece.Done, BridgeWire.chatPiece("""{"done": true}"""))
        assertEquals(ChatPiece.Done, BridgeWire.chatPiece("""{"token":"x","done":true}"""))
        assertEquals(ChatPiece.Ignore, BridgeWire.chatPiece("""{"token":""}"""))
        assertEquals(ChatPiece.Ignore, BridgeWire.chatPiece("""{"model":"qwen","created_at":"t"}"""))
        assertEquals(ChatPiece.Ignore, BridgeWire.chatPiece("""{"done": false}"""))
        assertEquals(ChatPiece.Ignore, BridgeWire.chatPiece("   "))
        assertEquals(ChatPiece.Ignore, BridgeWire.chatPiece(""))
    }

    @Test fun otherReplyShapesStillShowTheirText() {
        assertEquals(ChatPiece.Token("hi"), BridgeWire.chatPiece("""{"content":"hi"}"""))
        assertEquals(ChatPiece.Token("hi"), BridgeWire.chatPiece("""{"response":"hi"}"""))
    }

    @Test fun plainTextAndBrokenJsonAreShownAsText() {
        assertEquals(ChatPiece.Token("plain words"), BridgeWire.chatPiece("plain words"))
        assertEquals(ChatPiece.Token("{not json"), BridgeWire.chatPiece("{not json"))
    }

    // --- decision replies -------------------------------------------------------------------------------

    @Test fun executedKeepsExitCodeAndOutput() {
        val ok = BridgeWire.decision(200, """{"cmd_id":"a","status":"executed","returncode":0,"stdout":"hi\n","stderr":""}""")
        assertTrue(ok is Decision.Executed)
        ok as Decision.Executed
        assertEquals(0, ok.returnCode); assertEquals("hi\n", ok.stdout); assertTrue(ok.succeeded)

        val bad = BridgeWire.decision(200, """{"cmd_id":"a","status":"executed","returncode":2,"stdout":"","stderr":"no such file"}""") as Decision.Executed
        assertEquals(2, bad.returnCode); assertFalse(bad.succeeded); assertEquals("no such file", bad.stderr)
    }

    @Test fun anUnknownExitCodeIsNeverReportedAsSuccess() {
        val d = BridgeWire.decision(200, """{"cmd_id":"a","status":"executed","returncode":null,"stdout":"","stderr":""}""") as Decision.Executed
        assertNull(d.returnCode)
        assertFalse(d.succeeded)
    }

    @Test fun deniedTimeoutAndError() {
        assertEquals(Decision.Denied, BridgeWire.decision(200, """{"cmd_id":"a","status":"denied"}"""))
        assertEquals(Decision.Timeout, BridgeWire.decision(200, """{"cmd_id":"a","status":"timeout"}"""))
        assertEquals(Decision.Failed("boom"), BridgeWire.decision(200, """{"cmd_id":"a","status":"error","error":"boom"}"""))
    }

    @Test fun alreadyDecidedIsRecognisedSoTheListCanDropIt() {
        val d = BridgeWire.decision(404, """{"detail":"Command not found or already decided"}""") as Decision.Failed
        assertTrue(d.gone)
    }

    @Test fun badTokenAndServerErrorsAreFailuresThatKeepTheCommandListed() {
        val auth = BridgeWire.decision(401, """{"detail":"Unauthorized"}""") as Decision.Failed
        assertFalse(auth.gone); assertTrue(auth.message.contains("token"))
        val oops = BridgeWire.decision(500, """{"detail":"Internal"}""") as Decision.Failed
        assertFalse(oops.gone); assertEquals("Internal", oops.message)
    }

    @Test fun anUnreadableOrUnexpectedReplyIsNeverTreatedAsExecuted() {
        assertTrue(BridgeWire.decision(200, "<html>proxy page</html>") is Decision.Failed)
        assertTrue(BridgeWire.decision(200, """{"status":"surprise"}""") is Decision.Failed)
        assertTrue(BridgeWire.decision(200, "{}") is Decision.Failed)
    }

    @Test fun submittedIdAndErrorText() {
        assertEquals("ab12", BridgeWire.submittedId("""{"cmd_id":"ab12","status":"pending"}"""))
        assertNull(BridgeWire.submittedId("""{"status":"pending"}"""))
        assertNull(BridgeWire.submittedId("nope"))
        assertEquals("Unauthorized", BridgeWire.errorText("""{"detail":"Unauthorized"}"""))
        assertEquals("bad", BridgeWire.errorText("""{"error":"bad"}"""))
        assertEquals("", BridgeWire.errorText("<html>"))
    }
}
