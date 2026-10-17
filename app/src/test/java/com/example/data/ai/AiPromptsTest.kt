package com.example.data.ai

import org.junit.Assert.*
import org.junit.Test

class AiPromptsTest {
    @Test fun aSelectionBeatsTheWholeFile() {
        val p = AiPrompts.build(AiAction.EXPLAIN, "", "kotlin", selection = "val a = 1", fileText = "FILE_BODY_SHOULD_NOT_APPEAR")
        assertTrue(p.contains("val a = 1"))
        assertFalse(p.contains("FILE_BODY_SHOULD_NOT_APPEAR"))
        assertTrue(p.contains("selected kotlin code"))
    }

    @Test fun withoutASelectionTheFileIsUsed() {
        val p = AiPrompts.build(AiAction.FIX, "", "python", selection = "  ", fileText = "print('x')")
        assertTrue(p.contains("print('x')"))
        assertTrue(p.contains("this python file"))
    }

    @Test fun everyActionIsFencedWithItsLanguageAndKeepsExtraInstructions() {
        AiAction.values().filter { it != AiAction.ASK }.forEach { action ->
            val p = AiPrompts.build(action, "keep it short", "go", selection = "x := 1", fileText = "")
            assertTrue("$action fenced", p.contains("```go\nx := 1\n```"))
            assertTrue("$action extra", p.contains("keep it short"))
        }
    }

    @Test fun askWithNoCodeIsJustTheQuestion() {
        assertEquals("How do I mount a drive?", AiPrompts.build(AiAction.ASK, " How do I mount a drive? ", "plaintext", "", ""))
    }

    @Test fun hugeFilesAreClippedWithAVisibleMarker() {
        val big = "line\n".repeat(20_000)
        val p = AiPrompts.build(AiAction.EXPLAIN, "", "kotlin", "", big)
        assertTrue(p.contains("omitted"))
        assertTrue(ChatPrompt.roughTokens(p) < 1700)
    }

    @Test fun codeBlocksAreExtractedWithTheirLanguage() {
        val reply = "Here you go:\n```Kotlin\nfun a() = 1\n```\nand also\n```\nplain\n```\nDone."
        val blocks = AiPrompts.codeBlocks(reply)
        assertEquals(listOf(CodeBlock("kotlin", "fun a() = 1"), CodeBlock("", "plain")), blocks)
    }

    @Test fun anUnfinishedBlockIsNotOfferedForInsertion() {
        assertTrue(AiPrompts.codeBlocks("Start:\n```kotlin\nfun half(").isEmpty())
    }

    @Test fun windowsLineEndingsAndBlankLinesInsideCodeSurvive() {
        val blocks = AiPrompts.codeBlocks("```js\r\nlet a = 1;\r\n\r\nlet b = 2;\r\n```")
        assertEquals(1, blocks.size)
        assertEquals("let a = 1;\r\n\r\nlet b = 2;", blocks[0].code)
    }

    @Test fun segmentsSplitProseAndCode() {
        val segs = AiPrompts.segments("Intro line.\n```kotlin\nfun a() = 1\n```\nOutro.")
        assertEquals(
            listOf(ReplySegment(false, "", "Intro line."), ReplySegment(true, "kotlin", "fun a() = 1"), ReplySegment(false, "", "Outro.")),
            segs,
        )
    }

    @Test fun anUnfinishedBlockStillShowsAsCodeWhileStreaming() {
        val segs = AiPrompts.segments("Here:\n```python\nprint('he")
        assertEquals(listOf(ReplySegment(false, "", "Here:"), ReplySegment(true, "python", "print('he")), segs)
    }

    @Test fun plainTextIsOneProseSegmentAndEmptyIsNothing() {
        assertEquals(listOf(ReplySegment(false, "", "just words")), AiPrompts.segments("just words"))
        assertTrue(AiPrompts.segments("").isEmpty())
    }

    @Test fun twoBlocksInARowBothSurvive() {
        val segs = AiPrompts.segments("```a\n1\n```\n```b\n2\n```")
        assertEquals(listOf(ReplySegment(true, "a", "1"), ReplySegment(true, "b", "2")), segs)
    }
}
