package com.codereview.storage

import com.codereview.git.GitBranchProvider
import com.codereview.model.CommentFile
import com.codereview.model.ReviewComment
import com.codereview.ui.CommentNotifications
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class CommentStore(private val project: Project) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun rootDir(): Path {
        val base = project.basePath ?: System.getProperty("user.home")
        val dir = Path.of(base, ".code-review")
        Files.createDirectories(dir)
        return dir
    }

    fun currentBranchFile(): Path {
        val branch = GitBranchProvider.currentBranch(project)
        return rootDir().resolve("${GitBranchProvider.safeBranchFileName(branch)}.json")
    }

    @Synchronized
    fun load(): CommentFile {
        val file = currentBranchFile()
        val branch = GitBranchProvider.currentBranch(project)
        if (!Files.exists(file)) {
            return CommentFile(branch = branch)
        }
        return runCatching {
            Files.newBufferedReader(file).use {
                gson.fromJson(it, CommentFile::class.java) ?: CommentFile(branch = branch)
            }.apply {
                this.branch = branch
            }
        }.getOrElse {
            backupCorruptStore(file)
            ApplicationManager.getApplication().invokeLater {
                CommentNotifications.warn(
                    project,
                    "Comment Store Reset",
                    "Could not read `${file.fileName}`. The broken file was backed up and the plugin started with an empty comment list."
                )
            }
            CommentFile(branch = branch)
        }
    }

    @Synchronized
    fun save(data: CommentFile) {
        val file = currentBranchFile()
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(data, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun upsert(comment: ReviewComment) {
        val data = load()
        val idx = data.comments.indexOfFirst { it.id == comment.id }
        if (idx >= 0) data.comments[idx] = comment else data.comments.add(comment)
        save(data)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val data = load()
        val removed = data.comments.removeAll { it.id == id }
        if (removed) save(data)
        return removed
    }

    private fun backupCorruptStore(file: Path) {
        runCatching {
            val backup = file.resolveSibling("${file.fileName}.broken-${Instant.now().toEpochMilli()}")
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
