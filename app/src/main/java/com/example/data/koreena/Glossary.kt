package com.example.data.koreena

/** One line of the "what each part means" list. */
data class BreakdownItem(val token: Token, val explanation: String)

/**
 * Plain-language meanings for the parts of a command, written for a beginner. Works with no model and no
 * internet: this is the ground truth Miss Koreena shows first, and the facts she is handed before she talks.
 */
object Glossary {

    fun breakdown(reading: Reading): List<BreakdownItem> {
        val seen = HashSet<String>()
        val items = ArrayList<BreakdownItem>()
        fun add(t: Token, text: String?) {
            if (text == null || !seen.add(t.kind.name + "|" + t.command + "|" + t.text)) return
            items += BreakdownItem(t, text)
        }
        for (t in reading.tokens) {
            when (t.kind) {
                Kind.SPACE, Kind.COMMENT -> {}
                Kind.OPERATOR -> if (t.text != "(" && t.text != ")") add(t, operator(t.text))
                Kind.STRING -> {
                    // "quiet splash" after GRUB_CMDLINE_LINUX_DEFAULT: explain each kernel word inside it
                    val inner = t.text.trim('"', '\'')
                    var any = false
                    inner.split(Regex("\\s+")).forEach { w ->
                        kernelParam(w)?.let { any = true; add(Token(w, Kind.PLAIN, t.start, t.command), it) }
                    }
                    if (!any) add(t, "Text in quotes. Quotes keep spaces and symbols together as one piece.")
                }
                else -> add(t, explain(t))
            }
        }
        return items
    }

    fun explain(t: Token): String? = when (t.kind) {
        Kind.COMMAND, Kind.DANGER -> command(t.text.substringAfterLast('/'))
        Kind.PRIVILEGE -> when (t.text.substringAfterLast('/')) {
            "su" -> "Switch to another user (root unless you name one). Everything after it runs with that user's rights."
            else -> "Runs the rest of the line as the administrator (root). Linux asks for your password first. Anything wrong now can break the system, so read what follows before pressing Enter."
        }
        Kind.KEYWORD -> keyword(t.text)
        Kind.FLAG -> flag(t.command, t.text)
        Kind.CAPS -> caps(t.text)
        Kind.KEY -> kernelParam(t.text + "=") ?: "A setting name. What comes after the = is its value."
        Kind.PATH -> path(t.text)
        Kind.DEVICE -> device(t.text)
        Kind.UUID -> "A UUID: the permanent ID of a filesystem. Safer than /dev/sda1, because disk names can change between boots. Find yours with: blkid   or   lsblk -f"
        Kind.VARIABLE -> "A variable: a named box holding a value. ${t.text} is replaced by what is inside the box when the line runs."
        Kind.NUMBER -> "A number. Its meaning depends on the command (a count, a size, a seconds value or an ID)."
        Kind.PLAIN -> kernelParam(t.text) ?: "An argument: what you hand to ${t.command?.let { "'$it'" } ?: "the command"} to work on, such as a file name or a value."
        else -> null
    }

    // ── commands ──────────────────────────────────────────────────────────────

