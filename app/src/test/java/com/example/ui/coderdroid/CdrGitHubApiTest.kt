package com.example.ui.coderdroid

import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the validation guards in [CdrGitHubApi.create] — the checks that run before any
 * network call is made. The actual GitHub round trip is deliberately not exercised here
 * (same scope as [CdrGitServiceTest]'s own clone/push tests: no test talks to the real
 * github.com over the network).
 */
class CdrGitHubApiTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repoWithOrigin(url: String?): File {
        val root = temp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            if (url != null) {
                git.repository.config.setString("remote", "origin", "url", url)
                git.repository.config.save()
            }
        }
        return root
    }

    @Test fun blankTitleIsRejectedBeforeAnyNetworkCall() {
        val root = repoWithOrigin("https://github.com/example/repo.git")
        val result = CdrGitHubApi.create(root, "tok", "main", "   ", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("title", ignoreCase = true))
    }

    @Test fun blankBaseIsRejected() {
        val root = repoWithOrigin("https://github.com/example/repo.git")
        val result = CdrGitHubApi.create(root, "tok", "  ", "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("branch", ignoreCase = true))
    }

    @Test fun blankTokenIsRejected() {
        val root = repoWithOrigin("https://github.com/example/repo.git")
        val result = CdrGitHubApi.create(root, "", "main", "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("token", ignoreCase = true))
    }

    @Test fun missingOriginIsRejected() {
        val root = repoWithOrigin(null)
        val result = CdrGitHubApi.create(root, "tok", "main", "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("origin", ignoreCase = true))
    }

    @Test fun nonGitHubOriginIsRejected() {
        val root = repoWithOrigin("https://gitlab.com/example/repo.git")
        val result = CdrGitHubApi.create(root, "tok", "main", "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("github.com"))
    }

    @Test fun sameBaseAndHeadIsRejectedBeforeAnyNetworkCall() {
        val root = repoWithOrigin("https://github.com/example/repo.git")
        val currentBranch = Git.open(root).use { it.repository.branch } // whatever JGit's init default is
        val result = CdrGitHubApi.create(root, "tok", currentBranch, "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("same"))
    }

    @Test fun malformedOriginPathIsRejected() {
        val root = repoWithOrigin("https://github.com/just-one-segment")
        val result = CdrGitHubApi.create(root, "tok", "main", "A title", "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("repository", ignoreCase = true))
    }
}
