package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.config.SwarmConnection
import com.example.data.storage.StorageAccess
import com.example.ui.OfflineAiViewModel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import com.example.ui.coderdroid.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File

/**
 * Coder Droid IDE screen — fully integrated into Boltz Recovery Companion.
 * Layout: Full professional IDE with sidebar (Files, Search, Git), Monaco editor,
 * tab bar, status bar, Marketplace, terminal, and Lucille Assistant panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoderDroidScreen(
    viewModel: CoderDroidViewModel = viewModel(),
    onNavigateBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val editorController = remember { CdrEditorController() }
    val openTabIds = remember(state.tabs) { state.tabs.map { it.id }.toSet() }

    // Dr. Boltz's offline AI: one shared view model for the model manager and the IDE chat.
    val ai: OfflineAiViewModel = viewModel()
    val aiState by ai.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    // Phone-width screens show the explorer full-width instead of squeezing the editor into a sliver.
    val narrow = LocalConfiguration.current.screenWidthDp < 720
    var showModels by remember { mutableStateOf(false) }
    LaunchedEffect(showModels) { if (showModels) ai.refresh() }

    // Coming back from the Settings screen where "All files access" is switched on: re-read the folder.
    val lifecycleOwner = LocalLifecycleOwner.current
    var hadAccess by remember { mutableStateOf(StorageAccess.hasAccess(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = StorageAccess.hasAccess(context)
                if (now != hadAccess) { hadAccess = now; viewModel.onEvent(CoderDroidEvent.RefreshTree) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Intercept Android back so it doesn't accidentally pop out of the IDE.
    BackHandler(enabled = true) {
        when {
            state.bottomPanel != CdrBottomPanel.NONE -> viewModel.onEvent(CoderDroidEvent.SetBottomPanel(CdrBottomPanel.NONE))
            state.sidebar != null                     -> viewModel.onEvent(CoderDroidEvent.HideSidebar)
            else                                     -> onNavigateBack?.invoke()
        }
    }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMarketplaceDialog by remember { mutableStateOf(false) }
    var showAndroidTools by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }

    // SAF folder picker
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: SecurityException) {}

            val resolved = StorageAccess.resolveTreeUri(treeUri)
            if (resolved != null && resolved.exists() && resolved.isDirectory) {
                viewModel.onEvent(CoderDroidEvent.OpenWorkspace(resolved))
            } else {
                Toast.makeText(
                    context,
                    "Folder not directly accessible. Use Clone to edit an app-private copy.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Permission launcher for Android 10 and older
    val runtimeStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        if (grantedMap.values.all { it }) {
            folderPicker.launch(null)
        } else {
            Toast.makeText(context, "Storage permission is required to open local folders.", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestOpenFolder() {
        if (StorageAccess.hasAccess(context)) {
            folderPicker.launch(null)
        } else if (StorageAccess.needsRuntimePrompt) {
            runtimeStorageLauncher.launch(StorageAccess.runtimePermissions)
        } else {
            showPermissionDialog = true
        }
    }

    // Storage permission explanation dialog for Android 11+
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = CarbonSurface,
            title = { Text("All Files Access Required", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "To open project folders and edit files outside the app directory, Boltz Recovery requires All Files Access. Tap Grant to open Settings and enable access.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    StorageAccess.openSettings(context)
                }) {
                    Text("Grant Permission", color = ElectricCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    // Listen for disk changes to reload clean tabs
    LaunchedEffect(Unit) {
        viewModel.reloads.collect { r -> editorController.reloadClean(r.tabId, r.content) }
    }

    // Jump to the searched line once that file is showing (the editor needs a moment to load it).
    LaunchedEffect(state.reveal, state.activeTab?.filePath) {
        val rev = state.reveal ?: return@LaunchedEffect
        if (state.activeTab?.filePath == rev.path) {
            delay(300)
            editorController.revealLine(rev.line)
            viewModel.onEvent(CoderDroidEvent.ClearReveal)
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(CoderDroidEvent.DismissNotice)
        }
    }

    LaunchedEffect(state.cloneError) {
        state.cloneError?.let { err ->
            Toast.makeText(context, "Clone failed: $err", Toast.LENGTH_LONG).show()
            viewModel.onEvent(CoderDroidEvent.DismissCloneError)
        }
    }

    // Modal dialogs
    CdrDialogHost(
        dialog = state.dialog,
        workspaceRoot = state.workspaceRoot,
        onEvent = viewModel::onEvent
    )

    if (showAndroidTools) {
        CdrAndroidTools(
            root = state.workspaceRoot,
            unsaved = state.hasDirtyTabs,
            onDismiss = { showAndroidTools = false },
            onProjectCreated = { viewModel.onEvent(CoderDroidEvent.OpenWorkspace(it)) }
        )
    }

    if (showCloneDialog) {
        CdrCloneRepoDialog(
            inProgress = state.cloneInProgress,
            onDismiss = { showCloneDialog = false },
            onClone = { url ->
                viewModel.onEvent(CoderDroidEvent.CloneRepo(url))
                showCloneDialog = false
            }
        )
    }

    if (showModels) {
        OfflineModelsDialog(
            state = aiState,
            onDownload = ai::download, onPause = ai::pause, onVerify = ai::verify,
            onLoad = ai::load, onUnload = ai::unload, onDelete = ai::delete,
            onDismiss = { showModels = false },
        )
    }

    if (showMarketplaceDialog) {
        CdrMarketplaceDialog(
            controller = editorController,
            workspaceRoot = state.workspaceRoot,
            onDismiss = { showMarketplaceDialog = false },
            onRefreshWorkspace = { viewModel.onEvent(CoderDroidEvent.RefreshTree) },
            onInsertSnippet = { snippet -> editorController.insertAtCursor(snippet) },
            onManageModels = { showModels = true }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CarbonBackground)
            .imePadding()
    ) {
        // ── Top Bar ────────────────────────────────────────────────────────────
        CdrTopBar(
            workspaceName = state.workspaceName,
            activeSidebar = state.sidebar,
            bottomPanel = state.bottomPanel,
            gitHasChanges = state.gitState.hasChanges,
            onOpenFolder = { requestOpenFolder() },
            onCloneRepo = { showCloneDialog = true },
            onOpenMarketplace = { showMarketplaceDialog = true },
            onSelectSidebar = { which ->
                if (state.sidebar == which) viewModel.onEvent(CoderDroidEvent.HideSidebar)
                else viewModel.onEvent(CoderDroidEvent.ShowSidebar(which))
            },
            onToggleTerminal = {
                val next = if (state.bottomPanel == CdrBottomPanel.TERMINAL) CdrBottomPanel.NONE else CdrBottomPanel.TERMINAL
                viewModel.onEvent(CoderDroidEvent.SetBottomPanel(next))
            },
            onToggleLucille = {
                val next = if (state.bottomPanel == CdrBottomPanel.AI) CdrBottomPanel.NONE else CdrBottomPanel.AI
                viewModel.onEvent(CoderDroidEvent.SetBottomPanel(next))
            },
            onOpenDrI30LTZ = {
                val candidates = listOf(
                    File(Environment.getExternalStorageDirectory(), "Syncthing/SWARM_OS/i30ltz-recovery"),
                    File(Environment.getExternalStorageDirectory(), "SWARM_OS/i30ltz-recovery"),
                    File(Environment.getExternalStorageDirectory(), "i30ltz-recovery")
                )
                val found = candidates.firstOrNull { it.exists() }
                if (found != null) {
                    viewModel.onEvent(CoderDroidEvent.OpenWorkspace(found))
                } else {
                    Toast.makeText(context, "DR. I30LTZ project not found. Clone its Git repository first.", Toast.LENGTH_LONG).show()
                }
            }
        )

        // ── Quick Action Toolbar ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CarbonSurfaceVariant)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.onEvent(CoderDroidEvent.SaveAll) },
                enabled = state.hasDirtyTabs,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Save all", fontSize = 11.sp) }

            Button(
                onClick = { viewModel.onEvent(CoderDroidEvent.ShowSidebar(CdrSidebar.GIT)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Git", fontSize = 11.sp) }

            FilledTonalButton(
                onClick = { editorController.run("format") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Format", fontSize = 11.sp) }

            FilledTonalButton(
                onClick = { editorController.run("find") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Find", fontSize = 11.sp) }

            FilledTonalButton(
                onClick = { editorController.run("palette") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Palette", fontSize = 11.sp) }

            OutlinedButton(
                onClick = { showAndroidTools = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("Build APK / ADB", fontSize = 11.sp) }
        }

        // ── Main Workspace Area ────────────────────────────────────────────────
        Row(modifier = Modifier.weight(1f)) {
            // Sidebar
            when (state.sidebar) {
                CdrSidebar.FILES -> {
                    CdrFileTreePanel(
                        workspaceRoot = state.workspaceRoot,
                        workspaceName = state.workspaceName,
                        treeRevision = state.treeRevision,
                        showHidden = state.settings.showHidden,
                        accessProblem = state.accessProblem,
                        activePath = state.activeTab?.filePath,
                        recent = state.recentWorkspaces,
                        onFileClick = {
                            viewModel.onEvent(CoderDroidEvent.OpenFile(it))
                            if (narrow) viewModel.onEvent(CoderDroidEvent.HideSidebar)
                        },
                        onDialog = { viewModel.onEvent(CoderDroidEvent.ShowDialog(it)) },
                        onRefresh = { viewModel.onEvent(CoderDroidEvent.RefreshTree) },
                        onToggleHidden = { viewModel.onEvent(CoderDroidEvent.ToggleHidden) },
                        onOpenFolder = { requestOpenFolder() },
                        onGrantAccess = { requestOpenFolder() },
                        onOpenRecent = { viewModel.onEvent(CoderDroidEvent.OpenWorkspace(it)) },
                        modifier = if (narrow) Modifier.fillMaxSize() else Modifier.width(220.dp).fillMaxHeight()
                    )
                }
                CdrSidebar.SEARCH -> {
                    CdrSearchPanel(
                        search = state.search,
                        workspaceRoot = state.workspaceRoot,
                        onQuery = { viewModel.onEvent(CoderDroidEvent.SetSearchQuery(it)) },
                        onByName = { viewModel.onEvent(CoderDroidEvent.SetSearchByName(it)) },
                        onOpen = { file, line ->
                            viewModel.onEvent(CoderDroidEvent.OpenFileAt(file, line))
                            if (narrow) viewModel.onEvent(CoderDroidEvent.HideSidebar)
                        },
                        modifier = if (narrow) Modifier.fillMaxSize() else Modifier.width(220.dp).fillMaxHeight()
                    )
                }
                CdrSidebar.GIT -> {
                    CdrGitPanel(
                        gitState = state.gitState,
                        hasCredentials = state.gitHasCredentials,
                        username = state.gitUsername,
                        authorName = state.gitAuthorName,
                        authorEmail = state.gitAuthorEmail,
                        commitSequence = state.commitSequence,
                        modifier = if (narrow) Modifier.fillMaxSize() else Modifier.width(240.dp).fillMaxHeight(),
                        onRefresh = { viewModel.onEvent(CoderDroidEvent.RefreshGit) },
                        onStage = { viewModel.onEvent(CoderDroidEvent.StageFile(it)) },
                        onUnstage = { viewModel.onEvent(CoderDroidEvent.UnstageFile(it)) },
                        onStageAll = { viewModel.onEvent(CoderDroidEvent.StageAll) },
                        onCommit = { viewModel.onEvent(CoderDroidEvent.Commit(it)) },
                        onSetOrigin = { viewModel.onEvent(CoderDroidEvent.SetOrigin(it)) },
                        onPush = { viewModel.onEvent(CoderDroidEvent.Push) },
                        onPull = { viewModel.onEvent(CoderDroidEvent.Pull) },
                        onSaveCredentials = { u, t, n, e -> viewModel.onEvent(CoderDroidEvent.SetGitCredentials(u, t, n, e)) }
                    )
                }
                null -> Unit
            }

            // Editor column (on a phone it gives way to the full-width explorer while that is open)
            if (!(narrow && state.sidebar != null)) Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CdrTabBar(
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    onTabClick = { viewModel.onEvent(CoderDroidEvent.SwitchTab(it)) },
                    onTabClose = { viewModel.onEvent(CoderDroidEvent.RequestCloseTab(it)) }
                )

                CdrMonacoEditorView(
                    tab = state.activeTab,
                    openTabIds = openTabIds,
                    controller = editorController,
                    drafts = state.drafts,
                    fontSize = state.settings.fontSize,
                    wordWrap = state.settings.wordWrap,
                    onContentChanged = { id, content -> viewModel.onEvent(CoderDroidEvent.Edit(id, content)) },
                    onCursorMoved = { line, col -> viewModel.onEvent(CoderDroidEvent.CursorMoved(line, col)) },
                    onSaveRequested = { tabId, content -> viewModel.onEvent(CoderDroidEvent.SaveTab(tabId, content)) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                // Bottom panels
                when (state.bottomPanel) {
                    CdrBottomPanel.TERMINAL -> {
                        CdrInlineTerminal(modifier = Modifier.height(200.dp).fillMaxWidth())
                    }
                    CdrBottomPanel.AI -> {
                        // Dr. Boltz, this app's own assistant (user decision 2026-10-12). The other AI's
                        // CdrLucilleAssistantPanel file is intentionally left on disk, just not shown here.
                        CdrAiPanel(
                            state = aiState,
                            hasFile = state.activeTab?.isReadOnly == false,
                            onAction = { action, text -> editorController.context { ctx -> ai.send(action, text, ctx) } },
                            onStop = ai::stop,
                            onClear = ai::clearChat,
                            onManageModels = { showModels = true },
                            onInsert = { editorController.insertAtCursor(it) },
                            onReplaceSelection = { code ->
                                editorController.replaceSelection(code) { ok ->
                                    if (!ok) Toast.makeText(context, "Select the code you want to replace first.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCopy = {
                                clipboard.setText(AnnotatedString(it))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(300.dp).fillMaxWidth()
                        )
                    }
                    CdrBottomPanel.NONE -> Unit
                }
            }
        }

        // Keys a phone keyboard lacks (Tab, arrows, brackets…), shown while the keyboard is up.
        if (WindowInsets.isImeVisible && state.activeTab?.isReadOnly == false) {
            CdrExtraKeys(onAction = editorController::run, onType = editorController::insertAtCursor)
        }

        // ── Status Bar ─────────────────────────────────────────────────────────
        CdrStatusBar(
            workspaceName = state.workspaceName,
            activeTab = state.activeTab,
            bottomPanel = state.bottomPanel,
            onToggleTerminal = {
                val next = if (state.bottomPanel == CdrBottomPanel.TERMINAL) CdrBottomPanel.NONE else CdrBottomPanel.TERMINAL
                viewModel.onEvent(CoderDroidEvent.SetBottomPanel(next))
            },
            onToggleLucille = {
                val next = if (state.bottomPanel == CdrBottomPanel.AI) CdrBottomPanel.NONE else CdrBottomPanel.AI
                viewModel.onEvent(CoderDroidEvent.SetBottomPanel(next))
            },
            branch = if (state.gitState.isRepo) state.gitState.branch else "",
            unsaved = state.tabs.count { it.isDirty }
        )
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────────

@Composable
private fun CdrTopBar(
    workspaceName: String,
    activeSidebar: CdrSidebar?,
    bottomPanel: CdrBottomPanel,
    gitHasChanges: Boolean,
    onOpenFolder: () -> Unit,
    onCloneRepo: () -> Unit,
    onOpenMarketplace: () -> Unit,
    onSelectSidebar: (CdrSidebar) -> Unit,
    onToggleTerminal: () -> Unit,
    /** Opens or closes the AI panel (Dr. Boltz). The parameter keeps its old name so callers stay unchanged. */
    onToggleLucille: () -> Unit,
    onOpenDrI30LTZ: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Four everyday actions stay on the bar; the rest live in the ⋮ menu so nothing is squeezed off a phone screen.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(CarbonSurface)
            .border(1.dp, CarbonBorder)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(ElectricPurple.copy(alpha = 0.2f))
                .border(1.dp, ElectricPurple.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("⚡ CODER DROID", color = ElectricPurple, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1)
        }
        Text(
            text = workspaceName,
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
        )
        CdrIconButton(Icons.Default.FolderOpen, "Open folder", ElectricCyan) { onOpenFolder() }
        CdrIconButton(Icons.Default.Search, "Search files", if (activeSidebar == CdrSidebar.SEARCH) ElectricPurple else TextSecondary) { onSelectSidebar(CdrSidebar.SEARCH) }
        Box {
            CdrIconButton(Icons.Default.AccountTree, "Git panel", if (activeSidebar == CdrSidebar.GIT) ElectricPurple else TextSecondary) { onSelectSidebar(CdrSidebar.GIT) }
            if (gitHasChanges && activeSidebar != CdrSidebar.GIT) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(LightningYellow)
                )
            }
        }
        CdrIconButton(Icons.Default.AutoAwesome, "Dr. Boltz AI", if (bottomPanel == CdrBottomPanel.AI) NeonGreen else ElectricCyan) { onToggleLucille() }
        Box {
            CdrIconButton(Icons.Default.MoreVert, "More", TextSecondary) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                @Composable fun item(text: String, action: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = { menuOpen = false; action() })
                item("Files explorer") { onSelectSidebar(CdrSidebar.FILES) }
                item(if (bottomPanel == CdrBottomPanel.TERMINAL) "Hide terminal" else "Terminal") { onToggleTerminal() }
                item("Clone from Git…") { onCloneRepo() }
                item("Marketplace") { onOpenMarketplace() }
                item("Open I30LTZ Recovery OS project") { onOpenDrI30LTZ() }
            }
        }
    }
}

