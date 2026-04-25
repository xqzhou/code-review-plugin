package com.codereview.service

import com.codereview.git.GitUserProvider
import com.codereview.model.ReviewComment
import com.codereview.storage.CommentStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.time.Instant
import java.util.UUID

@Service(Service.Level.PROJECT)
class CommentService(private val project: Project) {

    private val store = CommentStore(project)

    fun all(): List<ReviewComment> = store.load().comments.filter { it.deletedAt == null }

    fun allStored(): List<ReviewComment> = store.load().comments.toList()

    fun forFile(relPath: String): List<ReviewComment> =
        all().filter { it.file == relPath }

    fun find(id: String): ReviewComment? =
        all().firstOrNull { it.id == id }

    fun add(
        file: String,
        startLine: Int,
        endLine: Int,
        codeSnippet: String,
        comment: String
    ): ReviewComment {
        val now = Instant.now().toString()
        val rc = ReviewComment(
            id = UUID.randomUUID().toString(),
            file = file,
            startLine = startLine,
            endLine = endLine,
            codeSnippet = codeSnippet,
            comment = comment,
            createdAt = now,
            updatedAt = now,
            createdByName = GitUserProvider.currentDisplayName(project)
        )
        store.upsert(rc)
        notifyChange()
        return rc
    }

    fun update(id: String, newText: String) {
        val data = store.load()
        val target = data.comments.firstOrNull { it.id == id && it.deletedAt == null } ?: return
        target.comment = newText
        target.updatedAt = Instant.now().toString()
        target.syncStatus = when (target.syncStatus) {
            "synced", "delete_pending" -> "modified"
            else -> target.syncStatus
        }
        store.save(data)
        notifyChange()
    }

    fun delete(id: String) {
        val data = store.load()
        val target = data.comments.firstOrNull { it.id == id } ?: return
        if (target.remoteProvider != null || target.remoteDiscussionId != null || target.remoteNoteId != null) {
            target.deletedAt = Instant.now().toString()
            target.updatedAt = target.deletedAt!!
            target.syncStatus = "delete_pending"
            store.save(data)
            notifyChange()
            return
        }
        if (store.delete(id)) notifyChange()
    }

    fun storeFilePath(): String = store.currentBranchFile().toString()

    private fun notifyChange() {
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(TOPIC).onCommentsChanged()
        }
    }

    interface Listener {
        fun onCommentsChanged()
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("CodeReviewCommentsChanged", Listener::class.java)
        fun getInstance(project: Project): CommentService = project.service()
    }
}
