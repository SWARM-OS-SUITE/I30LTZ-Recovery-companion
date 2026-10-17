package com.example.ui.coderdroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CdrFileOpsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun namesAreValidatedBeforeAnythingIsTouched() {
        listOf("", "  ", " pad", "pad ", ".", "..", "a/b", "a\\b", "x".repeat(256)).forEach {
            assertNotNull("'$it' should be rejected", CdrFileOps.nameProblem(it))
        }
        listOf("Main.kt", ".gitignore", "my file.txt", "x".repeat(255)).forEach {
            assertNull("'$it' should be allowed", CdrFileOps.nameProblem(it))
        }
        val dir = temp.newFolder()
        assertTrue(CdrFileOps.createFile(dir, "../escape.txt").isFailure)
        assertFalse(File(dir.parentFile, "escape.txt").exists())
    }

    @Test fun createFileAndFolderNeverOverwrite() {
        val dir = temp.newFolder()
        val file = CdrFileOps.createFile(dir, "a.txt").getOrThrow().apply { writeText("keep") }
        assertTrue(CdrFileOps.createFile(dir, "a.txt").isFailure)
        assertEquals("keep", file.readText())
        CdrFileOps.createFolder(dir, "src").getOrThrow()
        assertTrue(CdrFileOps.createFolder(dir, "src").isFailure)
        assertTrue("a file cannot be made inside a file", CdrFileOps.createFile(file, "x").isFailure)
    }

    @Test fun renameKeepsContentAndRefusesToClobber() {
        val dir = temp.newFolder()
        val a = File(dir, "a.txt").apply { writeText("A") }
        File(dir, "b.txt").writeText("B")
        assertTrue(CdrFileOps.rename(a, "b.txt").isFailure)
        assertEquals("B", File(dir, "b.txt").readText())
        val moved = CdrFileOps.rename(a, "c.txt").getOrThrow()
        assertEquals("A", moved.readText())
        assertFalse(a.exists())
    }

    @Test fun deleteRemovesAWholeFolderTree() {
        val dir = temp.newFolder()
        val deep = File(dir, "x/y/z").apply { mkdirs() }
        File(deep, "f.txt").writeText("1")
        CdrFileOps.delete(File(dir, "x")).getOrThrow()
        assertFalse(File(dir, "x").exists())
        assertTrue(dir.exists())
    }

    @Test fun deletingASymlinkNeverTouchesItsTarget() {
        val outside = temp.newFolder("outside").also { File(it, "precious.txt").writeText("keep") }
        val project = temp.newFolder("project")
        val link = File(project, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(CdrFileOps.isSymlink(link))
        CdrFileOps.delete(link).getOrThrow()
        assertFalse("link gone", Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("target untouched", "keep", File(outside, "precious.txt").readText())
    }

    @Test fun deleteOfAMissingItemIsHarmless() {
        assertTrue(CdrFileOps.delete(File(temp.root, "nope")).isSuccess)
    }

    @Test fun listingPutsFoldersFirstAndHidesNoiseUnlessAsked() {
        val dir = temp.newFolder()
        File(dir, "zeta.txt").writeText("")
        File(dir, "Alpha.txt").writeText("")
        File(dir, "beta").mkdir()
        File(dir, ".git").mkdir()
        File(dir, "build").mkdir()
        assertEquals(listOf("beta", "Alpha.txt", "zeta.txt"), CdrFileOps.list(dir, showHidden = false)!!.map { it.name })
        assertEquals(setOf(".git", "build", "beta", "Alpha.txt", "zeta.txt"), CdrFileOps.list(dir, showHidden = true)!!.map { it.name }.toSet())
        assertNull("an unreadable or missing folder is reported, not shown as empty", CdrFileOps.list(File(dir, "missing"), true))
    }

    @Test fun binaryFilesAreRecognised() {
        val dir = temp.newFolder()
        val bin = File(dir, "x.bin").apply { writeBytes(byteArrayOf(1, 2, 0, 3)) }
        val txt = File(dir, "x.txt").apply { writeText("hello\nworld") }
        assertTrue(CdrFileOps.looksBinary(bin))
        assertFalse(CdrFileOps.looksBinary(txt))
    }

    @Test fun textSearchFindsLinesAndSkipsBinariesAndNoise() {
        val root = temp.newFolder()
        File(root, "src").mkdir()
        File(root, "src/Main.kt").writeText("fun main() {\n  println(\"Needle here\")\n}\n")
        File(root, "README.md").writeText("nothing\nneedle again")
        File(root, "blob.bin").writeBytes(byteArrayOf(0, 'n'.code.toByte(), 'e'.code.toByte()) + "needle".toByteArray())
        File(root, ".git").mkdir()
        File(root, ".git/config").writeText("needle in git")
        val hits = CdrFileOps.searchText(root, "NEEDLE")
        assertEquals(setOf("Main.kt" to 2, "README.md" to 2), hits.map { it.file.name to it.line }.toSet())
        assertTrue(CdrFileOps.searchText(root, "").isEmpty())
    }

    @Test fun textSearchStopsAtTheHitLimitAndWhenCancelled() {
        val root = temp.newFolder()
        File(root, "many.txt").writeText((1..50).joinToString("\n") { "hit $it" })
        assertEquals(5, CdrFileOps.searchText(root, "hit", maxHits = 5).size)
        assertTrue(CdrFileOps.searchText(root, "hit", isCancelled = { true }).isEmpty())
    }

    @Test fun searchDoesNotFollowSymlinkedFolders() {
        val outside = temp.newFolder("outside").also { File(it, "secret.txt").writeText("needle") }
        val project = temp.newFolder("project")
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        assertTrue(CdrFileOps.searchText(project, "needle").isEmpty())
        assertTrue(CdrFileOps.findFiles(project, "secret").isEmpty())
    }

    @Test fun goToFileMatchesNamesAnywhereInTheTree() {
        val root = temp.newFolder()
        File(root, "a/b").mkdirs()
        File(root, "a/b/CoderDroidScreen.kt").writeText("")
        File(root, "Other.kt").writeText("")
        assertEquals(listOf("CoderDroidScreen.kt"), CdrFileOps.findFiles(root, "droidscreen").map { it.name })
    }

    @Test fun loadReturnsTextAndBlocksUnsafeFiles() {
        val dir = temp.newFolder()
        val ok = File(dir, "a.kt").apply { writeText("fun é() = \"ünï\"\r\nline2") }
        assertEquals("fun é() = \"ünï\"\r\nline2", (CdrFileOps.load(ok) as CdrFileOps.Loaded.Text).text)

        val bin = File(dir, "b.bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        assertTrue(CdrFileOps.load(bin) is CdrFileOps.Loaded.Blocked)

        val latin1 = File(dir, "c.txt").apply { writeBytes("caf\u00e9".toByteArray(Charsets.ISO_8859_1)) }
        val blocked = CdrFileOps.load(latin1) as CdrFileOps.Loaded.Blocked
        assertTrue(blocked.reason.contains("UTF-8"))

        val big = File(dir, "d.txt").apply { writeText("x".repeat(100)) }
        assertTrue(CdrFileOps.load(big, maxBytes = 50) is CdrFileOps.Loaded.Blocked)
        assertTrue(CdrFileOps.load(File(dir, "missing")) is CdrFileOps.Loaded.Blocked)
    }

    @Test fun saveReplacesContentAndLeavesNoTempFiles() {
        val dir = temp.newFolder()
        val f = File(dir, "a.txt").apply { writeText("old") }
        CdrFileOps.saveText(f, "new\ntext").getOrThrow()
        assertEquals("new\ntext", f.readText())
        assertEquals(listOf("a.txt"), dir.list()!!.toList())
    }

    @Test fun saveIntoAMissingFolderFailsWithoutCreatingJunk() {
        val ghost = File(File(temp.root, "gone"), "a.txt")
        assertTrue(CdrFileOps.saveText(ghost, "x").isFailure)
    }
}
