package com.example.data.koreena

/** What a piece of a command line is. The screen gives every kind its own colour, see `CommandColors`. */
enum class Kind(val label: String, val meaning: String) {
    CAPS("ALL CAPS", "Names in capitals: settings and variables such as GRUB_TIMEOUT"),
    COMMAND("command", "The program being run"),
    KEYWORD("keyword", "A GRUB or shell word that controls the flow (menuentry, linux, if, then)"),
    PRIVILEGE("sudo", "Run with administrator (root) rights"),
    DANGER("danger", "Can erase data or stop the PC booting. Read it twice"),
    FLAG("option", "Changes how the command behaves; starts with - or --"),
    KEY("setting", "The left side of name=value"),
    PATH("path", "A file or folder location"),
    DEVICE("disk", "A disk or partition: /dev/sda1 or GRUB's (hd0,gpt2)"),
    STRING("text", "Text in quotes"),
    VARIABLE("variable", "A value stored under a name, like \$disk or \${1}"),
    NUMBER("number", "A number or a size"),
    UUID("UUID", "A unique ID for a filesystem or partition"),
    OPERATOR("symbol", "Joins or redirects: | && ; > ="),
    COMMENT("comment", "A note for humans; the computer ignores it"),
    PLAIN("argument", "Something handed to the command to work on"),
    SPACE("space", ""),
}

/**
 * One piece of the original text. Concatenating every token's [text] gives the input back exactly, so the
 * screen can colour it without changing a character. [command] is the program this token belongs to, when it
 * has one (used to explain options).
 */
data class Token(val text: String, val kind: Kind, val start: Int, val command: String? = null) {
    val end: Int get() = start + text.length
}

/** The coloured reading of a command: its tokens plus plain-language warnings about risky parts. */
data class Reading(val tokens: List<Token>, val warnings: List<String>) {
    val isEmpty: Boolean get() = tokens.none { it.kind != Kind.SPACE }
}

/**
 * Splits a shell command or a GRUB config into coloured pieces. Pure text, no Android, no AI: this is what
 * makes the colouring dependable on a tiny offline model. It is a reader, not a shell: it never runs anything
 * and it is deliberately forgiving of half-pasted lines.
 */
object CommandReader {

