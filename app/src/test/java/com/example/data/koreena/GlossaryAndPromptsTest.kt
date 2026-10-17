package com.example.data.koreena

import org.junit.Assert.*
import org.junit.Test

class GlossaryAndPromptsTest {

    private fun items(s: String) = Glossary.breakdown(CommandReader.read(s))
    private fun about(s: String, token: String) = items(s).firstOrNull { it.token.text == token }?.explanation

    @Test fun commonCommandsAreExplainedInPlainWords() {
        assertTrue(about("lsblk -f", "lsblk")!!.contains("read-only"))
        assertTrue(about("update-grub", "update-grub")!!.contains("Rebuild GRUB"))
    }

    @Test fun anUnknownCommandIsHonestAboutIt() {
        val e = about("frobnicate --now", "frobnicate")!!
        assertTrue(e.contains("man frobnicate"))
    }

    @Test fun grubSettingsAndCapitalsAreExplained() {
        assertTrue(about("GRUB_TIMEOUT=5", "GRUB_TIMEOUT")!!.contains("seconds"))
        assertTrue(about("GRUB_FANCY_NEW_THING=1", "GRUB_FANCY_NEW_THING")!!.contains("case-sensitive"))
        assertTrue(about("echo \$HOME", "\$HOME")!!.contains("home folder"))
    }

    @Test fun kernelWordsInsideQuotesAreExplainedOneByOne() {
        val r = items("GRUB_CMDLINE_LINUX_DEFAULT=\"quiet splash nomodeset\"")
        assertEquals(setOf("quiet", "splash", "nomodeset"), r.map { it.token.text }.filter { it in setOf("quiet", "splash", "nomodeset") }.toSet())
        assertTrue(r.first { it.token.text == "nomodeset" }.explanation.contains("black screen"))
    }

    @Test fun grubDeviceNamesAreDecoded() {
        val e = about("set root=(hd0,gpt2)", "(hd0,gpt2)")!!
        assertTrue(e, e.contains("first disk"))
        assertTrue(e, e.contains("partition 2"))
        assertTrue(e, e.contains("GPT"))
    }

    @Test fun linuxDiskNamesAreDecoded() {
        assertTrue(about("mount /dev/sda1 /mnt", "/dev/sda1")!!.contains("partition 1"))
        assertTrue(about("mount /dev/nvme0n1p3 /mnt", "/dev/nvme0n1p3")!!.contains("partition 3"))
    }

    @Test fun combinedShortOptionsAreSplitApart() {
        val e = about("rm -rf build", "-rf")!!
        assertTrue(e, e.contains("-r =") && e.contains("-f ="))
    }

    @Test fun anOptionsMeaningDependsOnItsCommand() {
        assertTrue(about("grep -v x", "-v")!!.contains("do NOT match"))
        assertTrue(about("ls -h", "-h")!!.contains("read"))
    }

    @Test fun eachDistinctPartAppearsOnce() {
        val r = items("ls -l /etc && ls -l /var")
        assertEquals(1, r.count { it.token.text == "ls" })
        assertEquals(1, r.count { it.token.text == "-l" })
    }

    @Test fun spacesAndCommentsAreNotListed() {
        assertTrue(items("ls # hello").none { it.token.kind == Kind.SPACE || it.token.kind == Kind.COMMENT })
    }

    @Test fun importantPathsHaveSpecificAdvice() {
        assertTrue(about("nano /etc/default/grub", "/etc/default/grub")!!.contains("update-grub"))
        assertTrue(about("cat /boot/grub/grub.cfg", "/boot/grub/grub.cfg")!!.contains("do not edit"))
    }

    // ── prompts ───────────────────────────────────────────────────────────────

    @Test fun koreenaIsATeacherAndBoltzIsNot() {
        val k = KoreenaPrompts.SYSTEM
        assertTrue(k.contains("Miss Koreena"))
        assertTrue(k.contains("tutor"))
        assertFalse(k.contains("Dr. Boltz, an offline coding assistant"))
        assertTrue(com.example.data.ai.AiPrompts.IDE_SYSTEM.contains("Dr. Boltz"))
        assertFalse(com.example.data.ai.AiPrompts.IDE_SYSTEM.contains("Koreena"))
    }

    @Test fun herPromptAsksForColourableCodeBlocksAndSafetyChecks() {
        val k = KoreenaPrompts.SYSTEM
        assertTrue(k.contains("fenced code block"))
        assertTrue(k.contains("lsblk"))
        assertTrue(k.contains("back up"))
        assertTrue(k.contains("never claim you tested"))
    }

    @Test fun theExplainPromptCarriesTheCommandTheFactsAndTheRisks() {
        val cmd = "sudo dd if=a.iso of=/dev/sdb"
        val p = KoreenaPrompts.explain(cmd, CommandReader.read(cmd))
        assertTrue(p.contains("```bash\n$cmd\n```"))
        assertTrue(p.contains("Facts from the app's glossary"))
        assertTrue(p.contains("Risks the app spotted"))
        assertTrue(p.contains("dd:"))
    }

    @Test fun aFreeQuestionOnlyAddsTheCommandWhenThereIsOne() {
        assertEquals("What is GRUB?", KoreenaPrompts.ask(" What is GRUB? ", null))
        assertEquals("What is GRUB?", KoreenaPrompts.ask("What is GRUB?", "  "))
        assertTrue(KoreenaPrompts.ask("why?", "ls -l").contains("```bash\nls -l\n```"))
    }
}