    private val COMMANDS = mapOf(
        "ls" to "List files and folders. Add -l for details, -a to show hidden ones.",
        "cd" to "Change directory: move into a folder.",
        "pwd" to "Print the folder you are in right now.",
        "cp" to "Copy a file or folder.",
        "mv" to "Move or rename a file.",
        "rm" to "Remove (delete) files. There is no trash can: gone means gone.",
        "mkdir" to "Make a new folder.",
        "cat" to "Print a file's contents on the screen.",
        "less" to "Read a long file one screen at a time. Press q to quit.",
        "head" to "Show the first lines of a file.",
        "tail" to "Show the last lines of a file. With -f it keeps following new lines.",
        "grep" to "Search text for a pattern and print the matching lines.",
        "find" to "Search for files by name, size or age.",
        "echo" to "Print the text you give it.",
        "touch" to "Create an empty file, or update a file's timestamp.",
        "chmod" to "Change who may read, write or run a file.",
        "chown" to "Change which user owns a file.",
        "sudo" to "Run a command as the administrator.",
        "ps" to "List running programs.",
        "top" to "Live list of what is using the CPU and memory. Press q to quit.",
        "kill" to "Stop a program by its process number.",
        "df" to "Show how full each mounted disk is. -h gives sizes people can read.",
        "du" to "Show how much space folders use.",
        "free" to "Show memory (RAM) use.",
        "mount" to "Attach a disk or partition to a folder so you can read it. With no arguments it lists what is mounted.",
        "umount" to "Detach a mounted disk. Do this before unplugging a drive.",
        "lsblk" to "List disks and partitions as a tree. Safe, read-only. -f adds filesystem types and UUIDs.",
        "blkid" to "Show each partition's UUID and filesystem type. Read-only.",
        "fdisk" to "Partition tool. fdisk -l only lists; without -l it can change the partition table.",
        "parted" to "Partition tool. parted -l only lists.",
        "dd" to "Copy raw data block by block, often used to write an ISO onto a USB stick. Very powerful and easy to point at the wrong disk.",
        "systemctl" to "Control services: start, stop, enable at boot, check status.",
        "journalctl" to "Read the system log. -b means this boot, -p err means only errors.",
        "apt" to "Install and update software on Debian and Ubuntu.",
        "pacman" to "Install and update software on Arch-based systems.",
        "dnf" to "Install and update software on Fedora.",
        "ip" to "Show and change network settings. ip -br address lists your addresses briefly.",
        "ping" to "Check whether another computer answers over the network.",
        "ssh" to "Open a secure shell on another computer.",
        "scp" to "Copy files to or from another computer over ssh.",
        "rsync" to "Copy folders efficiently, only sending what changed.",
        "tar" to "Pack or unpack archive files (.tar, .tar.gz).",
        "curl" to "Fetch something from a web address.",
        "wget" to "Download a file from a web address.",
        "nano" to "A beginner-friendly text editor in the terminal. Ctrl+O saves, Ctrl+X exits.",
        "vim" to "A powerful text editor in the terminal. Press Esc then :q to quit.",
        "export" to "Make a variable available to the programs you start afterwards.",
        "source" to "Run a script's lines inside the current shell, so its settings stay.",
        "bash" to "The shell program itself. bash script.sh runs a script.",
        "sh" to "A basic shell. Running downloaded text through sh runs it as commands.",
        "python3" to "Run a Python 3 program.",
        "git" to "Track changes to a project: commit, pull, push.",
        "adb" to "Android Debug Bridge: talk to a connected Android phone. adb devices lists them.",
        "uname" to "Show system information. -r prints the kernel version.",
        "lsusb" to "List USB devices.",
        "lspci" to "List PCI devices such as the graphics card.",
        "efibootmgr" to "Show or change the UEFI boot menu entries. -v lists them with details.",
        "grub-install" to "Install the GRUB boot loader onto a disk or an EFI partition.",
        "update-grub" to "Rebuild GRUB's menu (grub.cfg) from /etc/default/grub and the installed systems. Debian and Ubuntu.",
        "grub-mkconfig" to "Generate GRUB's menu file. -o says where to write it, usually /boot/grub/grub.cfg.",
        "grub-mkpasswd-pbkdf2" to "Make a hashed password you can put in GRUB's config.",
        "os-prober" to "Look for other operating systems on the disks so GRUB can list them.",
        "fsck" to "Check and repair a filesystem. Only run it on an unmounted partition.",
        "ntfsfix" to "Clear the dirty flag on a Windows NTFS partition so Linux can mount it.",
        "btrfs" to "Tools for the Btrfs filesystem: subvolumes, snapshots, checks.",
        "smartctl" to "Read a drive's health report (SMART). smartctl -a /dev/sda shows all of it.",
        "ddrescue" to "Copy data off a failing drive, skipping bad spots first.",
        "testdisk" to "Find and recover lost partitions.",
        "photorec" to "Recover deleted files by their content.",
        "chroot" to "Run a command with a different folder as the root. Used to repair an installed system from a live USB.",
        "arch-chroot" to "chroot, but it also sets up /dev, /proc and /sys for you.",
        "genfstab" to "Write an fstab (list of disks to mount at boot) from what is mounted now.",
        "mkfs" to "Make a filesystem on a partition. Erases what was there.",
        "mkswap" to "Prepare a partition or file as swap space.",
        "wipefs" to "Remove filesystem signatures from a device.",
        "reboot" to "Restart the computer.",
        "shutdown" to "Power the computer off, now or at a set time.",
        "env" to "Run a command with certain variables set, or list the variables.",
        "time" to "Run a command and report how long it took.",
        "tailscale" to "Control the Tailscale private network.",
        "nmcli" to "Control NetworkManager: Wi-Fi and wired connections.",
    )

