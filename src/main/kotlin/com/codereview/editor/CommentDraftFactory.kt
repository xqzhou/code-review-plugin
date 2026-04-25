package com.codereview.editor

import com.codereview.diff.ReviewDiffDataKeys
import com.codereview.model.CommentDraft
import com.codereview.model.ReviewComment
import com.codereview.util.ProjectFiles
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

object CommentDraftFactory {

    fun fromSelectedEditor(project: Project): CommentDraft? {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return fromEditor(project, editor)
    }

    fun fromEvent(project: Project, event: AnActionEvent): CommentDraft? {
        val diffTarget = event.getData(ReviewDiffDataKeys.COMMENT_TARGET)
        if (diffTarget != null) {
            val editor = event.getData(DiffDataKeys.CURRENT_EDITOR) ?: return null
            val currentContent = event.getData(DiffDataKeys.CURRENT_CONTENT) ?: return null
            val newPath = diffTarget.newPath ?: return null
            if (currentContent != diffTarget.newContent || editor.document.lineCount <= 0) return null
            val selection = selection(editor)
            return CommentDraft(
                file = newPath,
                startLine = selection.startLine,
                endLine = selection.endLine,
                codeSnippet = codeSnippet(editor, selection.startLine, selection.endLine),
                reviewTargetRef = diffTarget.targetRef,
                diffBaseCommit = diffTarget.mergeBaseCommit,
                diffHeadCommit = diffTarget.headCommit,
                diffOldPath = diffTarget.oldPath,
                diffNewPath = diffTarget.newPath
            )
        }

        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        return fromEditor(project, editor)
    }

    fun fromEditor(project: Project, editor: Editor): CommentDraft? {
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return null
        val relPath = ProjectFiles.relativePath(project, vFile) ?: return null

        val selection = selection(editor)

        return CommentDraft(
            file = relPath,
            startLine = selection.startLine,
            endLine = selection.endLine,
            codeSnippet = codeSnippet(editor, selection.startLine, selection.endLine)
        )
    }

    fun fromComment(project: Project, comment: ReviewComment): CommentDraft =
        CommentDraft(
            file = comment.file,
            startLine = comment.startLine,
            endLine = comment.endLine,
            codeSnippet = currentSnippet(project, comment) ?: comment.codeSnippet,
            initialComment = comment.comment,
            existingCommentId = comment.id,
            reviewTargetRef = comment.reviewTargetRef,
            diffBaseCommit = comment.diffBaseCommit,
            diffHeadCommit = comment.diffHeadCommit,
            diffOldPath = comment.diffOldPath,
            diffNewPath = comment.diffNewPath
        )

    private fun selection(editor: Editor): LineSelection {
        val document = editor.document
        val selectionModel = editor.selectionModel
        val (startLine, endLine) = if (selectionModel.hasSelection()) {
            val start = document.getLineNumber(selectionModel.selectionStart) + 1
            val selectionEnd = selectionModel.selectionEnd
            val endLineIndex = document.getLineNumber(
                if (selectionEnd > 0 && selectionEnd == document.getLineStartOffset(document.getLineNumber(selectionEnd))) {
                    selectionEnd - 1
                } else {
                    selectionEnd
                }
            ) + 1
            start to endLineIndex
        } else {
            val line = document.getLineNumber(editor.caretModel.offset) + 1
            line to line
        }
        return LineSelection(startLine, endLine)
    }

    private fun codeSnippet(editor: Editor, startLine: Int, endLine: Int): String {
        val document = editor.document
        return buildString {
            for (line in startLine..endLine) {
                val index = line - 1
                if (index !in 0 until document.lineCount) continue
                append(
                    document.getText(
                        TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index))
                    )
                )
                if (line != endLine) append('\n')
            }
        }
    }

    private fun currentSnippet(project: Project, comment: ReviewComment): String? {
        val vFile = ProjectFiles.find(project, comment.file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(vFile)
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.document
            ?: return null

        if (document.lineCount <= 0) return null

        val startLine = (comment.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (comment.endLine - 1).coerceIn(startLine, document.lineCount - 1)
        return buildString {
            for (lineIndex in startLine..endLine) {
                append(
                    document.getText(
                        TextRange(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
                    )
                )
                if (lineIndex != endLine) append('\n')
            }
        }
    }

    private data class LineSelection(val startLine: Int, val endLine: Int)
}
