package com.codereview.ui

import com.codereview.git.GitUserProvider
import com.codereview.model.CommentDraft
import com.codereview.model.ReviewComment
import com.codereview.service.CommentSelectionService
import com.codereview.service.CommentService
import com.codereview.util.ProjectFiles
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.MouseInfo
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Timer
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CommentsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var comments: List<ReviewComment> = emptyList()
    private var editingState: InlineEditState? = null
    private val cardViews = linkedMapOf<String, CommentCardView>()
    private var transientDraftCard: CommentCardView? = null
    private var flashingCommentId: String? = null
    private var flashOn = false
    private var flashTimer: Timer? = null

    private val cardsContainer = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }
    private val cardsScrollPane = JBScrollPane(cardsContainer).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }
    private val emptyState = JBLabel("No comments on this branch yet.").apply {
        border = JBUI.Borders.empty(24, 0)
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    init {
        val toolbar = buildToolbar()
        add(toolbar.component, BorderLayout.NORTH)
        add(buildCommentsArea(), BorderLayout.CENTER)

        val connection = project.messageBus.connect()
        connection.subscribe(CommentService.TOPIC, object : CommentService.Listener {
            override fun onCommentsChanged() = refresh()
        })
        connection.subscribe(CommentSelectionService.TOPIC, object : CommentSelectionService.Listener {
            override fun onCommentSelectionChanged(commentId: String?) = syncCardSelection(commentId)
        })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, git4idea.repo.GitRepositoryChangeListener {
            refresh()
        })

        CommentToolWindowService.getInstance(project).bind(this)
        refresh()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload comments from disk", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            add(object : AnAction("Copy Path of JSON Store", "Copy the path of the comments JSON file", AllIcons.Actions.Copy) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    val path = CommentService.getInstance(project).storeFilePath()
                    CopyPasteManager.getInstance().setContents(StringSelection(path))
                }
            })
        }
        return ActionManager.getInstance().createActionToolbar("CodeReview.Toolbar", group, true).also {
            it.targetComponent = this
        }
    }

    private fun buildCommentsArea(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 0, 12)
            add(JBLabel("Comments").apply {
                border = JBUI.Borders.empty(12, 12, 0, 12)
            }, BorderLayout.NORTH)
            add(cardsScrollPane, BorderLayout.CENTER)
        }

    fun beginDraft(draft: CommentDraft) {
        finishActiveEditBefore {
            editingState = InlineEditState.New(draft = draft, text = draft.initialComment)
            CommentSelectionService.getInstance(project).select(null)
            renderCards()
            scrollToTransientDraft()
        }
    }

    fun beginEdit(commentId: String) {
        val current = editingState
        if (current is InlineEditState.Existing && current.commentId == commentId) {
            scrollToComment(commentId)
            focusActiveEditor()
            return
        }

        val comment = CommentService.getInstance(project).find(commentId) ?: run {
            CommentNotifications.warn(project, "Comment Not Found", "The selected comment no longer exists on this branch.")
            refresh()
            return
        }
        if (!canModify(comment)) {
            CommentNotifications.warn(project, "Comment Is Read-Only", "Only comments created by you can be edited or deleted.")
            selectComment(comment.id)
            return
        }

        finishActiveEditBefore {
            editingState = InlineEditState.Existing(
                commentId = comment.id,
                originalText = comment.comment,
                text = comment.comment
            )
            CommentSelectionService.getInstance(project).select(comment.id)
            renderCards()
            scrollToComment(comment.id)
            focusActiveEditor()
        }
    }

    fun selectComment(commentId: String) {
        CommentSelectionService.getInstance(project).select(commentId)
        scrollToComment(commentId)
    }

    private fun selected(): ReviewComment? {
        val selectedId = CommentSelectionService.getInstance(project).currentSelection() ?: return null
        return comments.firstOrNull { it.id == selectedId }
    }

    fun refresh() {
        comments = CommentService.getInstance(project).all().sortedWith(
            compareByDescending<ReviewComment> { it.updatedAt }.thenBy { it.file }.thenBy { it.startLine }
        )

        val selectedId = CommentSelectionService.getInstance(project).currentSelection()
        if (selectedId != null && comments.none { it.id == selectedId }) {
            CommentSelectionService.getInstance(project).select(null)
        }

        val currentEdit = editingState
        if (currentEdit is InlineEditState.Existing && comments.none { it.id == currentEdit.commentId }) {
            editingState = null
        }

        renderCards()
    }

    private fun renderCards() {
        cardViews.clear()
        transientDraftCard = null
        cardsContainer.removeAll()

        val activeEdit = editingState
        if (activeEdit is InlineEditState.New) {
            transientDraftCard = createEditingCard(activeEdit)
            cardsContainer.add(transientDraftCard!!.root)
            if (comments.isNotEmpty()) {
                cardsContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }

        if (comments.isEmpty() && activeEdit !is InlineEditState.New) {
            cardsContainer.add(emptyState)
        } else {
            comments.forEachIndexed { index, comment ->
                val cardView = when {
                    activeEdit is InlineEditState.Existing && activeEdit.commentId == comment.id ->
                        createEditingCard(comment, activeEdit)
                    else ->
                        createReadOnlyCard(comment)
                }
                cardViews[comment.id] = cardView
                cardsContainer.add(cardView.root)
                if (index != comments.lastIndex) {
                    cardsContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
            }
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
        scrollToComment(CommentSelectionService.getInstance(project).currentSelection())
        if (activeEdit is InlineEditState.New) {
            scrollToTransientDraft()
        }
    }

    private fun createReadOnlyCard(comment: ReviewComment): CommentCardView {
        val editable = canModify(comment)
        val titleLabel = JBLabel(fileNameOf(comment.file)).apply {
            foreground = UIUtil.getLabelForeground()
            font = uiFont(11f, bold = true)
            toolTipText = comment.file
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val locationLabel = JBLabel(buildLocationLabel(comment.file, lineRange(comment))).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            toolTipText = "${comment.file}:${lineRange(comment)}"
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val infoRow = buildInfoRow(comment)
        val body = JBTextArea(comment.comment).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            font = bodyFont()
            foreground = UIUtil.getLabelForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val actions = JPanel(HorizontalLayout(JBUI.scale(12))).apply {
            isOpaque = false
            if (editable) {
                add(cardActionLink("Edit") { beginEdit(comment.id) })
                add(cardActionLink("Delete") { deleteComment(comment) })
            }
        }
        val actionSlot = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(26))
            minimumSize = Dimension(0, JBUI.scale(26))
            add(actions, BorderLayout.WEST)
        }

        val header = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(locationLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(infoRow)
        }

        val content = JBPanel<JBPanel<*>>(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(actionSlot, BorderLayout.SOUTH)
        }

        val cardView = wrapCard(content, showActionsOnHover = true, actionContent = actions, commentId = comment.id)
        applyCardStyle(cardView, isSelected = comment.id == CommentSelectionService.getInstance(project).currentSelection())
        val clickListener = createReadOnlyCardMouseListener(cardView, comment)
        installCardMouseTargets(cardView, clickListener, listOf(content, header, titleLabel, locationLabel, infoRow, body, actionSlot))
        actions.components.forEach { installHoverMouseTarget(it, clickListener) }
        return cardView
    }

    private fun createEditingCard(editState: InlineEditState.New): CommentCardView {
        val titleLabel = JBLabel(fileNameOf(editState.draft.file)).apply {
            foreground = UIUtil.getLabelForeground()
            font = uiFont(11f, bold = true)
            toolTipText = editState.draft.file
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val locationLabel = JBLabel(buildLocationLabel(editState.draft.file, lineRange(editState.draft.startLine, editState.draft.endLine))).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val infoRow = buildMetaOnlyRow("Local draft")
        return createEditingCardShell(
            titleLabel = titleLabel,
            locationLabel = locationLabel,
            infoRow = infoRow,
            text = editState.text,
            isSaveEnabled = editState.text.trim().isNotEmpty(),
            onTextChanged = { editState.text = it },
            onSave = { saveNewDraft(editState) },
            onCancel = { cancelEditing() },
            onNavigate = { navigateToDraft(editState.draft) }
        )
    }

    private fun createEditingCard(comment: ReviewComment, editState: InlineEditState.Existing): CommentCardView {
        val titleLabel = JBLabel(fileNameOf(comment.file)).apply {
            foreground = UIUtil.getLabelForeground()
            font = uiFont(11f, bold = true)
            toolTipText = comment.file
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val locationLabel = JBLabel(buildLocationLabel(comment.file, lineRange(comment))).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            toolTipText = "${comment.file}:${lineRange(comment)}"
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val infoRow = buildInfoRow(comment)
        return createEditingCardShell(
            titleLabel = titleLabel,
            locationLabel = locationLabel,
            infoRow = infoRow,
            text = editState.text,
            isSaveEnabled = editState.text.trim().isNotEmpty() && editState.text != editState.originalText,
            onTextChanged = { editState.text = it },
            onSave = { saveExistingEdit(editState) },
            onCancel = { cancelEditing() },
            onNavigate = { navigateTo(comment, requestFocusComment = true) }
        )
    }

    private fun createEditingCardShell(
        titleLabel: JBLabel,
        locationLabel: JBLabel,
        infoRow: JComponent,
        text: String,
        isSaveEnabled: Boolean,
        onTextChanged: (String) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
        onNavigate: () -> Unit
    ): CommentCardView {
        val editor = JBTextArea(text).apply {
            lineWrap = true
            wrapStyleWord = true
            font = bodyFont()
            foreground = UIUtil.getLabelForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8)
            rows = 4
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val saveButton = JButton("Save").apply {
            isEnabled = isSaveEnabled
            addActionListener { onSave() }
        }
        val cancelButton = JButton("Cancel").apply {
            addActionListener { onCancel() }
        }

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
            private fun update() {
                onTextChanged(editor.text)
                saveButton.isEnabled = editor.text.trim().isNotEmpty()
            }
        })

        val header = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(locationLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(infoRow)
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(saveButton)
            add(cancelButton)
        }
        val footer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(32))
            minimumSize = Dimension(0, JBUI.scale(32))
            add(buttonRow, BorderLayout.WEST)
        }
        val content = JBPanel<JBPanel<*>>(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(editor).apply {
                border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                preferredSize = Dimension(0, JBUI.scale(120))
            }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        val cardView = wrapCard(
            content,
            showActionsOnHover = false,
            actionContent = null,
            commentId = (editingState as? InlineEditState.Existing)?.commentId
        )
        applyCardStyle(cardView, isSelected = true)

        val navigateListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                    onNavigate()
                }
            }
        }
        listOf(titleLabel, locationLabel, infoRow).forEach { installHoverMouseTarget(it, navigateListener) }
        SwingUtilities.invokeLater {
            editor.requestFocusInWindow()
            editor.caretPosition = editor.text.length
        }
        return cardView
    }

    private fun wrapCard(
        content: JComponent,
        showActionsOnHover: Boolean,
        actionContent: JComponent?,
        commentId: String?
    ): CommentCardView {
        val stripe = JBPanel<JBPanel<*>>().apply {
            preferredSize = Dimension(JBUI.scale(4), 0)
            minimumSize = Dimension(JBUI.scale(4), 0)
            maximumSize = Dimension(JBUI.scale(4), Int.MAX_VALUE)
        }
        val root = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            add(stripe, BorderLayout.WEST)
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(12)
                add(content, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        return CommentCardView(
            root = root,
            stripe = stripe,
            hoverActionContent = actionContent,
            showActionsOnHover = showActionsOnHover,
            commentId = commentId
        )
    }

    private fun createReadOnlyCardMouseListener(cardView: CommentCardView, comment: ReviewComment): MouseAdapter =
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                navigateTo(comment, requestFocusComment = true)
                if (e.clickCount == 2) {
                    beginEdit(comment.id)
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                cardView.hovered = true
                applyCardStyle(cardView, comment.id == CommentSelectionService.getInstance(project).currentSelection())
            }

            override fun mouseExited(e: MouseEvent) {
                SwingUtilities.invokeLater {
                    val pointer = MouseInfo.getPointerInfo()?.location ?: return@invokeLater
                    val point = pointer.location
                    SwingUtilities.convertPointFromScreen(point, cardView.root)
                    cardView.hovered = cardView.root.isShowing && cardView.root.contains(point)
                    applyCardStyle(cardView, comment.id == CommentSelectionService.getInstance(project).currentSelection())
                }
            }
        }

    private fun installCardMouseTargets(cardView: CommentCardView, listener: MouseAdapter, components: List<Component>) {
        installHoverMouseTarget(cardView.root, listener)
        components.forEach { installHoverMouseTarget(it, listener) }
        cardView.hoverActionContent?.let { installHoverMouseTarget(it, listener) }
    }

    private fun installHoverMouseTarget(component: Component, listener: MouseAdapter) {
        component.addMouseListener(listener)
        if (component is JComponent) {
            component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun applyCardStyle(cardView: CommentCardView, isSelected: Boolean) {
        val base = UIUtil.getPanelBackground()
        val accent = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: UIUtil.getFocusedFillColor()
        val isFlashing = flashOn && cardView.commentId != null && cardView.commentId == flashingCommentId
        val background = when {
            isFlashing -> mix(base, accent, 0.16f)
            isSelected -> mix(base, accent, 0.10f)
            cardView.hovered -> mix(base, accent, 0.04f)
            else -> elevate(base, 0.035f)
        }
        val borderColor = when {
            isFlashing -> accent
            isSelected -> mix(base, accent, 0.60f)
            cardView.hovered -> mix(base, accent, 0.18f)
            else -> JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        }

        cardView.root.background = background
        cardView.root.isOpaque = true
        cardView.root.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(borderColor, if (isSelected || isFlashing) 2 else 1),
            JBUI.Borders.empty()
        )
        cardView.stripe.background = when {
            isFlashing -> accent
            isSelected -> accent
            cardView.hovered -> mix(base, accent, 0.28f)
            else -> mix(base, borderColor, 0.14f)
        }
        cardView.stripe.isOpaque = true

        if (cardView.showActionsOnHover) {
            cardView.hoverActionContent?.isVisible = cardView.hovered
        }
    }

    private fun syncCardSelection(commentId: String?) {
        cardViews.forEach { (id, cardView) ->
            applyCardStyle(cardView, isSelected = id == commentId)
        }
        transientDraftCard?.let { applyCardStyle(it, isSelected = editingState is InlineEditState.New) }
        scrollToComment(commentId)
    }

    private fun scrollToComment(commentId: String?) {
        if (commentId == null) return
        val card = cardViews[commentId]?.root ?: return
        SwingUtilities.invokeLater {
            cardsContainer.scrollRectToVisible(card.bounds)
        }
    }

    private fun scrollToTransientDraft() {
        val card = transientDraftCard?.root ?: return
        SwingUtilities.invokeLater {
            cardsContainer.scrollRectToVisible(card.bounds)
        }
    }

    private fun focusActiveEditor() {
        val editCard = transientDraftCard?.root
            ?: (editingState as? InlineEditState.Existing)?.commentId?.let { cardViews[it]?.root }
            ?: return
        SwingUtilities.invokeLater {
            findFirstTextArea(editCard)?.requestFocusInWindow()
        }
    }

    private fun findFirstTextArea(component: JComponent): JTextArea? {
        if (component is JTextArea) return component
        component.components.forEach { child ->
            when (child) {
                is JTextArea -> return child
                is JComponent -> {
                    val found = findFirstTextArea(child)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun saveNewDraft(editState: InlineEditState.New): Boolean {
        val text = editState.text.trim()
        if (text.isBlank()) return false

        val created = CommentService.getInstance(project).add(
            file = editState.draft.file,
            startLine = editState.draft.startLine,
            endLine = editState.draft.endLine,
            codeSnippet = editState.draft.codeSnippet,
            comment = text
        )
        editingState = null
        CommentSelectionService.getInstance(project).select(created.id)
        refresh()
        startFlash(created.id)
        return true
    }

    private fun saveExistingEdit(editState: InlineEditState.Existing): Boolean {
        val text = editState.text.trim()
        if (text.isBlank()) return false
        if (text == editState.originalText) {
            editingState = null
            renderCards()
            return true
        }

        CommentService.getInstance(project).update(editState.commentId, text)
        editingState = null
        CommentSelectionService.getInstance(project).select(editState.commentId)
        refresh()
        return true
    }

    private fun cancelEditing() {
        editingState = null
        renderCards()
    }

    private fun finishActiveEditBefore(nextAction: () -> Unit) {
        val active = editingState
        if (active == null) {
            nextAction()
            return
        }

        if (!hasUnsavedChanges(active)) {
            editingState = null
            renderCards()
            nextAction()
            return
        }

        val result = Messages.showDialog(
            project,
            "You have an unsaved comment edit. What do you want to do?",
            "Unsaved Comment",
            arrayOf("Save", "Discard", "Cancel"),
            0,
            null
        )
        when (result) {
            0 -> {
                val saved = when (active) {
                    is InlineEditState.New -> saveNewDraft(active)
                    is InlineEditState.Existing -> saveExistingEdit(active)
                }
                if (saved) nextAction()
            }
            1 -> {
                editingState = null
                renderCards()
                nextAction()
            }
        }
    }

    private fun hasUnsavedChanges(active: InlineEditState): Boolean =
        when (active) {
            is InlineEditState.New -> active.text.trim().isNotEmpty()
            is InlineEditState.Existing -> active.text != active.originalText
        }

    private fun navigateToDraft(draft: CommentDraft) {
        val target = ProjectFiles.find(project, draft.file) ?: run {
            CommentNotifications.warn(project, "Comment Target Missing", "Cannot find `${draft.file}` in the current project.")
            return
        }
        val lineIndex = resolveLineIndex(target, draft.startLine)
        if (lineIndex == null) {
            CommentNotifications.warn(project, "Comment Target Outdated", "`${draft.file}` no longer has line ${draft.startLine}.")
            return
        }
        OpenFileDescriptor(project, target, lineIndex, 0).navigate(true)
    }

    private fun navigateTo(comment: ReviewComment, requestFocusComment: Boolean) {
        val target = ProjectFiles.find(project, comment.file) ?: run {
            CommentNotifications.warn(project, "Comment Target Missing", "Cannot find `${comment.file}` in the current project.")
            return
        }

        val lineIndex = resolveLineIndex(target, comment.startLine)
        if (lineIndex == null) {
            CommentNotifications.warn(project, "Comment Target Outdated", "`${comment.file}` no longer has line ${comment.startLine}.")
            return
        }

        OpenFileDescriptor(project, target, lineIndex, 0).navigate(true)
        if (requestFocusComment) {
            selectComment(comment.id)
        }
    }

    private fun resolveLineIndex(target: VirtualFile, oneBasedLine: Int): Int? {
        val document = FileDocumentManager.getInstance().getDocument(target) ?: return if (oneBasedLine > 0) oneBasedLine - 1 else 0
        if (document.lineCount <= 0) return 0
        val requested = oneBasedLine - 1
        return if (requested in 0 until document.lineCount) requested else null
    }

    private fun deleteComment(comment: ReviewComment) {
        if (!canModify(comment)) {
            CommentNotifications.warn(project, "Comment Is Read-Only", "Only comments created by you can be edited or deleted.")
            selectComment(comment.id)
            return
        }
        if (Messages.YES != Messages.showYesNoDialog(project, "Delete this comment?", "Confirm", null)) return
        CommentService.getInstance(project).delete(comment.id)
        if (CommentSelectionService.getInstance(project).currentSelection() == comment.id) {
            CommentSelectionService.getInstance(project).select(null)
        }
        if (editingState is InlineEditState.Existing && (editingState as InlineEditState.Existing).commentId == comment.id) {
            editingState = null
        }
        refresh()
    }

    private fun fileNameOf(path: String): String = path.substringAfterLast('/')

    private fun buildLocationLabel(path: String, lines: String): String {
        val parent = path.substringBeforeLast('/', "")
        return if (parent.isBlank()) "Lines $lines" else "${shortenParentPath(parent)} • Lines $lines"
    }

    private fun shortenParentPath(path: String, maxLength: Int = 52): String {
        if (path.length <= maxLength) return path
        val keepStart = (maxLength * 0.55).toInt().coerceAtLeast(10)
        val keepEnd = (maxLength - keepStart - 1).coerceAtLeast(8)
        return "${path.take(keepStart)}…${path.takeLast(keepEnd)}"
    }

    private fun buildAuthorAndTime(comment: ReviewComment): String {
        val author = comment.remoteAuthorName?.takeIf { it.isNotBlank() }
        val updated = "Updated ${formatTimestamp(comment.updatedAt)}"
        return if (author == null) updated else "$author • $updated"
    }

    private fun buildInfoRow(comment: ReviewComment): JComponent {
        val metaLabel = JBLabel(buildAuthorAndTime(comment)).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val row = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            statusBadge(comment)?.let { add(it) }
            findingBadge(comment)?.let { add(it) }
            add(metaLabel)
        }
        return row
    }

    private fun buildMetaOnlyRow(text: String): JComponent =
        JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(text).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = uiFont(10f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

    private fun findingBadge(comment: ReviewComment): JComponent? {
        val label = comment.findingId?.takeIf { it.isNotBlank() } ?: return null
        val tone = Color(0x6B8AD1)
        val foreground = mix(UIUtil.getLabelForeground(), tone, 0.48f)
        val background = mix(UIUtil.getPanelBackground(), tone, 0.08f)
        return JBLabel(label).apply {
            font = uiFont(10f)
            this.foreground = foreground
            isOpaque = true
            this.background = background
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mix(background, tone, 0.22f), 1),
                JBUI.Borders.empty(1, 6)
            )
            toolTipText = "Source finding $label"
        }
    }

    private fun statusBadge(comment: ReviewComment): JComponent? {
        val label = when (comment.syncStatus) {
            "local_only" -> "Local"
            "modified" -> "Modified"
            "delete_pending" -> "Delete pending"
            "synced" -> "Synced"
            else -> comment.syncStatus.takeIf { it.isNotBlank() }?.replace('_', ' ')
        } ?: return null

        val accent = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: UIUtil.getFocusedFillColor()
        val tone = when (comment.syncStatus) {
            "delete_pending" -> Color(0xD06565)
            "modified" -> Color(0xC9913A)
            "synced" -> Color(0x5E9E78)
            else -> mix(accent, Color(0x7F8FA6), 0.55f)
        }
        val foreground = mix(UIUtil.getLabelForeground(), tone, 0.42f)
        val background = mix(UIUtil.getPanelBackground(), tone, 0.08f)

        return JBLabel(label).apply {
            font = uiFont(10f)
            this.foreground = foreground
            isOpaque = true
            this.background = background
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mix(background, tone, 0.22f), 1),
                JBUI.Borders.empty(1, 6)
            )
        }
    }

    private fun startFlash(commentId: String) {
        flashTimer?.stop()
        flashingCommentId = commentId
        flashOn = true
        refreshCardStyles()

        var ticks = 0
        flashTimer = Timer(140) {
            ticks += 1
            flashOn = !flashOn
            if (ticks >= 6) {
                flashTimer?.stop()
                flashTimer = null
                flashingCommentId = null
                flashOn = false
            }
            refreshCardStyles()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun refreshCardStyles() {
        val selectedId = CommentSelectionService.getInstance(project).currentSelection()
        cardViews.forEach { (id, cardView) ->
            applyCardStyle(cardView, isSelected = id == selectedId)
        }
        transientDraftCard?.let { applyCardStyle(it, isSelected = editingState is InlineEditState.New) }
    }

    private fun canModify(comment: ReviewComment): Boolean {
        val remoteAuthor = comment.remoteAuthorName?.takeIf { it.isNotBlank() }
        if (remoteAuthor != null) {
            return GitUserProvider.matchesCurrentUser(project, remoteAuthor)
        }

        val createdBy = comment.createdByName?.takeIf { it.isNotBlank() }
        if (createdBy != null) {
            return GitUserProvider.matchesCurrentUser(project, createdBy)
        }

        // Backward compatibility for older local comments created before ownership metadata existed.
        return comment.remoteProvider == null && comment.remoteDiscussionId == null && comment.remoteNoteId == null
    }

    private fun lineRange(comment: ReviewComment): String = lineRange(comment.startLine, comment.endLine)

    private fun lineRange(startLine: Int, endLine: Int): String =
        if (startLine == endLine) "$startLine" else "$startLine-$endLine"

    private fun formatTimestamp(timestamp: String): String =
        runCatching {
            Instant.parse(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT)
        }.getOrElse { timestamp }

    private fun uiFont(size: Float, bold: Boolean = false): Font {
        val base = JBUI.Fonts.label()
        val font = base.deriveFont(size)
        return if (bold) font.deriveFont(Font.BOLD) else font
    }

    private fun cardActionLink(text: String, action: () -> Unit): ActionLink =
        ActionLink(text) { action() }.apply {
            font = uiFont(10f)
        }

    private fun bodyFont(): Font {
        val editorFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        val reducedSize = (editorFont.size2D - 1f).coerceAtLeast(11f)
        return editorFont.deriveFont(reducedSize)
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

    private fun elevate(base: Color, amount: Float): Color {
        val target = if (base.red + base.green + base.blue < 384) Color.WHITE else Color.BLACK
        return mix(base, target, amount)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Code Review"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private sealed interface InlineEditState {
        var text: String

        data class New(val draft: CommentDraft, override var text: String) : InlineEditState

        data class Existing(
            val commentId: String,
            val originalText: String,
            override var text: String
        ) : InlineEditState
    }

    private data class CommentCardView(
        val root: JComponent,
        val stripe: JComponent,
        val hoverActionContent: JComponent?,
        val showActionsOnHover: Boolean,
        val commentId: String? = null,
        var hovered: Boolean = false
    )
}
