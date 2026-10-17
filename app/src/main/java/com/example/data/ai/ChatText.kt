package com.example.data.ai

/** One earlier message in a chat with an offline model. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Removes `<think>…</think>` reasoning from a model's streamed reply.
 *
 * Qwen3 writes its reasoning inside think tags before the answer. In an IDE that is noise and burns the
 * slow phone-speed tokens, so the chat asks for no thinking and this filter drops any that still appears.
 * The stream arrives in arbitrary chunks, so a tag can be split across two of them; text that might be the
 * start of a tag is held back until the next chunk settles it.
 */
class ThinkFilter {
    private var inThink = false
    private var skipLeadingBlank = false
    private val buffer = StringBuilder()

    fun accept(chunk: String): String {
        buffer.append(chunk)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val end = buffer.indexOf(CLOSE)
                if (end >= 0) {
                    buffer.delete(0, end + CLOSE.length)
                    inThink = false
                    skipLeadingBlank = true
                    continue
                }
                // Still inside reasoning: keep only a possible partial "</think>" at the end.
                val keep = partialSuffix(buffer, CLOSE)
                buffer.delete(0, buffer.length - keep)
                break
            }
            val start = buffer.indexOf(OPEN)
            if (start >= 0) {
                appendVisible(out, buffer.substring(0, start))
                buffer.delete(0, start + OPEN.length)
                inThink = true
                continue
            }
            val keep = partialSuffix(buffer, OPEN)
            appendVisible(out, buffer.substring(0, buffer.length - keep))
            buffer.delete(0, buffer.length - keep)
            break
        }
        return out.toString()
    }

    /** Call once the stream ends: releases held-back text that turned out not to be a tag. */
    fun finish(): String {
        val rest = if (inThink) "" else buffer.toString()
        buffer.clear()
        val out = StringBuilder()
        appendVisible(out, rest)
        return out.toString()
    }

    private fun appendVisible(out: StringBuilder, text: String) {
        var t = text
        if (skipLeadingBlank) {
            t = t.trimStart()
            if (t.isNotEmpty()) skipLeadingBlank = false
        }
        out.append(t)
    }

    /** Length of the longest tail of [text] that is a proper prefix of [tag]. */
    private fun partialSuffix(text: CharSequence, tag: String): Int {
        val max = minOf(text.length, tag.length - 1)
        for (len in max downTo 1) {
            if (tag.startsWith(text.subSequence(text.length - len, text.length))) return len
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/** Keeps a prompt inside a small model's context window. Token counts are a rough 4-characters-each estimate. */
object ChatPrompt {
    fun roughTokens(text: String): Int = (text.length + 3) / 4

    /** Shortens [text] to about [maxTokens], keeping the start and the end and saying what was left out. */
    fun clip(text: String, maxTokens: Int): String {
        if (roughTokens(text) <= maxTokens) return text
        val keepChars = (maxTokens * 4 - 80).coerceAtLeast(40)
        val head = text.take(keepChars * 2 / 3)
        val tail = text.takeLast(keepChars - head.length)
        val omitted = text.length - head.length - tail.length
        return head + "\n[… $omitted characters omitted to fit the model's memory …]\n" + tail
    }

    /**
     * The most recent turns that fit with the system text and the new user message, leaving
     * [replyTokens] for the answer. Oldest turns go first; a turn is never cut in half.
     */
    fun fitHistory(
        history: List<ChatTurn>,
        system: String,
        user: String,
        contextTokens: Int,
        replyTokens: Int = 768,
    ): List<ChatTurn> {
        var budget = contextTokens - replyTokens - roughTokens(system) - roughTokens(user) - 16
        if (budget <= 0) return emptyList()
        val kept = ArrayDeque<ChatTurn>()
        for (turn in history.asReversed()) {
            val cost = roughTokens(turn.text) + 8
            if (cost > budget) break
            budget -= cost
            kept.addFirst(turn)
        }
        return kept.toList()
    }
}
