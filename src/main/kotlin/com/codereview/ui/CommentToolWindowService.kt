package com.codereview.ui

import com.codereview.model.CommentDraft
import com.codereview.service.CommentSelectionService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class CommentToolWindowService(private val project: Project) {

    private var panel: CommentsPanel? = null
    private val pendingActions = mutableListOf<(CommentsPanel) -> Unit>()

    fun bind(panel: CommentsPanel) {
        this.panel = panel
        if (pendingActions.isEmpty()) return
        pendingActions.toList().forEach(panel::run)
        pendingActions.clear()
    }

    fun showDraft(draft: CommentDraft) {
        CommentSelectionService.getInstance(project).select(draft.existingCommentId)
        openAndRun { beginDraft(draft) }
    }

    fun showComment(commentId: String, alignmentFraction: Double? = null) {
        CommentSelectionService.getInstance(project).select(commentId)
        openAndRun { selectComment(commentId, alignmentFraction) }
    }

    fun editComment(commentId: String) {
        CommentSelectionService.getInstance(project).select(commentId)
        openAndRun { beginEdit(commentId) }
    }

    private fun openAndRun(action: CommentsPanel.() -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CommentsPanel.TOOL_WINDOW_ID)
        if (toolWindow == null) {
            pendingActions += action
            return
        }

        pendingActions += action
        toolWindow.show {
            ApplicationManager.getApplication().invokeLater {
                panel?.let { boundPanel ->
                    pendingActions.toList().forEach(boundPanel::run)
                    pendingActions.clear()
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): CommentToolWindowService = project.service()
    }
}
