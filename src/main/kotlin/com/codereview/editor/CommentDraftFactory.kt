package com.codereview.editor

import com.codereview.model.CommentDraft
import com.codereview.model.ReviewComment
import com.codereview.util.ProjectFiles
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

    fun fromEditor(project: Project, editor: Editor): CommentDraft? {
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return null
        val relPath = ProjectFiles.relativePath(project, vFile) ?: return null

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

        val codeSnippet = buildString {
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

        return CommentDraft(
            file = relPath,
            startLine = startLine,
            endLine = endLine,
            codeSnippet = codeSnippet
        )
    }

    fun fromComment(project: Project, comment: ReviewComment): CommentDraft =
        CommentDraft(
            file = comment.file,
            startLine = comment.startLine,
            endLine = comment.endLine,
            codeSnippet = currentSnippet(project, comment) ?: comment.codeSnippet,
            initialComment = comment.comment,
            existingCommentId = comment.id
        )

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
}