    private val CAPS_RE = Regex("^[A-Z][A-Z0-9_]*[A-Z0-9]$")
    private val KEY_RE = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")
    private val NUM_RE = Regex("^[+-]?\\d+([.,]\\d+)*[KkMmGgTt]?[iI]?[Bb]?$")
    private val UUID_RE = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9A-F]{4}-[0-9A-F]{4})$")
    private val GRUB_DEVICE_RE = Regex("^\\((hd|cd|fd|ahci|usb|lvm/|crypto|md|proc|tftp|network|memdisk|loop)[^)\\s]*\\)")
    private val VAR_RE = Regex("^(?:[A-Za-z_][A-Za-z0-9_]*|[0-9@#?!*\\$-])")

    val PRIVILEGE = setOf("sudo", "doas", "pkexec", "su")
    /** Run another command: what follows is still a command, so keep colouring it as one. */
    private val PASS_THROUGH = setOf("env", "time", "nohup", "exec", "nice", "ionice", "command", "builtin", "xargs", "watch", "timeout", "chroot", "arch-chroot")
    private val SUDO_VALUE_FLAGS = setOf("-u", "-g", "-h", "-p", "-C", "-D", "-R", "-T", "-U", "--user", "--group")
    val SHELL_KEYWORDS = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function", "select")
    private val KEEPS_COMMAND_POSITION = setOf("if", "then", "else", "elif", "while", "until", "do", "!")
    val GRUB_KEYWORDS = setOf(
        "menuentry", "submenu", "insmod", "rmmod", "linux", "linuxefi", "linux16", "initrd", "initrdefi", "initrd16",
        "chainloader", "configfile", "search", "set", "unset", "boot", "loopback", "load_env", "save_env",
        "terminal_output", "terminal_input", "background_image", "gfxmode", "gfxpayload", "play", "reboot", "halt",
    )

    fun read(text: String): Reading {
        val scanner = Scanner(text)
        scanner.run()
        val tokens = scanner.out
        val warnings = Analysis.apply(tokens)
        return Reading(tokens, warnings)
    }

    fun isAllCaps(word: String) = CAPS_RE.matches(word)

    private class Scanner(val s: String) {
        val out = ArrayList<Token>()
        private var cursor = 0
        private var i = 0
        private var expectCommand = true
        private var command: String? = null
        private var sudoValueNext = false

        private fun emit(text: String, kind: Kind) {
            if (text.isEmpty()) return
            out += Token(text, kind, cursor, command)
            cursor += text.length
        }

        private fun endOfCommand() { expectCommand = true; command = null; sudoValueNext = false }

        fun run() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length && (s[i + 1] == '\n' || (s[i + 1] == '\r' && s.getOrNull(i + 2) == '\n')) -> {
                        emit("\\", Kind.OPERATOR); i++
                        // the line goes on: the next line is the same command, so no reset
                        val ws = takeWhile { it.isWhitespace() }
                        emit(ws, Kind.SPACE)
                    }
                    c.isWhitespace() -> {
                        val ws = takeWhile { it.isWhitespace() }
                        emit(ws, Kind.SPACE)
                        if (ws.contains('\n')) endOfCommand()
                    }
                    c == '#' && atWordStart() -> {
                        val rest = takeWhile { it != '\n' }
                        emit(rest, Kind.COMMENT)
                    }
                    c == '\'' || c == '"' -> string(c)
                    c == '$' -> dollar()
                    c == '(' && GRUB_DEVICE_RE.containsMatchIn(s.substring(i)) -> grubDevice()
                    c == '(' || c == ')' -> { emit(c.toString(), Kind.OPERATOR); i++; if (c == '(') endOfCommand() }
                    (c == '{' || c == '}') && (i + 1 >= s.length || s[i + 1].isWhitespace()) -> {
                        emit(c.toString(), Kind.OPERATOR); i++
                        if (c == '{') endOfCommand()
                    }
                    c in "|&;<>" -> operator()
                    else -> word()
                }
            }
        }

        private inline fun takeWhile(pred: (Char) -> Boolean): String {
            val begin = i
            while (i < s.length && pred(s[i])) i++
            return s.substring(begin, i)
        }

        private fun atWordStart() = i == 0 || s[i - 1].isWhitespace() || s[i - 1] in "|&;("

        private fun operator() {
            val begin = i
            while (i < s.length && s[i] in "|&;<>" && i - begin < 3) i++
            val op = s.substring(begin, i)
            emit(op, Kind.OPERATOR)
            val redirect = op.all { it == '<' || it == '>' } || op.contains(">&") || op.contains("&>")
            if (!redirect) endOfCommand()
        }

        private fun grubDevice() {
            val close = s.indexOf(')', i)
            emit(s.substring(i, close + 1), Kind.DEVICE)
            i = close + 1
            // (hd0,gpt2)/boot/vmlinuz: the rest of the word is a path
            if (i < s.length && !s[i].isWhitespace() && s[i] !in "|&;<>()\"'") {
                val rest = takeWhile { !it.isWhitespace() && it !in "|&;<>()\"'" }
                emit(rest, Kind.PATH)
            }
            expectCommand = false
        }

        private fun string(q: Char) {
            i++
            val pieces = ArrayList<Pair<String, Kind>>()
            var chunk = StringBuilder(q.toString())
            fun flush() { if (chunk.isNotEmpty()) { pieces += chunk.toString() to Kind.STRING; chunk = StringBuilder() } }
            while (i < s.length && s[i] != '\n') {
                val ch = s[i]
                if (q == '"' && ch == '\\' && i + 1 < s.length && s[i + 1] != '\n') { chunk.append(ch).append(s[i + 1]); i += 2; continue }
                if (ch == q) { chunk.append(ch); i++; break }
                if (q == '"' && ch == '$' && startsVariable(i)) {
                    flush()
                    val (text, len) = variableAt(i)
                    pieces += text to varKind(text)
                    i += len
                    continue
                }
                chunk.append(ch); i++
            }
            flush()
            pieces.forEach { (t, k) -> emit(t, k) }
            expectCommand = false
        }

        private fun startsVariable(at: Int): Boolean {
            val next = s.getOrNull(at + 1) ?: return false
            return next == '{' || VAR_RE.containsMatchIn(next.toString())
        }

        /** Returns the whole `$name` / `${name}` text at [at] and its length. */
        private fun variableAt(at: Int): Pair<String, Int> {
            if (s.getOrNull(at + 1) == '{') {
                val lineEnd = s.indexOf('\n', at).let { if (it < 0) s.length else it }
                val close = s.indexOf('}', at)
                val end = if (close in 0 until lineEnd) close + 1 else lineEnd
                return s.substring(at, end) to (end - at)
            }
            val len = 1 + (VAR_RE.find(s.substring(at + 1))?.value?.length ?: 0)
            return s.substring(at, at + len) to len
        }

        private fun varKind(text: String): Kind {
            val name = text.removePrefix("$").removePrefix("{").removeSuffix("}")
            return if (isAllCaps(name)) Kind.CAPS else Kind.VARIABLE
        }

        private fun dollar() {
            if (s.startsWith("$(", i)) {
                val ops = if (s.startsWith("$((", i)) "$((" else "$("
                emit(ops, Kind.OPERATOR); i += ops.length
                endOfCommand()
                return
            }
            val (text, len) = variableAt(i)
            if (len == 1) { emit("$", Kind.OPERATOR); i++ } else { emit(text, varKind(text)); i += len }
            expectCommand = false
        }

        private fun word() {
            val begin = i
            val sb = StringBuilder()
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length && s[i + 1] != '\n' && s[i + 1] != '\r') { sb.append(ch).append(s[i + 1]); i += 2; continue }
                if (ch == '\\' && sb.isNotEmpty()) break
                if (ch.isWhitespace() || ch in "|&;<>()\"'$") break
                sb.append(ch); i++
            }
            var w = sb.toString()
            // 2>file, 2>&1: a lone digit glued to a redirect belongs to it
            if (w.length == 1 && w[0].isDigit() && i < s.length && s[i] in "<>") {
                val opBegin = i
                while (i < s.length && s[i] in "<>&" && i - opBegin < 3) i++
                // 2>&1: the number after the & is part of the same symbol
                if (s[i - 1] == '&') while (i < s.length && (s[i].isDigit() || s[i] == '-')) i++
                emit(w + s.substring(opBegin, i), Kind.OPERATOR)
                return
            }
            if (w.isEmpty()) { // a stray character the loop did not consume (should not happen); never loop forever
                emit(s[begin].toString(), Kind.PLAIN); i = begin + 1; return
            }
            classify(w)
        }

        private fun classify(w: String) {
            if (sudoValueNext) { sudoValueNext = false; emit(w, Kind.PLAIN); return }
            // key=value (GRUB_TIMEOUT=5, root=UUID=..., set root=(hd0,gpt2)) but not --long=value (handled below)
            val eq = w.indexOf('=')
            if (!w.startsWith("-") && eq > 0 && KEY_RE.matches(w.substring(0, eq))) {
                val wasCommandPosition = expectCommand && command == null
                keyValue(w, eq)
                if (wasCommandPosition) expectCommand = true // VAR=value cmd args: the command is still to come
                return
            }
            if (w.startsWith("-") && w.length > 1 && !NUM_RE.matches(w)) {
                val e = w.indexOf('=')
                if (e > 1) { emit(w.substring(0, e), Kind.FLAG); emit("=", Kind.OPERATOR); value(w.substring(e + 1)) }
                else emit(w, Kind.FLAG)
                if (command in PRIVILEGE && w in SUDO_VALUE_FLAGS) sudoValueNext = true
                return
            }
            if (expectCommand) { commandWord(w); return }
            value(w)
        }

        private fun keyValue(w: String, eq: Int) {
            val key = w.substring(0, eq)
            emit(key, if (isAllCaps(key)) Kind.CAPS else Kind.KEY)
            emit("=", Kind.OPERATOR)
            value(w.substring(eq + 1))
        }

        private fun value(v: String) {
            if (v.isEmpty()) return
            val eq = v.indexOf('=')
            when {
                UUID_RE.matches(v) -> emit(v, Kind.UUID)
                v.startsWith("/dev/") -> emit(v, Kind.DEVICE)
                v.startsWith("/") || v.startsWith("~") || v.startsWith("./") || v.startsWith("../") || v == "." || v == ".." -> emit(v, Kind.PATH)
                eq > 0 && KEY_RE.matches(v.substring(0, eq)) -> keyValue(v, eq)
                NUM_RE.matches(v) -> emit(v, Kind.NUMBER)
                v.contains('/') -> emit(v, Kind.PATH)
                isAllCaps(v) -> emit(v, Kind.CAPS)
                else -> emit(v, Kind.PLAIN)
            }
            expectCommand = false
        }

        private fun commandWord(w: String) {
            val base = w.substringAfterLast('/')
            when {
                base in PRIVILEGE -> { emit(w, Kind.PRIVILEGE); command = base }
                base in PASS_THROUGH -> { emit(w, Kind.COMMAND); command = base }
                w in KEEPS_COMMAND_POSITION -> { emit(w, Kind.KEYWORD) }
                w in SHELL_KEYWORDS || w in GRUB_KEYWORDS -> { emit(w, Kind.KEYWORD); command = w; expectCommand = false }
                isAllCaps(w) -> { emit(w, Kind.CAPS); expectCommand = false }
                else -> { emit(w, Kind.COMMAND); command = base; expectCommand = false }
            }
        }
    }

    /** Marks risky commands as [Kind.DANGER] and returns plain-language warnings. Works on the finished tokens. */
    private object Analysis {
        private val SEPARATORS = setOf("|", "||", "&&", ";", ";;", "&", "|&")
        private val DISK_TOOLS = setOf("fdisk", "gdisk", "sgdisk", "cfdisk", "sfdisk", "parted", "wipefs", "shred", "mkswap")
        private val READ_ONLY_FLAGS = setOf("-l", "--list", "-p", "--print", "print", "-F", "--print-all", "-s", "--show")
        private val SYSTEM_DIRS = setOf("/", "/etc", "/usr", "/boot", "/home", "/var", "/bin", "/lib", "/sbin", "/root", "/opt")

        fun apply(tokens: MutableList<Token>): List<String> {
            val warnings = LinkedHashSet<String>()
            val segment = ArrayList<Int>() // token indexes of the words in the current command
            fun flush() {
                if (segment.isNotEmpty()) inspect(tokens, segment, warnings)
                segment.clear()
            }
            for ((idx, t) in tokens.withIndex()) {
                when {
                    t.kind == Kind.OPERATOR && t.text in SEPARATORS -> flush()
                    t.kind == Kind.SPACE -> {
                        val continued = tokens.getOrNull(idx - 1)?.text == "\\"
                        if (t.text.contains('\n') && !continued) flush()
                    }
                    t.kind == Kind.COMMENT -> {}
                    else -> segment += idx
                }
            }
            flush()
            pipedIntoShell(tokens, warnings)
            return warnings.toList()
        }

        private fun inspect(tokens: MutableList<Token>, seg: List<Int>, warnings: MutableSet<String>) {
            val cmdIdx = seg.firstOrNull { tokens[it].kind == Kind.COMMAND && tokens[it].text !in PASS_THROUGH } ?: return
            val cmd = tokens[cmdIdx].text.substringAfterLast('/')
            val words = seg.filter { it != cmdIdx }.map { tokens[it] }
            val flags = words.filter { it.kind == Kind.FLAG }.map { it.text }
            val shortFlags = flags.filter { !it.startsWith("--") }.joinToString("") { it.drop(1) }
            val args = words.filter { it.kind != Kind.FLAG && it.kind != Kind.OPERATOR && it.kind != Kind.KEY }.map { it.text }
            fun danger(msg: String) { tokens[cmdIdx] = tokens[cmdIdx].copy(kind = Kind.DANGER); warnings += msg }

            when {
                cmd == "rm" && (("r" in shortFlags || "R" in shortFlags || "--recursive" in flags) && ("f" in shortFlags || "--force" in flags) || "--no-preserve-root" in flags) -> {
                    val aimed = args.firstOrNull { it in SYSTEM_DIRS || it == "/*" || it == "~" || it == "~/" || it == "*" }
                    danger(if (aimed != null) "rm -rf $aimed would delete system or personal files for good. Do not run it."
                    else "rm -rf deletes a folder and everything in it for good: no trash can, no undo. Check the path twice.")
                }
                cmd == "dd" -> danger("dd copies raw bytes. If of= points at a disk, that disk is overwritten at once. Confirm the target with lsblk first.")
                cmd.startsWith("mkfs") -> danger("mkfs formats a partition and erases what was on it. Make sure the device is the one you mean (lsblk -f).")
                cmd in DISK_TOOLS && flags.none { it in READ_ONLY_FLAGS } && args.none { it in READ_ONLY_FLAGS } ->
                    danger("$cmd changes or erases disks. Only run it after a backup and after checking the device name with lsblk.")
                cmd == "grub-install" -> danger("grub-install writes the boot loader. Pointing it at the wrong disk can leave a PC that will not boot. Check with lsblk and efibootmgr -v first.")
                cmd == "efibootmgr" && flags.any { it == "-B" || it == "--delete-bootnum" } -> danger("This deletes a boot menu entry from the firmware. Run efibootmgr -v first and note the entry number.")
                (cmd == "chmod" || cmd == "chown" || cmd == "chgrp") && ("R" in shortFlags || "--recursive" in flags) && args.any { it in SYSTEM_DIRS } ->
                    danger("$cmd -R on a system folder can make the system unusable. Keep it to your own folders.")
                cmd == "btrfs" && flags.contains("--repair") -> danger("btrfs check --repair can damage a filesystem that is only slightly wrong. It is a last resort: back up first and read-only check first.")
                cmd in setOf("reboot", "shutdown", "poweroff", "halt") -> warnings += "$cmd turns the PC off or restarts it. Save your work first."
            }
            // writing straight onto a disk or replacing a config file with a single >
            val redirectIdx = seg.firstOrNull { tokens[it].kind == Kind.OPERATOR && tokens[it].text.matches(Regex("^\\d?>{1,2}$")) }
            if (redirectIdx != null) {
                val target = seg.firstOrNull { it > redirectIdx && tokens[it].kind != Kind.SPACE }?.let { tokens[it] }
                if (target != null && target.text.startsWith("/dev/") && (target.text.contains("sd") || target.text.contains("nvme") || target.text.contains("vd"))) {
                    tokens[cmdIdx] = tokens[cmdIdx].copy(kind = Kind.DANGER)
                    warnings += "Redirecting output onto ${target.text} overwrites that disk."
                } else if (target != null && tokens[redirectIdx].text.endsWith(">") && !tokens[redirectIdx].text.endsWith(">>") &&
                    (target.text.startsWith("/etc/") || target.text.startsWith("/boot/"))) {
                    warnings += "A single > replaces the whole file ${target.text}. Use >> to add to the end, and make a backup copy first."
                }
            }
        }

        private fun pipedIntoShell(tokens: List<Token>, warnings: MutableSet<String>) {
            for ((idx, t) in tokens.withIndex()) {
                if (t.kind == Kind.OPERATOR && t.text == "|") {
                    val next = tokens.drop(idx + 1).firstOrNull { it.kind != Kind.SPACE && it.kind != Kind.PRIVILEGE }
                    if (next != null && next.text in setOf("sh", "bash", "zsh", "dash")) {
                        warnings += "Piping a download into a shell runs whatever the website sends, with no chance to read it first. Download, read it, then run it."
                    }
                }
            }
        }
    }
}
