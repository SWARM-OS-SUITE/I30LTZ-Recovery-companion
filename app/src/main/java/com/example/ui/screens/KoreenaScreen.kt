package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ai.AiPrompts
import com.example.data.koreena.BreakdownItem
import com.example.data.koreena.CommandReader
import com.example.data.koreena.Glossary
import com.example.data.koreena.Kind
import com.example.ui.AiMessage
import com.example.ui.KoreenaViewModel
import com.example.ui.OfflineAiViewModel
import com.example.ui.koreena.CommandColors
import com.example.ui.koreena.CommandText
import com.example.ui.theme.*

private val KoreenaTeal = Color(0xFF2DD4BF)
private val Danger = Color(0xFFFF6B6B)

private data class Example(val label: String, val text: String)

private val EXAMPLES = listOf(
    Example("GRUB settings", "GRUB_TIMEOUT=5\nGRUB_CMDLINE_LINUX_DEFAULT=\"quiet splash\"\nGRUB_DISABLE_OS_PROBER=false"),
    Example("Menu entry", "menuentry 'Arch Linux' --class arch {\n  insmod part_gpt\n  set root=(hd0,gpt2)\n  linux /boot/vmlinuz-linux root=UUID=3f2c1a9e-1111-2222-3333-444455556666 rw quiet\n  initrd /boot/initramfs-linux.img\n}"),
    Example("Apply GRUB", "sudo nano /etc/default/grub && sudo update-grub"),
    Example("Find disks", "lsblk -o NAME,SIZE,FSTYPE,UUID,MOUNTPOINTS"),
    Example("Install GRUB", "sudo grub-install --target=x86_64-efi --efi-directory=/boot/efi --bootloader-id=GRUB"),
    Example("Danger demo", "sudo dd if=debian.iso of=/dev/sdb bs=4M status=progress"),
)

/**
 * Miss Koreena: the Linux and GRUB tutor. Paste or pick a command and it is coloured the same way every time
 * (ALL CAPS names in amber, danger in red), each part is explained in plain words, and she can teach it
 * step by step. The colouring and the explanations work with no AI model installed.
 */
