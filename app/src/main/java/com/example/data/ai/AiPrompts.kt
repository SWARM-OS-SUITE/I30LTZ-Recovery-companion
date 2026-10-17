package com.example.data.ai

/** What the user asked the IDE assistant to do with the code in front of them. */
enum class AiAction(val label: String) {
    ASK("Ask"),
    EXPLAIN("Explain"),
    FIX("Find bugs"),
    TESTS("Write tests"),
    DOCS("Add docs"),
}

/** One fenced code block found in a model reply. */
data class CodeBlock(val language: String, val code: String)

/** A piece of a reply for display: prose, or code (which may still be arriving). */
data class ReplySegment(val isCode: Boolean, val language: String, val text: String)

/** Builds the prompts for the IDE assistant and reads code back out of its replies. Pure text, no Android. */
object AiPrompts {

    const val IDE_SYSTEM = "You are Dr. Boltz, an offline coding assistant inside the Coder Droid IDE on an Android phone. " +
        "Be concise: phone screens are small. Use plain words. " +
        "When you change or write code, give the complete result in ONE fenced code block that starts with the language name, " +
        "so it can be inserted into the editor with one tap. " +
        "You cannot run code, open files or use the network, so never claim you tested anything. " +
        "If the code or question is unclear, say what is missing instead of guessing."

    /** How much code goes into one prompt; the rest is clipped by [ChatPrompt.clip] with a visible marker. */
    private const val CONTEXT_TOKENS = 1400

    /**
     * The text sent to the model. [selection] wins over [fileText] when present, because asking about a
     * few selected lines should not drag the whole file into a small model's memory.
     */
    fun build(action: AiAction, userText: String, language: String, selection: String, fileText: String): String {
        val hasSelection = selection.isNotBlank()
        val code = ChatPrompt.clip(if (hasSelection) selection else fileText, CONTEXT_TOKENS)
        val what = if (hasSelection) "this selected $language code" else "this $language file"
        val block = "```$language\n$code\n```"
        val extra = userText.trim().let { if (it.isEmpty()) "" else "\nExtra instructions: $it" }
        return when (action) {
            AiAction.ASK ->
                if (code.isBlank()) userText.trim()
                else "${userText.trim().ifEmpty { "Help me with $what." }}\n\nCode for reference:\n$block"
            AiAction.EXPLAIN -> "Explain $what in plain words. Say what it does, then point out anything surprising.$extra\n\n$block"
            AiAction.FIX -> "Look for bugs in $what. List each problem briefly, then give the corrected code in one code block.$extra\n\n$block"
            AiAction.TESTS -> "Write unit tests for $what. Give the complete test code in one code block.$extra\n\n$block"
            AiAction.DOCS -> "Add short, accurate doc comments to $what. Return the complete code with the comments in one code block. Do not change behaviour.$extra\n\n$block"
        }
    }

    private val FENCE = Regex("```([A-Za-z0-9_+#.-]*)[ \\t]*\\r?\\n(.*?)\\r?\\n?```", RegexOption.DOT_MATCHES_ALL)

    /** Fenced code blocks in [reply], in order. An unfinished last block (cut off mid-answer) is ignored. */
    fun codeBlocks(reply: String): List<CodeBlock> =
        FENCE.findAll(reply).map { CodeBlock(it.groupValues[1].lowercase(), it.groupValues[2]) }.toList()

    /**
     * Splits a reply into prose and code for display. Unlike [codeBlocks] this also shows a block that has
     * not finished yet, so code appears in its monospace box while it streams in.
     */
    fun segments(reply: String): List<ReplySegment> {
        val out = mutableListOf<ReplySegment>()
        var rest = reply
        while (true) {
            val open = rest.indexOf("```")
            if (open < 0) { if (rest.isNotBlank()) out += ReplySegment(false, "", rest.trim('\n')); break }
            if (open > 0 && rest.substring(0, open).isNotBlank()) out += ReplySegment(false, "", rest.substring(0, open).trim('\n'))
            val afterOpen = rest.substring(open + 3)
            val newline = afterOpen.indexOf('\n')
            val language = (if (newline < 0) afterOpen else afterOpen.substring(0, newline)).trim().lowercase()
            val bodyStart = if (newline < 0) afterOpen.length else newline + 1
            val close = afterOpen.indexOf("```", bodyStart)
            if (close < 0) { out += ReplySegment(true, language, afterOpen.substring(bodyStart).trimEnd('\n', '\r')); break }
            out += ReplySegment(true, language, afterOpen.substring(bodyStart, close).trimEnd('\n', '\r'))
            rest = afterOpen.substring(close + 3)
        }
        return out
    }
}
