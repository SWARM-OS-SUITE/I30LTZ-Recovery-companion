package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.service.OpenRouterManager
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CarbonSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.ElectricRed
import com.example.ui.theme.LightningYellow
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NvidiaGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

/** Dr. Boltz's realistic voice: an older male ElevenLabs voice through OpenRouter, off until switched on. */
@Composable
fun VoiceSettingsCard(
    hasKey: Boolean, enabled: Boolean, name: String, model: String, status: String, check: String,
    onSave: (Boolean, String, String) -> String?, onTest: () -> Unit, onCheck: (String, String) -> Unit,
) {
    var voice by remember(name) { mutableStateOf(name) }
    var chosenModel by remember(model) { mutableStateOf(model) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(name, model, enabled) { error = "" }
    fun save(on: Boolean) { error = onSave(on, voice, chosenModel).orEmpty() }

    Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) NvidiaGreen.copy(alpha = 0.5f) else CarbonBorder),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dr. Boltz's realistic voice", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Text("ElevenLabs through OpenRouter", color = TextMuted, fontSize = 11.sp)
                }
                Switch(checked = enabled, enabled = hasKey, onCheckedChange = { save(it) }, modifier = Modifier.testTag("voice_switch"))
            }
            if (!hasKey) Text("Save your OpenRouter API key above first.", color = LightningYellow, fontSize = 12.sp)

            Text("Voice", color = TextMuted, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpenRouterManager.SUGGESTED_VOICES.forEach { (v, why) ->
                    Choice("$v · $why", voice == v) { voice = v }
                }
            }
            OutlinedTextField(voice, { voice = it.take(40) }, singleLine = true, label = { Text("Voice name (any name OpenRouter lists)") },
                colors = readableFieldColors(), modifier = Modifier.fillMaxWidth().testTag("voice_name_field"))
            Text("Quality", color = TextMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpenRouterManager.VOICE_MODELS.forEach { (m, label) -> Choice(label, chosenModel == m) { chosenModel = m } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(enabled) }, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen), modifier = Modifier.testTag("voice_save")) {
                    Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { save(enabled); onTest() }, enabled = hasKey && enabled, modifier = Modifier.testTag("voice_test")) { Text("TEST VOICE", fontSize = 12.sp, maxLines = 1, color = TextPrimary) }
                OutlinedButton(onClick = { onCheck(chosenModel, voice) }, modifier = Modifier.testTag("voice_check")) { Text("CHECK", fontSize = 12.sp, maxLines = 1, color = TextPrimary) }
            }
            if (error.isNotBlank()) Text(error, color = ElectricRed, fontSize = 12.sp)
            if (status.isNotBlank()) Text(status, color = if (status.startsWith("Realistic voice unavailable") || status.contains("stopped partway")) ElectricRed else NeonGreen,
                fontSize = 12.sp, modifier = Modifier.testTag("voice_status"))
            if (check.isNotBlank()) Text(check, color = ElectricCyan, fontSize = 12.sp, modifier = Modifier.testTag("voice_check_result"))

            Text("When on, what Dr. Boltz says is sent to OpenRouter and ElevenLabs and billed to your OpenRouter credits " +
                "(about $0.02 per 1,000 characters; Best $0.04; replies are capped at 1,500 characters). Off, or if anything fails, the phone's own voice speaks. " +
                "ElevenLabs retires its default voices after 2026-12-31, so use CHECK if a voice stops working.", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) ElectricPurple else CarbonSurfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.clickable(onClick = onClick)) {
        Text(text, color = if (selected) Color.White else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
