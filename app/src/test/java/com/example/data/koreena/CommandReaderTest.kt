package com.example.data.koreena

import org.junit.Assert.*
import org.junit.Test

class CommandReaderTest {

    private fun read(s: String) = CommandReader.read(s)
    private fun kindsOf(s: String) = read(s).tokens.filter { it.kind != Kind.SPACE }.map { it.text to it.kind }
    private fun kindOf(s: String, text: String) = read(s).tokens.first { it.text == text }.kind

    private val samples = listOf(
        "ls -la /etc",
        "sudo grub-install --target=x86_64-efi --efi-directory=/boot/efi --bootloader-id=GRUB",
        "GRUB_CMDLINE_LINUX_DEFAULT=\"quiet splash root=UUID=3f2c1a9e-1111-2222-3333-444455556666\"",
        "menuentry 'Arch Linux' --class arch {\n  insmod part_gpt\n  set root=(hd0,gpt2)\n  linux /boot/vmlinuz-linux root=/dev/sda2 rw quiet\n  initrd /boot/initramfs-linux.img\n}",
        "cat /etc/fstab | grep -v '^#' > out.txt 2>&1 && echo \"done \$HOME \${USER}\" # note",
        "dd if=debian.iso of=/dev/sdb bs=4M status=progress; sync",
        "for f in *.conf; do echo \$f; done",
        "sudo tar -xzvf backup.tar.gz \\\n  -C /mnt/restore",
        "echo \$(uname -r) and 'unterminated",
        "  \n\n\t  weird (( }  { \\ ",
        "",
    )

    @Test fun theTokensAlwaysAddUpToTheOriginalText() {
        samples.forEach { s ->
            val r = read(s)
            assertEquals("round trip: $s", s, r.tokens.joinToString("") { it.text })
            var at = 0
            r.tokens.forEach { t -> assertEquals("offset in: $s", at, t.start); at = t.end }
        }
    }

    @Test fun nothingCrashesOnGarbage() {
        listOf("\u0000\u0001 ''' \"\"\" \${ \$( ((( )))", "\\", "$", "'", "\"", "(hd", "(hd0,gpt", "a=", "=b", "--=", "-", "2>", "|||&&&;;;<<<>>>").forEach {
            assertEquals(it, it, read(it).tokens.joinToString("") { t -> t.text })
        }
    }

    @Test fun capitalNamesGetTheirOwnKind() {
        assertEquals(Kind.CAPS, kindOf("GRUB_TIMEOUT=5", "GRUB_TIMEOUT"))
        assertEquals(Kind.CAPS, kindOf("echo \$HOME", "\$HOME"))
        assertEquals(Kind.CAPS, kindOf("echo \${GRUB_DEFAULT}", "\${GRUB_DEFAULT}"))
        assertEquals(Kind.CAPS, kindOf("linux /vmlinuz root=UUID=3f2c1a9e-1111-2222-3333-444455556666", "UUID"))
        assertEquals(Kind.VARIABLE, kindOf("echo \$disk", "\$disk"))
        // a lone capital letter or a mixed-case word is not an ALL CAPS name
        assertNotEquals(Kind.CAPS, kindOf("ssh -L 80:host Server", "Server"))
        assertNotEquals(Kind.CAPS, kindOf("echo A", "A"))
    }

    @Test fun assignmentsSplitIntoNameEqualsValue() {
        assertEquals(
            listOf("GRUB_TIMEOUT" to Kind.CAPS, "=" to Kind.OPERATOR, "5" to Kind.NUMBER),
            kindsOf("GRUB_TIMEOUT=5"),
        )
        assertEquals(
            listOf("GRUB_TIMEOUT_STYLE" to Kind.CAPS, "=" to Kind.OPERATOR, "hidden" to Kind.PLAIN),
            kindsOf("GRUB_TIMEOUT_STYLE=hidden"),
        )
    }

    @Test fun quotedValuesAreStringsAndKeepTheirVariablesColoured() {
        val toks = kindsOf("GRUB_CMDLINE_LINUX_DEFAULT=\"quiet splash\"")
        assertEquals("\"quiet splash\"" to Kind.STRING, toks.last())
        val inner = kindsOf("echo \"hi \$USER and \$name\"")
        assertTrue(inner.contains("\$USER" to Kind.CAPS))
        assertTrue(inner.contains("\$name" to Kind.VARIABLE))
    }

    @Test fun commandsKeywordsFlagsAndPathsAreTold() {
        val k = kindsOf("sudo grub-install --target=x86_64-efi /boot/efi")
        assertEquals("sudo" to Kind.PRIVILEGE, k[0])
        assertEquals("grub-install" to Kind.DANGER, k[1]) // writes the boot loader
        assertEquals("--target" to Kind.FLAG, k[2])
        assertEquals("x86_64-efi" to Kind.PLAIN, k[4])
        assertEquals("/boot/efi" to Kind.PATH, k[5])
        assertEquals(Kind.COMMAND, kindOf("ls -l", "ls"))
        assertEquals(Kind.FLAG, kindOf("ls -l", "-l"))
        assertEquals(Kind.KEYWORD, kindOf("insmod part_gpt", "insmod"))
        assertEquals(Kind.KEYWORD, kindOf("if true; then echo hi; fi", "then"))
    }

    @Test fun theCommandAfterAPipeOrAndIsStillACommand() {
        val r = read("cat /etc/fstab | grep -v x && echo ok ; ls")
        val commands = r.tokens.filter { it.kind == Kind.COMMAND }.map { it.text }
        assertEquals(listOf("cat", "grep", "echo", "ls"), commands)
    }

