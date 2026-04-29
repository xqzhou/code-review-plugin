package com.codereview.editor

import com.codereview.model.ReviewComment
import com.codereview.service.CommentSelectionService
import com.codereview.service.CommentService
import com.codereview.ui.CommentToolWindowService
import com.codereview.util.ProjectFiles
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.util.ui.UIUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.awt.Color
import javax.swing.Icon

class CommentEditorDecorations(private val project: Project) {

    private val lineMarkerKey = Key.create<Boolean>("code.review.comment.line.marker")
    private val rangeMarkerKey = Key.create<Boolean>("code.review.comment.range.marker")
    private val selectedRangeMarkerKey = Key.create<Boolean>("code.review.comment.selected.range.marker")
    private val icon: Icon = IconLoader.getIcon("/icons/comment.svg", CommentEditorDecorations::class.java)

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(CommentService.TOPIC, object : CommentService.Listener {
            override fun onCommentsChanged() = refreshOpenEditors()
        })
        connection.subscribe(CommentSelectionService.TOPIC, object : CommentSelectionService.Listener {
            override fun onCommentSelectionChanged(commentId: String?) = refreshOpenEditors()
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                refreshOpenEditors()
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                refreshOpenEditors()
            }
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            refreshOpenEditors()
        })

        ApplicationManager.getApplication().invokeLater { refreshOpenEditors() }
    }

    fun refreshOpenEditors() {
        ApplicationManager.getApplication().invokeLater {
            val editorManager = FileEditorManager.getInstance(project)
            editorManager.allEditors
                .filterIsInstance<TextEditor>()
                .map { it.editor }
                .filterIsInstance<EditorEx>()
                .forEach(::refreshEditor)
        }
    }

    private fun refreshEditor(editor: EditorEx) {
        if (editor.isDisposed) return

        clearMarkers(editor)

        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relPath = ProjectFiles.relativePath(project, vFile) ?: return
        val comments = CommentService.getInstance(project).forFile(relPath)
        if (comments.isEmpty()) return

        val selectedCommentId = CommentSelectionService.getInstance(project).currentSelection()
        val weakAttributes = rangeAttributes(selected = false)
        val selectedAttributes = rangeAttributes(selected = true)

        comments.forEach { comment ->
            val offsets = commentRangeOffsets(editor.document, comment) ?: return@forEach
            val (startOffset, endOffset) = offsets

            editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                weakAttributes,
                HighlighterTargetArea.EXACT_RANGE
            ).apply {
                putUserData(rangeMarkerKey, true)
                errorStripeTooltip = tooltipFor(comment)
            }

            if (comment.id == selectedCommentId) {
                editor.markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.SELECTION - 1,
                    selectedAttributes,
                    HighlighterTargetArea.EXACT_RANGE
                ).apply {
                    putUserData(selectedRangeMarkerKey, true)
                    errorStripeTooltip = tooltipFor(comment)
                }
            }
        }

        comments
            .sortedWith(compareBy({ it.startLine }, { it.endLine }, { it.createdAt }))
            .groupBy { it.startLine }
            .forEach { (line, groupedComments) ->
                val lineIndex = line - 1
                if (lineIndex !in 0 until editor.document.lineCount) return@forEach
                editor.markupModel.addLineHighlighter(lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply {
                    putUserData(lineMarkerKey, true)
                    gutterIconRenderer = CommentGutterRenderer(project, editor, lineIndex, icon, groupedComments)
                    errorStripeTooltip = groupedComments.joinToString("\n\n") { tooltipFor(it) }
                }
            }
    }

    private fun clearMarkers(editor: EditorEx) {
        editor.markupModel.allHighlighters
            .filter {
                it.getUserData(lineMarkerKey) == true ||
                    it.getUserData(rangeMarkerKey) == true ||
                    it.getUserData(selectedRangeMarkerKey) == true
            }
            .forEach(editor.markupModel::removeHighlighter)
    }

    private fun tooltipFor(comment: ReviewComment): String =
        "[${rangeLabel(comment)}] ${comment.comment}"

    private fun commentRangeOffsets(document: Document, comment: ReviewComment): Pair<Int, Int>? {
        if (document.lineCount <= 0) return null
        val startLineIndex = (comment.startLine - 1).coerceAtLeast(0)
        if (startLineIndex !in 0 until document.lineCount) return null
        val endLineIndex = (comment.endLine - 1).coerceAtLeast(startLineIndex).coerceAtMost(document.lineCount - 1)

        val startOffset = if (comment.startColumn != null) {
            val lineStart = document.getLineStartOffset(startLineIndex)
            val lineEnd = document.getLineEndOffset(startLineIndex)
            (lineStart + comment.startColumn!! - 1).coerceIn(lineStart, lineEnd)
        } else {
            document.getLineStartOffset(startLineIndex)
        }

        val endOffset = if (comment.endColumn != null) {
            val lineStart = document.getLineStartOffset(endLineIndex)
            val lineEnd = document.getLineEndOffset(endLineIndex)
            (lineStart + comment.endColumn!! - 1).coerceIn(lineStart, lineEnd)
        } else {
            document.getLineEndOffset(endLineIndex)
        }

        return if (endOffset > startOffset) {
            startOffset to endOffset
        } else {
            val fallbackEnd = document.getLineEndOffset(endLineIndex)
            if (fallbackEnd > startOffset) startOffset to fallbackEnd else null
        }
    }

    private fun rangeAttributes(selected: Boolean): TextAttributes {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val accent = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: UIUtil.getFocusedFillColor()
        val background = mix(scheme.defaultBackground ?: UIUtil.getEditorPaneBackground(), accent, if (selected) 0.42f else 0.14f)
        return TextAttributes().apply {
            backgroundColor = background
            if (selected) {
                effectType = EffectType.BOXED
                effectColor = accent
            }
        }
    }

    private fun mix(base: Color, accent: Color, weight: Float): Color {
        val normalized = weight.coerceIn(0f, 1f)
        val inverse = 1f - normalized
        return Color(
            (base.red * inverse + accent.red * normalized).toInt().coerceIn(0, 255),
            (base.green * inverse + accent.green * normalized).toInt().coerceIn(0, 255),
            (base.blue * inverse + accent.blue * normalized).toInt().coerceIn(0, 255)
        )
    }

    companion object {
        private fun rangeLabel(comment: ReviewComment): String {
            val startColumn = comment.startColumn
            val endColumn = comment.endColumn
            if (startColumn != null && endColumn != null) {
                return if (comment.startLine == comment.endLine) {
                    "${comment.startLine}:$startColumn-$endColumn"
                } else {
                    "${comment.startLine}:$startColumn-${comment.endLine}:$endColumn"
                }
            }
            return "${comment.startLine}-${comment.endLine}"
        }
    }

    private class CommentGutterRenderer(
        private val project: Project,
        private val editor: EditorEx,
        private val lineIndex: Int,
        private val icon: Icon,
        private val comments: List<ReviewComment>
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = icon

        override fun getTooltipText(): String =
            comments.joinToString("\n") { "[${rangeLabel(it)}] ${it.comment}" }

        override fun getAlignment(): Alignment = Alignment.LEFT

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) {
                val first = comments.firstOrNull() ?: return
                CommentToolWindowService.getInstance(project).showComment(first.id, editorLineAlignmentFraction(editor, lineIndex))
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommentGutterRenderer) return false
            return comments.map { it.id } == other.comments.map { it.id }
        }

        override fun hashCode(): Int = comments.map { it.id }.hashCode()

        private fun editorLineAlignmentFraction(editor: EditorEx, lineIndex: Int): Double {
            val visibleArea = editor.scrollingModel.visibleArea
            if (visibleArea.height <= 0) return 0.0
            val y = editor.logicalPositionToXY(LogicalPosition(lineIndex, 0)).y
            return ((y - visibleArea.y).toDouble() / visibleArea.height).coerceIn(0.0, 1.0)
        }
    }
}