    private fun command(name: String): String =
        COMMANDS[name] ?: if (name.startsWith("mkfs")) COMMANDS["mkfs"]!! else
            "A program named '$name'. The first word of a line is the program being run. To read its manual, run: man $name   or   $name --help"

    // ── GRUB and shell keywords ───────────────────────────────────────────────

    private val KEYWORDS = mapOf(
        "menuentry" to "Defines one line in the GRUB boot menu. The quoted text is its title; the commands inside { } run when you pick it.",
        "submenu" to "A boot-menu entry that opens another list of entries.",
        "insmod" to "Load a GRUB module (a driver) such as part_gpt or ext2, so GRUB can read that kind of disk.",
        "rmmod" to "Unload a GRUB module.",
        "linux" to "Load the Linux kernel. The text after the file name is handed to the kernel as parameters.",
        "linuxefi" to "Like linux, on UEFI systems that load the kernel through the firmware.",
        "linux16" to "Like linux, for the old BIOS boot path.",
        "initrd" to "Load the initial RAM disk: a small starter filesystem the kernel uses to find your real disk.",
        "initrdefi" to "Like initrd, on UEFI systems.",
        "chainloader" to "Hand over to another boot loader, for example Windows' bootmgfw.efi.",
        "configfile" to "Load another GRUB config file and show its menu.",
        "search" to "Find a partition by UUID or label and store it in a variable (usually root).",
        "set" to "Set a GRUB variable, such as set root=(hd0,gpt2) or set timeout=5.",
        "unset" to "Clear a GRUB variable.",
        "boot" to "Start the loaded kernel.",
        "loopback" to "Treat an ISO file as a disk, so you can boot from it.",
        "load_env" to "Load saved GRUB variables from the grubenv file.",
        "save_env" to "Save a GRUB variable to the grubenv file.",
        "terminal_output" to "Choose where GRUB prints: console or gfxterm.",
        "terminal_input" to "Choose where GRUB reads keys from.",
        "if" to "Start a test: run the commands after then only if the test is true.",
        "then" to "The commands that run when the test after if was true.",
        "else" to "The commands that run when the test was false.",
        "elif" to "Another test, tried when the previous one was false.",
        "fi" to "Ends an if block (it is if spelled backwards).",
        "for" to "Repeat commands once for each item in a list.",
        "while" to "Repeat commands as long as a test stays true.",
        "until" to "Repeat commands until a test becomes true.",
        "do" to "Opens the commands of a for or while loop.",
        "done" to "Closes a loop.",
        "case" to "Choose between several patterns, like a menu of if tests.",
        "esac" to "Ends a case block (case spelled backwards).",
        "in" to "Separates the thing being looped over or matched from its list.",
        "function" to "Defines a reusable named group of commands.",
        "select" to "Show a numbered menu in the terminal.",
        "reboot" to "Restart the machine from inside GRUB.",
        "halt" to "Power off from inside GRUB.",
    )

    private fun keyword(word: String) = KEYWORDS[word] ?: "A keyword: a word the shell or GRUB treats as part of its own language."

    // ── ALL CAPS ──────────────────────────────────────────────────────────────

