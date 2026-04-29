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
                side = "new",
                startLine = selection.startLine,
                endLine = selection.endLine,
                startColumn = selection.startColumn,
                endColumn = selection.endColumn,
                codeSnippet = codeSnippet(editor, selection),
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
            side = "new",
            startLine = selection.startLine,
            endLine = selection.endLine,
            startColumn = selection.startColumn,
            endColumn = selection.endColumn,
            codeSnippet = codeSnippet(editor, selection)
        )
    }

    fun fromComment(project: Project, comment: ReviewComment): CommentDraft =
        CommentDraft(
            file = comment.file,
            side = comment.side,
            startLine = comment.startLine,
            endLine = comment.endLine,
            startColumn = comment.startColumn,
            endColumn = comment.endColumn,
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
        if (selectionModel.hasSelection()) {
            val selectionStart = selectionModel.selectionStart
            val rawSelectionEnd = selectionModel.selectionEnd
            val adjustedSelectionEnd = if (
                rawSelectionEnd > selectionStart &&
                rawSelectionEnd > 0 &&
                rawSelectionEnd == document.getLineStartOffset(document.getLineNumber(rawSelectionEnd))
            ) {
                rawSelectionEnd - 1
            } else {
                rawSelectionEnd
            }
            val startLineIndex = document.getLineNumber(selectionStart)
            val endLineIndex = document.getLineNumber(adjustedSelectionEnd)
            val exclusiveEndOffset = rawSelectionEnd.coerceAtLeast(selectionStart + 1)
            return LineSelection(
                startLine = startLineIndex + 1,
                endLine = endLineIndex + 1,
                startColumn = selectionStart - document.getLineStartOffset(startLineIndex) + 1,
                endColumn = exclusiveEndOffset - document.getLineStartOffset(endLineIndex) + 1
            )
        } else {
            val line = document.getLineNumber(editor.caretModel.offset) + 1
            return LineSelection(line, line, null, null)
        }
    }

    private fun codeSnippet(editor: Editor, selection: LineSelection): String {
        val document = editor.document
        val selectedRange = selectedTextRange(editor, selection)
        if (selectedRange != null) return document.getText(selectedRange)
        return buildString {
            for (line in selection.startLine..selection.endLine) {
                val index = line - 1
                if (index !in 0 until document.lineCount) continue
                append(
                    document.getText(
                        TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index))
                    )
                )
                if (line != selection.endLine) append('\n')
            }
        }
    }

    private fun selectedTextRange(editor: Editor, selection: LineSelection): TextRange? {
        val startColumn = selection.startColumn ?: return null
        val endColumn = selection.endColumn ?: return null
        val document = editor.document
        val startLineIndex = selection.startLine - 1
        val endLineIndex = selection.endLine - 1
        if (startLineIndex !in 0 until document.lineCount || endLineIndex !in 0 until document.lineCount) return null
        val startOffset = (document.getLineStartOffset(startLineIndex) + startColumn - 1)
            .coerceIn(document.getLineStartOffset(startLineIndex), document.getLineEndOffset(startLineIndex))
        val endOffset = (document.getLineStartOffset(endLineIndex) + endColumn - 1)
            .coerceIn(document.getLineStartOffset(endLineIndex), document.getLineEndOffset(endLineIndex))
        if (endOffset <= startOffset) return null
        return TextRange(startOffset, endOffset)
    }

    private fun currentSnippet(project: Project, comment: ReviewComment): String? {
        val vFile = ProjectFiles.find(project, comment.file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(vFile)
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.document
            ?: return null

        if (document.lineCount <= 0) return null

        val startLine = (comment.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (comment.endLine - 1).coerceIn(startLine, document.lineCount - 1)
        val startColumn = comment.startColumn
        val endColumn = comment.endColumn
        if (startColumn != null && endColumn != null) {
            val startOffset = (document.getLineStartOffset(startLine) + startColumn - 1)
                .coerceIn(document.getLineStartOffset(startLine), document.getLineEndOffset(startLine))
            val endOffset = (document.getLineStartOffset(endLine) + endColumn - 1)
                .coerceIn(document.getLineStartOffset(endLine), document.getLineEndOffset(endLine))
            if (endOffset > startOffset) return document.getText(TextRange(startOffset, endOffset))
        }
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

    private data class LineSelection(
        val startLine: Int,
        val endLine: Int,
        val startColumn: Int?,
        val endColumn: Int?
    )
}
