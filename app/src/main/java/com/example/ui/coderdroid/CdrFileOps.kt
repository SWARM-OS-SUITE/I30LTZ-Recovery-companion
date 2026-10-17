package com.example.ui.coderdroid

import java.io.File
import java.io.IOException

/**
 * File operations behind the IDE's explorer: create, rename, delete, list, search.
 *
 * Plain `java.io.File` code with no Android types so every rule is covered by JVM tests. The caller (the
 * view model) is responsible for checking that a target lies inside the open project; this object enforces
 * the rules that hold anywhere: valid names, no overwriting, and never following a symlink out of a folder
 * when deleting or searching.
 */
object CdrFileOps {

    /** Folders that are noise in an explorer and enormous in a search. Shown only when "hidden files" is on. */
    private val NOISE_DIRS = setOf(".git", ".gradle", ".idea", "node_modules", "build", ".kotlin", ".cxx", ".dart_tool")

    /** Returns a plain-language problem with [name] as a single file or folder name, or null when it is fine. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Enter a name."
        name != name.trim() -> "A name cannot start or end with a space."
        name == "." || name == ".." -> "That name is reserved."
        name.any { it == '/' || it == '\\' || it == '\u0000' } -> "A name cannot contain / or \\."
        name.length > 255 -> "That name is too long."
        else -> null
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        requireValid(name)
        require(parent.isDirectory) { "The target is not a folder." }
        val file = File(parent, name)
        if (file.exists()) throw IOException("\"$name\" already exists here.")
        if (!file.createNewFile()) throw IOException("Could not create \"$name\".")
        file
    }

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        requireValid(name)
        require(parent.isDirectory) { "The target is not a folder." }
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("\"$name\" already exists here.")
        if (!dir.mkdir()) throw IOException("Could not create folder \"$name\".")
        dir
    }

    /** Renames within the same folder; never overwrites an existing entry. */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        requireValid(newName)
        require(file.exists()) { "That item no longer exists." }
        val target = File(file.parentFile ?: throw IOException("Cannot rename the top folder."), newName)
        if (target.exists()) throw IOException("\"$newName\" already exists here.")
        if (!file.renameTo(target)) throw IOException("Could not rename to \"$newName\".")
        target
    }

    /**
     * Deletes a file or a whole folder. A symlink is removed as a link and its target is never entered, so a
     * link inside a project that points at /sdcard or / cannot make a delete spill outside the project.
     */
    fun delete(file: File): Result<Unit> = runCatching {
        if (!file.exists() && !isSymlink(file)) return@runCatching
        deleteTree(file)
    }

    private fun deleteTree(file: File) {
        if (!isSymlink(file) && file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        if (!file.delete() && (file.exists() || isSymlink(file))) throw IOException("Could not delete ${file.name}.")
    }

    fun isSymlink(file: File): Boolean {
        val parent = file.parentFile ?: return false
        val inParent = File(parent.canonicalFile, file.name)
        return inParent.canonicalFile != inParent.absoluteFile
    }

    /** Children of [dir]: folders first, then files, each A to Z ignoring case. Unreadable folders give null. */
    fun list(dir: File, showHidden: Boolean): List<File>? {
        val children = dir.listFiles() ?: return null
        return children
            .filter { showHidden || !isNoise(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun isNoise(file: File): Boolean = file.isDirectory && file.name in NOISE_DIRS

    /** Result of opening a file for editing. Anything not [Text] is shown read-only, never saved back. */
    sealed interface Loaded {
        data class Text(val text: String) : Loaded
        data class Blocked(val reason: String) : Loaded
    }

    /**
     * Reads [file] for the editor, but only when it is safe to write back unchanged: small enough, not
     * binary, and strictly valid UTF-8. A file in another encoding would otherwise be silently corrupted
     * the first time it was saved.
     */
    fun load(file: File, maxBytes: Long = 2L * 1024 * 1024): Loaded {
        if (!file.isFile) return Loaded.Blocked("This file no longer exists.")
        if (file.length() > maxBytes) return Loaded.Blocked("This file is larger than ${maxBytes / (1024 * 1024)} MB, so it is not opened in the editor.")
        return try {
            val bytes = file.readBytes()
            if (bytes.take(8000).any { it == 0.toByte() }) return Loaded.Blocked("This looks like a binary file, so it is shown read-only.")
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            Loaded.Text(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        } catch (_: java.nio.charset.CharacterCodingException) {
            Loaded.Blocked("This file is not UTF-8 text, so it is shown read-only to avoid corrupting it.")
        } catch (e: IOException) {
            Loaded.Blocked("Could not read this file: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Saves text without ever leaving a half-written file: it goes to a temporary file in the same folder and
     * is renamed over the original. If the rename is refused (some removable-storage filesystems), it falls
     * back to writing in place.
     */
    fun saveText(file: File, text: String): Result<Unit> = runCatching {
        val dir = file.parentFile ?: throw IOException("No folder for ${file.name}.")
        val temp = File(dir, ".${file.name}.cdr-${System.nanoTime()}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) file.writeText(text)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** True when the first bytes contain a NUL, which is how text editors tell a binary from text. */
    fun looksBinary(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(8000)
            val n = input.read(head)
            (0 until maxOf(n, 0)).any { head[it] == 0.toByte() }
        }
    } catch (_: IOException) {
        false
    }

    data class SearchHit(val file: File, val line: Int, val text: String)

    /**
     * Finds [query] (case-insensitive) in text files under [root]. Skips binaries, files over [maxFileBytes],
     * noise folders and symlinked folders, and stops after [maxHits] so a broad query on a big project is
     * still quick. Checks [isCancelled] between files so a new search can abandon an old one.
     */
    fun searchText(
        root: File,
        query: String,
        maxHits: Int = 200,
        maxFileBytes: Long = 1_000_000,
        isCancelled: () -> Boolean = { false },
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        fun walk(dir: File) {
            val children = list(dir, showHidden = false) ?: return
            for (child in children) {
                if (hits.size >= maxHits || isCancelled()) return
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.length() <= maxFileBytes && !looksBinary(child)) {
                    runCatching {
                        child.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (hits.size < maxHits && line.contains(query, ignoreCase = true)) {
                                    hits += SearchHit(child, index + 1, line.trim().take(160))
                                }
                            }
                        }
                    }
                }
            }
        }
        walk(root)
        return hits
    }

    /** Files under [root] whose name contains [query], for "go to file". Same skipping rules as [searchText]. */
    fun findFiles(root: File, query: String, maxHits: Int = 60, isCancelled: () -> Boolean = { false }): List<File> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<File>()
        fun walk(dir: File) {
            val children = list(dir, showHidden = false) ?: return
            for (child in children) {
                if (hits.size >= maxHits || isCancelled()) return
                if (isSymlink(child)) continue
                if (child.isDirectory) walk(child)
                else if (child.name.contains(query, ignoreCase = true)) hits += child
            }
        }
        walk(root)
        return hits
    }

    private fun requireValid(name: String) {
        nameProblem(name)?.let { throw IllegalArgumentException(it) }
    }
}
