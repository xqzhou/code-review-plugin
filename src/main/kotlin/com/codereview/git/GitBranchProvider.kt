package com.codereview.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

object GitBranchProvider {

    fun currentBranch(project: Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return "no-branch"
        return repo.currentBranchName ?: repo.currentRevision?.take(8) ?: "detached"
    }

    fun safeBranchFileName(branch: String): String =
        branch.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
