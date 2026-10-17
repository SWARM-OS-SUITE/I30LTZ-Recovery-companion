package com.example.ui.coderdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.io.File

/** Find text inside the project, or jump to a file by name. */
@Composable
fun CdrSearchPanel(
    search: CdrSearchState,
    workspaceRoot: File?,
    onQuery: (String) -> Unit,
    onByName: (Boolean) -> Unit,
    onOpen: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(CarbonSurface).border(1.dp, CarbonBorder).padding(8.dp)) {
        OutlinedTextField(
            value = search.query, onValueChange = onQuery,
            label = { Text(if (search.byName) "File name" else "Find in files") },
            singleLine = true, enabled = workspaceRoot != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !search.byName, onClick = { onByName(false) }, label = { Text("Text", fontSize = 11.sp) })
            FilterChip(selected = search.byName, onClick = { onByName(true) }, label = { Text("File name", fontSize = 11.sp) })
        }
        if (search.running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ElectricPurple)

        val empty = search.searched && !search.running && search.hits.isEmpty() && search.files.isEmpty()
        when {
            workspaceRoot == null -> Hint("Open a project to search it.")
            search.query.isBlank() -> Hint("Type to search. Build and .git folders are skipped.")
            empty -> Hint("Nothing found for \"${search.query}\".")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(search.hits, key = { "${it.file.absolutePath}:${it.line}" }) { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(hit.file, hit.line) }.padding(vertical = 5.dp)) {
                        Text(
                            "${hit.file.relativeToOrSelf(workspaceRoot)}:${hit.line}",
                            color = ElectricCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(hit.text, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(search.files, key = { it.absolutePath }) { file ->
                    Text(
                        file.relativeToOrSelf(workspaceRoot).path,
                        color = ElectricCyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(file, 1) }.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopStart) {
        Text(text, color = TextMuted, fontSize = 11.sp)
    }
}

private fun File.relativeToOrSelf(root: File): File = try { relativeTo(root) } catch (_: IllegalArgumentException) { this }
