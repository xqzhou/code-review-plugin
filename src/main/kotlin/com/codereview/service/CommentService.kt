package com.codereview.service

import com.codereview.git.GitUserProvider
import com.codereview.model.CommentFile
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewThread
import com.codereview.storage.CommentStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Service(Service.Level.PROJECT)
class CommentService(private val project: Project) {

    interface Listener {
        fun onCommentsChanged()
    }

    fun storeFilePath(): String = store.currentBranchFile().toString()

    fun allThreadsStored(): List<ReviewThread> =
        read().threads.map(::copyThread).map(::enrichThread)

    fun allThreads(): List<ReviewThread> =
        allThreadsStored()
            .filter { it.deletedAt == null }
            .map { thread ->
                thread.comments = thread.comments.filter { it.deletedAt == null }.toMutableList()
                thread
            }
            .filter { it.comments.isNotEmpty() }

    fun all(): List<ReviewComment> =
        allThreads().flatMap { it.comments }

    fun forFile(relPath: String): List<ReviewComment> =
        forFileThreads(relPath).flatMap { it.comments }

    fun forFileThreads(relPath: String): List<ReviewThread> =
        allThreads().filter { it.file == relPath }

    fun find(commentId: String): ReviewComment? =
        all().firstOrNull { it.id == commentId }

    fun findThread(threadId: String): ReviewThread? =
        allThreads().firstOrNull { it.id == threadId }

    fun findStoredThread(threadId: String): ReviewThread? =
        allThreadsStored().firstOrNull { it.id == threadId }

    fun findThreadByCommentId(commentId: String): ReviewThread? =
        allThreads().firstOrNull { thread -> thread.comments.any { it.id == commentId } }

    fun add(
        file: String,
        startLine: Int,
        endLine: Int,
        codeSnippet: String,
        comment: String,
        side: String = "new",
        startColumn: Int? = null,
        endColumn: Int? = null,
        reviewTargetRef: String? = null,
        diffBaseCommit: String? = null,
        diffHeadCommit: String? = null,
        diffOldPath: String? = null,
        diffNewPath: String? = null
    ): ReviewComment =
        addThread(
            file = file,
            side = side,
            startLine = startLine,
            endLine = endLine,
            startColumn = startColumn,
            endColumn = endColumn,
            codeSnippet = codeSnippet,
            comment = comment,
            reviewTargetRef = reviewTargetRef,
            diffBaseCommit = diffBaseCommit,
            diffHeadCommit = diffHeadCommit,
            diffOldPath = diffOldPath,
            diffNewPath = diffNewPath
        ).comments.first()

    fun addThread(
        file: String,
        startLine: Int,
        endLine: Int,
        codeSnippet: String,
        comment: String,
        side: String = "new",
        startColumn: Int? = null,
        endColumn: Int? = null,
        reviewTargetRef: String? = null,
        diffBaseCommit: String? = null,
        diffHeadCommit: String? = null,
        diffOldPath: String? = null,
        diffNewPath: String? = null
    ): ReviewThread {
        val now = Instant.now().toString()
        val threadId = UUID.randomUUID().toString()
        val rootCommentId = UUID.randomUUID().toString()
        val data = read()
        val thread = ReviewThread(
            id = threadId,
            file = file,
            side = side.ifBlank { "new" },
            startLine = startLine,
            endLine = endLine.coerceAtLeast(startLine),
            startColumn = startColumn,
            endColumn = endColumn,
            createdAt = now,
            updatedAt = now,
            status = "open",
            outdated = false,
            reviewTargetRef = reviewTargetRef,
            diffBaseCommit = diffBaseCommit,
            diffHeadCommit = diffHeadCommit,
            diffOldPath = diffOldPath,
            diffNewPath = diffNewPath,
            syncStatus = "local_only",
            findingId = null,
            comments = mutableListOf(
                ReviewComment(
                    id = rootCommentId,
                    threadId = threadId,
                    content = comment.trim(),
                    createdAt = now,
                    updatedAt = now,
                    createdByName = GitUserProvider.currentDisplayName(project),
                    syncStatus = "local_only",
                    deletedAt = null,
                    remoteCommentId = null
                )
            )
        )
        data.threads.add(thread)
        persist(data)
        return enrichThread(copyThread(thread))
    }

    fun addReply(threadId: String, text: String): ReviewComment? {
        val data = read()
        val thread = data.threads.firstOrNull { it.id == threadId && it.deletedAt == null } ?: return null
        val now = Instant.now().toString()
        val reply = ReviewComment(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            content = text.trim(),
            createdAt = now,
            updatedAt = now,
            createdByName = GitUserProvider.currentDisplayName(project),
            syncStatus = "local_only",
            deletedAt = null,
            remoteCommentId = null
        )
        thread.comments.add(reply)
        thread.updatedAt = now
        if (thread.syncStatus == "synced") {
            thread.syncStatus = "modified"
        }
        persist(data)
        return enrichComment(copyComment(reply), thread)
    }

