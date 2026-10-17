package com.example.data.service

import com.example.data.ai.OfflineLlm
import com.example.data.ai.OfflineModelCatalog

/**
 * The long-standing "local model" entry point, now backed by [OfflineLlm] (Qwen3 / Gemma 4 on LiteRT-LM).
 *
 * When no model is installed, or the app has not started the engine (plain JVM tests), it behaves exactly
 * like the stub it replaced: it says plainly that offline AI is unavailable and never invents an answer.
 */
class LocalLlamaEngine {
    private val llm: OfflineLlm? get() = OfflineLlm.peek()

    /** Loads [modelName] (a catalog id) or, when blank, the best installed model. True when a model is ready. */
    suspend fun initializeModel(modelName: String = "", nCtx: Int = 2048): Boolean {
        val engine = llm ?: return false
        val model = OfflineModelCatalog.byId(modelName) ?: engine.bestInstalled() ?: return false
        return engine.load(model).isSuccess
    }

    suspend fun generateLocalResponse(prompt: String, systemContext: String): String {
        val engine = llm ?: return OfflineLlm.UNAVAILABLE
        return engine.answer(system = systemContext.ifBlank { DEFAULT_SYSTEM }, user = prompt)
    }

    fun isLoaded() = llm?.loadedModel != null
    fun getModelName() = llm?.loadedModel?.displayName ?: "Not installed"
    fun getContextTokens() = llm?.loadedModel?.contextTokens ?: 0

    private companion object {
        const val DEFAULT_SYSTEM = "You are Dr. Boltz, a careful repair and coding assistant. Be concise and honest, " +
            "and never claim to have run a command."
    }
}
