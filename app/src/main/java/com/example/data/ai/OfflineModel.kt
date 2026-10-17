package com.example.data.ai

/** One downloadable on-device language model, in the LiteRT-LM (`.litertlm`) format. */
data class OfflineModel(
    val id: String,
    val displayName: String,
    /** One line shown under the name: what this model is good for. */
    val tagline: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the whole file, taken from the publisher and re-checked by hashing a real download. */
    val sha256: String,
    /** Phone RAM (as Android reports it) below which the model will not run reliably. */
    val minRamBytes: Long,
    /** Phone RAM at which the model runs comfortably next to the rest of the app. */
    val comfortableRamBytes: Long,
    val license: String,
    val contextTokens: Int,
    /** Qwen3 thinks out loud unless told "/no_think"; the chat adds that and strips any leftover reasoning. */
    val usesQwenThinkSwitch: Boolean,
) {
    /** How this model sits on a phone with [totalRamBytes] of RAM. */
    fun fit(totalRamBytes: Long): Fit = when {
        totalRamBytes >= comfortableRamBytes -> Fit.COMFORTABLE
        totalRamBytes >= minRamBytes -> Fit.TIGHT
        else -> Fit.TOO_BIG
    }

    enum class Fit { COMFORTABLE, TIGHT, TOO_BIG }
}

/**
 * The models Dr. Boltz carries offline. All are Apache-2.0, public (no login), and published by Google's
 * litert-community on Hugging Face. Hashes and sizes were verified against real downloads on 2026-10-12, not
 * just copied from the web page.
 *
 * Sized for the phones this app runs on (2.8 GB and 3.4 GB of RAM as Android reports it):
 *  - Quick: Qwen3 1.7B (977 MB). The workhorse: solid at code and short answers. Tight but workable on 3 to 4 GB.
 *  - Deep: Gemma 4 E2B (2.6 GB). Noticeably stronger with a long memory, but wants a phone with 6 GB or more.
 *  - Lite: Qwen3 0.6B (345 MB). Small and fast, for low-memory moments and the smallest phones. Simpler answers.
 */
object OfflineModelCatalog {
    private const val GIB = 1024L * 1024 * 1024

    val quick = OfflineModel(
        id = "qwen3-1.7b",
        displayName = "Boltz Quick · Qwen3 1.7B",
        tagline = "Fast and small. Code help, explanations, quick fixes.",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 977_184_032L,
        sha256 = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
        minRamBytes = (2.5 * GIB).toLong(),
        comfortableRamBytes = (3.8 * GIB).toLong(),
        license = "Apache-2.0",
        contextTokens = 4096,
        usesQwenThinkSwitch = true,
    )

    val deep = OfflineModel(
        id = "gemma-4-e2b",
        displayName = "Boltz Deep · Gemma 4 E2B",
        tagline = "Stronger reasoning and longer files. Needs a 6 GB phone.",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minRamBytes = (4.5 * GIB).toLong(),
        comfortableRamBytes = (5.2 * GIB).toLong(),
        license = "Apache-2.0",
        contextTokens = 8192,
        usesQwenThinkSwitch = false,
    )

    val lite = OfflineModel(
        id = "qwen3-0.6b",
        displayName = "Boltz Lite · Qwen3 0.6B",
        tagline = "Tiny and fast. Simple questions and small edits; fits any phone here.",
        fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 344_671_744L,
        sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
        minRamBytes = (1.5 * GIB).toLong(),
        comfortableRamBytes = (2.3 * GIB).toLong(),
        license = "Apache-2.0",
        contextTokens = 4096,
        usesQwenThinkSwitch = true,
    )

    val all: List<OfflineModel> = listOf(quick, deep, lite)

    fun byId(id: String): OfflineModel? = all.firstOrNull { it.id == id }

    /**
     * Which model the app suggests first on a phone with [totalRamBytes] of RAM, or null if none fits.
     *
     * TODO(human): decide the policy. Today it picks the strongest model that runs COMFORTABLY, which gives the
     * 3.4 GB Samsungs the tiny Lite model even though Quick would fit (tightly). The alternative is to prefer
     * the strongest model that is at least TIGHT (better answers, but the phone may close other apps).
     */
    fun recommendedFor(totalRamBytes: Long): OfflineModel? =
        all.sortedByDescending { it.sizeBytes }.firstOrNull { it.fit(totalRamBytes) == OfflineModel.Fit.COMFORTABLE }
            ?: all.sortedBy { it.sizeBytes }.firstOrNull { it.fit(totalRamBytes) != OfflineModel.Fit.TOO_BIG }
}

/** Sizes the way phone stores and Hugging Face show them: decimal megabytes and gigabytes. */
object ByteFormat {
    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(java.util.Locale.US, bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        bytes >= 1_000L -> "${bytes / 1_000L} KB"
        else -> "$bytes B"
    }
}
