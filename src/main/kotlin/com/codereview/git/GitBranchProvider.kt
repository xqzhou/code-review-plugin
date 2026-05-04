package com.codereview.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path

object GitBranchProvider {

    fun currentBranch(project: Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        return repo?.currentBranchName
            ?: branchFromGitHead(project)
            ?: repo?.currentRevision?.take(8)
            ?: "detached"
    }

    fun safeBranchFileName(branch: String): String =
        branch.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun branchFromGitHead(project: Project): String? {
        val basePath = project.basePath?.let(Path::of) ?: return null
        val gitPath = generateSequence(basePath) { it.parent }
            .map { it.resolve(".git") }
            .firstOrNull { Files.exists(it) }
            ?: return null
        val gitDir = resolveGitDir(gitPath) ?: return null
        val head = gitDir.resolve("HEAD")
        if (!Files.isRegularFile(head)) return null
        val value = runCatching { Files.readString(head).trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val prefix = "ref: refs/heads/"
        if (value.startsWith(prefix)) return value.removePrefix(prefix)
        return value.take(8).takeIf { it.length == 8 }
    }

    private fun resolveGitDir(gitPath: Path): Path? {
        if (Files.isDirectory(gitPath)) return gitPath
        if (!Files.isRegularFile(gitPath)) return null
        val value = runCatching { Files.readString(gitPath).trim() }.getOrNull() ?: return null
        val prefix = "gitdir:"
        if (!value.startsWith(prefix)) return null
        val rawPath = value.removePrefix(prefix).trim()
        if (rawPath.isBlank()) return null
        val path = Path.of(rawPath)
        return if (path.isAbsolute) path else gitPath.parent.resolve(path).normalize()
    }
}
