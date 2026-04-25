package com.codereview.storage

import com.codereview.git.GitBranchProvider
import com.codereview.model.CommentFile
import com.codereview.model.LegacyStoredComment
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewThread
import com.codereview.ui.CommentNotifications
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

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
            }.normalize(branch)
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
        val normalized = data.normalize(GitBranchProvider.currentBranch(project)).apply {
            comments = null
        }
        val file = currentBranchFile()
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(normalized, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun CommentFile.normalize(branch: String): CommentFile {
        this.branch = branch
        migrateLegacyCommentsIfNeeded(this)
        threads.forEach { thread ->
            if (thread.id.isBlank()) thread.id = UUID.randomUUID().toString()
            if (thread.side.isBlank()) thread.side = "new"
            if (thread.endLine <= 0) thread.endLine = thread.startLine
            if (thread.createdAt.isBlank()) thread.createdAt = Instant.now().toString()
            if (thread.updatedAt.isBlank()) thread.updatedAt = thread.createdAt
            val activeComments = thread.comments.filter { it.deletedAt == null }
            if (thread.status.isBlank()) thread.status = "open"
            if (thread.syncStatus.isBlank()) thread.syncStatus = "local_only"
            if (thread.createdAt.isBlank()) {
                thread.createdAt = activeComments.firstOrNull()?.createdAt ?: Instant.now().toString()
            }
            if (thread.updatedAt.isBlank()) {
                thread.updatedAt = activeComments.maxOfOrNull { it.updatedAt.ifBlank { it.createdAt } }
                    ?: thread.createdAt
            }
            thread.comments.forEach { comment ->
                if (comment.id.isBlank()) comment.id = UUID.randomUUID().toString()
                if (comment.threadId.isBlank()) comment.threadId = thread.id
                if (comment.syncStatus.isBlank()) comment.syncStatus = "local_only"
                if (comment.createdAt.isBlank()) comment.createdAt = thread.createdAt
                if (comment.updatedAt.isBlank()) comment.updatedAt = comment.createdAt
            }
        }
        return this
    }

    private fun migrateLegacyCommentsIfNeeded(data: CommentFile) {
        val legacy = data.comments.orEmpty()
        if (legacy.isEmpty()) return
        if (data.threads.isNotEmpty()) {
            data.comments = null
            return
        }

        data.threads = legacy.map { it.toThread() }.toMutableList()
        data.comments = null
    }

    private fun LegacyStoredComment.toThread(): ReviewThread {
        val threadId = UUID.randomUUID().toString()
        return ReviewThread(
            id = threadId,
            file = file,
            side = "new",
            startLine = startLine,
            endLine = endLine,
            createdAt = createdAt.ifBlank { Instant.now().toString() },
            updatedAt = updatedAt.ifBlank { createdAt.ifBlank { Instant.now().toString() } },
            status = when (status) {
                "resolved" -> "resolved"
                else -> "open"
            },
            outdated = false,
            reviewTargetRef = reviewTargetRef,
            diffBaseCommit = diffBaseCommit,
            diffHeadCommit = diffHeadCommit,
            diffOldPath = diffOldPath,
            diffNewPath = diffNewPath,
            syncStatus = syncStatus,
            deletedAt = deletedAt,
            remoteThreadId = remoteDiscussionId,
            findingId = findingId,
            comments = mutableListOf(
                ReviewComment(
                    id = id.ifBlank { UUID.randomUUID().toString() },
                    threadId = threadId,
                    content = comment,
                    createdAt = createdAt.ifBlank { Instant.now().toString() },
                    updatedAt = updatedAt.ifBlank { createdAt.ifBlank { Instant.now().toString() } },
                    createdByName = remoteAuthorName ?: createdByName,
                    syncStatus = syncStatus,
                    deletedAt = deletedAt,
                    remoteCommentId = remoteNoteId
                )
            )
        )
    }

    private fun backupCorruptStore(file: Path) {
        runCatching {
            val backup = file.resolveSibling("${file.fileName}.broken-${Instant.now().toEpochMilli()}")
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
