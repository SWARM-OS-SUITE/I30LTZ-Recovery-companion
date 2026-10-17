package com.example.ui.coderdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.storage.StorageAccess
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.coroutineContext

enum class CdrSidebar { FILES, SEARCH, GIT }
enum class CdrBottomPanel { NONE, TERMINAL, AI }

data class CdrSettings(
    val fontSize: Int = 13,
    val wordWrap: Boolean = false,
    val autoSave: Boolean = false,
    val showHidden: Boolean = false,
)

/** Something the user must answer before the IDE carries on. */
sealed interface CdrDialog {
    data class NewFile(val parent: File) : CdrDialog
    data class NewFolder(val parent: File) : CdrDialog
    data class Rename(val file: File) : CdrDialog
    data class Delete(val file: File) : CdrDialog
    /** Unsaved edits are in the way of [then]; the user picks Save, Discard or Cancel. */
    data class UnsavedChanges(val then: PendingAction) : CdrDialog
}

/** An action that was held back because unsaved edits were open. */
sealed interface PendingAction {
    data class CloseTab(val tabId: String) : PendingAction
    data class OpenWorkspace(val root: File) : PendingAction
    data class Clone(val url: String) : PendingAction
}

data class CdrSearchState(
    val query: String = "",
    /** false = text inside files, true = file names ("go to file"). */
    val byName: Boolean = false,
    val running: Boolean = false,
    val searched: Boolean = false,
    val hits: List<CdrFileOps.SearchHit> = emptyList(),
    val files: List<File> = emptyList(),
)

/** A request to put [content] into the already-open clean tab [tabId] (file changed on disk). */
data class TabReload(val tabId: String, val content: String)

data class CoderDroidState(
    val workspaceRoot: File? = null,
    val workspaceName: String = "No workspace",
    /** Set when the open folder cannot be read at all: almost always missing "All files access". */
    val accessProblem: String? = null,
    val tabs: List<CdrEditorTab> = emptyList(),
    val activeTabId: String? = null,
    val sidebar: CdrSidebar? = CdrSidebar.FILES,
    val bottomPanel: CdrBottomPanel = CdrBottomPanel.NONE,
    val gitState: CdrGitState = CdrGitState(),
    val gitHasCredentials: Boolean = false,
    val cloneInProgress: Boolean = false,
    val cloneError: String? = null,
    val gitAuthorName: String = "",
    val gitAuthorEmail: String = "",
    val gitUsername: String = "",
    val notice: String? = null,
    val savedSequence: Int = 0,
    val commitSequence: Int = 0,
    val drafts: Map<String, String> = emptyMap(),
    val settings: CdrSettings = CdrSettings(),
    /** Bumped whenever files change on disk so the explorer reloads. */
    val treeRevision: Int = 0,
    val dialog: CdrDialog? = null,
    val search: CdrSearchState = CdrSearchState(),
    val recentWorkspaces: List<String> = emptyList(),
    /** Jump target set by a search result; the screen scrolls there once the file is showing. */
    val reveal: Reveal? = null,
) {
    data class Reveal(val path: String, val line: Int)

    val activeTab: CdrEditorTab? get() = tabs.firstOrNull { it.id == activeTabId }
    val hasDirtyTabs: Boolean get() = tabs.any { it.isDirty }
    val sidebarVisible: Boolean get() = sidebar != null
}

