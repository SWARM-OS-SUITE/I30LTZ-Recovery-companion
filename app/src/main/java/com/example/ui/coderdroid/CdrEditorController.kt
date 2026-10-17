package com.example.ui.coderdroid

import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** What the editor knows right now: used to give the AI panel the selection or the whole file. */
data class EditorContext(
    val hasFile: Boolean,
    val tabId: String? = null,
    val language: String = "plaintext",
    val hasSelection: Boolean = false,
    val selection: String = "",
    val line: Int = 1,
    val text: String = "",
) {
    companion object {
        /**
         * Parses what `evaluateJavascript` hands back for `getContext()`. The JS returns a JSON *string*, and
         * evaluateJavascript wraps a string result in quotes and escapes it, so it is unwrapped twice.
         */
        fun parse(raw: String?): EditorContext? {
            if (raw == null || raw == "null") return null
            return try {
                val inner = JSONTokener(raw).nextValue() as? String ?: return null
                val o = JSONObject(inner)
                if (o.has("error")) return null
                if (!o.optBoolean("hasFile")) return EditorContext(hasFile = false)
                EditorContext(
                    hasFile = true,
                    tabId = o.optString("tabId").ifEmpty { null },
                    language = o.optString("language", "plaintext"),
                    hasSelection = o.optBoolean("hasSelection"),
                    selection = o.optString("selection"),
                    line = o.optInt("line", 1),
                    text = o.optString("text"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * The Kotlin side's handle on the Monaco editor running in a WebView. Every method is a no-op until the
 * editor page has loaded, so callers never need to check. Text passed into the page is always JSON-quoted
 * (never string-pasted), so code containing quotes, backticks or `</script>` cannot break out of the call.
 */
class CdrEditorController {
    @Volatile private var webView: WebView? = null

    internal fun attach(view: WebView) { webView = view }
    internal fun detach(view: WebView) { if (webView === view) webView = null }

    val isAttached: Boolean get() = webView != null

    private fun js(code: String, callback: ((String?) -> Unit)? = null) {
        val view = webView ?: run { callback?.invoke(null); return }
        view.post { view.evaluateJavascript(code, callback?.let { cb -> { value: String? -> cb(value) } }) }
    }

    /** Find, replace, goToLine, palette, format, undo, redo, comment, tab, outdent and the arrow keys. */
    fun run(action: String) = js("window.coderdroid.run(${JSONObject.quote(action)});")

    fun insertAtCursor(text: String) = js("window.coderdroid.insertSnippet(${JSONObject.quote(text)});")

    /** Replaces the current selection; reports false (and changes nothing) if there is no selection. */
    fun replaceSelection(text: String, done: (Boolean) -> Unit = {}) =
        js("window.coderdroid.replaceSelection(${JSONObject.quote(text)});") { done(it == "true") }

    fun context(done: (EditorContext?) -> Unit) = js("window.coderdroid.getContext();") { done(EditorContext.parse(it)) }

    fun revealLine(line: Int) = js("window.coderdroid.revealLine($line);")

    fun closeTab(tabId: String) = js("window.coderdroid.closeTab(${JSONObject.quote(tabId)});")

    fun reloadClean(tabId: String, content: String) {
        val payload = JSONObject().put("tabId", tabId).put("content", content)
        js("window.coderdroid.reloadFile($payload);")
    }

    fun setOptions(fontSize: Int, wordWrap: Boolean) {
        val payload = JSONObject().put("fontSize", fontSize).put("wordWrap", if (wordWrap) "on" else "off")
        js("window.coderdroid.setOptions($payload);")
    }

    fun setTheme(theme: String) {
        js("window.coderdroid.setTheme(${JSONObject.quote(theme)});")
    }
}
