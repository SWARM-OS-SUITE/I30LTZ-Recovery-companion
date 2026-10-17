package com.example.data.ai

import org.junit.Assert.*
import org.junit.Test

class ThinkFilterTest {
    private fun run(vararg chunks: String): String {
        val f = ThinkFilter()
        return chunks.joinToString("") { f.accept(it) } + f.finish()
    }

    @Test fun plainTextPassesThroughUntouched() {
        assertEquals("Hello, world!", run("Hello, ", "world!"))
    }

    @Test fun aWholeThinkBlockIsRemoved() {
        assertEquals("The answer is 4.", run("<think>2+2 is 4, easy.</think>The answer is 4."))
    }

    @Test fun blankLinesAfterTheThinkBlockAreDropped() {
        assertEquals("Answer", run("<think>\n\n</think>\n\nAnswer"))
    }

    @Test fun tagsSplitAcrossChunksStillWork() {
        assertEquals("Done.", run("<th", "ink>reason", "ing</thi", "nk>Done."))
        assertEquals("AB", run("A<", "think>x</think", ">B"))
    }

    @Test fun aLookalikeThatIsNotATagIsKept() {
        assertEquals("a < b and <thing> stay", run("a < b and ", "<thing> stay"))
        assertEquals("x <th", run("x <th"))
    }

    @Test fun anUnfinishedThinkBlockIsDroppedNotShown() {
        assertEquals("before ", run("before <think>never ends", " and ends not"))
    }

    @Test fun manyBlocksInOneReplyAreAllRemoved() {
        assertEquals("one two", run("<think>a</think>one <think>b</think>two"))
    }

    @Test fun codeContainingAngleBracketsSurvives() {
        val code = "val x: List<String> = emptyList() // a < b > c"
        assertEquals(code, run(code))
    }
}

class ChatPromptTest {
    private fun turn(role: ChatTurn.Role, chars: Int) = ChatTurn(role, "x".repeat(chars))

    @Test fun shortTextIsNotClipped() {
        assertEquals("short", ChatPrompt.clip("short", 100))
    }

    @Test fun longTextKeepsBothEndsAndSaysWhatWasOmitted() {
        val text = "START" + "m".repeat(10_000) + "END"
        val clipped = ChatPrompt.clip(text, 200)
        assertTrue(clipped.startsWith("START"))
        assertTrue(clipped.endsWith("END"))
        assertTrue(clipped.contains("omitted"))
        assertTrue(ChatPrompt.roughTokens(clipped) <= 260)
    }

    @Test fun oldestTurnsAreDroppedFirst() {
        val history = listOf(
            ChatTurn(ChatTurn.Role.USER, "old question " + "a".repeat(4000)),
            ChatTurn(ChatTurn.Role.ASSISTANT, "old answer " + "b".repeat(4000)),
            ChatTurn(ChatTurn.Role.USER, "recent question"),
            ChatTurn(ChatTurn.Role.ASSISTANT, "recent answer"),
        )
        val kept = ChatPrompt.fitHistory(history, system = "sys", user = "now", contextTokens = 1200, replyTokens = 400)
        assertEquals(listOf("recent question", "recent answer"), kept.map { it.text })
    }

    @Test fun everythingFitsWhenThereIsRoom() {
        val history = listOf(turn(ChatTurn.Role.USER, 40), turn(ChatTurn.Role.ASSISTANT, 40))
        assertEquals(history, ChatPrompt.fitHistory(history, "s", "u", contextTokens = 4096))
    }

    @Test fun noRoomMeansNoHistoryRatherThanACrash() {
        val history = listOf(turn(ChatTurn.Role.USER, 40))
        assertTrue(ChatPrompt.fitHistory(history, "s".repeat(10_000), "u", contextTokens = 1000).isEmpty())
    }
}

