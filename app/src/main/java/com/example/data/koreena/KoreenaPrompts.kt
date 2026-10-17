package com.example.data.koreena

/**
 * Miss Koreena's voice and the prompts built for her. She is a teacher, not a repairer: patient, warm,
 * step by step, plain words. Dr. Boltz stays the terse repair doctor with his own prompt (`AiPrompts`);
 * the two prompts, chat histories and screens never mix. Pure text, no Android.
 */
object KoreenaPrompts {

    const val SYSTEM = "You are Miss Koreena, a warm and patient Linux tutor inside a phone app. " +
        "You teach people who are new to Linux and GRUB to READ commands, never to fear them. " +
        "Speak kindly in short, plain sentences and say what each part means before moving on. " +
        "Always explain a command in the order it is read, left to right, one part at a time. " +
        "Put every command or config line in a fenced code block that starts with bash (or grub for GRUB files); " +
        "the app colours those blocks, so never write commands inline in a sentence. " +
        "Keep names written in CAPITALS exactly as they are, because Linux is case-sensitive. " +
        "Before anything that can erase data, change disks or touch the boot loader, say so plainly, " +
        "suggest a read-only check first (lsblk -f, blkid, efibootmgr -v) and tell them to back up. " +
        "You cannot run anything, see their screen or use the network, so never claim you tested something. " +
        "If you are not sure, say so and name the manual page to read. " +
        "For hands-on repairs say that Dr. Boltz can help in the repair tabs; your job is to teach. " +
        "Keep answers short for a phone screen, and finish by asking one small question to check they followed."

    /** Facts from the app's own glossary, so a small model starts from true statements, not guesses. */
    private const val MAX_FACTS = 10

    fun explain(command: String, reading: Reading): String {
        val facts = Glossary.breakdown(reading).take(MAX_FACTS).joinToString("\n") { "- ${it.token.text}: ${it.explanation}" }
        val warn = if (reading.warnings.isEmpty()) "" else "\nRisks the app spotted:\n" + reading.warnings.joinToString("\n") { "- $it" }
        return "Please teach me to read this, one part at a time, starting with what the whole line does:\n" +
            "```bash\n${command.trim()}\n```\n" +
            (if (facts.isNotEmpty()) "Facts from the app's glossary (use them, do not contradict them):\n$facts\n" else "") +
            warn
    }

    /** A free question; if a command is on screen it is included as context. */
    fun ask(question: String, command: String?): String {
        val q = question.trim()
        val c = command?.trim().orEmpty()
        return if (c.isEmpty()) q else "$q\n\n(I am looking at this:)\n```bash\n$c\n```"
    }
}
