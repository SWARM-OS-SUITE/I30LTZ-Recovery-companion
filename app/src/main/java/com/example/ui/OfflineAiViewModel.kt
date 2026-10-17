package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiAction
import com.example.data.ai.AiPrompts
import com.example.data.ai.ChatTurn
import com.example.data.ai.OfflineLlm
import com.example.data.ai.OfflineModel
import com.example.data.ai.OfflineModelCatalog
import com.example.data.ai.OfflineModelStore
import com.example.ui.coderdroid.EditorContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One catalog model as the screen shows it. */
data class ModelUi(
    val model: OfflineModel,
    val status: OfflineModelStore.Status,
    val fit: OfflineModel.Fit,
    val progress: OfflineModelStore.Progress? = null,
    val error: String? = null,
) {
    val working: Boolean get() = progress != null
}

data class AiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val modelName: String? = null,
)

data class OfflineAiState(
    val models: List<ModelUi> = emptyList(),
    val llm: OfflineLlm.State = OfflineLlm.State.Idle,
    val totalRamBytes: Long = 0,
    val onMeteredNetwork: Boolean = false,
    val messages: List<AiMessage> = emptyList(),
    val generating: Boolean = false,
) {
    val anyInstalled: Boolean get() = models.any { it.status == OfflineModelStore.Status.Ready }
}

/**
 * Drives everything about the on-device AI: installing and removing the two models, loading one into
 * memory, and the chat in the IDE. Lives as long as the activity, so a download keeps going while the user
 * moves between tabs (and resumes from the saved part if the app is killed).
 */
class OfflineAiViewModel(application: Application) : AndroidViewModel(application) {

    private val llm = OfflineLlm.get(application)
    private val store = llm.store

