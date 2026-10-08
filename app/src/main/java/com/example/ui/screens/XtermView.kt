package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Handle to the xterm.js terminal page (assets/xterm/terminal.html). The page is bundled and never
 * loads anything from the network. Callbacks arrive on the main thread.
 */
class XtermController {
    internal var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    var onReady: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onInput: (String) -> Unit = {}
    var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onCopy: (String) -> Unit = {}
    var onCtrlConsumed: () -> Unit = {}

    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private var scheduled = false

    internal fun attach(view: WebView) {
        webView = view
        schedule()
    }

    /** Terminal output. Bytes are batched (about 60 updates a second) and sent as base64 so no byte is altered. */
    fun write(bytes: ByteArray) {
        synchronized(lock) { pending.write(bytes) }
        schedule()
    }

    private fun schedule() {
        val view = webView ?: return
        synchronized(lock) {
            if (scheduled || pending.size() == 0) return
            scheduled = true
        }
        view.postDelayed({ flush() }, 16)
    }

    private fun flush() {
        val data = synchronized(lock) { scheduled = false; pending.toByteArray().also { pending.reset() } }
        val view = webView ?: return
        var offset = 0
        while (offset < data.size) {
            val n = minOf(CHUNK, data.size - offset)
            view.evaluateJavascript("termWrite('" + Base64.encodeToString(data, offset, n, Base64.NO_WRAP) + "')", null)
            offset += n
        }
    }

    private fun js(script: String) {
        main.post { webView?.evaluateJavascript(script, null) }
    }

    fun reset() {
        synchronized(lock) { pending.reset() }
        js("termReset()")
    }

    fun paste(text: String) = js("termPaste(${JSONObject.quote(text)})")
    fun setCtrl(on: Boolean) = js("termSetCtrl($on)")
    fun setSelectMode(on: Boolean) = js("termSelectMode($on)")
    fun setFontSize(px: Int) = js("termSetFont($px)")
    fun requestCopy() = js("termCopy()")

    /** Feeds text through the same path as the on-screen keyboard (so sticky Ctrl applies). */
    fun type(text: String) = js("termType(${JSONObject.quote(text)})")

    /** Reads the visible screen text (used by tests and the on-device smoke check). */
    fun dump(callback: (String) -> Unit) {
        main.post { webView?.evaluateJavascript("termDump()") { callback(JSONObject("{\"v\":$it}").optString("v")) } }
    }

    fun showKeyboard() {
        main.post {
            val view = webView ?: return@post
            view.requestFocus()
            view.evaluateJavascript("termFocus()", null)
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    internal inner class Bridge {
        @JavascriptInterface fun onReady(cols: Int, rows: Int) { main.post { this@XtermController.onReady(cols, rows) } }
        @JavascriptInterface fun onInput(data: String) { main.post { this@XtermController.onInput(data) } }
        @JavascriptInterface fun onResize(cols: Int, rows: Int) { main.post { this@XtermController.onResize(cols, rows) } }
        @JavascriptInterface fun onCopy(text: String) { main.post { this@XtermController.onCopy(text) } }
        @JavascriptInterface fun onCtrlConsumed() { main.post { this@XtermController.onCtrlConsumed() } }
        @JavascriptInterface fun onLog(message: String) { if (com.example.BuildConfig.DEBUG) android.util.Log.d("XtermView", message) }
    }

    private companion object {
        const val CHUNK = 48 * 1024
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermView(controller: XtermController, modifier: Modifier = Modifier) {
    DisposableEffect(controller) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(0xFF0C0D12.toInt())
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowContentAccess = false
                    setSupportZoom(false)
                }
                if (com.example.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    // Only our bundled terminal files may load; anything else gets an empty answer.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url.toString()
                        return if (url.startsWith("file:///android_asset/xterm/")) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                addJavascriptInterface(controller.Bridge(), "AndroidTerm")
                loadUrl("file:///android_asset/xterm/terminal.html")
                controller.attach(this)
            }
        },
    )
}