sealed class CoderDroidEvent {
    // workspace
    data class OpenWorkspace(val root: File) : CoderDroidEvent()
    data class CloneRepo(val url: String) : CoderDroidEvent()
    object DismissCloneError : CoderDroidEvent()
    object RefreshTree : CoderDroidEvent()
    // tabs and editing
    data class OpenFile(val node: CdrFileNode) : CoderDroidEvent()
    data class OpenFileAt(val file: File, val line: Int) : CoderDroidEvent()
    data class RequestCloseTab(val tabId: String) : CoderDroidEvent()
    data class SwitchTab(val tabId: String) : CoderDroidEvent()
    data class Edit(val tabId: String, val content: String) : CoderDroidEvent()
    data class CursorMoved(val line: Int, val column: Int) : CoderDroidEvent()
    data class SaveTab(val tabId: String, val content: String) : CoderDroidEvent()
    object SaveAll : CoderDroidEvent()
    object ClearReveal : CoderDroidEvent()
    // chrome
    data class ShowSidebar(val which: CdrSidebar) : CoderDroidEvent()
    object HideSidebar : CoderDroidEvent()
    data class SetBottomPanel(val panel: CdrBottomPanel) : CoderDroidEvent()
    object DismissNotice : CoderDroidEvent()
    // dialogs and file operations
    data class ShowDialog(val dialog: CdrDialog) : CoderDroidEvent()
    object DismissDialog : CoderDroidEvent()
    data class CreateFile(val parent: File, val name: String) : CoderDroidEvent()
    data class CreateFolder(val parent: File, val name: String) : CoderDroidEvent()
    data class Rename(val file: File, val newName: String) : CoderDroidEvent()
    data class Delete(val file: File) : CoderDroidEvent()
    object SaveThenContinue : CoderDroidEvent()
    object DiscardThenContinue : CoderDroidEvent()
    // search
    data class SetSearchQuery(val query: String) : CoderDroidEvent()
    data class SetSearchByName(val byName: Boolean) : CoderDroidEvent()
    // settings
    data class ChangeFontSize(val delta: Int) : CoderDroidEvent()
    object ToggleWordWrap : CoderDroidEvent()
    object ToggleAutoSave : CoderDroidEvent()
    object ToggleHidden : CoderDroidEvent()
    // git
    object RefreshGit : CoderDroidEvent()
    data class StageFile(val path: String) : CoderDroidEvent()
    data class UnstageFile(val path: String) : CoderDroidEvent()
    object StageAll : CoderDroidEvent()
    data class Commit(val message: String) : CoderDroidEvent()
    data class SetOrigin(val url: String) : CoderDroidEvent()
    object Push : CoderDroidEvent()
    object Pull : CoderDroidEvent()
    data class SetGitCredentials(val username: String, val token: String, val authorName: String, val authorEmail: String) : CoderDroidEvent()
}