    private val errors = mutableMapOf<String, String?>()
    private val progress = mutableMapOf<String, OfflineModelStore.Progress?>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private var chatJob: Job? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(OfflineAiState(llm = llm.state.value, totalRamBytes = llm.totalRamBytes()))
    val state: StateFlow<OfflineAiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { llm.state.collect { s -> _state.update { it.copy(llm = s) } } }
        refresh()
    }

    /** Re-reads what is installed (call when a screen opens, or after copying a file in by hand). */
    fun refresh() {
        viewModelScope.launch {
            val ram = llm.totalRamBytes()
            val rows = withContext(Dispatchers.IO) {
                OfflineModelCatalog.all.map { m ->
                    ModelUi(m, store.status(m), m.fit(ram), progress[m.id], errors[m.id])
                }
            }
            _state.update { it.copy(models = rows, totalRamBytes = ram, onMeteredNetwork = isMetered()) }
        }
    }

    private fun isMetered(): Boolean =
        (getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    // ── Model files ───────────────────────────────────────────────────────────

    fun download(model: OfflineModel, allowMetered: Boolean) {
        if (downloadJobs[model.id]?.isActive == true) return
        if (!allowMetered && isMetered()) {
            errors[model.id] = "You are on mobile data. Connect to Wi-Fi, or choose \"Use mobile data\"."
            refresh(); return
        }
        errors[model.id] = null
        progress[model.id] = OfflineModelStore.Progress(OfflineModelStore.Phase.DOWNLOADING, 0, model.sizeBytes)
        downloadJobs[model.id] = viewModelScope.launch {
            val result = try {
                store.download(model) { p -> progress[model.id] = p; publishProgress() }
            } catch (e: CancellationException) {
                progress[model.id] = null
                errors[model.id] = "Paused. Tap Download to continue where it stopped."
                refresh()
                throw e
            }
            progress[model.id] = null
            errors[model.id] = result.exceptionOrNull()?.message
            refresh()
        }
    }

    private fun publishProgress() {
        _state.update { s -> s.copy(models = s.models.map { it.copy(progress = progress[it.model.id]) }) }
    }

    /** Pauses a download. The bytes already fetched are kept so it can resume. */
    fun pause(model: OfflineModel) {
        downloadJobs[model.id]?.cancel()
    }

    fun delete(model: OfflineModel) {
        downloadJobs[model.id]?.cancel()
        viewModelScope.launch {
            if (llm.loadedModel?.id == model.id) llm.unload()
            withContext(Dispatchers.IO) { store.delete(model) }
            errors[model.id] = null
            progress[model.id] = null
            refresh()
        }
    }

    /** For a model file copied onto the phone by hand: check it really is the expected file. */
    fun verify(model: OfflineModel) {
        if (downloadJobs[model.id]?.isActive == true) return
        errors[model.id] = null
        downloadJobs[model.id] = viewModelScope.launch {
            val result = store.verify(model) { p -> progress[model.id] = p; publishProgress() }
            progress[model.id] = null
            errors[model.id] = result.exceptionOrNull()?.message
            refresh()
        }
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    fun load(model: OfflineModel) {
        viewModelScope.launch {
            llm.load(model).onFailure { errors[model.id] = it.message; refresh() }
        }
    }

    fun unload() = llm.unload()

    // ── Chat ──────────────────────────────────────────────────────────────────

    /**
     * Sends [action] on the code in [context] (the editor's selection or file) to the loaded model, loading
     * the best installed one first if none is in memory. The reply streams into the last message.
     */
    fun send(action: AiAction, userText: String, context: EditorContext?) {
        if (chatJob?.isActive == true) return
        val question = userText.trim()
        if (action == AiAction.ASK && question.isEmpty()) return

        val shown = if (action == AiAction.ASK) question
        else action.label + (if (context?.hasSelection == true) " (selection)" else " (this file)") + if (question.isEmpty()) "" else ": $question"
        val userMsg = AiMessage(nextId++, fromUser = true, text = shown)
        val replyId = nextId++
        val history = _state.value.messages.filter { !it.streaming && !it.isError }
            .map { ChatTurn(if (it.fromUser) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT, it.text) }
        _state.update { it.copy(messages = it.messages + userMsg + AiMessage(replyId, fromUser = false, text = "", streaming = true), generating = true) }

        chatJob = viewModelScope.launch {
            fun setReply(update: (AiMessage) -> AiMessage) =
                _state.update { s -> s.copy(messages = s.messages.map { if (it.id == replyId) update(it) else it }) }
            try {
                if (llm.loadedModel == null) {
                    val candidate = llm.bestInstalled()
                    if (candidate == null) {
                        setReply { it.copy(text = OfflineLlm.UNAVAILABLE, streaming = false, isError = true) }
                        return@launch
                    }
                    setReply { it.copy(text = "Loading ${candidate.displayName}…") }
                    llm.load(candidate).onFailure { e ->
                        setReply { it.copy(text = e.message ?: "Could not load the model.", streaming = false, isError = true) }
                        return@launch
                    }
                }
                val model = llm.loadedModel
                val prompt = AiPrompts.build(
                    action, question,
                    language = context?.language ?: "plaintext",
                    selection = context?.selection.orEmpty(),
                    fileText = context?.text.orEmpty(),
                )
                var started = false
                llm.chat(AiPrompts.IDE_SYSTEM, history, prompt).collect { piece ->
                    setReply { it.copy(text = (if (started) it.text else "") + piece, modelName = model?.displayName) }
                    started = true
                }
                setReply {
                    if (it.text.isBlank()) it.copy(text = "The model returned no text. Try rephrasing.", streaming = false, isError = true)
                    else it.copy(streaming = false)
                }
            } catch (e: CancellationException) {
                setReply { it.copy(text = it.text.ifBlank { "(stopped)" }.let { t -> if (t == "(stopped)") t else "$t\n\n(stopped)" }, streaming = false) }
                throw e
            } catch (e: Exception) {
                setReply { it.copy(text = e.message ?: "Something went wrong.", streaming = false, isError = true) }
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        llm.stop()
        chatJob?.cancel()
    }

    fun clearChat() {
        stop()
        _state.update { it.copy(messages = emptyList(), generating = false) }
    }
}
