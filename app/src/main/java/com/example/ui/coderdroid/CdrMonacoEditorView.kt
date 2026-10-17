package com.example.ui.coderdroid

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.CarbonBackground
import com.example.ui.theme.TextMuted
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The code editor: Monaco (the engine inside VS Code) running offline from the app's assets in a WebView.
 *
 * One WebView lives as long as this composable does, even with no file open, because starting Monaco takes
 * a second or two. Each open tab keeps its own editor model (undo history, scroll, cursor) inside the page,
 * and a model is freed as soon as its tab disappears from [openTabIds].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CdrMonacoEditorView(
    tab: CdrEditorTab?,
    openTabIds: Set<String>,
    controller: CdrEditorController,
    drafts: Map<String, String>,
    fontSize: Int,
    wordWrap: Boolean,
    onContentChanged: (tabId: String, content: String) -> Unit,
    onCursorMoved: (line: Int, column: Int) -> Unit,
    onSaveRequested: (tabId: String, content: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isReady by remember { mutableStateOf(false) }

    // The bridge outlives recompositions, so it must always call the *latest* lambdas.
    val latestContent by rememberUpdatedState(onContentChanged)
    val latestCursor by rememberUpdatedState(onCursorMoved)
    val latestSave by rememberUpdatedState(onSaveRequested)

    // Show (or create) the active tab's model once the page is ready.
    LaunchedEffect(tab?.id, isReady) {
        val active = tab ?: return@LaunchedEffect
        if (!isReady) return@LaunchedEffect
        val content = drafts[active.id] ?: active.initialContent
        val data = JSONObject()
            .put("tabId", active.id)
            .put("path", active.filePath)
            .put("language", active.languageId)
            .put("content", content)
            .put("readOnly", active.isReadOnly)
        webViewRef?.evaluateJavascript("window.coderdroid.openFile($data);", null)
    }

    // Free the editor model of every tab that has been closed.
    var knownIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(openTabIds, isReady) {
        if (!isReady) return@LaunchedEffect
        (knownIds - openTabIds).forEach { controller.closeTab(it) }
        knownIds = openTabIds
    }

    LaunchedEffect(fontSize, wordWrap, isReady) {
        if (isReady) controller.setOptions(fontSize, wordWrap)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { controller.detach(it); it.destroy() }
            webViewRef = null
        }
    }

    Box(modifier = modifier.background(CarbonBackground)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        // Monaco loads its own scripts and language files from the APK; nothing else may load.
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        allowContentAccess = false
                        domStorageEnabled = true
                        setSupportZoom(false)
                    }
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url.toString()
                            return if (url.startsWith("file:///android_asset/monaco/")) null
                            else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        }
                    }
                    addJavascriptInterface(
                        CdrEditorBridge(
                            onContentChanged = { id, text -> latestContent(id, text) },
                            onCursorMoved = { line, col -> latestCursor(line, col) },
                            onSaveRequested = { id, text -> latestSave(id, text) },
                            onReady = { isReady = true },
                        ),
                        "Android",
                    )
                    loadUrl("file:///android_asset/monaco/editor.html")
                    controller.attach(this)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (tab == null) {
            // Covers the (blank) editor when nothing is open.
            Box(Modifier.fillMaxSize().background(CarbonBackground), contentAlignment = Alignment.Center) {
                Text(
                    text = "Open a file from the explorer",
                    fontSize = 13.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
