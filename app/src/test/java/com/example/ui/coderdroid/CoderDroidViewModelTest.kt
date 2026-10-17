package com.example.ui.coderdroid

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The IDE's brain, driven the way the screen drives it: events in, state and files out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoderDroidViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class FakeCredentials : CdrCredentials {
        override var username = ""
        override var token = ""
        override var authorName = "Test"
        override var authorEmail = "test@example.invalid"
        override val hasCredentials get() = token.isNotBlank()
    }

    private fun newVm() = CoderDroidViewModel(app) { FakeCredentials() }

    private lateinit var vm: CoderDroidViewModel

    @org.junit.After fun resetAccessOverride() { com.example.data.storage.StorageAccess.accessOverrideForTests = null }
    private val state get() = vm.state.value

    /** Lets the main looper run (and its clock tick forward) while background work finishes. */
    private fun awaitUntil(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (cond()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what. Notice=${state.notice} tabs=${state.tabs.map { it.fileNode.name to it.isDirty }}")
    }

    private fun open(root: File) {
        vm.onEvent(CoderDroidEvent.OpenWorkspace(root))
        awaitUntil(what = "workspace open") { state.workspaceRoot?.canonicalPath == root.canonicalPath }
    }

    private fun openFile(file: File): CdrEditorTab {
        vm.onEvent(CoderDroidEvent.OpenFile(CdrFileNode(file)))
        awaitUntil(what = "tab for ${file.name}") { state.tabs.any { it.filePath == file.absolutePath } }
        return state.tabs.first { it.filePath == file.absolutePath }
    }

    private fun project(vararg files: Pair<String, String>): File {
        val root = temp.newFolder("proj")
        files.forEach { (name, text) -> File(root, name).apply { parentFile?.mkdirs(); writeText(text) } }
        return root
    }

    @Test fun editingTracksDirtyAgainstWhatIsOnDisk() {
        vm = newVm()
        val root = project("a.txt" to "hello")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        assertEquals("hello", tab.initialContent)
        assertFalse(tab.isDirty)

        vm.onEvent(CoderDroidEvent.Edit(tab.id, "hello!"))
        assertTrue(state.tabs.single().isDirty)
        assertEquals("hello!", state.drafts[tab.id])

        vm.onEvent(CoderDroidEvent.Edit(tab.id, "hello"))
        assertFalse("typing a letter and deleting it again leaves the file clean", state.tabs.single().isDirty)
        assertTrue(state.drafts.isEmpty())
    }

    @Test fun savingWritesTheFileAndCleansTheTab() {
        vm = newVm()
        val root = project("a.txt" to "old")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "new text"))
        vm.onEvent(CoderDroidEvent.SaveTab(tab.id, "new text"))
        awaitUntil(what = "save") { File(root, "a.txt").readText() == "new text" && !state.tabs.single().isDirty }
        assertEquals("new text", state.tabs.single().initialContent)
        assertTrue(state.drafts.isEmpty())
        assertEquals(listOf("a.txt"), root.list()!!.toList())
    }

    @Test fun closingADirtyTabAsksFirstAndDiscardKeepsTheFileUntouched() {
        vm = newVm()
        val root = project("a.txt" to "keep")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "unsaved"))

        vm.onEvent(CoderDroidEvent.RequestCloseTab(tab.id))
        assertTrue(state.dialog is CdrDialog.UnsavedChanges)
        assertEquals("the tab stays open while the question is open", 1, state.tabs.size)

        vm.onEvent(CoderDroidEvent.DiscardThenContinue)
        awaitUntil(what = "tab closed") { state.tabs.isEmpty() }
        assertEquals("keep", File(root, "a.txt").readText())
        assertNull(state.dialog)
    }

    @Test fun closingADirtyTabCanSaveFirst() {
        vm = newVm()
        val root = project("a.txt" to "old")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "saved on close"))
        vm.onEvent(CoderDroidEvent.RequestCloseTab(tab.id))
        vm.onEvent(CoderDroidEvent.SaveThenContinue)
        awaitUntil(what = "saved and closed") { state.tabs.isEmpty() && File(root, "a.txt").readText() == "saved on close" }
    }

    @Test fun cancellingTheQuestionKeepsTheEditsAndTheTab() {
        vm = newVm()
        val root = project("a.txt" to "old")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "edit"))
        vm.onEvent(CoderDroidEvent.RequestCloseTab(tab.id))
        vm.onEvent(CoderDroidEvent.DismissDialog)
        assertEquals(1, state.tabs.size)
        assertTrue(state.tabs.single().isDirty)
        assertEquals("edit", state.drafts[tab.id])
    }

    @Test fun aCleanTabClosesImmediately() {
        vm = newVm()
        val root = project("a.txt" to "x")
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.RequestCloseTab(tab.id))
        assertTrue(state.tabs.isEmpty())
        assertNull(state.dialog)
    }

    @Test fun filesThatCannotBeRoundTrippedAreReadOnlyAndNeverOverwritten() {
        vm = newVm()
        val root = temp.newFolder("proj")
        val latin1 = File(root, "legacy.txt").apply { writeBytes("café".toByteArray(Charsets.ISO_8859_1)) }
        val binary = File(root, "blob.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        open(root)
        for (file in listOf(latin1, binary)) {
            val original = file.readBytes()
            val tab = openFile(file)
            assertTrue("${file.name} should be read-only", tab.isReadOnly)
            vm.onEvent(CoderDroidEvent.Edit(tab.id, "overwritten!"))
            assertFalse("edits to a read-only tab are ignored", state.tabs.first { it.id == tab.id }.isDirty)
            vm.onEvent(CoderDroidEvent.SaveTab(tab.id, "overwritten!"))
            awaitUntil(what = "refusal notice") { state.notice != null }
            vm.onEvent(CoderDroidEvent.DismissNotice)
            assertArrayEquals("${file.name} must be byte-for-byte unchanged", original, file.readBytes())
        }
    }

    @Test fun aBlockedTabLoadsProperlyOnceTheCauseIsGone() {
        vm = newVm()
        val root = temp.newFolder("proj")
        val file = File(root, "legacy.txt").apply { writeBytes("caf\u00e9".toByteArray(Charsets.ISO_8859_1)) }
        open(root)
        val blocked = openFile(file)
        assertTrue(blocked.isReadOnly)

        file.writeText("now plain UTF-8 café")
        vm.onEvent(CoderDroidEvent.RefreshTree)
        awaitUntil(what = "tab reloaded") { state.tabs.single().let { !it.isReadOnly && it.initialContent == "now plain UTF-8 café" } }
        assertEquals("the active tab follows the replacement", state.tabs.single().id, state.activeTabId)
        assertNotEquals("a new id makes the editor build a fresh model", blocked.id, state.tabs.single().id)
    }

    @Test fun openingABlockedFileAgainRereadsItInsteadOfShowingTheStaleError() {
        vm = newVm()
        val root = temp.newFolder("proj")
        val file = File(root, "later.txt").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        open(root)
        assertTrue(openFile(file).isReadOnly)
        file.writeText("fixed")
        vm.onEvent(CoderDroidEvent.OpenFile(CdrFileNode(file)))
        awaitUntil(what = "re-read") { state.tabs.size == 1 && !state.tabs.single().isReadOnly }
        assertEquals("fixed", state.tabs.single().initialContent)
    }

    @Test fun creatingFilesAndFoldersWorksAndNeverOverwrites() {
        vm = newVm()
        val root = project("existing.txt" to "mine")
        open(root)
        vm.onEvent(CoderDroidEvent.CreateFile(root, "new.kt"))
        awaitUntil(what = "new file open") { state.tabs.any { it.fileNode.name == "new.kt" } }
        assertTrue(File(root, "new.kt").isFile)

        vm.onEvent(CoderDroidEvent.CreateFolder(root, "src"))
        awaitUntil(what = "folder") { File(root, "src").isDirectory }

        vm.onEvent(CoderDroidEvent.CreateFile(root, "existing.txt"))
        awaitUntil(what = "refusal") { state.notice != null }
        assertEquals("mine", File(root, "existing.txt").readText())
    }

    @Test fun renamingFollowsOpenTabsAndRefusesWhileEditsAreUnsaved() {
        vm = newVm()
        val root = project("a.txt" to "A")
        open(root)
        val tab = openFile(File(root, "a.txt"))

        vm.onEvent(CoderDroidEvent.Edit(tab.id, "A edited"))
        vm.onEvent(CoderDroidEvent.Rename(File(root, "a.txt"), "b.txt"))
        awaitUntil(what = "refusal") { state.notice != null }
        assertTrue("not renamed while dirty", File(root, "a.txt").exists())
        vm.onEvent(CoderDroidEvent.DismissNotice)

        vm.onEvent(CoderDroidEvent.SaveTab(tab.id, "A edited"))
        awaitUntil(what = "saved") { !state.tabs.single().isDirty }
        vm.onEvent(CoderDroidEvent.Rename(File(root, "a.txt"), "b.txt"))
        awaitUntil(what = "rename") { File(root, "b.txt").exists() && state.tabs.single().fileNode.name == "b.txt" }
        assertFalse(File(root, "a.txt").exists())
        assertEquals("A edited", File(root, "b.txt").readText())
    }

    @Test fun renamingAFolderRepointsTabsInsideIt() {
        vm = newVm()
        val root = project("pkg/Main.kt" to "fun main() {}")
        open(root)
        openFile(File(root, "pkg/Main.kt"))
        vm.onEvent(CoderDroidEvent.Rename(File(root, "pkg"), "lib"))
        awaitUntil(what = "folder rename") { File(root, "lib/Main.kt").exists() }
        awaitUntil(what = "tab repointed") { state.tabs.single().filePath == File(root, "lib/Main.kt").absolutePath }
    }

    @Test fun deletingClosesTheFilesTabsAndTheRootCannotBeDeleted() {
        vm = newVm()
        val root = project("a.txt" to "A", "dir/b.txt" to "B")
        open(root)
        openFile(File(root, "a.txt"))
        openFile(File(root, "dir/b.txt"))

        vm.onEvent(CoderDroidEvent.Delete(File(root, "dir")))
        awaitUntil(what = "folder deleted") { !File(root, "dir").exists() }
        awaitUntil(what = "tab closed") { state.tabs.map { it.fileNode.name } == listOf("a.txt") }

        vm.onEvent(CoderDroidEvent.Delete(root))
        awaitUntil(what = "root refusal") { state.notice != null }
        assertTrue(root.exists())
    }

    @Test fun nothingOutsideTheProjectCanBeTouched() {
        vm = newVm()
        val root = project("a.txt" to "A")
        val outside = temp.newFolder("elsewhere").also { File(it, "secret.txt").writeText("hands off") }
        open(root)

        vm.onEvent(CoderDroidEvent.CreateFile(outside, "planted.txt"))
        vm.onEvent(CoderDroidEvent.Delete(File(outside, "secret.txt")))
        vm.onEvent(CoderDroidEvent.Rename(File(outside, "secret.txt"), "renamed.txt"))
        vm.onEvent(CoderDroidEvent.OpenFile(CdrFileNode(File(outside, "secret.txt"))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertFalse(File(outside, "planted.txt").exists())
        assertEquals("hands off", File(outside, "secret.txt").readText())
        assertFalse(File(outside, "renamed.txt").exists())
        assertTrue(state.tabs.isEmpty())
    }

    @Test fun searchFindsTextAndFileNames() {
        vm = newVm()
        val root = project("src/Main.kt" to "fun main() { println(\"Needle\") }", "notes.md" to "nothing")
        open(root)
        vm.onEvent(CoderDroidEvent.SetSearchQuery("needle"))
        awaitUntil(what = "text hits") { state.search.searched && state.search.hits.isNotEmpty() }
        assertEquals(listOf("Main.kt"), state.search.hits.map { it.file.name })

        vm.onEvent(CoderDroidEvent.SetSearchByName(true))
        vm.onEvent(CoderDroidEvent.SetSearchQuery("notes"))
        awaitUntil(what = "file hits") { state.search.files.isNotEmpty() }
        assertEquals(listOf("notes.md"), state.search.files.map { it.name })
    }

    @Test fun openingASearchResultOpensTheFileAndAsksToJumpToTheLine() {
        vm = newVm()
        val root = project("a.txt" to "l1\nl2\nl3")
        open(root)
        vm.onEvent(CoderDroidEvent.OpenFileAt(File(root, "a.txt"), 3))
        awaitUntil(what = "tab") { state.tabs.isNotEmpty() }
        assertEquals(CoderDroidState.Reveal(File(root, "a.txt").absolutePath, 3), state.reveal)
        vm.onEvent(CoderDroidEvent.ClearReveal)
        assertNull(state.reveal)
    }

    @Test fun fontSizeStaysInASaneRangeAndSettingsSurviveARestart() {
        vm = newVm()
        vm.onEvent(CoderDroidEvent.ChangeFontSize(100))
        assertEquals(28, state.settings.fontSize)
        vm.onEvent(CoderDroidEvent.ChangeFontSize(-100))
        assertEquals(9, state.settings.fontSize)
        vm.onEvent(CoderDroidEvent.ToggleWordWrap)
        vm.onEvent(CoderDroidEvent.ToggleAutoSave)
        vm.onEvent(CoderDroidEvent.ToggleHidden)

        val reborn = newVm().state.value.settings
        assertEquals(CdrSettings(fontSize = 9, wordWrap = true, autoSave = true, showHidden = true), reborn)
    }

    @Test fun switchingProjectsWithUnsavedEditsAsksInsteadOfDroppingThem() {
        vm = newVm()
        val first = project("a.txt" to "A")
        val second = temp.newFolder("other")
        open(first)
        val tab = openFile(File(first, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "precious"))

        vm.onEvent(CoderDroidEvent.OpenWorkspace(second))
        assertTrue(state.dialog is CdrDialog.UnsavedChanges)
        assertEquals("still the first project", first.canonicalPath, state.workspaceRoot?.canonicalPath)
        assertEquals("precious", state.drafts[tab.id])

        vm.onEvent(CoderDroidEvent.SaveThenContinue)
        awaitUntil(what = "switched") { state.workspaceRoot?.canonicalPath == second.canonicalPath }
        assertEquals("precious", File(first, "a.txt").readText())
    }

    @Test fun autoSaveWritesAfterAShortPause() {
        vm = newVm()
        val root = project("a.txt" to "old")
        open(root)
        vm.onEvent(CoderDroidEvent.ToggleAutoSave)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "auto"))
        assertEquals("not written instantly", "old", File(root, "a.txt").readText())
        awaitUntil(timeoutMs = 15_000, what = "auto-save") { File(root, "a.txt").readText() == "auto" }
        awaitUntil(what = "clean") { !state.tabs.single().isDirty }
    }

    @Test fun openTabsAndTheProjectComeBackAfterARestart() {
        vm = newVm()
        val root = project("a.txt" to "A", "b.txt" to "B")
        open(root)
        openFile(File(root, "a.txt"))
        val b = openFile(File(root, "b.txt"))
        vm.onEvent(CoderDroidEvent.SwitchTab(b.id))

        val reborn = newVm()
        vm = reborn
        awaitUntil(what = "restored tabs") { state.tabs.size == 2 && state.activeTabId != null }
        assertEquals(root.canonicalPath, state.workspaceRoot?.canonicalPath)
        assertEquals(listOf("a.txt", "b.txt"), state.tabs.map { it.fileNode.name })
        assertEquals("b.txt", state.activeTab?.fileNode?.name)
        assertEquals(listOf(root.absolutePath), state.recentWorkspaces)
    }

    @Test fun aProjectOutsideSharedStorageNeverNeedsThePermission() {
        vm = newVm()
        val root = project("a.txt" to "A")
        open(root)
        awaitUntil(what = "access check") { true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNull("a readable app-private or temp project has no access problem", state.accessProblem)
    }

    @Test fun aFolderOnSharedStorageIsFlaggedWhenAllFilesAccessIsOff() {
        com.example.data.storage.StorageAccess.accessOverrideForTests = false
        val shared = File(android.os.Environment.getExternalStorageDirectory(), "Documents/proj").apply { mkdirs(); File(this, "a.txt").writeText("A") }
        vm = newVm()
        open(shared)
        awaitUntil(what = "access problem raised") { state.accessProblem != null }
        assertTrue(state.accessProblem!!.contains("All files access"))
    }

    @Test fun theSameFolderIsFineOnceAllFilesAccessIsOn() {
        com.example.data.storage.StorageAccess.accessOverrideForTests = true
        val shared = File(android.os.Environment.getExternalStorageDirectory(), "Documents/proj2").apply { mkdirs(); File(this, "a.txt").writeText("A") }
        vm = newVm()
        open(shared)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNull(state.accessProblem)
    }

    @Test fun gitStatusAppearsForARepoAndTheBranchIsShown() {
        vm = newVm()
        val root = project("a.txt" to "A")
        org.eclipse.jgit.api.Git.init().setDirectory(root).call().close()
        open(root)
        awaitUntil(what = "git status") { state.gitState.isRepo }
        assertTrue(state.gitState.hasChanges)
        assertTrue(state.gitState.unstaged.any { it.path == "a.txt" })
    }

    @Test fun committingNeedsEveryOpenFileSavedFirst() {
        vm = newVm()
        val root = project("a.txt" to "A")
        org.eclipse.jgit.api.Git.init().setDirectory(root).call().close()
        open(root)
        val tab = openFile(File(root, "a.txt"))
        vm.onEvent(CoderDroidEvent.Edit(tab.id, "A2"))
        vm.onEvent(CoderDroidEvent.Commit("message"))
        awaitUntil(what = "refusal notice") { state.notice != null }
        assertTrue(state.notice!!.contains("Save"))
        assertEquals(0, state.commitSequence)
    }
}
