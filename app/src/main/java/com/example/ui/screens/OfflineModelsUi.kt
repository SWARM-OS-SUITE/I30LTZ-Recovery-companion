package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.ByteFormat
import com.example.data.ai.OfflineLlm
import com.example.data.ai.OfflineModel
import com.example.data.ai.OfflineModelStore
import com.example.ui.ModelUi
import com.example.ui.OfflineAiState
import com.example.ui.theme.*

private val Danger = Color(0xFFFF6B6B)

/** Short line for the AI Lab and the IDE: is Dr. Boltz's offline brain ready? */
fun offlineHeadline(state: OfflineAiState): String = when (val s = state.llm) {
    is OfflineLlm.State.Loading -> "Starting ${s.model.displayName}…"
    is OfflineLlm.State.Ready -> "${s.model.displayName} is ready"
    is OfflineLlm.State.Failed -> s.message
    OfflineLlm.State.Idle -> if (state.anyInstalled) "Installed. Loads when you ask a question." else "No offline model installed yet"
}

/** The pop-up where the two offline models are downloaded, checked, loaded and removed. */
@Composable
fun OfflineModelsDialog(
    state: OfflineAiState,
    onDownload: (OfflineModel, Boolean) -> Unit,
    onPause: (OfflineModel) -> Unit,
    onVerify: (OfflineModel) -> Unit,
    onLoad: (OfflineModel) -> Unit,
    onUnload: () -> Unit,
    onDelete: (OfflineModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        title = { Text("Offline AI models", color = Color.White, fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "These run on this phone with no internet. This phone reports ${ByteFormat.human(state.totalRamBytes)} of memory.",
                    color = TextMuted, fontSize = 11.sp,
                )
                state.models.forEach { ui ->
                    ModelCard(ui, loaded = (state.llm as? OfflineLlm.State.Ready)?.model?.id == ui.model.id,
                        loading = (state.llm as? OfflineLlm.State.Loading)?.model?.id == ui.model.id,
                        metered = state.onMeteredNetwork,
                        onDownload = onDownload, onPause = onPause, onVerify = onVerify, onLoad = onLoad, onUnload = onUnload, onDelete = onDelete)
                }
                Text(
                    "Downloaded from Hugging Face (litert-community). Each file is checked against its published SHA-256 before it can be used.",
                    color = TextMuted, fontSize = 10.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = ElectricCyan) } },
    )
}

@Composable
private fun ModelCard(
    ui: ModelUi,
    loaded: Boolean,
    loading: Boolean,
    metered: Boolean,
    onDownload: (OfflineModel, Boolean) -> Unit,
    onPause: (OfflineModel) -> Unit,
    onVerify: (OfflineModel) -> Unit,
    onLoad: (OfflineModel) -> Unit,
    onUnload: () -> Unit,
    onDelete: (OfflineModel) -> Unit,
) {
    val m = ui.model
    Column(
        Modifier.fillMaxWidth().background(CarbonSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, if (loaded) NvidiaGreen else CarbonBorder, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(m.displayName, color = Color.White, fontSize = 13.sp)
        Text(m.tagline, color = TextSecondary, fontSize = 11.sp)
        Text("${ByteFormat.human(m.sizeBytes)} · ${m.license} · ${m.contextTokens / 1024}k memory for chat", color = TextMuted, fontSize = 10.sp)

        val (fitText, fitColor) = when (ui.fit) {
            OfflineModel.Fit.COMFORTABLE -> "Runs comfortably on this phone" to NvidiaGreen
            OfflineModel.Fit.TIGHT -> "Tight on this phone: close other apps first" to LightningYellow
            OfflineModel.Fit.TOO_BIG -> "Needs a phone with more memory" to Danger
        }
        Text(fitText, color = fitColor, fontSize = 11.sp)

        val progress = ui.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), color = ElectricPurple)
            Text(
                if (progress.phase == OfflineModelStore.Phase.VERIFYING) "Checking the file… ${(progress.fraction * 100).toInt()}%"
                else "${(progress.fraction * 100).toInt()}% · ${ByteFormat.human(progress.doneBytes)} of ${ByteFormat.human(progress.totalBytes)}",
                color = TextSecondary, fontSize = 11.sp,
            )
            if (progress.phase == OfflineModelStore.Phase.DOWNLOADING) {
                OutlinedButton(onClick = { onPause(m) }) { Text("Pause") }
            }
        } else {
            when (val status = ui.status) {
                OfflineModelStore.Status.Missing -> DownloadButtons(m, ui.fit, metered, "Download ${ByteFormat.human(m.sizeBytes)}", onDownload)
                is OfflineModelStore.Status.Partial -> {
                    Text("Paused at ${ByteFormat.human(status.bytes)}", color = TextSecondary, fontSize = 11.sp)
                    DownloadButtons(m, ui.fit, metered, "Resume", onDownload)
                    TextButton(onClick = { onDelete(m) }) { Text("Discard partial download", color = Danger) }
                }
                OfflineModelStore.Status.Unverified -> {
                    Text("File found but not checked yet.", color = LightningYellow, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onVerify(m) }) { Text("Check file") }
                        TextButton(onClick = { onDelete(m) }) { Text("Delete", color = Danger) }
                    }
                }
                OfflineModelStore.Status.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        loading -> Text("Starting…", color = LightningYellow, fontSize = 12.sp)
                        loaded -> OutlinedButton(onClick = onUnload) { Text("Free memory") }
                        else -> Button(onClick = { onLoad(m) }, enabled = ui.fit != OfflineModel.Fit.TOO_BIG) { Text("Start") }
                    }
                    if (loaded) Text("In memory", color = NvidiaGreen, fontSize = 11.sp)
                    TextButton(onClick = { onDelete(m) }) { Text("Delete", color = Danger) }
                }
            }
        }
        ui.error?.let { Text(it, color = Danger, fontSize = 11.sp) }
    }
}

@Composable
private fun DownloadButtons(
    m: OfflineModel,
    fit: OfflineModel.Fit,
    metered: Boolean,
    label: String,
    onDownload: (OfflineModel, Boolean) -> Unit,
) {
    val canRun = fit != OfflineModel.Fit.TOO_BIG
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onDownload(m, false) }, enabled = canRun) { Text(label) }
        if (metered) OutlinedButton(onClick = { onDownload(m, true) }, enabled = canRun) { Text("Use mobile data") }
    }
    if (!canRun) Text("This model would not fit in memory here, so downloading is switched off.", color = TextMuted, fontSize = 10.sp,
        modifier = Modifier.padding(top = 2.dp))
}