// ── Status bar ─────────────────────────────────────────────────────────────────

@Composable
private fun CdrStatusBar(
    workspaceName: String,
    activeTab: CdrEditorTab?,
    bottomPanel: CdrBottomPanel,
    onToggleTerminal: () -> Unit,
    onToggleLucille: () -> Unit,
    branch: String = "",
    unsaved: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(ElectricPurple.copy(alpha = 0.85f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bolt, null, tint = Color.White, modifier = Modifier.size(12.dp))
            Text(workspaceName, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
            if (branch.isNotEmpty()) Text("⎇ $branch", color = Color.White, fontSize = 10.sp, maxLines = 1)
            if (unsaved > 0) Text("● $unsaved unsaved", color = LightningYellow, fontSize = 10.sp, maxLines = 1)
        }

        Row(
            modifier = Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            activeTab?.let {
                if (it.isReadOnly) Text("READ-ONLY", color = LightningYellow, fontSize = 10.sp)
                Text(it.languageId, color = Color.White, fontSize = 10.sp)
                Text("Ln ${it.cursorLine + 1}  Col ${it.cursorColumn + 1}", color = Color.White, fontSize = 10.sp)
            }
            Text(
                text = "Dr. Boltz AI",
                color = if (bottomPanel == CdrBottomPanel.AI) NeonGreen else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggleLucille() }
            )
            Text(
                text = "Terminal",
                color = if (bottomPanel == CdrBottomPanel.TERMINAL) LightningYellow else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggleTerminal() }
            )
        }
    }
}

