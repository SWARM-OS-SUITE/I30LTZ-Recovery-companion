package com.example.ui.coderdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.AiAction
import com.example.data.ai.AiPrompts
import com.example.ui.AiMessage
import com.example.ui.OfflineAiState
import com.example.ui.screens.offlineHeadline
import com.example.ui.theme.*

private val Danger = Color(0xFFFF6B6B)

/**
 * Dr. Boltz inside the IDE: ask a question, or run Explain / Find bugs / Write tests / Add docs on the
 * selected code (or the whole file). Code in an answer can be put into the editor with one tap.
 */
@Composable
fun CdrAiPanel(
    state: OfflineAiState,
    hasFile: Boolean,
    onAction: (AiAction, String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onManageModels: () -> Unit,
    onInsert: (String) -> Unit,
    onReplaceSelection: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.text?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
    }

    Column(modifier.background(CarbonSurface).border(1.dp, CarbonBorder)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = ElectricPurple, modifier = Modifier.size(14.dp))
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("DR. BOLTZ · OFFLINE", color = ElectricPurple, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(offlineHeadline(state), color = TextMuted, fontSize = 10.sp, maxLines = 1)
            }
            TextButton(onClick = onManageModels) { Text(if (state.anyInstalled) "Models" else "Get models", color = ElectricCyan, fontSize = 12.sp) }
            IconButton(onClick = onClear, enabled = state.messages.isNotEmpty() || state.generating, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.DeleteSweep, "Clear chat", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }

        // Quick actions work on the selection if there is one, else the whole file.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(AiAction.EXPLAIN, AiAction.FIX, AiAction.TESTS, AiAction.DOCS).forEach { action ->
                AssistChip(
                    onClick = { onAction(action, input.also { input = "" }) },
                    label = { Text(action.label, fontSize = 11.sp) },
                    enabled = hasFile && !state.generating,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Text(
                    if (state.anyInstalled) "Ask a question, or select some code and tap Explain. Everything stays on this phone."
                    else "No offline model is installed yet. Tap Get models to download one; after that Dr. Boltz works with no internet.",
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.messages, key = { it.id }) { msg -> Bubble(msg, onInsert, onReplaceSelection, onCopy) }
                }
            }
        }

        // Input
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Ask Dr. Boltz…", fontSize = 12.sp) },
                maxLines = 3, modifier = Modifier.weight(1f),
            )
            if (state.generating) {
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop", tint = Danger) }
            } else {
                IconButton(
                    onClick = { onAction(AiAction.ASK, input); input = "" },
                    enabled = input.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (input.isNotBlank()) ElectricCyan else TextMuted) }
            }
        }
    }
}

@Composable
private fun Bubble(msg: AiMessage, onInsert: (String) -> Unit, onReplace: (String) -> Unit, onCopy: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    if (msg.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                msg.text, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.background(ElectricPurple.copy(alpha = 0.35f), shape).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().background(CarbonSurfaceVariant, shape)
            .border(1.dp, if (msg.isError) Danger.copy(alpha = 0.6f) else CarbonBorder, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (msg.text.isEmpty() && msg.streaming) Text("Thinking…", color = TextMuted, fontSize = 12.sp)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AiPrompts.segments(msg.text).forEach { seg ->
                    if (seg.isCode) {
                        Text(
                            seg.text, color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF111118), RoundedCornerShape(6.dp))
                                .horizontalScroll(rememberScrollState()).padding(8.dp),
                        )
                    } else {
                        Text(seg.text, color = if (msg.isError) Danger else TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
        if (!msg.streaming) {
            AiPrompts.codeBlocks(msg.text).forEachIndexed { index, block ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Code ${index + 1}${if (block.language.isNotEmpty()) " (${block.language})" else ""}:", color = TextMuted, fontSize = 10.sp)
                    TextButton(onClick = { onInsert(block.code) }) { Text("Insert", color = ElectricCyan, fontSize = 12.sp) }
                    TextButton(onClick = { onReplace(block.code) }) { Text("Replace selection", color = ElectricCyan, fontSize = 12.sp) }
                    TextButton(onClick = { onCopy(block.code) }) { Text("Copy", color = TextSecondary, fontSize = 12.sp) }
                }
            }
            msg.modelName?.let { Text("answered offline by $it", color = TextMuted, fontSize = 9.sp) }
        }
    }
}