    private val CAPS = mapOf(
        "GRUB_DEFAULT" to "Which menu entry GRUB picks by default: a number (0 is the first), the entry's title, or 'saved'.",
        "GRUB_TIMEOUT" to "How many seconds GRUB waits at the menu before booting the default. 0 skips the menu.",
        "GRUB_TIMEOUT_STYLE" to "menu shows the menu, hidden waits invisibly (hold Shift or Esc to open it), countdown shows only the timer.",
        "GRUB_CMDLINE_LINUX_DEFAULT" to "Kernel parameters for a normal boot. Words like quiet and splash go here.",
        "GRUB_CMDLINE_LINUX" to "Kernel parameters used on every boot, including recovery mode.",
        "GRUB_DISTRIBUTOR" to "The name GRUB shows for this Linux in the menu.",
        "GRUB_DISABLE_OS_PROBER" to "true stops GRUB from searching other disks for other systems; false lets it add them to the menu.",
        "GRUB_DISABLE_RECOVERY" to "true hides the recovery-mode entries.",
        "GRUB_DISABLE_SUBMENU" to "true lists older kernels in the main menu instead of a submenu.",
        "GRUB_GFXMODE" to "Screen resolution for the GRUB menu, like 1920x1080.",
        "GRUB_GFXPAYLOAD_LINUX" to "Whether the kernel keeps GRUB's screen mode while it starts.",
        "GRUB_THEME" to "Path to a theme file that styles the GRUB menu.",
        "GRUB_BACKGROUND" to "Path to an image used as the GRUB menu background.",
        "GRUB_TERMINAL" to "Where GRUB shows its menu: console, gfxterm or serial.",
        "GRUB_SAVEDEFAULT" to "With GRUB_DEFAULT=saved, remember the last entry you booted.",
        "GRUB_ENABLE_CRYPTODISK" to "Let GRUB unlock an encrypted /boot.",
        "GRUB_DISABLE_LINUX_UUID" to "true makes GRUB use /dev/sdX names instead of UUIDs. Usually leave false.",
        "UUID" to "In root=UUID=... it tells the kernel to find the root filesystem by its unique ID.",
        "PARTUUID" to "The partition's own ID (from the partition table), not the filesystem's.",
        "LABEL" to "Find a filesystem by its label name.",
        "HOME" to "Your home folder (like /home/you).",
        "PATH" to "The list of folders searched for programs when you type a command name.",
        "USER" to "Your user name.",
        "PWD" to "The folder you are in now.",
        "SHELL" to "Your login shell program.",
        "EDITOR" to "The text editor programs open when they need you to edit something.",
        "LANG" to "Your language and character set.",
        "DISPLAY" to "Which screen graphical programs should draw on.",
        "EFI" to "UEFI: the modern firmware that starts the PC. EFI is also the name of the small boot partition.",
        "ESP" to "EFI System Partition: the small FAT partition that holds boot loaders on UEFI PCs.",
    )

    private fun caps(word: String): String {
        val name = word.removePrefix("$").removePrefix("{").removeSuffix("}")
        CAPS[name]?.let { return it }
        return "Written in capitals: a name for a setting or variable (like $name). Capitals are only a habit that makes these names stand out. Linux is case-sensitive, so type it exactly as shown."
    }

    // ── kernel parameters ─────────────────────────────────────────────────────

