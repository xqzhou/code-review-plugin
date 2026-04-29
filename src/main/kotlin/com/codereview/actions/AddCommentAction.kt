package com.codereview.actions

import com.codereview.editor.CommentDraftFactory
import com.codereview.editor.CommentEditorDecorations
import com.codereview.diff.ReviewDiffDataKeys
import com.codereview.ui.CommentToolWindowService
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.keymap.KeymapManager

class AddCommentAction : AnAction("Add Review Comment") {

    init {
        normalizeShortcut()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val diffTarget = e.getData(ReviewDiffDataKeys.COMMENT_TARGET)
        if (diffTarget != null) {
            val editor = e.getData(DiffDataKeys.CURRENT_EDITOR)
            val currentContent = e.getData(DiffDataKeys.CURRENT_CONTENT)
            e.presentation.isEnabledAndVisible =
                editor != null &&
                diffTarget.newPath != null &&
                currentContent == diffTarget.newContent &&
                editor.document.lineCount > 0
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val draft = CommentDraftFactory.fromEvent(project, e) ?: return
        project.service<CommentEditorDecorations>()
        CommentToolWindowService.getInstance(project).showDraft(draft)
    }

    companion object {
        private const val ACTION_ID = "CodeReview.AddComment"
        private val commandShiftM = KeyboardShortcut.fromString("shift meta M")
        private val controlShiftM = KeyboardShortcut.fromString("shift ctrl M")

        private fun normalizeShortcut() {
            runCatching {
                val keymap = KeymapManager.getInstance().activeKeymap
                if (!keymap.canModify()) return
                val shortcuts = keymap.getShortcuts(ACTION_ID).toSet()
                if (shortcuts.contains(controlShiftM)) {
                    keymap.removeShortcut(ACTION_ID, controlShiftM)
                }
                if (!shortcuts.contains(commandShiftM)) {
                    keymap.addShortcut(ACTION_ID, commandShiftM)
                }
            }
        }
    }
}
