package com.example.ui.coderdroid

import org.eclipse.jgit.api.Git
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The one GitHub-specific operation CoderDroid's git panel needs that plain git (JGit)
 * has no concept of: opening a pull request. Everything else in [CdrGitService] speaks
 * only the git protocol; this speaks GitHub's REST API, and only to github.com — same
 * host restriction [CdrGitService.credentials] already enforces for push/pull.
 */
object CdrGitHubApi {

    data class PullRequest(val number: Int, val url: String)

    /**
     * Opens a PR from [head] into [base] on the repo the workspace's origin points at.
     * [head] is the branch name only (same-repo PR, not a fork) — pass the branch that
     * is already pushed; GitHub itself reports a clear error if there is nothing to
     * compare (e.g. head not pushed, or head and base are the same).
     */
    fun create(root: File, token: String, base: String, title: String, body: String): Result<PullRequest> = runCatching {
        require(title.isNotBlank()) { "Enter a title for the pull request." }
        require(base.isNotBlank()) { "Enter the branch to open the pull request against." }
        require(token.isNotBlank()) { "No GitHub token set — tap the key icon in the Git panel." }
        val (owner, repo, head) = Git.open(root).use { git ->
            val url = git.repository.config.getString("remote", "origin", "url")
                ?: error("No origin remote configured.")
            val uri = URI(url.trim())
            require(uri.host.equals("github.com", ignoreCase = true)) { "Pull requests can only be opened against a github.com origin." }
            val parts = uri.path.trim('/').removeSuffix(".git").split("/")
            require(parts.size >= 2) { "The origin URL doesn't look like a GitHub repository." }
            val head = git.repository.branch ?: error("Could not determine the current branch.")
            Triple(parts[0], parts[1], head)
        }
        require(head != base) { "The current branch and the base branch are the same ($base) — nothing to compare." }

        val payload = JSONObject().put("title", title.trim()).put("head", head).put("base", base.trim())
        if (body.isNotBlank()) payload.put("body", body.trim())

        val conn = (URL("https://api.github.com/repos/$owner/$repo/pulls").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                error(msg?.takeIf { it.isNotBlank() } ?: "GitHub answered HTTP $code")
            }
            val json = JSONObject(text)
            PullRequest(number = json.getInt("number"), url = json.getString("html_url"))
        } finally {
            conn.disconnect()
        }
    }
}