    fun update(commentId: String, text: String): ReviewComment? {
        val data = read()
        val thread = data.threads.firstOrNull { current ->
            current.comments.any { it.id == commentId }
        } ?: return null
        val comment = thread.comments.firstOrNull { it.id == commentId } ?: return null
        val now = Instant.now().toString()
        comment.content = text.trim()
        comment.updatedAt = now
        if (comment.remoteCommentId != null && comment.syncStatus == "synced") {
            comment.syncStatus = "modified"
        }
        thread.updatedAt = now
        if (thread.remoteThreadId != null && thread.syncStatus == "synced") {
            thread.syncStatus = "modified"
        }
        persist(data)
        return enrichComment(copyComment(comment), thread)
    }

    fun updateThreadStatus(threadId: String, status: String): ReviewThread? {
        val normalized = when (status) {
            "resolved" -> "resolved"
            else -> "open"
        }
        val data = read()
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return null
        val now = Instant.now().toString()
        thread.status = normalized
        thread.updatedAt = now
        if (thread.remoteThreadId != null && thread.syncStatus == "synced") {
            thread.syncStatus = "modified"
        }
        persist(data)
        return enrichThread(copyThread(thread))
    }

    fun delete(commentId: String) {
        val data = read()
        val thread = data.threads.firstOrNull { current ->
            current.comments.any { it.id == commentId }
        } ?: return
        val rootCommentId = thread.comments.firstOrNull { it.deletedAt == null }?.id
        if (commentId == rootCommentId) {
            deleteThreadInternal(data, thread)
        } else {
            deleteReplyInternal(thread, commentId)
        }
        persist(data)
    }

    fun deleteThread(threadId: String) {
        val data = read()
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return
        deleteThreadInternal(data, thread)
        persist(data)
    }

    private fun deleteReplyInternal(thread: ReviewThread, commentId: String) {
        val reply = thread.comments.firstOrNull { it.id == commentId } ?: return
        if (reply.remoteCommentId != null) {
            reply.deletedAt = Instant.now().toString()
            reply.syncStatus = "delete_pending"
        } else {
            thread.comments.removeIf { it.id == commentId }
        }
        thread.updatedAt = Instant.now().toString()
        if (thread.remoteThreadId != null && thread.syncStatus == "synced") {
            thread.syncStatus = "modified"
        }
    }

    private fun deleteThreadInternal(data: CommentFile, thread: ReviewThread) {
        if (thread.remoteThreadId != null) {
            val deletedAt = Instant.now().toString()
            thread.deletedAt = deletedAt
            thread.syncStatus = "delete_pending"
            thread.comments.forEach { comment ->
                if (comment.deletedAt == null) {
                    comment.deletedAt = deletedAt
                }
                if (comment.remoteCommentId != null) {
                    comment.syncStatus = "delete_pending"
                }
            }
            thread.updatedAt = deletedAt
        } else {
            data.threads.removeIf { it.id == thread.id }
        }
    }

    private fun read(): CommentFile = store.load()

    private fun persist(data: CommentFile) {
        store.save(data)
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(TOPIC).onCommentsChanged()
        }
    }

    private fun enrichThread(thread: ReviewThread): ReviewThread {
        thread.comments = thread.comments.map { enrichComment(it, thread) }.toMutableList()
        return thread
    }

    private fun enrichComment(comment: ReviewComment, thread: ReviewThread): ReviewComment =
        comment.apply {
            threadId = thread.id
            file = thread.file
            side = thread.side
            startLine = thread.startLine
            endLine = thread.endLine
            startColumn = thread.startColumn
            endColumn = thread.endColumn
            reviewTargetRef = thread.reviewTargetRef
            diffBaseCommit = thread.diffBaseCommit
            diffHeadCommit = thread.diffHeadCommit
            diffOldPath = thread.diffOldPath
            diffNewPath = thread.diffNewPath
            remoteThreadId = thread.remoteThreadId
            threadStatus = thread.status
            outdated = thread.outdated
            findingId = thread.findingId
        }

    private fun copyThread(thread: ReviewThread): ReviewThread =
        thread.copy(comments = thread.comments.map(::copyComment).toMutableList())

    private fun copyComment(comment: ReviewComment): ReviewComment =
        comment.copy().also { copy ->
            copy.file = comment.file
            copy.side = comment.side
            copy.startLine = comment.startLine
            copy.endLine = comment.endLine
            copy.startColumn = comment.startColumn
            copy.endColumn = comment.endColumn
            copy.reviewTargetRef = comment.reviewTargetRef
            copy.diffBaseCommit = comment.diffBaseCommit
            copy.diffHeadCommit = comment.diffHeadCommit
            copy.diffOldPath = comment.diffOldPath
            copy.diffNewPath = comment.diffNewPath
            copy.remoteThreadId = comment.remoteThreadId
            copy.threadStatus = comment.threadStatus
            copy.outdated = comment.outdated
            copy.findingId = comment.findingId
        }

    private val store = CommentStore(project)

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("CodeReviewCommentsChanged", Listener::class.java)

        fun getInstance(project: Project): CommentService = project.service()
    }
}
