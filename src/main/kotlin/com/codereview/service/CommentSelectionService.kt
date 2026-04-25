package com.codereview.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
class CommentSelectionService(private val project: Project) {

    private var selectedCommentId: String? = null

    fun currentSelection(): String? = selectedCommentId

    fun select(commentId: String?) {
        if (selectedCommentId == commentId) return
        selectedCommentId = commentId
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(TOPIC).onCommentSelectionChanged(commentId)
        }
    }

    interface Listener {
        fun onCommentSelectionChanged(commentId: String?)
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("CodeReviewCommentSelectionChanged", Listener::class.java)

        fun getInstance(project: Project): CommentSelectionService = project.service()
    }
}
