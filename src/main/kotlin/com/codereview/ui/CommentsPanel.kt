package com.codereview.ui

import com.codereview.editor.CommentEditorDecorations
import com.codereview.git.GitUserProvider
import com.codereview.model.CommentDraft
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewThread
import com.codereview.service.CommentSelectionService
import com.codereview.service.CommentService
import com.codereview.service.MrReviewService
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
import com.intellij.openapi.components.service
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
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CommentsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var threads: List<ReviewThread> = emptyList()
    private var editingState: InlineEditState? = null
    private val threadViews = linkedMapOf<String, ThreadCardView>()
    private var transientDraftCard: ThreadCardView? = null
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
        project.service<CommentEditorDecorations>()
        add(buildToolbar().component, BorderLayout.NORTH)
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
            add(object : AnAction("Open MR Diff", "Open the MR diff review for the current branch", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    MrReviewService.getInstance(project).openReview()
                }
            })
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
            editingState = InlineEditState.NewThread(draft, draft.initialComment)
            CommentSelectionService.getInstance(project).select(null)
            renderCards()
            scrollToTransientDraft()
        }
    }

    fun beginEdit(commentId: String) {
        val comment = CommentService.getInstance(project).find(commentId) ?: run {
            CommentNotifications.warn(project, "Comment Not Found", "The selected comment no longer exists on this branch.")
            refresh()
            return
        }
        if (!canModify(comment)) {
            CommentNotifications.warn(project, "Comment Is Read-Only", "Only comments created by you can be edited or deleted.")
            selectComment(commentId)
            return
        }
        val current = editingState
        if (current is InlineEditState.EditComment && current.commentId == commentId) {
            scrollToComment(commentId)
            focusActiveEditor()
            return
        }
        finishActiveEditBefore {
            editingState = InlineEditState.EditComment(
                threadId = comment.threadId,
                commentId = commentId,
                originalText = comment.content,
                text = comment.content
            )
            CommentSelectionService.getInstance(project).select(commentId)
            renderCards()
            scrollToComment(commentId)
            focusActiveEditor()
        }
    }

    fun selectComment(commentId: String) {
        CommentSelectionService.getInstance(project).select(commentId)
        scrollToComment(commentId)
    }

    fun refresh() {
        val service = CommentService.getInstance(project)
        threads = service.allThreads().sortedWith(
            compareByDescending<ReviewThread> { it.updatedAt }.thenBy { it.file }.thenBy { it.startLine }
        )

        val selectedId = CommentSelectionService.getInstance(project).currentSelection()
        if (selectedId != null && service.find(selectedId) == null) {
            CommentSelectionService.getInstance(project).select(null)
        }

        val active = editingState
        if (active is InlineEditState.EditComment && service.find(active.commentId) == null) {
            editingState = null
        }
        if (active is InlineEditState.Reply && service.findThread(active.threadId) == null) {
            editingState = null
        }

        renderCards()
    }

    private fun renderCards() {
        threadViews.clear()
        transientDraftCard = null
        cardsContainer.removeAll()

        val activeEdit = editingState
        if (activeEdit is InlineEditState.NewThread) {
            transientDraftCard = createDraftCard(activeEdit)
            cardsContainer.add(transientDraftCard!!.root)
            if (threads.isNotEmpty()) cardsContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        if (threads.isEmpty() && activeEdit !is InlineEditState.NewThread) {
            cardsContainer.add(emptyState)
        } else {
            threads.forEachIndexed { index, thread ->
                val card = createThreadCard(thread)
                threadViews[thread.id] = card
                cardsContainer.add(card.root)
                if (index != threads.lastIndex) {
                    cardsContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
            }
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
        scrollToComment(CommentSelectionService.getInstance(project).currentSelection())
        if (activeEdit is InlineEditState.NewThread) scrollToTransientDraft()
    }

    private fun createDraftCard(editState: InlineEditState.NewThread): ThreadCardView {
        val titleLabel = headerTitle(fileNameOf(editState.draft.file), editState.draft.file)
        val locationLabel = locationLabel(editState.draft.file, lineRange(editState.draft.startLine, editState.draft.endLine))
        val infoRow = metaOnlyRow("Local draft")
        val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(locationLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(infoRow)
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(editorBlock(
                initialText = editState.text,
                placeholder = "New comment",
                onTextChanged = { updated -> editState.text = updated },
                onSave = { saveNewThread(editState) },
                onCancel = { cancelEditing() }
            ))
        }
        return wrapCard(
            threadId = "__draft__",
            content = content,
            isSelected = true,
            isFlashing = false,
            onOpen = { navigateToDraft(editState.draft) }
        )
    }

    private fun createThreadCard(thread: ReviewThread): ThreadCardView {
        val rootComment = rootComment(thread) ?: return wrapCard(
            threadId = thread.id,
            content = JBLabel("Empty thread"),
            isSelected = false,
            isFlashing = false,
            onOpen = {}
        )
        val selectedCommentId = CommentSelectionService.getInstance(project).currentSelection()
        val isSelected = thread.comments.any { it.id == selectedCommentId }
        val isFlashing = flashOn && thread.comments.any { it.id == flashingCommentId }

        val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerTitle(fileNameOf(thread.file), thread.file))
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(locationLabel(thread.file, lineRange(thread.startLine, thread.endLine)))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(threadInfoRow(thread))
            add(Box.createVerticalStrut(JBUI.scale(10)))
        }

        thread.comments.forEach { comment ->
            content.add(createCommentBlock(thread, comment))
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        if (thread.outdated) {
            content.add(statusRowLabel("This thread is outdated in the current diff."))
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        if (editingState is InlineEditState.Reply && (editingState as InlineEditState.Reply).threadId == thread.id) {
            val state = editingState as InlineEditState.Reply
            content.add(editorBlock(
                initialText = state.text,
                placeholder = "Reply",
                onTextChanged = { updated -> state.text = updated },
                onSave = { saveReply(state) },
                onCancel = { cancelEditing() }
            ))
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        content.add(threadActions(thread))

        return wrapCard(
            threadId = thread.id,
            content = content,
            isSelected = isSelected,
            isFlashing = isFlashing,
            onOpen = {
                selectComment(selectedCommentId.takeIf { id -> thread.comments.any { it.id == id } } ?: rootComment.id)
                navigateTo(rootComment, requestFocusComment = false)
            },
            onDoubleOpen = {
                if (canModify(rootComment)) beginEdit(rootComment.id)
            }
        )
    }

    private fun createCommentBlock(thread: ReviewThread, comment: ReviewComment): JComponent {
        val isEditing = editingState is InlineEditState.EditComment &&
            (editingState as InlineEditState.EditComment).commentId == comment.id
        val canModify = canModify(comment)

        val block = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val meta = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(buildCommentMeta(comment)).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = uiFont(10f)
            })
            if (canModify) {
                add(commentAction("Edit") { beginEdit(comment.id) })
                add(commentAction("Delete") { deleteComment(comment) })
            }
        }
        block.add(meta)
        block.add(Box.createVerticalStrut(JBUI.scale(4)))

        if (isEditing) {
            val state = editingState as InlineEditState.EditComment
            block.add(editorBlock(
                initialText = state.text,
                placeholder = "Edit comment",
                onTextChanged = { updated -> state.text = updated },
                onSave = { saveExistingEdit(state) },
                onCancel = { cancelEditing() }
            ))
        } else {
            block.add(JBTextArea(comment.content).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                border = null
                font = bodyFont()
                foreground = UIUtil.getLabelForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        val clickTarget = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                selectComment(comment.id)
                navigateTo(comment, requestFocusComment = false)
                if (e.clickCount == 2 && canModify) beginEdit(comment.id)
            }
        }
        markClickOwned(block)
        installClickTarget(block, clickTarget)
        return block
    }

    private fun editorBlock(
        initialText: String,
        placeholder: String,
        onTextChanged: (String) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit
    ): JComponent {
        val editor = JBTextArea(initialText).apply {
            lineWrap = true
            wrapStyleWord = true
            font = bodyFont()
            foreground = UIUtil.getLabelForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8)
            rows = 4
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = placeholder
        }
        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
            private fun update() {
                onTextChanged(editor.text)
                saveButton.isEnabled = editor.text.trim().isNotEmpty()
            }
        })
        saveButton.isEnabled = initialText.trim().isNotEmpty()
        saveButton.addActionListener { onSave() }
        cancelButton.addActionListener { onCancel() }

        return JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBScrollPane(editor).apply {
                border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                preferredSize = Dimension(0, JBUI.scale(120))
            }, BorderLayout.CENTER)
            add(JPanel(HorizontalLayout(JBUI.scale(8))).apply {
                isOpaque = false
                add(saveButton)
                add(cancelButton)
            }, BorderLayout.SOUTH)
        }
    }

    private fun threadActions(thread: ReviewThread): JComponent =
        JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(12))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(cardAction("Reply") { beginReply(thread.id) })
            add(cardAction(if (thread.status == "resolved") "Reopen" else "Resolve") { toggleResolved(thread) })
            if (canDeleteThread(thread)) {
                add(cardAction("Delete") { deleteThread(thread) })
            }
        }

    private fun headerTitle(text: String, tooltip: String): JBLabel =
        JBLabel(text).apply {
            foreground = UIUtil.getLabelForeground()
            font = uiFont(11f, bold = true)
            toolTipText = tooltip
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun locationLabel(path: String, lines: String): JBLabel =
        JBLabel(buildLocationLabel(path, lines)).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            toolTipText = "$path:$lines"
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun threadInfoRow(thread: ReviewThread): JComponent {
        val row = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            syncStatusBadge(thread.syncStatus)?.let(::add)
            if (thread.status == "resolved") {
                add(infoBadge("Resolved", Color(0x5E9E78)))
            }
            if (thread.outdated) {
                add(infoBadge("Outdated", Color(0xC9913A)))
            }
            thread.findingId?.takeIf { it.isNotBlank() }?.let { add(infoBadge(it, Color(0x6B8AD1))) }
        }
        return row
    }

    private fun metaOnlyRow(text: String): JComponent =
        JBPanel<JBPanel<*>>(HorizontalLayout(0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(statusRowLabel(text))
        }

    private fun statusRowLabel(text: String): JBLabel =
        JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = uiFont(10f)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun syncStatusBadge(syncStatus: String): JComponent? {
        val label = when (syncStatus) {
            "local_only" -> "Local"
            "modified" -> "Modified"
            "delete_pending" -> "Delete pending"
            "synced" -> "Synced"
            else -> syncStatus.takeIf { it.isNotBlank() }?.replace('_', ' ')
        } ?: return null
        val accent = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: UIUtil.getFocusedFillColor()
        val tone = when (syncStatus) {
            "delete_pending" -> Color(0xD06565)
            "modified" -> Color(0xC9913A)
            "synced" -> Color(0x5E9E78)
            else -> mix(accent, Color(0x7F8FA6), 0.55f)
        }
        return infoBadge(label, tone)
    }

    private fun infoBadge(text: String, tone: Color): JComponent {
        val foreground = mix(UIUtil.getLabelForeground(), tone, 0.42f)
        val background = mix(UIUtil.getPanelBackground(), tone, 0.08f)
        return JBLabel(text).apply {
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

    private fun wrapCard(
        threadId: String,
        content: JComponent,
        isSelected: Boolean,
        isFlashing: Boolean,
        onOpen: () -> Unit,
        onDoubleOpen: (() -> Unit)? = null
    ): ThreadCardView {
        val stripe = JBPanel<JBPanel<*>>().apply {
            preferredSize = Dimension(JBUI.scale(4), 0)
            minimumSize = Dimension(JBUI.scale(4), 0)
            maximumSize = Dimension(JBUI.scale(4), Int.MAX_VALUE)
        }
        val root = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = true
            add(stripe, BorderLayout.WEST)
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(12)
                add(content, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val view = ThreadCardView(root, stripe, threadId)
        applyCardStyle(view, isSelected, isFlashing)
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                onOpen()
                if (e.clickCount == 2) {
                    onDoubleOpen?.invoke()
                }
            }
        }
        installClickTarget(root, listener, skipOwnedSubtrees = true)
        return view
    }

    private fun applyCardStyle(view: ThreadCardView, isSelected: Boolean, isFlashing: Boolean) {
        val base = UIUtil.getPanelBackground()
        val accent = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: UIUtil.getFocusedFillColor()
        val background = when {
            isFlashing -> mix(base, accent, 0.16f)
            isSelected -> mix(base, accent, 0.10f)
            else -> elevate(base, 0.035f)
        }
        val borderColor = when {
            isFlashing -> accent
            isSelected -> mix(base, accent, 0.60f)
            else -> JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        }
        view.root.background = background
        view.root.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(borderColor, if (isSelected || isFlashing) 2 else 1),
            JBUI.Borders.empty()
        )
        view.stripe.background = if (isSelected || isFlashing) accent else mix(base, borderColor, 0.14f)
        view.stripe.isOpaque = true
    }

    private fun syncCardSelection(commentId: String?) {
        threadViews.forEach { (threadId, view) ->
            val thread = threads.firstOrNull { it.id == threadId }
            val isSelected = thread?.comments?.any { it.id == commentId } == true
            val isFlashing = flashOn && thread?.comments?.any { it.id == flashingCommentId } == true
            applyCardStyle(view, isSelected, isFlashing)
        }
        transientDraftCard?.let { applyCardStyle(it, editingState is InlineEditState.NewThread, false) }
        scrollToComment(commentId)
    }

    private fun scrollToComment(commentId: String?) {
        if (commentId == null) return
        val threadId = CommentService.getInstance(project).findThreadByCommentId(commentId)?.id ?: return
        val card = threadViews[threadId]?.root ?: return
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
        val target = transientDraftCard?.root
            ?: when (val active = editingState) {
                is InlineEditState.EditComment -> threadViews[active.threadId]?.root
                is InlineEditState.Reply -> threadViews[active.threadId]?.root
                else -> null
            }
            ?: return
        SwingUtilities.invokeLater {
            findFirstTextArea(target)?.requestFocusInWindow()
        }
    }

    private fun findFirstTextArea(component: JComponent): JTextArea? {
        if (component is JTextArea) return component
        component.components.forEach { child ->
            when (child) {
                is JTextArea -> return child
                is JComponent -> findFirstTextArea(child)?.let { return it }
            }
        }
        return null
    }

    private fun beginReply(threadId: String) {
        val current = editingState
        if (current is InlineEditState.Reply && current.threadId == threadId) {
            focusActiveEditor()
            return
        }
        finishActiveEditBefore {
            editingState = InlineEditState.Reply(threadId, "")
            renderCards()
            threadViews[threadId]?.root?.let { cardsContainer.scrollRectToVisible(it.bounds) }
            focusActiveEditor()
        }
    }

    private fun saveNewThread(editState: InlineEditState.NewThread): Boolean {
        val text = editState.text.trim()
        if (text.isBlank()) return false
        val created = CommentService.getInstance(project).addThread(
            file = editState.draft.file,
            startLine = editState.draft.startLine,
            endLine = editState.draft.endLine,
            codeSnippet = editState.draft.codeSnippet,
            comment = text,
            reviewTargetRef = editState.draft.reviewTargetRef,
            diffBaseCommit = editState.draft.diffBaseCommit,
            diffHeadCommit = editState.draft.diffHeadCommit,
            diffOldPath = editState.draft.diffOldPath,
            diffNewPath = editState.draft.diffNewPath
        )
        editingState = null
        val root = rootComment(created) ?: return true
        CommentSelectionService.getInstance(project).select(root.id)
        refresh()
        startFlash(root.id)
        return true
    }

    private fun saveReply(editState: InlineEditState.Reply): Boolean {
        val text = editState.text.trim()
        if (text.isBlank()) return false
        val reply = CommentService.getInstance(project).addReply(editState.threadId, text) ?: return false
        editingState = null
        CommentSelectionService.getInstance(project).select(reply.id)
        refresh()
        startFlash(reply.id)
        return true
    }

    private fun saveExistingEdit(editState: InlineEditState.EditComment): Boolean {
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
        val active = editingState ?: run {
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
                    is InlineEditState.NewThread -> saveNewThread(active)
                    is InlineEditState.Reply -> saveReply(active)
                    is InlineEditState.EditComment -> saveExistingEdit(active)
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
            is InlineEditState.NewThread -> active.text.trim().isNotEmpty()
            is InlineEditState.Reply -> active.text.trim().isNotEmpty()
            is InlineEditState.EditComment -> active.text != active.originalText
        }

    private fun navigateToDraft(draft: CommentDraft) {
        if (MrReviewService.getInstance(project).navigateToDraft(draft)) return
        val target = ProjectFiles.find(project, draft.file) ?: run {
            CommentNotifications.warn(project, "Comment Target Missing", "Cannot find `${draft.file}` in the current project.")
            return
        }
        val lineIndex = resolveLineIndex(target, draft.startLine) ?: run {
            CommentNotifications.warn(project, "Comment Target Outdated", "`${draft.file}` no longer has line ${draft.startLine}.")
            return
        }
        OpenFileDescriptor(project, target, lineIndex, 0).navigate(true)
    }

    private fun navigateTo(comment: ReviewComment, requestFocusComment: Boolean) {
        if (!comment.outdated && MrReviewService.getInstance(project).navigateToComment(comment)) {
            if (requestFocusComment) selectComment(comment.id)
            return
        }
        val target = ProjectFiles.find(project, comment.file) ?: run {
            CommentNotifications.warn(project, "Comment Target Missing", "Cannot find `${comment.file}` in the current project.")
            return
        }
        val lineIndex = resolveLineIndex(target, comment.startLine) ?: run {
            CommentNotifications.warn(project, "Comment Target Outdated", "`${comment.file}` no longer has line ${comment.startLine}.")
            return
        }
        OpenFileDescriptor(project, target, lineIndex, 0).navigate(true)
        if (requestFocusComment) selectComment(comment.id)
    }

    private fun resolveLineIndex(target: VirtualFile, oneBasedLine: Int): Int? {
        val document = FileDocumentManager.getInstance().getDocument(target) ?: return if (oneBasedLine > 0) oneBasedLine - 1 else 0
        if (document.lineCount <= 0) return 0
        val requested = oneBasedLine - 1
        return if (requested in 0 until document.lineCount) requested else null
    }

    private fun toggleResolved(thread: ReviewThread) {
        val next = if (thread.status == "resolved") "open" else "resolved"
        CommentService.getInstance(project).updateThreadStatus(thread.id, next)
        refresh()
    }

    private fun deleteThread(thread: ReviewThread) {
        if (Messages.YES != Messages.showYesNoDialog(project, "Delete this thread?", "Confirm", null)) return
        CommentService.getInstance(project).deleteThread(thread.id)
        val selected = CommentSelectionService.getInstance(project).currentSelection()
        if (selected != null && thread.comments.any { it.id == selected }) {
            CommentSelectionService.getInstance(project).select(null)
        }
        refresh()
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
        refresh()
    }

    private fun rootComment(thread: ReviewThread): ReviewComment? =
        thread.comments.firstOrNull()

    private fun canDeleteThread(thread: ReviewThread): Boolean =
        rootComment(thread)?.let(::canModify) == true

    private fun canModify(comment: ReviewComment): Boolean {
        comment.createdByUsername?.takeIf { it.isNotBlank() }?.let {
            // Username-based matching can be added later once the local git identity exposes it.
        }
        val author = comment.createdByName?.takeIf { it.isNotBlank() } ?: return comment.remoteCommentId == null
        return GitUserProvider.matchesCurrentUser(project, author)
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
        threadViews.forEach { (threadId, view) ->
            val thread = threads.firstOrNull { it.id == threadId }
            val isSelected = thread?.comments?.any { it.id == selectedId } == true
            val isFlashing = flashOn && thread?.comments?.any { it.id == flashingCommentId } == true
            applyCardStyle(view, isSelected, isFlashing)
        }
        transientDraftCard?.let { applyCardStyle(it, editingState is InlineEditState.NewThread, false) }
    }

    private fun buildCommentMeta(comment: ReviewComment): String {
        val author = comment.createdByName?.takeIf { it.isNotBlank() } ?: "Unknown"
        return "$author • ${formatTimestamp(comment.updatedAt)}"
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

    private fun bodyFont(): Font {
        val editorFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        val reducedSize = (editorFont.size2D - 1f).coerceAtLeast(11f)
        return editorFont.deriveFont(reducedSize)
    }

    private fun cardAction(text: String, action: () -> Unit): ActionLink =
        ActionLink(text) { action() }.apply { font = uiFont(10f) }

    private fun commentAction(text: String, action: () -> Unit): ActionLink =
        ActionLink(text) { action() }.apply { font = uiFont(10f) }

    private fun installClickTarget(component: Component, listener: MouseAdapter) {
        installClickTarget(component, listener, skipOwnedSubtrees = false)
    }

    private fun installClickTarget(component: Component, listener: MouseAdapter, skipOwnedSubtrees: Boolean) {
        if (component is JButton || component is ActionLink || component is JTextArea) {
            return
        }
        if (skipOwnedSubtrees && component is JComponent && component.getClientProperty(CLICK_OWNED_CLIENT_PROPERTY) == true) {
            return
        }
        component.addMouseListener(listener)
        if (component is JComponent) {
            component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            component.components.forEach { child ->
                installClickTarget(child, listener, skipOwnedSubtrees)
            }
        }
    }

    private fun markClickOwned(component: JComponent) {
        component.putClientProperty(CLICK_OWNED_CLIENT_PROPERTY, true)
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
        private const val CLICK_OWNED_CLIENT_PROPERTY = "code.review.click.owned"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private sealed interface InlineEditState {
        var text: String

        data class NewThread(val draft: CommentDraft, override var text: String) : InlineEditState

        data class Reply(val threadId: String, override var text: String) : InlineEditState

        data class EditComment(
            val threadId: String,
            val commentId: String,
            val originalText: String,
            override var text: String
        ) : InlineEditState
    }

    private data class ThreadCardView(
        val root: JComponent,
        val stripe: JComponent,
        val threadId: String
    )
}
