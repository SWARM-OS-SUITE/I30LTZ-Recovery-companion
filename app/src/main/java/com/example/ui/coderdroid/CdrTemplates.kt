package com.example.ui.coderdroid

import java.io.File
import java.io.IOException

/**
 * Starter files for the Marketplace's "Starters" tab.
 *
 * The one rule that matters: a starter must never replace a file that is already there. The first version of
 * this wrote straight over `app/build.gradle.kts`, `MainActivity.kt` and friends, so tapping Generate inside a
 * real project silently swapped its files for three-line stubs. Existing files are now left alone and listed.
 */
object CdrTemplates {

    enum class Template(val title: String) {
        ANDROID("Android Kotlin starter"),
        RUST("Rust utility"),
        PYTHON("Python SWARM agent"),
    }

    /** What a scaffold did, in the project's own relative paths. */
    data class Outcome(val created: List<String>, val skipped: List<String>) {
        fun summary(): String = buildString {
            append(if (created.isEmpty()) "Nothing was created" else "Created ${created.size} file${if (created.size == 1) "" else "s"}")
            if (skipped.isNotEmpty()) append("; left ${skipped.size} existing file${if (skipped.size == 1) "" else "s"} untouched (${skipped.joinToString()})")
            append(".")
        }
    }

    fun files(template: Template): Map<String, String> = when (template) {
        Template.ANDROID -> linkedMapOf(
            "app/build.gradle.kts" to "plugins {\n    alias(libs.plugins.android.application)\n    alias(libs.plugins.kotlin.android)\n}\n",
            "app/src/main/AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"ScaffoldApp\" />\n</manifest>\n",
            "app/src/main/java/com/example/MainActivity.kt" to "package com.example\n\nimport android.app.Activity\n\nclass MainActivity : Activity()\n",
        )
        Template.RUST -> linkedMapOf(
            "Cargo.toml" to "[package]\nname = \"recovery_tool\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
            "src/main.rs" to "fn main() {\n    println!(\"Boltz Recovery Tool initialized.\");\n}\n",
        )
        Template.PYTHON -> linkedMapOf(
            "requirements.txt" to "requests>=2.31.0\nwebsockets>=12.0\n",
            "agent.py" to "#!/usr/bin/env python3\nimport sys\n\n\ndef main():\n    print(\"SWARM Node Agent active.\")\n\n\nif __name__ == \"__main__\":\n    main()\n",
        )
    }

    /** Writes the template's files under [root], skipping any that exist. Fails loudly if [root] is not writable. */
    fun scaffold(root: File, template: Template): Result<Outcome> = runCatching {
        if (!root.isDirectory) throw IOException("The project folder is not available.")
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((relative, text) in files(template)) {
            val target = File(root, relative)
            if (target.exists()) { skipped += relative; continue }
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Could not create the folder for $relative.") }
            CdrFileOps.saveText(target, text).getOrThrow()
            created += relative
        }
        Outcome(created, skipped)
    }
}