    private val KERNEL = mapOf(
        "quiet" to "Kernel parameter: hide most start-up messages.",
        "splash" to "Kernel parameter: show the graphical boot splash instead of text.",
        "ro" to "Kernel parameter: mount the root filesystem read-only at first. The system remounts it read-write once the checks are done.",
        "rw" to "Kernel parameter: mount the root filesystem read-write from the start.",
        "nomodeset" to "Kernel parameter: do not switch the graphics mode early. A common fix for a black screen with some graphics cards.",
        "single" to "Kernel parameter: boot to single-user (rescue) mode.",
        "rescue" to "Kernel parameter: boot into rescue mode.",
        "emergency" to "Kernel parameter: boot to a minimal emergency shell.",
        "noapic" to "Kernel parameter: turn off APIC interrupt handling. A last-resort workaround for hardware that hangs at boot.",
        "acpi=off" to "Kernel parameter: turn off power management. A last-resort workaround.",
        "root=" to "Kernel parameter: which partition holds the root filesystem (/).",
        "init=" to "Kernel parameter: the first program the kernel starts, normally systemd.",
        "resume=" to "Kernel parameter: where to restore the saved memory image after hibernation.",
        "rootflags=" to "Kernel parameter: mount options for the root filesystem, like subvol=@ on Btrfs.",
        "rd.break" to "Kernel parameter: stop in the initramfs, before the real root is mounted. Used for password resets and repairs.",
        "systemd.unit=" to "Kernel parameter: boot to a chosen target, like rescue.target or multi-user.target.",
        "loglevel=" to "Kernel parameter: how many kernel messages to show, 0 to 7.",
    )

    private fun kernelParam(word: String): String? =
        KERNEL[word] ?: KERNEL.entries.firstOrNull { it.key.endsWith("=") && word.startsWith(it.key) }?.value

    // ── options ───────────────────────────────────────────────────────────────

    private val COMMAND_FLAGS = mapOf(
        "ls" to mapOf("-l" to "long list with sizes, owners and dates", "-a" to "include hidden files (names starting with .)", "-h" to "sizes people can read (K, M, G)", "-R" to "go into sub-folders too"),
        "rm" to mapOf("-r" to "go into folders (recursive)", "-R" to "go into folders (recursive)", "-f" to "force: no questions, ignore missing files", "-i" to "ask before each deletion", "--recursive" to "go into folders", "--force" to "no questions"),
        "cp" to mapOf("-r" to "copy folders too", "-a" to "archive: keep permissions, dates and links", "-v" to "say what is being copied"),
        "mkdir" to mapOf("-p" to "also create missing parent folders"),
        "grep" to mapOf("-i" to "ignore upper/lower case", "-r" to "search every file in the folder", "-n" to "show line numbers", "-v" to "show lines that do NOT match", "-E" to "use extended patterns"),
        "tail" to mapOf("-f" to "keep following new lines as they appear", "-n" to "how many lines"),
        "df" to mapOf("-h" to "sizes people can read"),
        "du" to mapOf("-h" to "sizes people can read", "-s" to "one total per argument"),
        "lsblk" to mapOf("-f" to "show filesystem type, label and UUID", "-o" to "choose the columns to show"),
        "journalctl" to mapOf("-b" to "only this boot", "-p" to "only messages at or above this level (err = errors)", "-u" to "only this service", "-n" to "how many lines", "--no-pager" to "print everything instead of paging"),
        "systemctl" to mapOf("--now" to "also start or stop it right away"),
        "apt" to mapOf("-y" to "answer yes to every question"),
        "chmod" to mapOf("-R" to "apply to everything inside folders too"),
        "chown" to mapOf("-R" to "apply to everything inside folders too"),
        "tar" to mapOf("-x" to "extract", "-c" to "create", "-z" to "gzip compression", "-f" to "the archive file name follows", "-v" to "list files as they are processed"),
        "grub-install" to mapOf("--target" to "which platform to install for, e.g. x86_64-efi", "--efi-directory" to "where the EFI partition is mounted", "--bootloader-id" to "the name shown in the firmware boot menu", "--recheck" to "re-detect devices"),
        "grub-mkconfig" to mapOf("-o" to "write the result to this file"),
        "efibootmgr" to mapOf("-v" to "verbose: show paths and details", "-B" to "delete a boot entry", "-o" to "set the boot order", "-c" to "create a new entry"),
        "dd" to mapOf("bs" to "block size per read/write", "if" to "input file", "of" to "output file: where the data is WRITTEN"),
        "ssh" to mapOf("-p" to "port number", "-i" to "identity (key) file", "-L" to "forward a local port"),
        "curl" to mapOf("-o" to "save to this file name", "-O" to "save with the remote file's name", "-L" to "follow redirects", "-s" to "silent", "-f" to "fail on HTTP errors"),
        "rsync" to mapOf("-a" to "archive: keep permissions, dates and links", "-v" to "verbose", "-n" to "dry run: show what would happen", "--progress" to "show progress"),
        "smartctl" to mapOf("-a" to "show all health information", "-H" to "show only the overall health verdict"),
        "mount" to mapOf("-o" to "mount options (ro, subvol=@, ...)", "-t" to "filesystem type"),
        "ip" to mapOf("-br" to "brief one-line-per-interface output", "-c" to "colour the output"),
    )

