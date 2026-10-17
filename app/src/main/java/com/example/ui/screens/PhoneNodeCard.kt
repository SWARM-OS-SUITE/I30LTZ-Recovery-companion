package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.phonenode.PhoneNode
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricRed
import com.example.ui.theme.LightningYellow
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NvidiaGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/** "This phone as a node": lets the person's other devices see this phone (RAM, storage, battery) after pairing. */
@Composable
fun PhoneNodeCard() {
    val context = LocalContext.current
    val state by PhoneNode.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmRevoke by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { PhoneNode.load(context) }
    LaunchedEffect(state.code) { while (state.code != null) { now = System.currentTimeMillis(); delay(1000) } }

    val tailnetIp = state.tailnet.firstOrNull()
    val codeLeftMs = state.codeExpiresAt - now
    val code = state.code?.takeIf { codeLeftMs > 0 }

    Card(colors = CardDefaults.cardColors(containerColor = CarbonSurface), border = BorderStroke(1.dp, CarbonBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This phone as a node", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Let my other devices see this phone", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = {
                    error = ""
                    try { PhoneNode.setEnabled(context, it) } catch (e: Exception) { error = e.message.orEmpty() }
                }, modifier = Modifier.testTag("phone_node_switch"))
            }

            when {
                !state.enabled -> Text("Off. Nothing on this phone is reachable from outside.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.testTag("phone_node_status"))
                !state.running -> Text("Starting…", color = ElectricCyan, fontSize = 12.sp, modifier = Modifier.testTag("phone_node_status"))
                else -> Text("On. Listening on ${state.listening.joinToString(", ").ifEmpty { "…" }}", color = NeonGreen, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("phone_node_status"))
            }
            if (state.message.isNotBlank()) Text(state.message, color = LightningYellow, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, color = ElectricRed, fontSize = 12.sp)

            if (state.enabled && state.running) {
                if (code == null) {
                    Button(onClick = { PhoneNode.showPairingCode(context) }, colors = ButtonDefaults.buttonColors(containerColor = NvidiaGreen),
                        modifier = Modifier.testTag("phone_node_show_code")) { Text("SHOW PAIRING CODE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                } else {
                    Text(code, color = ElectricCyan, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("phone_node_code"))
                    Text("Works once, for ${((codeLeftMs + 59_999) / 60_000)} more minute(s). On the other device: Nodes → ADD NODE → " +
                        "Phone running I30LTZ Companion → host ${tailnetIp ?: "(connect Tailscale first)"}, port ${state.port} → enter this code.",
                        color = TextPrimary, fontSize = 12.sp)
                }
            }

            if (state.paired.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("Paired devices (${state.paired.size})", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                state.paired.forEach { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(d.label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { confirmRevoke = d.label }, modifier = Modifier.testTag("phone_node_revoke")) { Text("REVOKE", fontSize = 11.sp, color = ElectricRed) }
                    }
                }
            }

            if (state.events.isNotEmpty()) {
                Text("Recent activity", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                state.events.takeLast(5).forEach { Text(it, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }

            Text("Shares RAM, storage and battery only. Android does not let an app share CPU load, other apps' processes, a terminal or a malware scan of the whole phone. " +
                "Only devices you pair with a code shown here can read anything; the code is never sent over the network.", color = TextMuted, fontSize = 11.sp)
        }
    }

    confirmRevoke?.let { label ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null }, containerColor = CarbonSurface,
            title = { Text("Revoke \"$label\"?") },
            text = { Text("That device stops seeing this phone at once. You can pair it again with a new code.") },
            confirmButton = { Button(onClick = { PhoneNode.revoke(context, label); confirmRevoke = null }, colors = ButtonDefaults.buttonColors(containerColor = ElectricRed)) { Text("REVOKE") } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("CANCEL") } },
        )
    }
}