// ── Inline terminal ────────────────────────────────────────────────────────────

@Composable
private fun CdrInlineTerminal(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection = remember { SwarmConnection(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var commandInput by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf("Connecting to ${connection.host}:${connection.port}…") }
    var socket by remember { mutableStateOf<WebSocket?>(null) }

    fun append(text: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            text.split("\n").filter { it.isNotEmpty() }.forEach { lines.add(it) }
            if (lines.size > 500) repeat(lines.size - 500) { lines.removeAt(0) }
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
    }

    DisposableEffect(connection.host, connection.port) {
        if (!connection.isUsable) {
            append("No node configured. Add one under Nodes (Monitor tab).")
            return@DisposableEffect onDispose { }
        }
        val client = connection.terminalClient()
        val request = Request.Builder()
            .url("${connection.wsBase()}/api/terminal/ws")
            .addHeader("X-Swarm-Auth", connection.authToken)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { connected = true }
                append("⚡ connected to ${connection.host}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = append(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { connected = false }
                append("[disconnected: $reason]")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { connected = false }
                append("[connection error: ${connection.explain(t, response?.code)}]")
            }
        })
        socket = ws
        onDispose { ws.close(1000, "terminal closed") }
    }

    Column(
        modifier = modifier
            .background(CarbonSurface)
            .border(1.dp, CarbonBorder)
            .padding(8.dp)
    ) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = if (line.startsWith("[")) Color(0xFFFF6B6B) else NeonGreen,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (connected) "$ " else "× ",
                color = if (connected) NeonGreen else Color(0xFFFF6B6B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            BasicTextField(
                value = commandInput,
                onValueChange = { newValue ->
                    if (newValue.contains("\n")) {
                        val cmd = newValue.replace("\n", "").trim()
                        if (cmd.isNotEmpty()) {
                            socket?.send(cmd + "\n")
                            append("$ $cmd")
                        }
                        commandInput = ""
                    } else {
                        commandInput = newValue
                    }
                },
                singleLine = true,
                enabled = connected,
                textStyle = TextStyle(color = NeonGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(NeonGreen),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = commandInput.trim()
                    if (cmd.isNotEmpty() && connected) {
                        socket?.send(cmd + "\n")
                        append("$ $cmd")
                    }
                    commandInput = ""
                }),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CdrIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun CdrCloneRepoDialog(
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onClone: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        containerColor = CarbonSurface,
        title = { Text("Clone repository", color = Color.White, fontSize = 14.sp) },
        text = {
            Column {
                Text(
                    "Downloads the repo into app-private storage and opens it as the workspace. Private repos need a token set in the Git panel first.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository URL") },
                    placeholder = { Text("https://github.com/user/repo.git") },
                    singleLine = true,
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                )
                if (inProgress) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricPurple)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClone(url) }, enabled = url.isNotBlank() && !inProgress) {
                Text("Clone", color = ElectricCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) { Text("Cancel", color = TextMuted) }
        }
    )
}
