package com.example.ui.coderdroid

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ai.OfflineLlm
import com.example.data.ai.OfflineModel
import com.example.data.ai.OfflineModelCatalog
import com.example.data.ai.OfflineModelStore
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MarketplaceTab { MODELS, THEMES, TEMPLATES, SNIPPETS }

@Composable
fun CdrMarketplaceDialog(
    controller: CdrEditorController,
    workspaceRoot: File?,
    onDismiss: () -> Unit,
    onRefreshWorkspace: () -> Unit,
    onInsertSnippet: (String) -> Unit,
    onManageModels: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(MarketplaceTab.MODELS) }

    var actionError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, CarbonBorder, RoundedCornerShape(12.dp)),
            color = CarbonSurface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(22.dp))
                        Column {
                            Text("CODER DROID MARKETPLACE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("Themes, offline models, project starters and snippets", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == MarketplaceTab.MODELS,
                        onClick = { selectedTab = MarketplaceTab.MODELS },
                        label = { Text("AI Models", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Psychology, null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = selectedTab == MarketplaceTab.THEMES,
                        onClick = { selectedTab = MarketplaceTab.THEMES },
                        label = { Text("Themes", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Palette, null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = selectedTab == MarketplaceTab.TEMPLATES,
                        onClick = { selectedTab = MarketplaceTab.TEMPLATES },
                        label = { Text("Starters", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Widgets, null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = selectedTab == MarketplaceTab.SNIPPETS,
                        onClick = { selectedTab = MarketplaceTab.SNIPPETS },
                        label = { Text("Snippets", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Code, null, modifier = Modifier.size(14.dp)) }
                    )
                }

                actionError?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(err, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = CarbonBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                // Tab Content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        MarketplaceTab.MODELS -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "Downloading, checking and starting the offline models happens in one place, so progress, storage and " +
                                        "memory checks are always the same.",
                                    color = TextSecondary, fontSize = 12.sp,
                                )
                                Button(onClick = { onDismiss(); onManageModels() }) { Text("Open offline model manager") }
                            }
                        }

                        MarketplaceTab.THEMES -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    ThemeOptionCard(
                                        title = "Dark (default)",
                                        themeId = "vs-dark",
                                        description = "Monaco's classic dark theme with high-contrast syntax highlighting.",
                                        onApply = {
                                            controller.setTheme("vs-dark")
                                            Toast.makeText(context, "Dark theme applied", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                item {
                                    ThemeOptionCard(
                                        title = "Light",
                                        themeId = "vs",
                                        description = "Clean light theme suitable for bright ambient lighting.",
                                        onApply = {
                                            controller.setTheme("vs")
                                            Toast.makeText(context, "Light theme applied", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                item {
                                    ThemeOptionCard(
                                        title = "High Contrast Black",
                                        themeId = "hc-black",
                                        description = "Maximum accessibility and sharp outline contrast on OLED displays.",
                                        onApply = {
                                            controller.setTheme("hc-black")
                                            Toast.makeText(context, "High Contrast theme applied", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }

                        MarketplaceTab.TEMPLATES -> {
                            val root = workspaceRoot
                            fun scaffold(template: CdrTemplates.Template) {
                                if (root == null) return
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { CdrTemplates.scaffold(root, template) }
                                    result.fold(
                                        onSuccess = { outcome ->
                                            actionError = null
                                            onRefreshWorkspace()
                                            Toast.makeText(context, outcome.summary(), Toast.LENGTH_LONG).show()
                                        },
                                        onFailure = { ex -> actionError = "Could not create the starter: ${ex.message}" },
                                    )
                                }
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (root == null) {
                                    item { Text("Open a project folder first to add starter files.", color = TextMuted, fontSize = 12.sp) }
                                } else {
                                    item {
                                        Text("Starters only add files that are missing; existing files are never replaced.", color = TextMuted, fontSize = 11.sp)
                                    }
                                    item {
                                        TemplateScaffoldCard(
                                            title = "Android Kotlin starter",
                                            description = "Minimal build.gradle.kts, AndroidManifest.xml and MainActivity.kt. For a full buildable project use Build APK / ADB.",
                                            onScaffold = { scaffold(CdrTemplates.Template.ANDROID) },
                                        )
                                    }
                                    item {
                                        TemplateScaffoldCard(
                                            title = "Rust Recovery Utility",
                                            description = "Cargo.toml and src/main.rs for kiosk or command tools.",
                                            onScaffold = { scaffold(CdrTemplates.Template.RUST) },
                                        )
                                    }
                                    item {
                                        TemplateScaffoldCard(
                                            title = "Python SWARM Agent",
                                            description = "agent.py and requirements.txt for talking to Swarm OS.",
                                            onScaffold = { scaffold(CdrTemplates.Template.PYTHON) },
                                        )
                                    }
                                }
                            }
                        }

                        MarketplaceTab.SNIPPETS -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    SnippetCard(
                                        title = "Jetpack Compose Scaffold",
                                        code = "@Composable\nfun MyScreen() {\n    Surface(modifier = Modifier.fillMaxSize()) {\n        Text(\"Hello Boltz\")\n    }\n}",
                                        onInsert = { onInsertSnippet(it); Toast.makeText(context, "Snippet inserted at cursor", Toast.LENGTH_SHORT).show() }
                                    )
                                }
                                item {
                                    SnippetCard(
                                        title = "OkHttp GET Request",
                                        code = "val client = OkHttpClient()\nval request = Request.Builder().url(url).build()\nval response = client.newCall(request).execute()",
                                        onInsert = { onInsertSnippet(it); Toast.makeText(context, "Snippet inserted at cursor", Toast.LENGTH_SHORT).show() }
                                    )
                                }
                                item {
                                    SnippetCard(
                                        title = "Room Coroutines DAO Query",
                                        code = "@Query(\"SELECT * FROM items ORDER BY id DESC\")\nfun getAll(): Flow<List<ItemEntity>>",
                                        onInsert = { onInsertSnippet(it); Toast.makeText(context, "Snippet inserted at cursor", Toast.LENGTH_SHORT).show() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    themeId: String,
    description: String,
    onApply: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CarbonSurfaceVariant)
            .border(1.dp, CarbonBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 11.sp)
        }
        Button(onClick = onApply) { Text("Apply") }
    }
}

@Composable
private fun TemplateScaffoldCard(
    title: String,
    description: String,
    onScaffold: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CarbonSurfaceVariant)
            .border(1.dp, CarbonBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 11.sp)
        }
        FilledTonalButton(onClick = onScaffold) { Text("Generate") }
    }
}

@Composable
private fun SnippetCard(
    title: String,
    code: String,
    onInsert: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CarbonSurfaceVariant)
            .border(1.dp, CarbonBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onInsert(code) }) { Text("Insert at Cursor", color = ElectricCyan) }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            code,
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(CarbonBackground, RoundedCornerShape(4.dp))
                .padding(8.dp)
        )
    }
}
