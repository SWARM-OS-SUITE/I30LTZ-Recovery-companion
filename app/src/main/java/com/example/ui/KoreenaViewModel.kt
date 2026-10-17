package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.ChatTurn
import com.example.data.ai.OfflineLlm
import com.example.data.koreena.CommandReader
import com.example.data.koreena.KoreenaPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KoreenaState(
    /** What is in the reader box. Kept here so other screens (the cheat sheet) can send a command to her. */
    val input: String = "",
    val messages: List<AiMessage> = emptyList(),
    val generating: Boolean = false,
)

/**
 * Miss Koreena's side of the offline AI. She has her own messages and her own system prompt, so her talk
 * never leaks into Dr. Boltz's IDE chat (and the other way round). Both use the one on-device model
 * ([OfflineLlm]): a phone cannot hold two. The colouring and the glossary need no model at all.
 */
class KoreenaViewModel(application: Application) : AndroidViewModel(application) {

    private val llm = OfflineLlm.get(application)
    private var chatJob: Job? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(KoreenaState())
    val state: StateFlow<KoreenaState> = _state.asStateFlow()

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    /** "Teach me this": her lesson on whatever is in the reader box. */
    fun explainInput() {
        val command = _state.value.input.trim()
        if (command.isEmpty()) return
        run("Teach me this:\n$command", KoreenaPrompts.explain(command, CommandReader.read(command)))
    }

    /** A free question; the command in the reader box (if any) is sent along as context. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        run(q, KoreenaPrompts.ask(q, _state.value.input))
    }

    private fun run(shown: String, prompt: String) {
        if (chatJob?.isActive == true) return
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
                var started = false
                llm.chat(KoreenaPrompts.SYSTEM, history, prompt).collect { piece ->
                    setReply { it.copy(text = (if (started) it.text else "") + piece, modelName = model?.displayName) }
                    started = true
                }
                setReply {
                    if (it.text.isBlank()) it.copy(text = "The model returned no text. Try asking another way.", streaming = false, isError = true)
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