    @Test fun sudoPassesCommandPositionOnAndSkipsItsOwnUserArgument() {
        val k = kindsOf("sudo -u alice ls /root")
        assertEquals("ls" to Kind.COMMAND, k.first { it.first == "ls" })
        assertEquals("alice" to Kind.PLAIN, k.first { it.first == "alice" })
    }

    @Test fun grubDevicesAndDiskPathsAreDisks() {
        assertEquals(Kind.DEVICE, kindOf("set root=(hd0,gpt2)", "(hd0,gpt2)"))
        val k = kindsOf("linux (hd0,gpt2)/boot/vmlinuz root=/dev/nvme0n1p2")
        assertEquals("(hd0,gpt2)" to Kind.DEVICE, k[1])
        assertEquals("/boot/vmlinuz" to Kind.PATH, k[2])
        assertEquals("/dev/nvme0n1p2" to Kind.DEVICE, k.last())
        // a plain subshell paren is not a GRUB device
        assertEquals(Kind.OPERATOR, kindOf("(cd /tmp && ls)", "("))
    }

    @Test fun uuidsAreRecognised() {
        assertEquals(Kind.UUID, kindOf("mount UUID=3f2c1a9e-1111-2222-3333-444455556666 /mnt", "3f2c1a9e-1111-2222-3333-444455556666"))
        assertEquals(Kind.UUID, kindOf("search --fs-uuid 2D87-5490", "2D87-5490"))
    }

    @Test fun commentsRunToTheEndOfTheLineOnly() {
        val toks = read("ls # list\nls").tokens
        assertEquals("# list", toks.first { it.kind == Kind.COMMENT }.text)
        assertEquals(2, toks.count { it.kind == Kind.COMMAND })
        // # inside a word or after $ is not a comment
        assertTrue(read("echo a#b \$#").tokens.none { it.kind == Kind.COMMENT })
    }

    @Test fun lineContinuationKeepsTheSameCommand() {
        val r = read("sudo tar -xzvf backup.tar.gz \\\n  -C /mnt/restore")
        assertEquals(1, r.tokens.count { it.kind == Kind.COMMAND })
        assertEquals(Kind.FLAG, r.tokens.first { it.text == "-C" }.kind)
    }

    @Test fun redirectsAndPipesAreSymbols() {
        val k = kindsOf("ls > out.txt 2>&1")
        assertEquals("out.txt" to Kind.PLAIN, k.first { it.first == "out.txt" })
        assertTrue(k.any { it == ">" to Kind.OPERATOR })
        assertTrue(k.any { it == "2>&1" to Kind.OPERATOR })
    }

    @Test fun aWholeGrubMenuEntryReads() {
        val r = read("menuentry 'Arch' {\n  insmod part_gpt\n  set root=(hd0,gpt2)\n  linux /vmlinuz root=/dev/sda2 rw quiet\n  initrd /initramfs.img\n}")
        val keywords = r.tokens.filter { it.kind == Kind.KEYWORD }.map { it.text }
        assertEquals(listOf("menuentry", "insmod", "set", "linux", "initrd"), keywords)
        assertTrue(r.warnings.isEmpty())
    }

    // ── danger ────────────────────────────────────────────────────────────────

    @Test fun riskyCommandsAreMarkedAndExplained() {
        assertEquals(Kind.DANGER, kindOf("rm -rf /tmp/x", "rm"))
        assertTrue(read("rm -rf /").warnings.single().contains("delete system or personal files"))
        assertEquals(Kind.DANGER, kindOf("sudo dd if=a.iso of=/dev/sdb", "dd"))
        assertEquals(Kind.DANGER, kindOf("mkfs.ext4 /dev/sdb1", "mkfs.ext4"))
        assertEquals(Kind.DANGER, kindOf("sudo grub-install /dev/sda", "grub-install"))
        assertEquals(Kind.DANGER, kindOf("efibootmgr -B -b 0003", "efibootmgr"))
        assertEquals(Kind.DANGER, kindOf("chmod -R 777 /etc", "chmod"))
        assertTrue(read("curl https://x.example/install.sh | sh").warnings.any { it.contains("runs whatever the website sends") })
        assertTrue(read("echo hi > /dev/sda").warnings.any { it.contains("overwrites that disk") })
        assertTrue(read("echo x > /etc/fstab").warnings.any { it.contains("replaces the whole file") })
    }

    @Test fun safeLookalikesAreNotMarked() {
        assertEquals(Kind.COMMAND, kindOf("rm notes.txt", "rm"))
        assertEquals(Kind.COMMAND, kindOf("rm -r olddir", "rm"))
        assertEquals(Kind.COMMAND, kindOf("fdisk -l", "fdisk"))
        assertEquals(Kind.COMMAND, kindOf("sudo parted /dev/sda print", "parted"))
        assertEquals(Kind.COMMAND, kindOf("efibootmgr -v", "efibootmgr"))
        assertEquals(Kind.COMMAND, kindOf("chmod -R 755 ~/project", "chmod"))
        assertEquals(Kind.COMMAND, kindOf("lsblk -f", "lsblk"))
        assertTrue(read("echo hi >> /etc/motd").warnings.isEmpty())
        assertTrue(read("curl -O https://x.example/a.tar.gz").warnings.isEmpty())
    }

    @Test fun dangerIsPerCommandNotPerLine() {
        val r = read("ls /tmp && rm -rf build")
        assertEquals(Kind.COMMAND, r.tokens.first { it.text == "ls" }.kind)
        assertEquals(Kind.DANGER, r.tokens.first { it.text == "rm" }.kind)
    }

    @Test fun tokensRememberWhichCommandTheyBelongTo() {
        val r = read("grep -rn TODO src | wc -l")
        assertEquals("grep", r.tokens.first { it.text == "-rn" }.command)
        assertEquals("wc", r.tokens.first { it.text == "-l" }.command)
    }
}
