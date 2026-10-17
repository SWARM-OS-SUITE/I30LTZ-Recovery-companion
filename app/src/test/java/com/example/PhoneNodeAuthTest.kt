package com.example

import com.example.data.phonenode.PhoneNodeAuth
import com.example.data.phonenode.PhoneNodeAuth.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class MemStore : PhoneNodeAuth.Store {
    var saved: List<PhoneNodeAuth.Device> = emptyList()
    var failNext = false
    override fun load() = saved
    override fun save(devices: List<PhoneNodeAuth.Device>) {
        if (failNext) { failNext = false; throw IllegalStateException("disk full") }
        saved = devices.toList()
    }
}

class PhoneNodeAuthTest {
    private var now = 1_000_000L
    private fun auth(store: MemStore = MemStore()) = PhoneNodeAuth(store) { now }

    private fun token(r: Pairing): String = (r as Pairing.Ok).token

    @Test fun aCodeWorksOnceAndIsForgivingAboutTyping() {
        val a = auth()
        val code = a.newCode().code
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
        val t = token(a.pair(code.lowercase().replace("-", " "), "pixel"))
        assertTrue(a.verify(t))
        assertEquals("pixel", a.label(t))
        assertEquals(Pairing.BadCode, a.pair(code, "again")) // spent
    }

    @Test fun noCodeMeansNoPairing() {
        assertEquals(Pairing.BadCode, auth().pair("AAAA-AAAA", "x"))
    }

    @Test fun unknownEmptyAndAlteredTokensAreRefused() {
        val a = auth()
        val t = token(a.pair(a.newCode().code, "p"))
        assertFalse(a.verify(""))
        assertFalse(a.verify("nope"))
        assertFalse(a.verify(t + "0"))
        assertFalse(a.verify(t.dropLast(1)))
        assertNull(a.label(""))
    }

    @Test fun aNewCodeReplacesTheOldOne() {
        val a = auth()
        val old = a.newCode().code
        val fresh = a.newCode().code
        assertNotEquals(old, fresh)
        assertEquals(Pairing.BadCode, a.pair(old, "x"))
        assertTrue(a.pair(fresh, "x") is Pairing.Ok)
    }

    @Test fun codesExpireAfterTenMinutes() {
        val a = auth()
        val code = a.newCode()
        assertNotNull(a.currentCode())
        now += PhoneNodeAuth.CODE_TTL_MS + 1
        assertNull(a.currentCode())
        assertEquals(Pairing.BadCode, a.pair(code.code, "late"))
    }

    @Test fun fiveWrongGuessesLockPairingEvenForTheRightCode() {
        val a = auth()
        val code = a.newCode().code
        val results = (1..5).map { a.pair("WRONG-000", "x") }
        assertEquals(listOf(Pairing.BadCode, Pairing.BadCode, Pairing.BadCode, Pairing.BadCode, Pairing.Locked), results)
        assertEquals(Pairing.Locked, a.pair(code, "x"))
        now += PhoneNodeAuth.LOCKOUT_MS + 1
        assertTrue(a.pair(code, "x") is Pairing.Ok)
    }

    @Test fun onlyAHashOfTheTokenIsStoredAndItSurvivesARestart() {
        val store = MemStore()
        val a = auth(store)
        val t = token(a.pair(a.newCode().code, "tablet"))
        val d = store.saved.single()
        assertEquals("tablet", d.label)
        assertNotEquals(t, d.hash)
        assertEquals(PhoneNodeAuth.sha256Hex(t), d.hash)
        assertFalse(store.saved.toString().contains(t))
        val again = auth(store)
        assertTrue(again.verify(t))
    }

    @Test fun revokingTakesEffectAtOnce() {
        val store = MemStore()
        val a = auth(store)
        val keep = token(a.pair(a.newCode().code, "keep"))
        val lose = token(a.pair(a.newCode().code, "lose"))
        assertEquals(1, a.revoke("lose"))
        assertFalse(a.verify(lose))
        assertTrue(a.verify(keep))
        assertEquals(0, a.revoke("lose"))
        assertEquals(listOf("keep"), store.saved.map { it.label })
    }

    @Test fun aFailedSaveLeavesNothingPairedAndTheCodeUsable() {
        val store = MemStore()
        val a = auth(store)
        val code = a.newCode().code
        store.failNext = true
        try { a.pair(code, "x"); fail("expected the save error") } catch (_: IllegalStateException) { }
        assertTrue(a.devices().isEmpty())
        assertTrue("the code must still work after a storage hiccup", a.pair(code, "x") is Pairing.Ok)
    }

    @Test fun labelsAreCleaned() {
        val a = auth()
        assertEquals("device", a.label(token(a.pair(a.newCode().code, "   "))))
        assertEquals(64, a.label(token(a.pair(a.newCode().code, "x".repeat(200))))!!.length)
    }
}
