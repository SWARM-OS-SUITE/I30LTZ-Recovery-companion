package com.example.ui.coderdroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CdrTemplatesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun anEmptyFolderGetsEveryFileOfTheTemplate() {
        val root = temp.newFolder()
        val outcome = CdrTemplates.scaffold(root, CdrTemplates.Template.RUST).getOrThrow()
        assertEquals(listOf("Cargo.toml", "src/main.rs"), outcome.created)
        assertTrue(outcome.skipped.isEmpty())
        assertTrue(File(root, "src/main.rs").readText().contains("fn main"))
    }

    @Test fun existingFilesAreNeverOverwritten() {
        val root = temp.newFolder()
        File(root, "app/src/main/java/com/example").mkdirs()
        val real = File(root, "app/build.gradle.kts").apply { writeText("// the real build file") }
        val realMain = File(root, "app/src/main/java/com/example/MainActivity.kt").apply { writeText("// the real activity") }

        val outcome = CdrTemplates.scaffold(root, CdrTemplates.Template.ANDROID).getOrThrow()

        assertEquals("// the real build file", real.readText())
        assertEquals("// the real activity", realMain.readText())
        assertEquals(listOf("app/src/main/AndroidManifest.xml"), outcome.created)
        assertEquals(setOf("app/build.gradle.kts", "app/src/main/java/com/example/MainActivity.kt"), outcome.skipped.toSet())
    }

    @Test fun runningTwiceCreatesNothingNew() {
        val root = temp.newFolder()
        CdrTemplates.scaffold(root, CdrTemplates.Template.PYTHON).getOrThrow()
        val second = CdrTemplates.scaffold(root, CdrTemplates.Template.PYTHON).getOrThrow()
        assertTrue(second.created.isEmpty())
        assertEquals(2, second.skipped.size)
        assertTrue(second.summary().startsWith("Nothing was created"))
    }

    @Test fun aMissingFolderIsAnErrorNotAFalseSuccess() {
        assertTrue(CdrTemplates.scaffold(File(temp.root, "gone"), CdrTemplates.Template.RUST).isFailure)
    }

    @Test fun summaryReadsInPlainWords() {
        assertEquals("Created 2 files.", CdrTemplates.Outcome(listOf("a", "b"), emptyList()).summary())
        assertEquals("Created 1 file; left 1 existing file untouched (x).", CdrTemplates.Outcome(listOf("a"), listOf("x")).summary())
    }
}