class OfflineModelCatalogTest {
    private val gib = 1024L * 1024 * 1024
    @Test fun everyModelHasAWellFormedPinnedDownload() {
        OfflineModelCatalog.all.forEach { m ->
            assertTrue("${m.id} sha256", Regex("^[0-9a-f]{64}$").matches(m.sha256))
            assertTrue("${m.id} https", m.url.startsWith("https://huggingface.co/litert-community/"))
            assertTrue("${m.id} file name matches url", m.url.endsWith("/" + m.fileName))
            assertTrue("${m.id} extension", m.fileName.endsWith(".litertlm"))
            assertTrue("${m.id} size", m.sizeBytes > 300_000_000L)
            assertTrue("${m.id} ram order", m.minRamBytes < m.comfortableRamBytes)
            assertEquals("Apache-2.0", m.license)
        }
        assertEquals("ids are unique", OfflineModelCatalog.all.size, OfflineModelCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun threeModelsShipAndTheyAreLookupable() {
        assertEquals(3, OfflineModelCatalog.all.size)
        assertSame(OfflineModelCatalog.quick, OfflineModelCatalog.byId("qwen3-1.7b"))
        assertSame(OfflineModelCatalog.deep, OfflineModelCatalog.byId("gemma-4-e2b"))
        assertSame(OfflineModelCatalog.lite, OfflineModelCatalog.byId("qwen3-0.6b"))
        assertNull(OfflineModelCatalog.byId("nope"))
    }

    /** The user's three phones, as Android reports their RAM (MemTotal). */
    private val samsung = 3_532_812L * 1024
    private val revoview = 2_908_172L * 1024

    @Test fun theSamsungsCanRunQuickTightlyAndLiteComfortablyButNotDeep() {
        assertEquals(OfflineModel.Fit.COMFORTABLE, OfflineModelCatalog.lite.fit(samsung))
        assertEquals(OfflineModel.Fit.TIGHT, OfflineModelCatalog.quick.fit(samsung))
        assertEquals(OfflineModel.Fit.TOO_BIG, OfflineModelCatalog.deep.fit(samsung))
    }

    @Test fun theSmallestPhoneIsNotLockedOutOfOfflineAi() {
        assertEquals(OfflineModel.Fit.COMFORTABLE, OfflineModelCatalog.lite.fit(revoview))
        assertEquals("allowed with a warning, not blocked", OfflineModel.Fit.TIGHT, OfflineModelCatalog.quick.fit(revoview))
        assertEquals(OfflineModel.Fit.TOO_BIG, OfflineModelCatalog.deep.fit(revoview))
    }

    @Test fun everyPhoneHereGetsASuggestionThatFits() {
        listOf(samsung, revoview).forEach { ram ->
            val pick = OfflineModelCatalog.recommendedFor(ram)
            assertNotNull(pick)
            assertNotEquals(OfflineModel.Fit.TOO_BIG, pick!!.fit(ram))
        }
    }

    @Test fun aBigPhoneGetsTheStrongerModel() {
        val eightGb = (7.4 * gib).toLong()
        assertEquals(OfflineModel.Fit.COMFORTABLE, OfflineModelCatalog.deep.fit(eightGb))
        assertSame(OfflineModelCatalog.deep, OfflineModelCatalog.recommendedFor(eightGb))
    }

    @Test fun aTinyPhoneGetsNoRecommendationInsteadOfACrash() {
        assertNull(OfflineModelCatalog.recommendedFor(1 * gib))
    }
}

class ByteFormatTest {
    @Test fun sizesReadTheWayHuggingFaceShowsThem() {
        assertEquals("977 MB", ByteFormat.human(OfflineModelCatalog.quick.sizeBytes))
        assertEquals("2.6 GB", ByteFormat.human(OfflineModelCatalog.deep.sizeBytes))
        assertEquals("12 KB", ByteFormat.human(12_345))
        assertEquals("999 B", ByteFormat.human(999))
        assertEquals("0 B", ByteFormat.human(0))
    }
}
