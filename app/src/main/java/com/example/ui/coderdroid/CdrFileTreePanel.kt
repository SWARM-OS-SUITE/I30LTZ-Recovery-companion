package com.example.ui.coderdroid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CdrFileTreePanel(
    workspaceRoot: File?,
    workspaceName: String,
    treeRevision: Int,
    showHidden: Boolean,
    accessProblem: String?,
    activePath: String?,
    recent: List<String>,
    onFileClick: (CdrFileNode) -> Unit,
    onDialog: (CdrDialog) -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenFolder: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpenRecent: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The project's top folder starts open, so its files are visible straight away.
    var expandedPaths by remember(workspaceRoot) { mutableStateOf(setOfNotNull(workspaceRoot?.absolutePath)) }

    Column(modifier = modifier.background(CarbonSurface).border(1.dp, CarbonBorder)) {
        // Header: project name and the common actions.
        Row(
            modifier = Modifier.fillMaxWidth().background(CarbonSurfaceVariant).padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.FolderOpen, null, tint = ElectricPurple, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = workspaceName.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (workspaceRoot != null) {
                HeaderAction(Icons.Default.NoteAdd, "New file") { onDialog(CdrDialog.NewFile(workspaceRoot)) }
                HeaderAction(Icons.Default.CreateNewFolder, "New folder") { onDialog(CdrDialog.NewFolder(workspaceRoot)) }
                HeaderAction(Icons.Default.Refresh, "Refresh files", onRefresh)
                HeaderAction(
                    if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    if (showHidden) "Hide build and git folders" else "Show build and git folders",
                    onToggleHidden,
                )
            }
        }
        HorizontalDivider(color = CarbonBorder, thickness = 1.dp)

        if (workspaceRoot == null) {
            EmptyWorkspace(recent, onOpenFolder, onOpenRecent)
            return@Column
        }
        if (accessProblem != null) {
            AccessProblem(accessProblem, onGrantAccess, onRefresh)
            return@Column
        }

        val nodes by produceState<List<CdrFileNode>?>(null, workspaceRoot, expandedPaths, treeRevision, showHidden) {
            value = withContext(Dispatchers.IO) { buildNodes(listOf(workspaceRoot), expandedPaths, 0, showHidden) }
        }
        val rows = nodes
        if (rows == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", fontSize = 11.sp, color = TextMuted)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.path }) { node ->
                CdrFileRow(
                    node = node,
                    isExpanded = node.path in expandedPaths,
                    isActive = node.path == activePath,
                    isRoot = node.file.absolutePath == workspaceRoot.absolutePath,
                    onClick = {
                        if (node.isDirectory) {
                            expandedPaths = if (node.path in expandedPaths) expandedPaths - node.path else expandedPaths + node.path
                        } else onFileClick(node)
                    },
                    onDialog = onDialog,
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, desc, tint = TextSecondary, modifier = Modifier.size(17.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyWorkspace(recent: List<String>, onOpenFolder: () -> Unit, onOpenRecent: (File) -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No project open", fontSize = 12.sp, color = TextPrimary)
        Text("Open a folder on this phone, or clone a Git repository.", fontSize = 11.sp, color = TextMuted)
        TextButton(onClick = onOpenFolder) { Text("Open folder", color = ElectricCyan) }
        if (recent.isNotEmpty()) {
            Text("RECENT", fontSize = 9.sp, letterSpacing = 1.sp, color = TextMuted)
            recent.forEach { path ->
                Text(
                    text = File(path).name.ifEmpty { path },
                    fontSize = 12.sp, color = ElectricCyan, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenRecent(File(path)) }).padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AccessProblem(message: String, onGrantAccess: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This folder can't be read", fontSize = 12.sp, color = LightningYellow)
        Text(message, fontSize = 11.sp, color = TextMuted)
        TextButton(onClick = onGrantAccess) { Text("Allow file access", color = ElectricCyan) }
        TextButton(onClick = onRetry) { Text("Try again", color = TextSecondary) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CdrFileRow(
    node: CdrFileNode,
    isExpanded: Boolean,
    isActive: Boolean,
    isRoot: Boolean,
    onClick: () -> Unit,
    onDialog: (CdrDialog) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val parentForNew = if (node.isDirectory) node.file else node.file.parentFile ?: node.file

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(if (isActive) ElectricPurple.copy(alpha = 0.18f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(start = (node.depth * 14 + 6).dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (node.isDirectory) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    null, tint = TextMuted, modifier = Modifier.size(14.dp),
                )
            } else Spacer(Modifier.width(14.dp))
            Icon(
                imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (node.isDirectory) ElectricPurple else when (node.extension) {
                    "kt", "kts" -> ElectricCyan
                    "java" -> NvidiaGreen
                    "xml" -> LightningYellow
                    "json" -> NeonGreen
                    else -> TextSecondary
                },
                modifier = Modifier.size(14.dp),
            )
            Text(text = node.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("New file…") }, onClick = { menuOpen = false; onDialog(CdrDialog.NewFile(parentForNew)) })
            DropdownMenuItem(text = { Text("New folder…") }, onClick = { menuOpen = false; onDialog(CdrDialog.NewFolder(parentForNew)) })
            if (!isRoot) {
                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menuOpen = false; onDialog(CdrDialog.Rename(node.file)) })
                DropdownMenuItem(text = { Text("Delete…", color = Color(0xFFFF6B6B)) }, onClick = { menuOpen = false; onDialog(CdrDialog.Delete(node.file)) })
            }
        }
    }
}

private fun buildNodes(files: List<File>, expanded: Set<String>, depth: Int, showHidden: Boolean): List<CdrFileNode> {
    val result = mutableListOf<CdrFileNode>()
    files.forEach { f ->
        result.add(CdrFileNode(f, depth))
        if (f.isDirectory && f.absolutePath in expanded && !CdrFileOps.isSymlink(f)) {
            val children = CdrFileOps.list(f, showHidden) ?: emptyList()
            result.addAll(buildNodes(children, expanded, depth + 1, showHidden))
        }
    }
    return result
}