    private val GENERIC_FLAGS = mapOf(
        "-h" to "help or human-readable sizes, depending on the command",
        "--help" to "print how to use the command and exit",
        "-v" to "verbose: say more about what it is doing",
        "--version" to "print the version and exit",
        "-y" to "answer yes to every question",
        "-f" to "force",
        "-r" to "recursive: include folders and what is inside",
        "-R" to "recursive: include folders and what is inside",
        "-o" to "output: the name of the file or place to write to follows",
        "-i" to "interactive or ignore-case, depending on the command",
        "-n" to "a number, a count, or a dry run, depending on the command",
        "-p" to "path, port or parents, depending on the command",
        "-q" to "quiet: print less",
        "-l" to "long format or list, depending on the command",
        "-a" to "all, or archive, depending on the command",
        "--force" to "do it without asking",
        "--quiet" to "print less",
        "--verbose" to "say more about what it is doing",
        "--dry-run" to "show what would happen without doing it",
        "--no-pager" to "print everything instead of paging it",
    )

    private fun flag(command: String?, text: String): String {
        val name = text.substringBefore('=')
        command?.let { COMMAND_FLAGS[it] }?.get(name)?.let { return "Option for $command: $it." }
        // -rf, -la, -xzvf: several one-letter options stuck together
        if (!name.startsWith("--") && name.length > 2 && command != null) {
            val map = COMMAND_FLAGS[command].orEmpty()
            val letters = name.drop(1).map { "-$it" }
            val known = letters.mapNotNull { l -> (map[l] ?: GENERIC_FLAGS[l])?.let { "$l = $it" } }
            if (known.size == letters.size) return "Several one-letter options written together: " + known.joinToString("; ") + "."
        }
        GENERIC_FLAGS[name]?.let { return "Option: $it." }
        return "An option for ${command?.let { "'$it'" } ?: "the command"}. Options start with - (short) or -- (long). See: ${command ?: "command"} --help"
    }

    // ── places and symbols ────────────────────────────────────────────────────

    private val PATHS = mapOf(
        "/etc/default/grub" to "GRUB's settings file. Edit it, then run update-grub (Debian) or grub-mkconfig (others) to apply.",
        "/boot/grub/grub.cfg" to "The menu GRUB actually reads. It is generated, so do not edit it by hand: change /etc/default/grub and regenerate.",
        "/boot/grub" to "Where GRUB keeps its files and menu.",
        "/boot/efi" to "Where the EFI system partition is usually mounted.",
        "/boot" to "Kernels, initramfs files and the boot loader.",
        "/etc/fstab" to "The list of disks that are mounted at boot. A wrong line here can stop the system booting.",
        "/etc" to "System-wide settings files.",
        "/etc/passwd" to "The list of user accounts (not the passwords).",
        "/etc/shadow" to "The hashed passwords. Only root can read it.",
        "/home" to "Where each user's personal files live.",
        "/dev" to "Device files: your disks, USB sticks and terminals appear here.",
        "/var/log" to "System log files.",
        "/tmp" to "Temporary files, cleared on reboot.",
        "/mnt" to "A conventional place to mount a disk by hand.",
        "/" to "The root of the filesystem: the top of the folder tree.",
        "~" to "Your home folder.",
        "." to "The current folder.",
        ".." to "The folder above the current one.",
    )