class CoderDroidViewModel @JvmOverloads constructor(
    application: Application,
    credentialsFactory: (Application) -> CdrCredentials = { CdrGitCredentials(it) },
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CoderDroidState())
    val state: StateFlow<CoderDroidState> = _state.asStateFlow()

    private val _reloads = MutableSharedFlow<TabReload>(extraBufferCapacity = 16)
    val reloads: SharedFlow<TabReload> = _reloads.asSharedFlow()

    private val prefs by lazy { getApplication<Application>().getSharedPreferences("cdr_workspace", 0) }
    private val gitCredentials by lazy { credentialsFactory(getApplication()) }
    private var searchJob: Job? = null
    private val autoSaveJobs = mutableMapOf<String, Job>()

    init {
        _state.update {
            it.copy(
                gitHasCredentials = gitCredentials.hasCredentials,
                gitUsername = gitCredentials.username,
                gitAuthorName = gitCredentials.authorName,
                gitAuthorEmail = gitCredentials.authorEmail,
                settings = loadSettings(),
                recentWorkspaces = loadRecents(),
            )
        }
        prefs.getString("root", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { restoreWorkspace(it) }
    }

    fun onEvent(event: CoderDroidEvent) {
        when (event) {
            is CoderDroidEvent.OpenWorkspace -> guarded(PendingAction.OpenWorkspace(event.root))
            is CoderDroidEvent.CloneRepo -> guarded(PendingAction.Clone(event.url))
            CoderDroidEvent.DismissCloneError -> _state.update { it.copy(cloneError = null) }
            CoderDroidEvent.RefreshTree -> { bumpTree(); recheckAccess(); reloadBlockedTabs() }

            is CoderDroidEvent.OpenFile -> openFile(event.node)
            is CoderDroidEvent.OpenFileAt -> openFileAt(event.file, event.line)
            is CoderDroidEvent.RequestCloseTab -> guarded(PendingAction.CloseTab(event.tabId))
            is CoderDroidEvent.SwitchTab -> { _state.update { it.copy(activeTabId = event.tabId) }; persistSession() }
            is CoderDroidEvent.Edit -> edit(event.tabId, event.content)
            is CoderDroidEvent.CursorMoved -> _state.update { s ->
                s.copy(tabs = s.tabs.map { t -> if (t.id == s.activeTabId) t.copy(cursorLine = event.line - 1, cursorColumn = event.column - 1) else t })
            }
            is CoderDroidEvent.SaveTab -> saveTab(event.tabId, event.content)
            CoderDroidEvent.SaveAll -> saveAll()
            CoderDroidEvent.ClearReveal -> _state.update { it.copy(reveal = null) }

            is CoderDroidEvent.ShowSidebar -> {
                _state.update { it.copy(sidebar = event.which) }
                if (event.which == CdrSidebar.GIT) refreshGit()
            }
            CoderDroidEvent.HideSidebar -> _state.update { it.copy(sidebar = null) }
            is CoderDroidEvent.SetBottomPanel -> _state.update { it.copy(bottomPanel = event.panel) }
            CoderDroidEvent.DismissNotice -> _state.update { it.copy(notice = null) }

            is CoderDroidEvent.ShowDialog -> _state.update { it.copy(dialog = event.dialog) }
            CoderDroidEvent.DismissDialog -> _state.update { it.copy(dialog = null) }
            is CoderDroidEvent.CreateFile -> createEntry(event.parent, event.name, folder = false)
            is CoderDroidEvent.CreateFolder -> createEntry(event.parent, event.name, folder = true)
            is CoderDroidEvent.Rename -> rename(event.file, event.newName)
            is CoderDroidEvent.Delete -> delete(event.file)
            CoderDroidEvent.SaveThenContinue -> resolveUnsaved(save = true)
            CoderDroidEvent.DiscardThenContinue -> resolveUnsaved(save = false)

            is CoderDroidEvent.SetSearchQuery -> { _state.update { it.copy(search = it.search.copy(query = event.query)) }; runSearch() }
            is CoderDroidEvent.SetSearchByName -> { _state.update { it.copy(search = it.search.copy(byName = event.byName)) }; runSearch() }

            is CoderDroidEvent.ChangeFontSize -> updateSettings { it.copy(fontSize = (it.fontSize + event.delta).coerceIn(9, 28)) }
            CoderDroidEvent.ToggleWordWrap -> updateSettings { it.copy(wordWrap = !it.wordWrap) }
            CoderDroidEvent.ToggleAutoSave -> updateSettings { it.copy(autoSave = !it.autoSave) }
            CoderDroidEvent.ToggleHidden -> { updateSettings { it.copy(showHidden = !it.showHidden) }; bumpTree() }

            CoderDroidEvent.RefreshGit -> refreshGit()
            is CoderDroidEvent.StageFile -> withGitRoot { CdrGitService.stage(it, listOf(event.path)) }
            is CoderDroidEvent.UnstageFile -> withGitRoot { CdrGitService.unstage(it, listOf(event.path)) }
            CoderDroidEvent.StageAll -> withGitRoot { CdrGitService.stageAll(it) }
            is CoderDroidEvent.Commit -> commit(event.message)
            is CoderDroidEvent.SetOrigin -> withGitRoot { CdrGitService.setOrigin(it, event.url) }
            CoderDroidEvent.Push -> withGitCredentials { root, user, token -> CdrGitService.push(root, user, token) }
            CoderDroidEvent.Pull -> pull()
            is CoderDroidEvent.SetGitCredentials -> setGitCredentials(event.username, event.token, event.authorName, event.authorEmail)
        }
    }

    // ── Workspace boundary guard ───────────────────────────────────────────────
    // Resolves canonical paths so symlink tricks can't escape the project folder.
    private fun isWithinWorkspace(file: File): Boolean {
        val root = _state.value.workspaceRoot ?: return false
        return try {
            val canonical = file.canonicalPath
            val rootCanonical = root.canonicalPath
            canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    // ── Unsaved-changes gate ───────────────────────────────────────────────────

    /** Runs [action] now, or asks first when it would throw away unsaved edits. */
    private fun guarded(action: PendingAction) {
        val s = _state.value
        val endangered = when (action) {
            is PendingAction.CloseTab -> s.tabs.any { it.id == action.tabId && it.isDirty }
            else -> s.hasDirtyTabs
        }
        if (endangered) _state.update { it.copy(dialog = CdrDialog.UnsavedChanges(action)) } else perform(action)
    }

    private fun resolveUnsaved(save: Boolean) {
        val pending = (_state.value.dialog as? CdrDialog.UnsavedChanges)?.then ?: return
        _state.update { it.copy(dialog = null) }
        if (!save) { discardEdits(pending); perform(pending); return }
        viewModelScope.launch {
            val ok = when (pending) {
                is PendingAction.CloseTab -> _state.value.tabs.firstOrNull { it.id == pending.tabId }?.let { writeTab(it) } ?: true
                else -> writeAllDirty()
            }
            if (ok) perform(pending)
        }
    }

    private fun discardEdits(pending: PendingAction) {
        if (pending is PendingAction.CloseTab) _state.update { it.copy(drafts = it.drafts - pending.tabId) }
        else _state.update { it.copy(drafts = emptyMap()) }
    }

    private fun perform(action: PendingAction) {
        when (action) {
            is PendingAction.CloseTab -> closeTab(action.tabId)
            is PendingAction.OpenWorkspace -> openWorkspace(action.root, restoreTabs = false)
            is PendingAction.Clone -> cloneRepo(action.url)
        }
    }

    // ── Tabs ───────────────────────────────────────────────────────────────────

    private fun openFile(node: CdrFileNode) {
        if (!isWithinWorkspace(node.file)) return
        val existing = _state.value.tabs.find { it.filePath == node.path }
        // A tab that was blocked (no permission yet, wrong encoding...) is read again: the cause may be gone now.
        if (existing != null && !existing.isReadOnly) { _state.update { it.copy(activeTabId = existing.id) }; persistSession(); return }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { CdrFileOps.load(node.file) }
            val tab = when (loaded) {
                is CdrFileOps.Loaded.Text -> CdrEditorTab(fileNode = node, initialContent = loaded.text)
                is CdrFileOps.Loaded.Blocked -> CdrEditorTab(fileNode = node, initialContent = loaded.reason, readOnlyReason = loaded.reason)
            }
            _state.update { s ->
                val stale = s.tabs.firstOrNull { it.filePath == node.path }
                if (stale != null && !stale.isReadOnly) s.copy(activeTabId = stale.id)
                else {
                    // Replace a stale blocked tab (new id, so the editor builds a fresh model) in its old position.
                    var tabs = s.tabs.map { if (it.filePath == node.path) tab else it }.let { if (stale != null) it else s.tabs }
                    if (tabs.size >= MAX_TABS) {
                        // Keep memory in check: drop the oldest tab that has nothing unsaved.
                        tabs.firstOrNull { !it.isDirty }?.let { old -> tabs = tabs - old }
                    }
                    s.copy(tabs = if (stale != null) tabs else tabs + tab, activeTabId = tab.id)
                }
            }
            persistSession()
        }
    }

    /** After access changes or a refresh: give every blocked tab another chance to load. */
    private fun reloadBlockedTabs() {
        val blocked = _state.value.tabs.filter { it.isReadOnly }
        if (blocked.isEmpty()) return
        viewModelScope.launch {
            for (old in blocked) {
                val loaded = withContext(Dispatchers.IO) { CdrFileOps.load(old.fileNode.file) }
                if (loaded is CdrFileOps.Loaded.Text) {
                    val fresh = CdrEditorTab(fileNode = old.fileNode, initialContent = loaded.text)
                    _state.update { s ->
                        s.copy(
                            tabs = s.tabs.map { if (it.id == old.id) fresh else it },
                            activeTabId = if (s.activeTabId == old.id) fresh.id else s.activeTabId,
                        )
                    }
                }
            }
            persistSession()
        }
    }

    private fun openFileAt(file: File, line: Int) {
        openFile(CdrFileNode(file))
        _state.update { it.copy(reveal = CoderDroidState.Reveal(file.absolutePath, line)) }
    }

    private fun closeTab(tabId: String) {
        autoSaveJobs.remove(tabId)?.cancel()
        _state.update { s ->
            val tabs = s.tabs.filter { it.id != tabId }
            val newActive = when {
                s.activeTabId != tabId -> s.activeTabId
                tabs.isEmpty() -> null
                else -> tabs.last().id
            }
            s.copy(tabs = tabs, activeTabId = newActive, drafts = s.drafts - tabId)
        }
        persistSession()
    }

    private fun edit(tabId: String, content: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isReadOnly) return
        // "Dirty" means different from what is on disk, so typing a letter and deleting it again is clean.
        val dirty = content != tab.initialContent
        _state.update { s ->
            s.copy(
                drafts = if (dirty) s.drafts + (tabId to content) else s.drafts - tabId,
                tabs = s.tabs.map { t -> if (t.id == tabId) t.copy(isDirty = dirty) else t },
            )
        }
        if (_state.value.settings.autoSave && dirty) {
            autoSaveJobs.remove(tabId)?.cancel()
            autoSaveJobs[tabId] = viewModelScope.launch {
                delay(AUTOSAVE_DELAY_MS)
                _state.value.tabs.firstOrNull { it.id == tabId }?.takeIf { it.isDirty }?.let { writeTab(it) }
            }
        }
    }

    private fun saveTab(tabId: String, content: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        viewModelScope.launch { writeTab(tab, content) }
    }

    private fun saveAll() {
        viewModelScope.launch { writeAllDirty() }
    }

    private suspend fun writeAllDirty(): Boolean {
        var ok = true
        for (tab in _state.value.tabs.filter { it.isDirty }) if (!writeTab(tab)) ok = false
        return ok
    }

    /** Writes the tab's current text (the editor's [content], else its draft) to disk. Returns success. */
    private suspend fun writeTab(tab: CdrEditorTab, content: String? = null): Boolean {
        if (tab.isReadOnly) {
            _state.update { it.copy(notice = "${tab.fileNode.name} is read-only here (${tab.readOnlyReason}).") }
            return false
        }
        if (!isWithinWorkspace(tab.fileNode.file)) return false
        val text = content ?: _state.value.drafts[tab.id] ?: tab.initialContent
        val result = withContext(Dispatchers.IO) { CdrFileOps.saveText(tab.fileNode.file, text) }
        result.onFailure { err -> _state.update { it.copy(notice = "Save failed: ${err.message}") } }
        if (result.isSuccess) {
            _state.update { s ->
                // If the user typed more while saving, that newer draft stays; otherwise the tab is now clean.
                val newer = s.drafts[tab.id]?.takeIf { it != text }
                s.copy(
                    tabs = s.tabs.map { t -> if (t.id == tab.id) t.copy(initialContent = text, isDirty = newer != null) else t },
                    drafts = if (newer == null) s.drafts - tab.id else s.drafts,
                    savedSequence = s.savedSequence + 1,
                )
            }
            refreshGit()
        }
        return result.isSuccess
    }

    // ── Workspace ──────────────────────────────────────────────────────────────

    private fun restoreWorkspace(root: File) = openWorkspace(root, restoreTabs = true)

    private fun openWorkspace(root: File, restoreTabs: Boolean) {
        prefs.edit().putString("root", root.absolutePath).apply()
        autoSaveJobs.values.forEach { it.cancel() }
        autoSaveJobs.clear()
        searchJob?.cancel()
        // Switching projects clears all open tabs: nothing from the old project leaks in.
        _state.update {
            it.copy(
                workspaceRoot = root, workspaceName = root.name.ifEmpty { root.path },
                tabs = emptyList(), drafts = emptyMap(), activeTabId = null, gitState = CdrGitState(),
                search = CdrSearchState(), accessProblem = null, treeRevision = it.treeRevision + 1,
                recentWorkspaces = pushRecent(root),
            )
        }
        recheckAccess()
        refreshGit()
        if (restoreTabs) restoreSession(root)
    }

    /**
     * Decides whether the open folder can really be used. Without storage permission Android does not fail a
     * listing on shared storage, it quietly returns a filtered one (folders only), so the folder would look empty
     * with no explanation. A folder on shared storage therefore needs the permission itself, not just a listing.
     */
    private fun recheckAccess() {
        val root = _state.value.workspaceRoot ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) {
                val unreadable = CdrFileOps.list(root, showHidden = true) == null
                val blockedByAndroid = StorageAccess.isOnSharedStorage(root) && !StorageAccess.hasAccess(app)
                if (unreadable || blockedByAndroid) ACCESS_MESSAGE else null
            }
            _state.update { if (it.workspaceRoot != root) it else it.copy(accessProblem = problem) }
        }
    }

    private fun bumpTree() = _state.update { it.copy(treeRevision = it.treeRevision + 1) }

    // ── File operations ────────────────────────────────────────────────────────

    private fun createEntry(parent: File, name: String, folder: Boolean) {
        if (!isWithinWorkspace(parent)) return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (folder) CdrFileOps.createFolder(parent, name) else CdrFileOps.createFile(parent, name)
            }
            result.onFailure { err -> _state.update { it.copy(notice = err.message ?: "Could not create \"$name\".") } }
            result.onSuccess { created ->
                bumpTree()
                refreshGit()
                if (!folder) openFile(CdrFileNode(created))
            }
        }
    }

    private fun rename(file: File, newName: String) {
        _state.update { it.copy(dialog = null) }
        if (!isWithinWorkspace(file) || file.canonicalPath == _state.value.workspaceRoot?.canonicalPath) return
        val affected = tabsUnder(file)
        if (affected.any { it.isDirty }) {
            _state.update { it.copy(notice = "Save the open files in \"${file.name}\" before renaming it.") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { CdrFileOps.rename(file, newName) }
            result.onFailure { err -> _state.update { it.copy(notice = err.message ?: "Could not rename.") } }
            result.onSuccess { renamed ->
                _state.update { s ->
                    s.copy(tabs = s.tabs.map { t ->
                        if (isSameOrUnder(t.fileNode.file, file)) {
                            val moved = File(renamed, t.fileNode.file.absolutePath.removePrefix(file.absolutePath).trimStart(File.separatorChar))
                            t.copy(fileNode = CdrFileNode(if (t.fileNode.file.absolutePath == file.absolutePath) renamed else moved))
                        } else t
                    })
                }
                bumpTree()
                refreshGit()
                persistSession()
            }
        }
    }

    private fun delete(file: File) {
        _state.update { it.copy(dialog = null) }
        if (!isWithinWorkspace(file)) return
        if (file.canonicalPath == _state.value.workspaceRoot?.canonicalPath) {
            _state.update { it.copy(notice = "The open project folder itself cannot be deleted from inside the IDE.") }
            return
        }
        val doomed = tabsUnder(file).map { it.id }.toSet()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { CdrFileOps.delete(file) }
            result.onFailure { err -> _state.update { it.copy(notice = err.message ?: "Could not delete.") } }
            result.onSuccess {
                doomed.forEach { autoSaveJobs.remove(it)?.cancel() }
                _state.update { s ->
                    val tabs = s.tabs.filter { it.id !in doomed }
                    s.copy(
                        tabs = tabs, drafts = s.drafts - doomed,
                        activeTabId = if (s.activeTabId in doomed) tabs.lastOrNull()?.id else s.activeTabId,
                    )
                }
                bumpTree()
                refreshGit()
                persistSession()
            }
        }
    }

    private fun tabsUnder(file: File) = _state.value.tabs.filter { isSameOrUnder(it.fileNode.file, file) }

    private fun isSameOrUnder(candidate: File, base: File): Boolean =
        candidate.absolutePath == base.absolutePath || candidate.absolutePath.startsWith(base.absolutePath + File.separator)

    // ── Search ─────────────────────────────────────────────────────────────────

    private fun runSearch() {
        searchJob?.cancel()
        val root = _state.value.workspaceRoot ?: return
        val query = _state.value.search.query
        if (query.isBlank()) {
            _state.update { it.copy(search = it.search.copy(running = false, searched = false, hits = emptyList(), files = emptyList())) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(search = it.search.copy(running = true)) }
            val byName = _state.value.search.byName
            val job = coroutineContext[Job]
            val (hits, files) = withContext(Dispatchers.IO) {
                val cancelled = { job?.isActive == false }
                if (byName) emptyList<CdrFileOps.SearchHit>() to CdrFileOps.findFiles(root, query, isCancelled = cancelled)
                else CdrFileOps.searchText(root, query, isCancelled = cancelled) to emptyList()
            }
            _state.update { it.copy(search = it.search.copy(running = false, searched = true, hits = hits, files = files)) }
        }
    }

    // ── Settings, recents, session ─────────────────────────────────────────────

    private fun updateSettings(change: (CdrSettings) -> CdrSettings) {
        val updated = change(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        prefs.edit()
            .putInt("font_size", updated.fontSize).putBoolean("word_wrap", updated.wordWrap)
            .putBoolean("auto_save", updated.autoSave).putBoolean("show_hidden", updated.showHidden).apply()
    }

    private fun loadSettings() = CdrSettings(
        fontSize = prefs.getInt("font_size", 13).coerceIn(9, 28),
        wordWrap = prefs.getBoolean("word_wrap", false),
        autoSave = prefs.getBoolean("auto_save", false),
        showHidden = prefs.getBoolean("show_hidden", false),
    )

    private fun loadRecents(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map { arr.getString(it) }.filter { File(it).isDirectory }
    }.getOrDefault(emptyList())

    private fun pushRecent(root: File): List<String> {
        val updated = (listOf(root.absolutePath) + _state.value.recentWorkspaces.filter { it != root.absolutePath }).take(MAX_RECENTS)
        prefs.edit().putString("recent", JSONArray(updated).toString()).apply()
        return updated
    }

    private fun persistSession() {
        val s = _state.value
        prefs.edit()
            .putString("tabs", JSONArray(s.tabs.map { it.filePath }).toString())
            .putString("active", s.activeTab?.filePath)
            .apply()
    }

    private fun restoreSession(root: File) {
        val paths = runCatching {
            val arr = JSONArray(prefs.getString("tabs", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        val active = prefs.getString("active", null)
        val files = paths.map { File(it) }.filter { it.isFile && isSameOrUnder(it, root) }
        if (files.isEmpty()) return
        viewModelScope.launch {
            files.forEach { f ->
                val loaded = withContext(Dispatchers.IO) { CdrFileOps.load(f) }
                val tab = when (loaded) {
                    is CdrFileOps.Loaded.Text -> CdrEditorTab(fileNode = CdrFileNode(f), initialContent = loaded.text)
                    is CdrFileOps.Loaded.Blocked -> CdrEditorTab(fileNode = CdrFileNode(f), initialContent = loaded.reason, readOnlyReason = loaded.reason)
                }
                _state.update { s -> if (s.workspaceRoot != root || s.tabs.any { it.filePath == tab.filePath }) s else s.copy(tabs = s.tabs + tab) }
            }
            _state.update { s ->
                if (s.workspaceRoot != root) s
                else s.copy(activeTabId = (s.tabs.firstOrNull { it.filePath == active } ?: s.tabs.lastOrNull())?.id)
            }
        }
    }

    // ── Git ─────────────────────────────────────────────────────────────────────

    private fun refreshGit() {
        val root = _state.value.workspaceRoot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = CdrGitService.status(root)
            _state.update { if (it.workspaceRoot == root && !it.gitState.isBusy) it.copy(gitState = result) else it }
        }
    }

    private fun commit(message: String) {
        if (message.isBlank()) return
        if (_state.value.hasDirtyTabs) {
            _state.update { it.copy(notice = "Save changed files before committing.") }; return
        }
        withGitRoot { root ->
            CdrGitService.commit(root, message, gitCredentials.authorName, gitCredentials.authorEmail).onSuccess {
                _state.update { it.copy(commitSequence = it.commitSequence + 1, notice = "Commit saved locally. Use Push to send it to GitHub.") }
            }
        }
    }

    private fun pull() = withGitCredentials { root, user, token ->
        CdrGitService.pull(root, user, token).onSuccess { reloadCleanTabs() }
    }

    /** After a pull, files may have changed on disk: refresh every tab that has no unsaved edits. */
    private fun reloadCleanTabs() {
        viewModelScope.launch {
            for (tab in _state.value.tabs.filter { !it.isDirty && !it.isReadOnly }) {
                val loaded = withContext(Dispatchers.IO) { CdrFileOps.load(tab.fileNode.file) }
                if (loaded is CdrFileOps.Loaded.Text && loaded.text != tab.initialContent) {
                    _state.update { s -> s.copy(tabs = s.tabs.map { t -> if (t.id == tab.id && !t.isDirty) t.copy(initialContent = loaded.text) else t }) }
                    _reloads.emit(TabReload(tab.id, loaded.text))
                }
            }
            bumpTree()
        }
    }

    /**
     * Clones [url] into app-private storage (files/projects/<repo-name>) and opens it. This is CoderDroid's
     * "download a folder from git" entry point, separate from the folder picker which only opens what is
     * already on the phone.
     */
    private fun cloneRepo(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || _state.value.cloneInProgress) return
        val repoName = trimmed.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        if (!repoName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}"))) {
            _state.update { it.copy(cloneError = "Repository name is not a valid project folder.") }; return
        }
        val dest = File(getApplication<Application>().filesDir, "projects/$repoName")
        _state.update { it.copy(cloneInProgress = true, cloneError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = CdrGitService.clone(dest, trimmed, gitCredentials.username, gitCredentials.token)
            result.fold(
                onSuccess = { openedRoot ->
                    _state.update { it.copy(cloneInProgress = false) }
                    openWorkspace(openedRoot, restoreTabs = false)
                },
                onFailure = { err -> _state.update { it.copy(cloneInProgress = false, cloneError = err.message ?: "clone failed") } },
            )
        }
    }

    private fun setGitCredentials(username: String, token: String, authorName: String, authorEmail: String) {
        gitCredentials.username = username
        if (token.isNotBlank()) gitCredentials.token = token
        gitCredentials.authorName = authorName
        gitCredentials.authorEmail = authorEmail
        _state.update {
            it.copy(
                gitHasCredentials = gitCredentials.hasCredentials, gitUsername = username,
                gitAuthorName = authorName, gitAuthorEmail = authorEmail, notice = "Git settings saved on this phone.",
            )
        }
    }

    /** Runs a git mutation on IO, then refreshes status: sets lastError on failure. */
    private fun withGitRoot(block: (File) -> Result<Unit>) {
        val root = _state.value.workspaceRoot ?: return
        if (_state.value.gitState.isBusy) return
        _state.update { it.copy(gitState = it.gitState.copy(isBusy = true, lastError = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { block(root).getOrThrow() }
            if (_state.value.workspaceRoot != root) return@launch
            result.exceptionOrNull()?.let { err ->
                _state.update { it.copy(gitState = it.gitState.copy(isBusy = false, lastError = err.message ?: "git operation failed")) }
                return@launch
            }
            val fresh = CdrGitService.status(root)
            _state.update { it.copy(gitState = fresh) }
        }
    }

    private fun withGitCredentials(block: (File, String, String) -> Result<Unit>) {
        val root = _state.value.workspaceRoot ?: return
        val token = gitCredentials.token
        if (token.isBlank()) {
            _state.update { it.copy(gitState = it.gitState.copy(lastError = "No GitHub token set. Tap the key icon in the Git panel.")) }
            return
        }
        withGitRoot { r -> block(r, gitCredentials.username, token) }
    }

    private companion object {
        const val ACCESS_MESSAGE = "Android is not letting this app read this folder. Turn on \"All files access\" for the app, then reopen the folder."
        const val MAX_TABS = 24
        const val MAX_RECENTS = 8
        const val AUTOSAVE_DELAY_MS = 1500L
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