@Composable
fun KoreenaScreen(vm: KoreenaViewModel, ai: OfflineAiViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val aiState by ai.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showModels by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val reading = remember(state.input) { CommandReader.read(state.input) }
    val breakdown = remember(reading) { Glossary.breakdown(reading) }
    val keyKinds = if (reading.isEmpty) CommandColors.legendOrder
    else CommandColors.legendOrder.filter { k -> reading.tokens.any { it.kind == k } }

    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Command", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.text?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.koreena_avatar), "Miss Koreena",
                        Modifier.size(60.dp), contentScale = ContentScale.Fit,
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("MISS KOREENA", color = KoreenaTeal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                        Text("Linux & GRUB tutor", color = TextSecondary, fontSize = 11.sp)
                        Text(offlineHeadline(aiState), color = TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                    TextButton(onClick = { showModels = true }) {
                        Text(if (aiState.anyInstalled) "Models" else "Get models", color = ElectricCyan, fontSize = 12.sp)
                    }
                }
            }

            item(key = "input") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = vm::setInput,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paste a command, a GRUB line or a config", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White),
                        minLines = 2, maxLines = 7,
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EXAMPLES.forEach { ex ->
                            AssistChip(onClick = { vm.setInput(ex.text) }, label = { Text(ex.label, fontSize = 11.sp) })
                        }
                        if (state.input.isNotEmpty()) AssistChip(onClick = { vm.setInput("") }, label = { Text("Clear", fontSize = 11.sp) })
                    }
                }
            }

            if (!reading.isEmpty) {
                item(key = "reading") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HOW IT READS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { copy(state.input) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        CommandText(state.input, Modifier.fillMaxWidth(), reading = reading)
                    }
                }
                if (reading.warnings.isNotEmpty()) {
                    item(key = "warnings") {
                        Column(
                            Modifier.fillMaxWidth().background(Danger.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).border(1.dp, Danger.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            reading.warnings.forEach { w ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Warning, null, tint = Danger, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                                    Text(w, color = Color(0xFFFFD0D0), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }

            item(key = "key") {
                Column(Modifier.fillMaxWidth().background(CarbonSurface, RoundedCornerShape(8.dp)).border(1.dp, CarbonBorder, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (reading.isEmpty) "COLOUR KEY" else "COLOURS IN THIS LINE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    keyKinds.forEach { kind -> KeyRow(kind) }
                }
            }

            if (breakdown.isNotEmpty()) {
                item(key = "breakdown-title") { Text("WHAT EACH PART MEANS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp) }
                items(breakdown.size, key = { "b$it" }) { idx -> BreakdownRow(breakdown[idx]) }
                item(key = "teach") {
                    Button(
                        onClick = { vm.explainInput() },
                        enabled = !state.generating,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = KoreenaTeal, contentColor = Color(0xFF04201D)),
                    ) { Text("Ask Miss Koreena to teach me this", fontWeight = FontWeight.Bold) }
                }
            }

            if (state.messages.isNotEmpty()) {
                item(key = "chat-title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CONVERSATION", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = vm::clearChat, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.DeleteSweep, "Clear conversation", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                items(state.messages.size, key = { "m${state.messages[it].id}" }) { idx ->
                    Message(state.messages[idx], onUseInReader = vm::setInput, onCopy = ::copy)
                }
            }
        }

        // Free question
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Miss Koreena about Linux or GRUB…", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                maxLines = 3,
            )
            if (state.generating) {
                IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop", tint = Danger) }
            } else {
                IconButton(onClick = { vm.ask(question); question = "" }, enabled = question.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (question.isNotBlank()) KoreenaTeal else TextMuted)
                }
            }
        }
    }

    if (showModels) {
        OfflineModelsDialog(
            state = aiState,
            onDownload = ai::download, onPause = ai::pause, onVerify = ai::verify,
            onLoad = ai::load, onUnload = ai::unload, onDelete = ai::delete,
            onDismiss = { showModels = false },
        )
    }
}

@Composable
private fun KeyRow(kind: Kind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString { withStyle(CommandColors.style(kind)) { append(kind.label) } },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(88.dp),
        )
        Text(kind.meaning, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun BreakdownRow(item: BreakdownItem) {
    val danger = item.token.kind == Kind.DANGER
    Column(
        Modifier.fillMaxWidth()
            .background(if (danger) Danger.copy(alpha = 0.10f) else CarbonSurface, RoundedCornerShape(8.dp))
            .border(1.dp, if (danger) Danger.copy(alpha = 0.5f) else CarbonBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString { withStyle(CommandColors.style(item.token.kind)) { append(item.token.text) } },
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Text(item.token.kind.label, color = CommandColors.color(item.token.kind).copy(alpha = 0.8f), fontSize = 10.sp)
        }
        Text(item.explanation, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun Message(msg: AiMessage, onUseInReader: (String) -> Unit, onCopy: (String) -> Unit) {
    if (msg.fromUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                msg.text, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(start = 40.dp)
                    .background(ElectricPurpleDark.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(10.dp),
            )
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Image(painterResource(R.drawable.koreena_avatar), null, Modifier.size(28.dp))
        Column(
            Modifier.weight(1f).padding(start = 8.dp)
                .background(if (msg.isError) Danger.copy(alpha = 0.12f) else CarbonSurfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (msg.text.isEmpty() && msg.streaming) Text("Miss Koreena is thinking…", color = TextMuted, fontSize = 12.sp)
            // Prose is plain; any fenced block is coloured by the same reader, even while it is still arriving.
            AiPrompts.segments(msg.text).forEach { seg ->
                if (!seg.isCode) {
                    SelectionContainer { Text(seg.text, color = if (msg.isError) Color(0xFFFFD0D0) else TextPrimary, fontSize = 13.sp, lineHeight = 19.sp) }
                } else {
                    CommandText(seg.text, Modifier.fillMaxWidth(), fontSize = 12.sp)
                    if (!msg.streaming) {
                        Row {
                            TextButton(onClick = { onUseInReader(seg.text) }) { Text("Read this above", color = ElectricCyan, fontSize = 11.sp) }
                            TextButton(onClick = { onCopy(seg.text) }) { Text("Copy", color = TextSecondary, fontSize = 11.sp) }
                        }
                    }
                }
            }
            if (!msg.streaming && msg.modelName != null) Text("offline · ${msg.modelName}", color = TextMuted, fontSize = 9.sp)
            Spacer(Modifier.height(0.dp))
        }
    }
}