    private fun path(p: String): String {
        PATHS[p]?.let { return it }
        PATHS.entries.filter { p.startsWith(it.key + "/") && it.key.length > 1 }.maxByOrNull { it.key.length }?.let { return "A path inside ${it.key}. ${it.value}" }
        return when {
            p.startsWith("/") -> "A path: a file or folder location. It starts with / so it is counted from the very top of the folder tree."
            p.startsWith("~") -> "A path in your home folder."
            p.startsWith("./") || p.startsWith("../") -> "A path counted from the folder you are in now."
            else -> "A path: a file or folder location."
        }
    }

    private fun device(d: String): String {
        Regex("^\\((hd|cd|fd)(\\d+)(?:,(gpt|msdos)?(\\d+))?\\)$").find(d)?.let { m ->
            val kind = when (m.groupValues[1]) { "hd" -> "disk"; "cd" -> "optical drive"; else -> "floppy" }
            val disk = "${m.groupValues[1]}${m.groupValues[2]} = $kind number ${m.groupValues[2]} (GRUB counts from 0, so hd0 is the first disk)"
            val part = if (m.groupValues[4].isNotEmpty()) {
                val table = if (m.groupValues[3] == "msdos") "an old MBR (msdos) partition table" else "a GPT partition table"
                "; ${m.groupValues[3]}${m.groupValues[4]} = partition ${m.groupValues[4]} on $table (partitions count from 1)"
            } else ""
            return "GRUB's way of naming a disk: $disk$part."
        }
        if (d.startsWith("(")) return "GRUB's name for a disk or device."
        Regex("^/dev/(sd|vd|hd)([a-z]+)(\\d*)$").find(d)?.let { m ->
            val prefix = when (m.groupValues[1]) { "sd" -> "SATA/USB/SCSI disk"; "vd" -> "virtual disk"; else -> "IDE disk" }
            val partition = if (m.groupValues[3].isNotEmpty()) ", partition ${m.groupValues[3]}" else " (the whole disk)"
            return "A $prefix named ${m.groupValues[1]}${m.groupValues[2]}: letter ${m.groupValues[2]} = which disk (a = first, b = second)$partition. Names can change when you plug drives in or out. Check with lsblk first."
        }
        Regex("^/dev/nvme(\\d+)n(\\d+)(?:p(\\d+))?$").find(d)?.let { m ->
            val partition = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ", partition $it" } ?: " (the whole drive)"
            return "An NVMe SSD: controller ${m.groupValues[1]}, namespace ${m.groupValues[2]}$partition. Check with lsblk first."
        }
        return when (d) {
            "/dev/null" -> "The bit bucket: anything written here disappears."
            "/dev/zero" -> "Endless zero bytes."
            "/dev/urandom" -> "Endless random bytes."
            else -> if (d.startsWith("/dev/mapper/")) "A device-mapper volume: an unlocked encrypted partition or an LVM volume."
            else "A device file under /dev: hardware shown as a file."
        }
    }

    private fun operator(op: String): String? = when {
        op == "|" -> "Pipe: sends the output of the command on the left into the command on the right."
        op == "||" -> "OR: run the right side only if the left side failed."
        op == "&&" -> "AND: run the right side only if the left side worked."
        op == ";" -> "Run the next command after this one finishes, whatever happened."
        op == "&" -> "Run the command in the background."
        op == ">" -> "Redirect: write the output into a file, replacing what was there."
        op == ">>" -> "Redirect: add the output to the end of a file."
        op == "<" -> "Redirect: read input from a file."
        op == "<<" || op == "<<<" -> "Here-document / here-string: feed the following text to the command as input."
        op.startsWith("2>") || op == ">&" || op == "&>" -> "Redirect error messages (stream 2) or join them with the normal output."
        op == "=" -> "Assignment: the name on the left gets the value on the right. No spaces around it."
        op == "\\" -> "A backslash at the end of a line means the command continues on the next line."
        op == "{" -> "Opens a block of commands."
        op == "}" -> "Closes a block of commands."
        op == "$(" -> "Command substitution: run what is inside and put its output here."
        op == "$((" -> "Arithmetic: calculate what is inside."
        else -> null
    }
}
