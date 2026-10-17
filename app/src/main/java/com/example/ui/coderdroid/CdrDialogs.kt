package com.example.ui.coderdroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.TextMuted

private val Danger = Color(0xFFFF6B6B)

/** Shows whichever [CdrDialog] is current. All wording is plain language; destructive buttons are red. */
@Composable
fun CdrDialogHost(
    dialog: CdrDialog?,
    workspaceRoot: java.io.File?,
    onEvent: (CoderDroidEvent) -> Unit,
) {
    when (dialog) {
        null -> Unit
        is CdrDialog.NewFile -> NameDialog(
            title = "New file", hint = "Name, for example Main.kt", initial = "", confirm = "Create",
            where = relativeTo(workspaceRoot, dialog.parent),
            onDismiss = { onEvent(CoderDroidEvent.DismissDialog) },
            onConfirm = { onEvent(CoderDroidEvent.CreateFile(dialog.parent, it)) },
        )
        is CdrDialog.NewFolder -> NameDialog(
            title = "New folder", hint = "Folder name", initial = "", confirm = "Create",
            where = relativeTo(workspaceRoot, dialog.parent),
            onDismiss = { onEvent(CoderDroidEvent.DismissDialog) },
            onConfirm = { onEvent(CoderDroidEvent.CreateFolder(dialog.parent, it)) },
        )
        is CdrDialog.Rename -> NameDialog(
            title = "Rename", hint = "New name", initial = dialog.file.name, confirm = "Rename",
            where = null,
            onDismiss = { onEvent(CoderDroidEvent.DismissDialog) },
            onConfirm = { onEvent(CoderDroidEvent.Rename(dialog.file, it)) },
        )
        is CdrDialog.Delete -> AlertDialog(
            onDismissRequest = { onEvent(CoderDroidEvent.DismissDialog) },
            containerColor = CarbonSurface,
            title = { Text("Delete ${dialog.file.name}?", color = Color.White, fontSize = 15.sp) },
            text = {
                Text(
                    if (dialog.file.isDirectory) "This deletes the folder and everything inside it from the phone. It cannot be undone, unless it is saved in Git."
                    else "This deletes the file from the phone. It cannot be undone, unless it is saved in Git.",
                    color = TextMuted, fontSize = 12.sp,
                )
            },
            confirmButton = { TextButton(onClick = { onEvent(CoderDroidEvent.Delete(dialog.file)) }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { onEvent(CoderDroidEvent.DismissDialog) }) { Text("Cancel", color = TextMuted) } },
        )
        is CdrDialog.UnsavedChanges -> AlertDialog(
            onDismissRequest = { onEvent(CoderDroidEvent.DismissDialog) },
            containerColor = CarbonSurface,
            title = { Text("Save your changes?", color = Color.White, fontSize = 15.sp) },
            text = {
                Text(
                    when (dialog.then) {
                        is PendingAction.CloseTab -> "This file has changes that are not saved yet."
                        is PendingAction.OpenWorkspace -> "Some open files have changes that are not saved yet. Opening another project closes them."
                        is PendingAction.Clone -> "Some open files have changes that are not saved yet. Cloning opens a different project and closes them."
                    },
                    color = TextMuted, fontSize = 12.sp,
                )
            },
            confirmButton = { TextButton(onClick = { onEvent(CoderDroidEvent.SaveThenContinue) }) { Text("Save", color = ElectricCyan) } },
            dismissButton = {
                Column {
                    TextButton(onClick = { onEvent(CoderDroidEvent.DiscardThenContinue) }) { Text("Don't save", color = Danger) }
                    TextButton(onClick = { onEvent(CoderDroidEvent.DismissDialog) }) { Text("Cancel", color = TextMuted) }
                }
            },
        )
    }
}

private fun relativeTo(root: java.io.File?, dir: java.io.File): String? {
    root ?: return null
    val rel = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
    return if (rel.isEmpty()) "in the project folder" else "in $rel"
}

@Composable
private fun NameDialog(
    title: String,
    hint: String,
    initial: String,
    confirm: String,
    where: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val problem = if (name.isEmpty()) null else CdrFileOps.nameProblem(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        title = { Text(title, color = Color.White, fontSize = 15.sp) },
        text = {
            Column {
                if (where != null) {
                    Text(where, color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text(hint) },
                    singleLine = true, isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty() && CdrFileOps.nameProblem(name) == null) {
                Text(confirm, color = ElectricCyan)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}
