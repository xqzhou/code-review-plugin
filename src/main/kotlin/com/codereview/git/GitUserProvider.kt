package com.codereview.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.config.GitConfigUtil
import git4idea.repo.GitRepositoryManager

object GitUserProvider {

    fun currentDisplayName(project: Project): String? {
        val root = GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.root ?: return null
        return runCatching {
            GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun matchesCurrentUser(project: Project, name: String?): Boolean {
        val candidate = normalize(name) ?: return false
        val current = normalize(currentDisplayName(project)) ?: return false
        return candidate == current
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}
