package com.example.ui.coderdroid

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.OfflineLlm
import com.example.data.memory.MemorySyncEngine
import com.example.data.service.GeminiAiService
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lucille Assistant IDE Panel: connects CoderDroid directly to Lucille Assistant,
 * the SWARM memory mesh, and Git workflows.
 *
 * Supports:
 *  - Pulling latest Git code from remote
 *  - Reading active editor context (file, selection, line, language)
 *  - Requesting code suggestions and improvements from Lucille Assistant
 *  - 1-click applying Lucille's suggestion to Monaco editor
 *  - 1-click committing the applied suggestion with Git
 */
@Composable
fun CdrLucilleAssistantPanel(
    controller: CdrEditorController,
    activeTab: CdrEditorTab?,
    gitBusy: Boolean,
    onPullGit: () -> Unit,
    onCommit: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var promptInput by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    var lucilleResponse by remember { mutableStateOf<String?>(null) }
    var extractedCodeSnippet by remember { mutableStateOf<String?>(null) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    val liveMemory by MemorySyncEngine.live.collectAsState()
    val totalMemories by MemorySyncEngine.count().collectAsState(initial = 0)

    fun extractSnippet(fullText: String): String? {
        val codeFenceRegex = Regex("```[a-zA-Z0-9_-]*\\n([\\s\\S]*?)```")
        val match = codeFenceRegex.find(fullText)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    fun askLucille(customInstruction: String? = null) {
        val instruction = customInstruction ?: promptInput.trim()
        if (instruction.isBlank()) return

        isThinking = true
        statusNotice = null
        lucilleResponse = null
        extractedCodeSnippet = null

        controller.context { ctx ->
            scope.launch(Dispatchers.IO) {
                try {
                    val fileContext = if (ctx != null && ctx.hasFile) {
                        "File: ${activeTab?.fileNode?.name ?: "active"}, Language: ${ctx.language}, Line: ${ctx.line}\n" +
                        if (ctx.hasSelection) "Selected Code:\n```\n${ctx.selection}\n```\n"
                        else "Full File Content:\n```\n${ctx.text.take(3000)}\n```\n"
                    } else "No open file in editor."

                    val memoryHeader = "SWARM Memory Context ($totalMemories entries, Live: $liveMemory)\n"

                    val fullPrompt = "You are Lucille Assistant, the SWARM companion and expert code assistant paired with Boltz Recovery and CoderDroid. " +
                        "Provide a concise, direct code suggestion or improvement for the following user request.\n" +
                        "Always enclose the suggested code inside markdown triple backticks (```) so the IDE can parse and apply it.\n\n" +
                        "$memoryHeader\n$fileContext\n\nUser Request: $instruction"

                    val llm = OfflineLlm.peek()
                    val answer = if (llm?.loadedModel != null || llm?.bestInstalled() != null) {
                        llm.answer(
                            system = "You are Lucille Assistant, a helpful and precise code assistant.",
                            user = fullPrompt
                        )
                    } else {
                        val aiService = GeminiAiService()
                        aiService.getAiResponse(userPrompt = fullPrompt, systemContext = "CoderDroid Lucille Assistant Panel")
                    }

                    withContext(Dispatchers.Main) {
                        isThinking = false
                        lucilleResponse = answer
                        extractedCodeSnippet = extractSnippet(answer)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isThinking = false
                        statusNotice = "Lucille Assistant error: ${e.message}"
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .background(CarbonSurface)
            .border(1.dp, CarbonBorder)
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (liveMemory) NeonGreen else ElectricCyan))
                Text(
                    "⚡ LUCILLE ASSISTANT",
                    color = ElectricPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text("· Memory Mesh ($totalMemories)", color = TextMuted, fontSize = 10.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Pull git code button
                FilledTonalButton(
                    onClick = onPullGit,
                    enabled = !gitBusy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pull Git Code", fontSize = 10.sp)
                }

                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Quick action chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SuggestionChip(
                onClick = { askLucille("Review and suggest improvements for this code.") },
                label = { Text("Suggest Fix", fontSize = 10.sp) }
            )
            SuggestionChip(
                onClick = { askLucille("Find bugs and security issues in this code.") },
                label = { Text("Find Bugs", fontSize = 10.sp) }
            )
            SuggestionChip(
                onClick = { askLucille("Refactor this code for readability and performance.") },
                label = { Text("Refactor", fontSize = 10.sp) }
            )
        }

        // Response or progress area
        if (isThinking) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = ElectricPurple)
            Text("Lucille is reviewing the code…", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        statusNotice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 11.sp)
        }

        lucilleResponse?.let { resp ->
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(CarbonBackground, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                item {
                    Text(resp, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (extractedCodeSnippet != null) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Apply snippet to Monaco editor
                            Button(
                                onClick = {
                                    val code = extractedCodeSnippet ?: return@Button
                                    controller.replaceSelection(code) { success ->
                                        if (!success) controller.insertAtCursor(code)
                                        Toast.makeText(context, "Lucille suggestion applied to editor!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple),
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Apply Suggestion", fontSize = 11.sp)
                            }

                            // Commit suggestion to Git
                            Button(
                                onClick = {
                                    val fileName = activeTab?.fileNode?.name ?: "code"
                                    onCommit("feat(lucille): apply suggestion for $fileName")
                                    Toast.makeText(context, "Suggestion committed locally!", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !gitBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(13.dp), tint = Color.Black)
                                Spacer(Modifier.width(4.dp))
                                Text("Commit Suggestion", fontSize = 11.sp, color = Color.Black)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Prompt input field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                placeholder = { Text("Ask Lucille Assistant…", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { askLucille(); promptInput = "" },
                enabled = promptInput.isNotBlank() && !isThinking,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = ElectricCyan, modifier = Modifier.size(18.dp))
            }
        }
    }
}
