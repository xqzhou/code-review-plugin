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
import com.intellij.openapi.wm.IdeFocusManager
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
import java.awt.GridLayout
import java.awt.Image
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultEditorKit

class CommentsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var threads: List<ReviewThread> = emptyList()
    private var editingState: InlineEditState? = null
    private val threadViews = linkedMapOf<String, ThreadCardView>()
    private var transientDraftCard: ThreadCardView? = null
    private var flashingCommentId: String? = null
    private var flashOn = false
    private var flashTimer: Timer? = null
    private val commentViews = mutableMapOf<String, JComponent>()

    private val cardsContainer = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
        isOpaque = true
        background = panelSurface()
    }
    private val cardsScrollPane = JBScrollPane(cardsContainer).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        viewport.background = panelSurface()
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
            isOpaque = true
            background = panelSurface()
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = true
                background = panelSurface()
                border = JBUI.Borders.empty(10, 12, 6, 12)
                add(JBLabel("Comments").apply {
                    isOpaque = false
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(cardsScrollPane, BorderLayout.CENTER)
        }

    fun beginDraft(draft: CommentDraft) {
        finishActiveEditBefore {
            editingState = InlineEditState.NewThread(draft, draft.initialComment)
            CommentSelectionService.getInstance(project).select(null)
            renderCards()
            scrollToTransientDraft()
            focusActiveEditor()
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
        selectComment(commentId, null)
    }

    fun selectComment(commentId: String, alignmentFraction: Double?) {
        CommentSelectionService.getInstance(project).select(commentId)
        scrollToComment(commentId, alignmentFraction)
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
        commentViews.clear()
        transientDraftCard = null
        cardsContainer.removeAll()
        cardsContainer.background = panelSurface()
        cardsScrollPane.viewport.background = panelSurface()

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
        val locationLabel = locationLabel(
            editState.draft.file,
            locationRange(
                editState.draft.startLine,
                editState.draft.endLine,
                editState.draft.startColumn,
                editState.draft.endColumn
            )
        )
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
            add(threadHeader(thread, rootComment))
            add(Box.createVerticalStrut(JBUI.scale(10)))
        }

        thread.comments.forEachIndexed { index, comment ->
            content.add(createCommentBlock(thread, comment, isReplyComment = index > 0))
            content.add(Box.createVerticalStrut(if (index == thread.comments.lastIndex) JBUI.scale(10) else JBUI.scale(12)))
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

        val isReplying = editingState is InlineEditState.Reply && (editingState as InlineEditState.Reply).threadId == thread.id
        if (!isReplying) {
            content.add(threadActions(thread))
        }

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

    private fun createCommentBlock(thread: ReviewThread, comment: ReviewComment, isReplyComment: Boolean): JComponent {
        val isEditing = editingState is InlineEditState.EditComment &&
            (editingState as InlineEditState.EditComment).commentId == comment.id
        val canModify = canModify(comment)

        val block = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        if (isReplyComment) {
            block.border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(replyThreadLineColor(), 0, 0, 0, 2),
                JBUI.Borders.empty(0, 10, 0, 0)
            )
        }

        val meta = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(buildCommentMeta(comment)).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = uiFont(10f)
            })
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
            block.add(commentContent(comment.content))
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
        commentViews[comment.id] = block
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
            background = inputSurface()
            border = JBUI.Borders.empty(8)
            rows = 4
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = placeholder
        }
        installImageTransferSupport(editor)
        installImagePasteShortcut(editor)
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
            add(JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
                isOpaque = false
                add(JBScrollPane(editor).apply {
                    border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                    preferredSize = Dimension(0, JBUI.scale(120))
                }, BorderLayout.CENTER)
                add(liveImagePreview(editor), BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(HorizontalLayout(JBUI.scale(8))).apply {
                    isOpaque = false
                    add(saveButton)
                    add(cancelButton)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }

    private fun liveImagePreview(editor: JTextArea): JComponent {
        val gap = JBUI.scale(5)
        val thumbnailWidth = JBUI.scale(96)
        val thumbnailHeight = JBUI.scale(72)
        val preview = JBPanel<JBPanel<*>>().apply {
            layout = GridLayout(0, 1, gap, gap)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
            border = JBUI.Borders.emptyTop(gap)
        }

        fun updateGridColumns(imageCount: Int) {
            val availableWidth = (editor.parent?.width ?: editor.width).takeIf { it > 0 } ?: thumbnailWidth
            val columns = ((availableWidth + gap) / (thumbnailWidth + gap)).coerceAtLeast(1).coerceAtMost(imageCount.coerceAtLeast(1))
            preview.layout = GridLayout(0, columns, gap, gap)
        }

        fun refreshPreview() {
            preview.removeAll()
            val matches = IMAGE_MARKDOWN.findAll(editor.text).toList()
            updateGridColumns(matches.size)
            matches.forEach { match ->
                preview.add(previewImageTile(match.groupValues[1], thumbnailWidth, thumbnailHeight))
            }
            preview.isVisible = preview.componentCount > 0
            preview.revalidate()
            preview.repaint()
        }

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshPreview()
            override fun removeUpdate(e: DocumentEvent) = refreshPreview()
            override fun changedUpdate(e: DocumentEvent) = refreshPreview()
        })
        editor.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = refreshPreview()
            override fun componentShown(e: ComponentEvent) = refreshPreview()
        })
        refreshPreview()
        return preview
    }

    private fun previewImageTile(path: String, width: Int, height: Int): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            markClickOwned(this)
            isOpaque = true
            background = inputSurface()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to preview"
            border = BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            preferredSize = Dimension(width, height)
            minimumSize = Dimension(width, height)
            val openPreview = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) showImagePreview(path)
                }
            }
            addMouseListener(openPreview)
            add(commentImage(path, maxWidth = width, maxHeight = height).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(openPreview)
            }, BorderLayout.CENTER)
        }

    private fun commentContent(content: String): JComponent {
        val matches = IMAGE_MARKDOWN.findAll(content).toList()
        if (matches.isEmpty()) return commentText(content)

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            var offset = 0
            matches.forEach { match ->
                val textBefore = content.substring(offset, match.range.first)
                if (textBefore.isNotBlank()) {
                    add(commentText(textBefore.trimEnd()))
                    add(Box.createVerticalStrut(JBUI.scale(6)))
                }
                add(commentImage(match.groupValues[1]))
                add(Box.createVerticalStrut(JBUI.scale(6)))
                offset = match.range.last + 1
            }

            val trailingText = content.substring(offset)
            if (trailingText.isNotBlank()) {
                add(commentText(trailingText.trimStart()))
            }
        }
    }

    private fun commentText(text: String): JComponent =
        JBTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            font = bodyFont()
            foreground = UIUtil.getLabelForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun commentImage(path: String, maxWidth: Int = JBUI.scale(360), maxHeight: Int = JBUI.scale(260)): JComponent {
        val file = CommentImageSupport.resolve(project, path)
        val icon = file?.takeIf { it.exists() }?.let { scaledImageIcon(it, maxWidth, maxHeight) }
        if (icon == null) {
            return JBLabel("![image]($path)").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = bodyFont()
                alignmentX = Component.LEFT_ALIGNMENT
                toolTipText = path
            }
        }
        return JBLabel(icon).apply {
            markClickOwned(this)
            horizontalAlignment = JBLabel.CENTER
            verticalAlignment = JBLabel.CENTER
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = file.absolutePath
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) showImagePreview(path)
                }
            })
        }
    }

    private fun showImagePreview(path: String) {
        val file = CommentImageSupport.resolve(project, path)?.takeIf { it.exists() } ?: return
        val icon = scaledImageIcon(file, JBUI.scale(860), JBUI.scale(640)) ?: return
        val previewSize = Dimension(
            minOf(icon.iconWidth + JBUI.scale(32), JBUI.scale(900)),
            minOf(icon.iconHeight + JBUI.scale(32), JBUI.scale(680))
        )
        JOptionPane.showMessageDialog(
            this,
            JBScrollPane(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                preferredSize = previewSize
                isOpaque = true
                background = inputSurface()
                add(JBLabel(icon).apply {
                    horizontalAlignment = JBLabel.CENTER
                    verticalAlignment = JBLabel.CENTER
                }, BorderLayout.CENTER)
            }).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            },
            file.name,
            JOptionPane.PLAIN_MESSAGE
        )
    }

    private fun scaledImageIcon(file: File, maxWidth: Int, maxHeight: Int): ImageIcon? {
        val icon = ImageIcon(file.absolutePath)
        if (icon.iconWidth <= 0 || icon.iconHeight <= 0) return null
        val scale = minOf(1.0, maxWidth.toDouble() / icon.iconWidth, maxHeight.toDouble() / icon.iconHeight)
        if (scale >= 1.0) return icon
        val width = (icon.iconWidth * scale).toInt().coerceAtLeast(1)
        val height = (icon.iconHeight * scale).toInt().coerceAtLeast(1)
        return ImageIcon(icon.image.getScaledInstance(width, height, Image.SCALE_SMOOTH))
    }

    private fun installImageTransferSupport(editor: JTextArea) {
        val fallback = editor.transferHandler
        editor.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                hasImageFlavor(support.transferable) ||
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    fallback?.canImport(support) == true

            override fun importData(support: TransferSupport): Boolean {
                if (importImageData(editor, support)) return true
                return fallback?.importData(support) == true
            }
        }
    }

    private fun installImagePasteShortcut(editor: JTextArea) {
        val pasteActionName = "code.review.paste.image.or.text"
        val fallbackPasteAction = editor.actionMap.get(DefaultEditorKit.pasteAction)
        editor.actionMap.put(pasteActionName, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                if (pasteImageFromClipboard(editor, reportFailure = true)) return
                fallbackPasteAction?.actionPerformed(event) ?: editor.paste()
            }
        })
        editor.actionMap.put(DefaultEditorKit.pasteAction, editor.actionMap.get(pasteActionName))

        val menuShortcutMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        editor.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuShortcutMask), pasteActionName)
        editor.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), pasteActionName)
        editor.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), pasteActionName)
        installFocusedPasteDispatcher(editor)
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode != KeyEvent.VK_V) return
                val pasteModifier = event.isMetaDown || event.isControlDown
                if (!pasteModifier) return
                if (pasteImageFromClipboard(editor, reportFailure = true)) {
                    event.consume()
                }
            }
        })
    }

    private fun installFocusedPasteDispatcher(editor: JTextArea) {
        val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        var installed = false
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || !editor.isFocusOwner) return@KeyEventDispatcher false
            if (event.keyCode != KeyEvent.VK_V) return@KeyEventDispatcher false
            if (!event.isMetaDown && !event.isControlDown) return@KeyEventDispatcher false
            pasteImageFromClipboard(editor, reportFailure = true)
        }

        fun setInstalled(value: Boolean) {
            if (installed == value) return
            installed = value
            if (value) {
                keyboardFocusManager.addKeyEventDispatcher(dispatcher)
            } else {
                keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
            }
        }

        editor.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = setInstalled(true)
            override fun focusLost(event: FocusEvent) = setInstalled(false)
        })
        editor.addHierarchyListener {
            if (!editor.isDisplayable) setInstalled(false)
        }
    }

    private fun pasteImageFromClipboard(editor: JTextArea, reportFailure: Boolean = false): Boolean =
        runCatching {
            val transferables = clipboardTransferables()
            val pasted = transferables.any { insertImagesFromTransferable(editor, it) }
            if (!pasted && reportFailure && transferables.any(::hasImageSourceFlavor)) {
                CommentNotifications.warn(
                    project,
                    "Image Paste Not Detected",
                    "Clipboard flavors: ${clipboardFlavorSummary(transferables)}"
                )
            }
            pasted
        }.getOrElse {
            if (reportFailure) {
                CommentNotifications.warn(project, "Image Paste Failed", it.message ?: "Could not read the clipboard image.")
            }
            false
        }

    private fun clipboardTransferables(): List<Transferable> =
        buildList {
            CopyPasteManager.getInstance().contents?.let(::add)
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
                .getOrNull()
                ?.takeIf { systemClipboard ->
                    none { existing -> sameFlavorSet(existing, systemClipboard) }
                }
                ?.let(::add)
        }

    private fun sameFlavorSet(left: Transferable, right: Transferable): Boolean =
        left.transferDataFlavors.map { it.mimeType }.toSet() == right.transferDataFlavors.map { it.mimeType }.toSet()

    private fun importImageData(editor: JTextArea, support: TransferHandler.TransferSupport): Boolean =
        runCatching {
            insertImagesFromTransferable(editor, support.transferable)
        }.getOrElse {
            CommentNotifications.warn(project, "Image Attach Failed", it.message ?: "Could not attach the selected image.")
            false
        }

    private fun insertImagesFromTransferable(editor: JTextArea, transferable: Transferable): Boolean {
        imageFromTransferable(transferable)?.let { image ->
            insertImageMarkdown(editor, CommentImageSupport.saveClipboardImage(project, image))
            return true
        }

        htmlImagesFromTransferable(transferable).forEach { path ->
            insertImageMarkdown(editor, path)
            return true
        }

        val imageFiles = imageFilesFromTransferable(transferable)
        if (imageFiles.isEmpty()) return false
        imageFiles.forEach { file ->
            insertImageMarkdown(editor, CommentImageSupport.copyImageFile(project, file), file.nameWithoutExtension)
        }
        return true
    }

    private fun imageFromTransferable(transferable: Transferable): Image? {
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            (transferable.getTransferData(DataFlavor.imageFlavor) as? Image)?.let { return it }
        }

        transferable.transferDataFlavors
            .filter(::isImageDataFlavor)
            .sortedBy(::imageFlavorPriority)
            .forEach { flavor ->
                val data = runCatching { transferable.getTransferData(flavor) }.getOrNull()
                runCatching { imageFromTransferData(data) }.getOrNull()?.let { return it }
            }
        return null
    }

    private fun isImageDataFlavor(flavor: DataFlavor): Boolean {
        val mimeType = flavor.mimeType
        return flavor.primaryType.equals("image", ignoreCase = true) ||
            mimeType.contains("image/", ignoreCase = true) ||
            mimeType.contains("png", ignoreCase = true) ||
            mimeType.contains("jpeg", ignoreCase = true) ||
            mimeType.contains("jpg", ignoreCase = true) ||
            mimeType.contains("tiff", ignoreCase = true)
    }

    private fun imageFlavorPriority(flavor: DataFlavor): Int =
        when {
            flavor == DataFlavor.imageFlavor -> 0
            flavor.mimeType.contains("png", ignoreCase = true) -> 1
            flavor.mimeType.contains("jpeg", ignoreCase = true) -> 2
            flavor.mimeType.contains("jpg", ignoreCase = true) -> 2
            flavor.mimeType.contains("tiff", ignoreCase = true) -> 3
            else -> 4
        }

    private fun imageFromTransferData(data: Any?): Image? =
        when (data) {
            is Image -> data
            is InputStream -> imageFromBytes(data.readBytesAndClose())
            is ByteArray -> imageFromBytes(data)
            is ByteBuffer -> imageFromBytes(data.toByteArray())
            else -> null
        }

    private fun imageFromBytes(bytes: ByteArray): Image? {
        if (bytes.isEmpty()) return null
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let { return it }
        return ImageIcon(bytes).takeIf { it.iconWidth > 0 && it.iconHeight > 0 }?.image
    }

    private fun InputStream.readBytesAndClose(): ByteArray =
        use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toByteArray()
        }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = slice()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun htmlImagesFromTransferable(transferable: Transferable): List<String> {
        val html = htmlFromTransferable(transferable) ?: return emptyList()
        return HTML_IMAGE_SRC.findAll(html).mapNotNull { match ->
            val src = match.groupValues[2].ifBlank { match.groupValues[3] }
            when {
                src.startsWith("data:image/", ignoreCase = true) -> saveDataImage(src)
                src.startsWith("file:", ignoreCase = true) -> fileFromClipboardText(src)
                    ?.takeIf(CommentImageSupport::isSupportedImage)
                    ?.let { CommentImageSupport.copyImageFile(project, it) }
                src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true) -> src
                else -> fileFromClipboardText(src)
                    ?.takeIf(CommentImageSupport::isSupportedImage)
                    ?.let { CommentImageSupport.copyImageFile(project, it) }
            }
        }.toList()
    }

    private fun htmlFromTransferable(transferable: Transferable): String? {
        val htmlFlavor = listOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor)
            .firstOrNull(transferable::isDataFlavorSupported)
            ?: transferable.transferDataFlavors.firstOrNull { flavor ->
                flavor.subType.equals("html", ignoreCase = true) ||
                    flavor.mimeType.contains("html", ignoreCase = true)
            }
        val data = htmlFlavor?.let { runCatching { transferable.getTransferData(it) }.getOrNull() } ?: return null
        return when (data) {
            is String -> data
            is Reader -> data.readText()
            is InputStream -> data.bufferedReader().readText()
            else -> data.toString()
        }
    }

    private fun saveDataImage(src: String): String? =
        runCatching {
            val comma = src.indexOf(',')
            if (comma < 0) return null
            val metadata = src.substring(0, comma)
            if (!metadata.contains(";base64", ignoreCase = true)) return null
            val bytes = Base64.getDecoder().decode(src.substring(comma + 1))
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            CommentImageSupport.saveBufferedImage(project, image)
        }.getOrNull()

    private fun imageFilesFromTransferable(transferable: Transferable): List<File> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            return files.orEmpty().filterIsInstance<File>().filter(CommentImageSupport::isSupportedImage)
        }
        transferable.transferDataFlavors.forEach { flavor ->
            val data = runCatching { transferable.getTransferData(flavor) }.getOrNull()
            when (data) {
                is File -> return listOf(data).filter(CommentImageSupport::isSupportedImage)
                is URL -> return listOfNotNull(fileFromClipboardText(data.toString()))
                    .filter(CommentImageSupport::isSupportedImage)
                is URI -> return listOfNotNull(fileFromClipboardText(data.toString()))
                    .filter(CommentImageSupport::isSupportedImage)
            }
        }
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return emptyList()
            return text.lineSequence()
                .mapNotNull { line -> fileFromClipboardText(line.trim()) }
                .filter(CommentImageSupport::isSupportedImage)
                .toList()
        }
        return emptyList()
    }

    private fun fileFromClipboardText(text: String): File? {
        if (text.isBlank() || text.startsWith("#")) return null
        return runCatching {
            when {
                text.startsWith("file:") -> File(URI(text))
                else -> File(text)
            }
        }.getOrNull()?.takeIf { it.exists() }
    }

    private fun hasImageFlavor(transferable: Transferable): Boolean =
        transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ||
            transferable.transferDataFlavors.any(::isImageDataFlavor)

    private fun hasImageSourceFlavor(transferable: Transferable): Boolean =
        hasImageFlavor(transferable) ||
            transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
            runCatching { htmlFromTransferable(transferable) }.getOrNull()?.contains("<img", ignoreCase = true) == true

    private fun clipboardFlavorSummary(transferables: List<Transferable>): String =
        transferables.flatMap { transferable ->
            transferable.transferDataFlavors.map { flavor ->
                "${flavor.mimeType}; class=${flavor.representationClass.name}"
            }
        }.distinct().joinToString(" | ").take(900).ifBlank { "none" }

    private fun insertImageMarkdown(editor: JTextArea, path: String, alt: String = "image") {
        val prefix = if (editor.caretPosition > 0 && editor.text.getOrNull(editor.caretPosition - 1) != '\n') "\n" else ""
        val suffix = if (editor.text.getOrNull(editor.caretPosition) == '\n') "" else "\n"
        editor.replaceSelection("$prefix![$alt]($path)$suffix")
        editor.requestFocusInWindow()
    }

    private fun threadActions(thread: ReviewThread): JComponent =
        JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(12))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(cardAction("Reply") { beginReply(thread.id) })
        }

    private fun threadHeader(thread: ReviewThread, rootComment: ReviewComment): JComponent {
        val actions = threadMenuActions(thread, rootComment)
        return JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(headerTitle(fileNameOf(thread.file), thread.file))
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(locationLabel(thread.file, locationRange(thread.startLine, thread.endLine, thread.startColumn, thread.endColumn)))
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(threadInfoRow(thread))
            }, BorderLayout.CENTER)
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(moreActionsButton(actions), BorderLayout.NORTH)
            }, BorderLayout.EAST)
        }
    }

    private fun threadMenuActions(thread: ReviewThread, rootComment: ReviewComment): List<Pair<String, () -> Unit>> {
        val selectedId = CommentSelectionService.getInstance(project).currentSelection()
        val selectedComment = thread.comments.firstOrNull { it.id == selectedId }?.takeIf(::canModify)
        val editableComment = selectedComment ?: rootComment.takeIf(::canModify)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        editableComment?.let { comment ->
            actions.add("Edit" to { beginEdit(comment.id) })
            if (comment.id != rootComment.id) {
                actions.add("Delete Comment" to { deleteComment(comment) })
            }
        }
        actions.add((if (thread.status == "resolved") "Reopen" else "Resolve") to { toggleResolved(thread) })
        if (canDeleteThread(thread)) {
            actions.add("Delete Thread" to { deleteThread(thread) })
        }
        return actions
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
        val background = mix(cardSurface(), tone, 0.08f)
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
        val base = cardSurface()
        val accent = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: UIUtil.getFocusedFillColor()
        val background = when {
            isFlashing -> mix(base, accent, if (isDarkTheme()) 0.16f else 0.10f)
            isSelected -> mix(base, accent, if (isDarkTheme()) 0.10f else 0.06f)
            else -> base
        }
        val borderColor = when {
            isFlashing -> accent
            isSelected -> mix(base, accent, 0.60f)
            else -> cardBorder()
        }
        view.root.background = background
        view.root.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(borderColor, if (isSelected || isFlashing) 2 else 1),
            JBUI.Borders.empty()
        )
        view.stripe.background = if (isSelected || isFlashing) accent else mix(background, borderColor, 0.28f)
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
    }

    private fun scrollToComment(commentId: String?) {
        scrollToComment(commentId, null)
    }

    private fun scrollToComment(commentId: String?, alignmentFraction: Double?) {
        if (commentId == null) return
        val threadId = CommentService.getInstance(project).findThreadByCommentId(commentId)?.id ?: return
        val target = commentViews[commentId] ?: threadViews[threadId]?.root ?: return
        SwingUtilities.invokeLater {
            if (alignmentFraction == null) {
                cardsContainer.scrollRectToVisible(convertToCardsContainer(target))
            } else {
                scrollComponentToAlignment(target, alignmentFraction)
            }
        }
    }

    private fun convertToCardsContainer(component: Component): Rectangle =
        SwingUtilities.convertRectangle(component.parent, component.bounds, cardsContainer)

    private fun scrollComponentToAlignment(component: Component, alignmentFraction: Double) {
        val rect = convertToCardsContainer(component)
        val viewport = cardsScrollPane.viewport
        val fraction = alignmentFraction.coerceIn(0.0, 1.0)
        val maxY = (cardsContainer.height - viewport.height).coerceAtLeast(0)
        val y = (rect.y - ((viewport.height - rect.height) * fraction)).toInt().coerceIn(0, maxY)
        viewport.viewPosition = Point(viewport.viewPosition.x, y)
        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun scrollToTransientDraft() {
        val card = transientDraftCard?.root ?: return
        SwingUtilities.invokeLater {
            cardsContainer.scrollRectToVisible(card.bounds)
        }
    }

    private fun focusActiveEditor() {
        SwingUtilities.invokeLater {
            focusActiveEditorWithRetry()
        }
    }

    private fun focusActiveEditorWithRetry() {
        var attempts = 0
        val timer = Timer(45, null)
        timer.addActionListener {
            attempts += 1
            val editor = activeEditorTextArea()
            if (editor == null || !editor.isDisplayable) {
                if (attempts >= 8) timer.stop()
                return@addActionListener
            }
            if (attempts >= 8) {
                timer.stop()
                return@addActionListener
            }
            editor.caretPosition = editor.document.length
            IdeFocusManager.getInstance(project).requestFocus(editor, true)
            editor.requestFocusInWindow()
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner == editor) {
                timer.stop()
            }
        }
        timer.isRepeats = true
        timer.initialDelay = 0
        timer.start()
    }

    private fun activeEditorTextArea(): JTextArea? {
        val target = transientDraftCard?.root
            ?: when (val active = editingState) {
                is InlineEditState.EditComment -> threadViews[active.threadId]?.root
                is InlineEditState.Reply -> threadViews[active.threadId]?.root
                else -> null
            }
            ?: return null
        return findFirstTextArea(target)
    }

    private fun findFirstTextArea(component: JComponent): JTextArea? {
        if (component is JTextArea && component.isEditable) return component
        component.components.forEach { child ->
            when (child) {
                is JTextArea -> if (child.isEditable) return child
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
            SwingUtilities.invokeLater {
                threadViews[threadId]?.root?.let { cardsContainer.scrollRectToVisible(it.bounds) }
            }
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
            side = editState.draft.side,
            startColumn = editState.draft.startColumn,
            endColumn = editState.draft.endColumn,
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
        OpenFileDescriptor(project, target, lineIndex, columnIndex(draft.startColumn)).navigate(true)
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
        OpenFileDescriptor(project, target, lineIndex, columnIndex(comment.startColumn)).navigate(true)
        if (requestFocusComment) selectComment(comment.id)
    }

    private fun columnIndex(oneBasedColumn: Int?): Int =
        ((oneBasedColumn ?: 1) - 1).coerceAtLeast(0)

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
        if (comment.remoteCommentId == null) return true
        comment.createdByUsername?.takeIf { it.isNotBlank() }?.let {
            // Username-based matching can be added later once the local git identity exposes it.
        }
        val author = comment.createdByName?.takeIf { it.isNotBlank() } ?: return false
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

    private fun locationRange(startLine: Int, endLine: Int, startColumn: Int?, endColumn: Int?): String {
        if (startColumn != null && endColumn != null) {
            return if (startLine == endLine) {
                "$startLine:$startColumn-$endColumn"
            } else {
                "$startLine:$startColumn-$endLine:$endColumn"
            }
        }
        return lineRange(startLine, endLine)
    }

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

    private fun moreActionsButton(actions: List<Pair<String, () -> Unit>>): JButton =
        JButton("...").apply {
            val buttonSize = Dimension(JBUI.scale(24), JBUI.scale(20))
            font = uiFont(10f)
            preferredSize = buttonSize
            minimumSize = buttonSize
            maximumSize = buttonSize
            margin = JBUI.emptyInsets()
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusable = false
            isFocusPainted = false
            toolTipText = "More actions"
            addActionListener {
                val button = this
                JPopupMenu().apply {
                    actions.forEach { (label, action) ->
                        add(JMenuItem(label).apply {
                            font = uiFont(11f)
                            border = JBUI.Borders.empty(4, 10)
                            addActionListener { action() }
                        })
                    }
                }.show(button, 0, button.height)
            }
        }

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

    private fun isDarkTheme(): Boolean =
        luminance(UIUtil.getPanelBackground()) < luminance(UIUtil.getLabelForeground())

    private fun panelSurface(): Color =
        if (isDarkTheme()) UIUtil.getPanelBackground() else Color(0xF6F8FA)

    private fun cardSurface(): Color =
        if (isDarkTheme()) elevate(UIUtil.getPanelBackground(), 0.035f) else Color.WHITE

    private fun inputSurface(): Color =
        if (isDarkTheme()) UIUtil.getPanelBackground() else Color.WHITE

    private fun cardBorder(): Color =
        if (isDarkTheme()) {
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        } else {
            Color(0xD0D7DE)
        }

    private fun replyThreadLineColor(): Color =
        if (isDarkTheme()) {
            mix(cardSurface(), UIUtil.getLabelForeground(), 0.18f)
        } else {
            Color(0xD8DEE4)
        }

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Code Review"
        private const val CLICK_OWNED_CLIENT_PROPERTY = "code.review.click.owned"
        private val IMAGE_MARKDOWN = Regex("!\\[[^\\]]*]\\(([^)]+)\\)")
        private val HTML_IMAGE_SRC = Regex("<img\\b[^>]*\\bsrc\\s*=\\s*(?:(['\"])(.*?)\\1|([^\\s>]+))", RegexOption.IGNORE_CASE)
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
