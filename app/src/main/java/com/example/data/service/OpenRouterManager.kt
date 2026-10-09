package com.example.data.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class OpenRouterManager(context: Context) {
    private val prefs: SharedPreferences = com.example.data.config.securePreferences(context, "openrouter_config")

    private val _apiKey = MutableStateFlow(getApiKey())
    val apiKey: StateFlow<String> = _apiKey

    private val _selectedModel = MutableStateFlow(getSelectedModel())
    val selectedModel: StateFlow<String> = _selectedModel

    // ── Dr. Boltz's voice (ElevenLabs through OpenRouter's /audio/speech). Off by default: it sends the spoken text to the cloud. ──
    data class VoiceSettings(val enabled: Boolean, val voice: String, val model: String)

    private val _voiceEnabled = MutableStateFlow(readVoice().enabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled
    private val _voiceName = MutableStateFlow(readVoice().voice)
    val voiceName: StateFlow<String> = _voiceName
    private val _voiceModel = MutableStateFlow(readVoice().model)
    val voiceModel: StateFlow<String> = _voiceModel

    /** Read fresh from storage, so a change made on the settings card applies to the next thing spoken. */
    fun readVoice() = VoiceSettings(
        enabled = prefs.getBoolean("voice_enabled", false),
        voice = (prefs.getString("voice_name", DEFAULT_VOICE) ?: DEFAULT_VOICE).ifBlank { DEFAULT_VOICE },
        model = (prefs.getString("voice_model", DEFAULT_VOICE_MODEL) ?: DEFAULT_VOICE_MODEL).ifBlank { DEFAULT_VOICE_MODEL },
    )

    /** Voice names are lower-case words like "bill"; anything else is refused instead of being sent. */
    fun saveVoice(enabled: Boolean, voice: String, model: String) {
        val v = voice.trim().lowercase()
        require(VOICE_NAME.matches(v)) { "A voice name is letters, digits, - or _ (for example: bill)" }
        require(model in VOICE_MODELS.map { it.first }) { "Unknown voice model" }
        check(prefs.edit().putBoolean("voice_enabled", enabled).putString("voice_name", v).putString("voice_model", model).commit()) { "Could not save the voice settings" }
        _voiceEnabled.value = enabled; _voiceName.value = v; _voiceModel.value = model
    }

    fun getApiKey(): String = prefs.getString("openrouter_api_key", "") ?: ""

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("openrouter_api_key", trimmed).apply()
        _apiKey.value = trimmed
    }

    fun getSelectedModel(): String = prefs.getString("openrouter_model", "deepseek/deepseek-r1") ?: "deepseek/deepseek-r1"

    fun saveSelectedModel(model: String) {
        val trimmed = model.trim()
        prefs.edit().putString("openrouter_model", trimmed).apply()
        _selectedModel.value = trimmed
    }

    fun clearApiKey() {
        prefs.edit().remove("openrouter_api_key").apply()
        _apiKey.value = ""
    }

    companion object {
        /** ElevenLabs' own table lists Bill as the older American male voice (trustworthy, for narration). */
        const val DEFAULT_VOICE = "bill"
        const val DEFAULT_VOICE_MODEL = "elevenlabs/eleven-turbo-v2.5"
        val VOICE_NAME = Regex("[a-z0-9_-]{1,40}")
        val VOICE_MODELS = listOf(
            "elevenlabs/eleven-flash-v2.5" to "Fast",
            "elevenlabs/eleven-turbo-v2.5" to "Balanced",
            "elevenlabs/eleven-v3" to "Best",
        )
        /** Names OpenRouter listed for ElevenLabs models on 2026-10-08 (premade voices, which ElevenLabs retires after 2026-12-31). */
        val SUGGESTED_VOICES = listOf(
            "bill" to "older American, trustworthy",
            "george" to "middle-aged British, warm",
            "daniel" to "middle-aged British, authoritative",
            "brian" to "middle-aged American, deep",
            "roger" to "middle-aged American, confident",
        )

        val AVAILABLE_MODELS = listOf(
            "deepseek/deepseek-r1" to "DeepSeek R1 (Reasoning Swarm)",
            "deepseek/deepseek-chat" to "DeepSeek V3 (Fast Chat)",
            "moonshotai/kimi-k2" to "Kimi K2 (Moonshot Swarm)",
            "thudm/glm-4" to "GLM 4 (Zhipu AI)",
            "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet",
            "qwen/qwen-2.5-coder-32b-instruct" to "Qwen 2.5 Coder 32B",
            "meta-llama/llama-3.3-70b-instruct" to "Meta Llama 3.3 70B",
            "google/gemini-2.5-flash" to "Google Gemini 2.5 Flash"
        )
    }
}
