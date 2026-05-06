package com.codereview.mr

import com.codereview.model.MrReviewChange
import com.codereview.model.MrReviewSession
import com.codereview.model.ReviewComment
import com.codereview.service.CommentSelectionService
import com.codereview.service.CommentService
import com.codereview.service.MrReviewService
import com.codereview.service.MrReviewUiStateService
import com.codereview.ui.CommentToolWindowService
import com.codereview.util.ProjectFiles
import com.intellij.icons.AllIcons
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.PrevNextDifferenceIterable
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.ide.DataManager
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.EditorMouseAdapter
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import java.awt.event.MouseEvent
import kotlin.math.max

class MrReviewFileEditor(
    private val project: Project,
    private val file: MrReviewVirtualFile
) : UserDataHolderBase(), FileEditor, Disposable {

    private val service = MrReviewService.getInstance(project)
    private val uiState = MrReviewUiStateService.getInstance(project)
    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val requestPanel: DiffRequestPanel = DiffManager.getInstance().createRequestPanel(project, this, null)

    private val lineMarkerKey = Key.create<Boolean>("code.review.mr.line.marker")
    private val rangeMarkerKey = Key.create<Boolean>("code.review.mr.range.marker")
    private val selectedRangeMarkerKey = Key.create<Boolean>("code.review.mr.selected.range.marker")
    private val commentIcon = IconLoader.getIcon("/icons/comment.svg", MrReviewFileEditor::class.java)
    @Volatile
    private var diffDecorationRefreshScheduled = false

    private val titleLabel = JBLabel("MR Review").apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D + 1f)
    }
    private val metaLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val currentFileLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search files..."
    }

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode(TreeEntry.Root))
    private val changesTree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = ChangeTreeRenderer()
        rowHeight = JBUI.scale(22)
        setLineStyleAngled()
        emptyText.text = "No changed files"
        border = JBUI.Borders.empty(2, 0)
    }
    private val placeholder = JPanel(BorderLayout()).apply {
        add(
            JBLabel("Select a changed file to review.").apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(24)
            },
            BorderLayout.CENTER
        )
    }
    private val diffContainer = JPanel(BorderLayout()).apply {
        add(placeholder, BorderLayout.CENTER)
    }
    private val collapsedListPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        minimumSize = JBUI.emptySize()
        preferredSize = JBUI.emptySize()
    }
    private lateinit var splitter: OnePixelSplitter
    private lateinit var listPanel: JPanel
    private val toggleFilesLink = ActionLink("Hide Files") { toggleFileList() }

    private val root = JPanel(BorderLayout())

    private var session: MrReviewSession? = null
    private var applyingSelection = false
    private var selectedChangeIndex = -1
    private var filterText = ""
    private var decoratedEditor: EditorEx? = null
    private var navigationEditors: List<EditorEx> = emptyList()
    private val editorDataProviders = linkedMapOf<JComponent, DataProvider?>()
    private var fileListVisible = uiState.fileListVisible()
    private var expandedListProportion = uiState.fileListProportion().coerceIn(0.05f, 0.95f)

    private val gotoDeclarationShortcut = diffSafeShortcutSet(IdeActions.ACTION_GOTO_DECLARATION)
    private val gotoImplementationShortcut = diffSafeShortcutSet(IdeActions.ACTION_GOTO_IMPLEMENTATION)
    private val gotoTypeDeclarationShortcut = diffSafeShortcutSet(IdeActions.ACTION_GOTO_TYPE_DECLARATION)
    private val gotoBackShortcut = CustomShortcutSet(
        *KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_GOTO_BACK).shortcuts
    )
    private val nextDifferenceShortcut = CustomShortcutSet(
        *KeymapUtil.getActiveKeymapShortcuts("NextDiff").shortcuts
    )
    private val previousDifferenceShortcut = CustomShortcutSet(
        *KeymapUtil.getActiveKeymapShortcuts("PreviousDiff").shortcuts
    )
    private val nextDifferenceKeyStrokes = keyStrokesForAction("NextDiff")
    private val previousDifferenceKeyStrokes = keyStrokesForAction("PreviousDiff")
    private var diffNavigationDispatcherInstalled = false

    private val diffNavigationKeyDispatcher = KeyEventDispatcher { event ->
        if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
        if (!isFocusInsideDiffEditor()) return@KeyEventDispatcher false
        val handled = when (diffNavigationDirection(event)) {
            true -> navigateDifferenceOrFile(next = true)
            false -> navigateDifferenceOrFile(next = false)
            else -> false
        }
        if (handled) {
            event.consume()
        }
        handled
    }

    private val navigateFromDiffAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            navigateFromDiffCaret(editorFromEvent(e))
        }
    }

    private val navigateToImplementationAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            navigateToImplementationFromDiffCaret(editorFromEvent(e))
        }
    }

    private val navigateToTypeDeclarationAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            navigateToTypeDeclarationFromDiffCaret(editorFromEvent(e))
        }
    }

    private val navigateBackFromDiffAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            if (!navigateBackInDiff()) {
                val action = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_BACK) ?: return
                action.actionPerformed(
                    AnActionEvent.createFromAnAction(action, e.inputEvent, e.place, e.dataContext)
                )
            }
        }
    }

    private val nextDifferenceFromDiffAction = object : DumbAwareAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            navigateDifferenceOrFile(next = true, e)
        }
    }

    private val previousDifferenceFromDiffAction = object : DumbAwareAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            navigateDifferenceOrFile(next = false, e)
        }
    }

    private val navigateMouseListener = object : EditorMouseAdapter() {
        override fun mouseClicked(event: EditorMouseEvent) {
            val mouseEvent = event.mouseEvent
            if (mouseEvent.button != MouseEvent.BUTTON1) return
            if (!mouseEvent.isMetaDown && !mouseEvent.isControlDown) return
            val handled = if (mouseEvent.isAltDown) {
                navigateToImplementationFromDiffOffset(event.editor as? EditorEx, event.logicalPosition.line, event.logicalPosition.column)
            } else {
                navigateFromDiffOffset(event.editor as? EditorEx, event.logicalPosition.line, event.logicalPosition.column)
            }
            if (handled) {
                mouseEvent.consume()
            }
        }
    }

    init {
        listPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineRight(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(6, 6)
            )
            add(searchField.apply { border = JBUI.Borders.emptyBottom(6) }, BorderLayout.NORTH)
            add(JBScrollPane(changesTree).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }
        splitter = OnePixelSplitter(false, expandedListProportion, 0.0f, 1.0f).apply {
            firstComponent = listPanel
            secondComponent = diffContainer
            dividerWidth = JBUI.scale(1)
            setHonorComponentsMinimumSize(false)
        }
        val headerInfo = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(titleLabel)
            add(metaLabel)
        }
        val headerCurrentFile = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(12)
            add(currentFileLabel, BorderLayout.CENTER)
        }

        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(toggleFilesLink)
        }

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 12)
            add(headerInfo, BorderLayout.WEST)
            add(headerCurrentFile, BorderLayout.CENTER)
            add(headerActions, BorderLayout.EAST)
        }

        root.add(header, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        updateFileListVisibility()
        installRequestPanelDataProvider()

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })

        changesTree.addTreeSelectionListener {
            if (applyingSelection) return@addTreeSelectionListener
            val entry = selectedTreeEntry() as? TreeEntry.File ?: return@addTreeSelectionListener
            showChange(entry.index, null, Side.RIGHT)
        }
        changesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val entry = treeEntryAt(event.x, event.y) as? TreeEntry.File ?: return
                if (entry.index == selectedChangeIndex) return
                showChange(entry.index, null, Side.RIGHT)
            }
        })

        val connection = project.messageBus.connect(this)
        connection.subscribe(MrReviewService.TOPIC, object : MrReviewService.Listener {
            override fun onReviewSessionChanged(session: MrReviewSession?, navigation: MrReviewService.ReviewNavigation?) {
                if (session != null && session.sessionKey == this@MrReviewFileEditor.session?.sessionKey) {
                    if (navigation != null) {
                        selectChange(navigation.changeIndex, navigation.line, navigation.side)
                    } else {
                        refreshDiffDecorations()
                    }
                } else {
                    updateSession(session, navigation)
                }
            }
        })
        connection.subscribe(CommentService.TOPIC, object : CommentService.Listener {
            override fun onCommentsChanged() = refreshDiffDecorations()
        })
        connection.subscribe(CommentSelectionService.TOPIC, object : CommentSelectionService.Listener {
            override fun onCommentSelectionChanged(commentId: String?) = refreshDiffDecorations()
        })

        updateSession(service.currentSession(), service.currentNavigation())
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = changesTree

    override fun getName(): String = "MR Review"

    override fun getState(level: FileEditorStateLevel): FileEditorState = State(selectedChangeIndex)

    override fun setState(state: FileEditorState) {
        if (state is State && state.selectedChangeIndex >= 0) {
            selectChange(state.selectedChangeIndex, null, Side.RIGHT)
        }
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getFile() = file

    override fun dispose() {
        decoratedEditor?.let(::clearMarkers)
        persistFileListState()
        uninstallNavigationSupport()
        DataManager.removeDataProvider(requestPanel.component)
    }

    fun applySession(session: MrReviewSession, navigation: MrReviewService.ReviewNavigation?) {
        updateSession(session, navigation)
    }

    private fun updateSession(session: MrReviewSession?, navigation: MrReviewService.ReviewNavigation?) {
        this.session = session
        renderHeader(session)
        rebuildTree(session)

        val targetIndex = navigation?.changeIndex
            ?: selectedChangeIndex.takeIf { treeHasChangeIndex(it) }
            ?: firstVisibleChangeIndex()
            ?: -1
        if (targetIndex >= 0) {
            selectChange(targetIndex, navigation?.line, navigation?.side ?: Side.RIGHT)
        } else {
            selectedChangeIndex = -1
            clearDiffPanel()
        }
    }

    private fun renderHeader(session: MrReviewSession?) {
        if (session == null) {
            titleLabel.text = "MR Review"
            metaLabel.text = "No active MR diff review."
            renderCurrentFile(null)
            return
        }
        titleLabel.text = session.title?.takeIf { it.isNotBlank() }
            ?: session.mrId?.let { "MR !$it" }
            ?: "MR Review"
        metaLabel.text = "${session.targetRef} (${session.mergeBaseCommit.take(8)}) -> ${session.sourceBranch} (${session.headCommit.take(8)})"
    }

    private fun onSearchChanged() {
        filterText = searchField.text.trim()
        rebuildTree(session)
        val targetIndex = selectedChangeIndex.takeIf { treeHasChangeIndex(it) } ?: firstVisibleChangeIndex()
        if (targetIndex != null) {
            selectTreeNode(targetIndex)
        } else if (!changesTree.isSelectionEmpty) {
            changesTree.clearSelection()
        }
    }

    private fun rebuildTree(session: MrReviewSession?) {
        val rootNode = DefaultMutableTreeNode(TreeEntry.Root)
        val directoryNodes = linkedMapOf<String, DefaultMutableTreeNode>()
        val query = filterText.lowercase()
        session?.changes
            ?.mapIndexed { index, change -> index to change }
            ?.filter { (_, change) -> matchesFilter(change, query) }
            ?.sortedBy { (_, change) -> change.displayPath() }
            ?.forEach { (index, change) ->
                val path = change.displayPath()
                val parentPath = path.substringBeforeLast('/', "")
                var parentNode = rootNode
                if (parentPath.isNotBlank()) {
                    var currentPath = ""
                    parentPath.split('/').forEach { segment ->
                        currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                        val node = directoryNodes.getOrPut(currentPath) {
                            DefaultMutableTreeNode(TreeEntry.Directory(segment, currentPath)).also(parentNode::add)
                        }
                        parentNode = node
                    }
                }
                parentNode.add(DefaultMutableTreeNode(TreeEntry.File(index, change)))
            }

        treeModel.setRoot(rootNode)
        expandAll()
    }

    private fun expandAll() {
        var row = 0
        while (row < changesTree.rowCount) {
            changesTree.expandRow(row)
            row += 1
        }
    }

    private fun treeHasChangeIndex(index: Int): Boolean =
        findTreePath(index) != null

    private fun firstVisibleChangeIndex(): Int? {
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return null
        val enumeration = rootNode.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
            val entry = node.userObject as? TreeEntry.File ?: continue
            return entry.index
        }
        return null
    }

    private fun selectChange(index: Int, line: Int?, side: Side) {
        if (findTreePath(index) == null) return
        selectTreeNode(index)
        showChange(index, line, side)
    }

    private fun selectTreeNode(index: Int) {
        val path = findTreePath(index) ?: return
        applyingSelection = true
        try {
            if (changesTree.selectionPath != path) {
                changesTree.selectionPath = path
            }
            changesTree.scrollPathToVisible(path)
        } finally {
            applyingSelection = false
        }
    }

    private fun findTreePath(index: Int): TreePath? {
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return null
        val enumeration = rootNode.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
            val entry = node.userObject as? TreeEntry.File ?: continue
            if (entry.index == index) {
                return TreePath(node.path)
            }
        }
        return null
    }

    private fun selectedTreeEntry(): TreeEntry? {
        val node = changesTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? TreeEntry
    }

    private fun treeEntryAt(x: Int, y: Int): TreeEntry? {
        val path = changesTree.getPathForLocation(x, y) ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? TreeEntry
    }

    private fun installRequestPanelDataProvider() {
        DataManager.registerDataProvider(requestPanel.component, DataProvider { dataId ->
            when {
                DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.`is`(dataId) -> crossFileDifferenceIterable()
                else -> null
            }
        })
    }

    private fun showChange(index: Int, line: Int?, side: Side) {
        val request = service.createRequest(index, line, side) ?: return
        selectedChangeIndex = index
        renderCurrentFile(currentChange())
        diffContainer.removeAll()
        diffContainer.add(requestPanel.component, BorderLayout.CENTER)
        requestPanel.setRequest(request)
        diffContainer.revalidate()
        diffContainer.repaint()
        SwingUtilities.invokeLater { refreshDiffDecorations() }
    }

    private fun clearDiffPanel() {
        selectedChangeIndex = -1
        renderCurrentFile(null)
        decoratedEditor?.let(::clearMarkers)
        decoratedEditor = null
        diffContainer.removeAll()
        diffContainer.add(placeholder, BorderLayout.CENTER)
        diffContainer.revalidate()
        diffContainer.repaint()
    }

    private fun toggleFileList() {
        if (fileListVisible) {
            expandedListProportion = splitter.proportion.coerceIn(0.05f, 0.95f)
        }
        fileListVisible = !fileListVisible
        updateFileListVisibility()
        persistFileListState()
    }

    private fun updateFileListVisibility() {
        splitter.firstComponent = if (fileListVisible) listPanel else collapsedListPanel
        splitter.dividerWidth = JBUI.scale(1)
        splitter.proportion = if (fileListVisible) expandedListProportion else 0f
        toggleFilesLink.text = if (fileListVisible) "Hide Files" else "Show Files"
        splitter.revalidate()
        splitter.repaint()
    }

    private fun persistFileListState() {
        if (fileListVisible) {
            expandedListProportion = splitter.proportion.coerceIn(0.05f, 0.95f)
        }
        uiState.setFileListState(fileListVisible, expandedListProportion)
    }

    private fun renderCurrentFile(change: MrReviewChange?) {
        val path = change?.displayPath().orEmpty()
        currentFileLabel.text = path
        currentFileLabel.toolTipText = path.ifBlank { null }
    }

    private fun refreshDiffDecorations() {
        if (diffDecorationRefreshScheduled) return
        diffDecorationRefreshScheduled = true
        SwingUtilities.invokeLater {
            diffDecorationRefreshScheduled = false
            decoratedEditor?.let(::clearMarkers)
            val editor = currentRightEditor() ?: run {
                decoratedEditor = null
                uninstallNavigationSupport()
                return@invokeLater
            }
            val change = currentChange() ?: run {
                decoratedEditor = editor
                installNavigationSupport()
                return@invokeLater
            }
            decoratedEditor = editor
            installNavigationSupport()

            val comments = CommentService.getInstance(project).all().filter { matchesChange(it, change) }
            if (comments.isEmpty()) return@invokeLater

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
                        gutterIconRenderer = CommentGutterRenderer(project, editor, lineIndex, commentIcon, groupedComments)
                        errorStripeTooltip = groupedComments.joinToString("\n\n") { tooltipFor(it) }
                    }
                }
        }
    }

    private fun currentChange(): MrReviewChange? =
        session?.changes?.getOrNull(selectedChangeIndex)

    private fun currentViewer(): TwosideTextDiffViewer? {
        val dataManager = DataManager.getInstance()
        val probeComponents = buildList<Component> {
            add(requestPanel.component)
            add(diffContainer)
            if (requestPanel.component.width > 2 && requestPanel.component.height > 2) {
                SwingUtilities.getDeepestComponentAt(
                    requestPanel.component,
                    max(requestPanel.component.width / 2, 1),
                    max(requestPanel.component.height / 2, 1)
                )?.let(::add)
            }
        }

        probeComponents.forEach { component ->
            val dataContext = dataManager.getDataContext(component)
            val viewer = DiffDataKeys.DIFF_VIEWER.getData(dataContext)
                ?: DiffDataKeys.WRAPPING_DIFF_VIEWER.getData(dataContext)
            if (viewer is TwosideTextDiffViewer) {
                return viewer
            }
        }
        return null
    }

    private fun currentRightEditor(): EditorEx? =
        currentViewer()?.editor2 as? EditorEx

    private fun clearMarkers(editor: EditorEx) {
        editor.markupModel.allHighlighters
            .filter {
                it.getUserData(lineMarkerKey) == true ||
                    it.getUserData(rangeMarkerKey) == true ||
                    it.getUserData(selectedRangeMarkerKey) == true
            }
            .forEach(editor.markupModel::removeHighlighter)
    }

    private fun installNavigationSupport() {
        val editors = buildList {
            currentViewer()?.let { viewer ->
                (viewer.editor1 as? EditorEx)?.let(::add)
                (viewer.editor2 as? EditorEx)?.let(::add)
            }
        }
        if (navigationEditors == editors) return
        uninstallNavigationSupport()
        navigationEditors = editors
        installDiffNavigationKeyDispatcher()
        editors.forEach { editor ->
            navigateFromDiffAction.registerCustomShortcutSet(gotoDeclarationShortcut, editor.contentComponent, this)
            navigateToImplementationAction.registerCustomShortcutSet(gotoImplementationShortcut, editor.contentComponent, this)
            navigateToTypeDeclarationAction.registerCustomShortcutSet(gotoTypeDeclarationShortcut, editor.contentComponent, this)
            navigateBackFromDiffAction.registerCustomShortcutSet(gotoBackShortcut, editor.contentComponent, this)
            nextDifferenceFromDiffAction.registerCustomShortcutSet(nextDifferenceShortcut, editor.contentComponent, this)
            previousDifferenceFromDiffAction.registerCustomShortcutSet(previousDifferenceShortcut, editor.contentComponent, this)
            installEditorDiffDataProvider(editor)
            editor.addEditorMouseListener(navigateMouseListener, this)
        }
    }

    private fun uninstallNavigationSupport() {
        navigationEditors.forEach { editor ->
            navigateFromDiffAction.unregisterCustomShortcutSet(editor.contentComponent)
            navigateToImplementationAction.unregisterCustomShortcutSet(editor.contentComponent)
            navigateToTypeDeclarationAction.unregisterCustomShortcutSet(editor.contentComponent)
            navigateBackFromDiffAction.unregisterCustomShortcutSet(editor.contentComponent)
            nextDifferenceFromDiffAction.unregisterCustomShortcutSet(editor.contentComponent)
            previousDifferenceFromDiffAction.unregisterCustomShortcutSet(editor.contentComponent)
        }
        restoreEditorDiffDataProviders()
        uninstallDiffNavigationKeyDispatcher()
        navigationEditors = emptyList()
    }

    private fun installEditorDiffDataProvider(editor: EditorEx) {
        val component = editor.contentComponent
        if (component in editorDataProviders) return
        val originalProvider = DataManager.getDataProvider(component)
        editorDataProviders[component] = originalProvider
        DataManager.registerDataProvider(component, DataProvider { dataId ->
            when {
                DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.`is`(dataId) -> crossFileDifferenceIterable()
                else -> originalProvider?.getData(dataId)
            }
        })
    }

    private fun restoreEditorDiffDataProviders() {
        editorDataProviders.forEach { (component, provider) ->
            if (provider != null) {
                DataManager.registerDataProvider(component, provider)
            } else {
                DataManager.removeDataProvider(component)
            }
        }
        editorDataProviders.clear()
    }

    private fun diffSafeShortcutSet(actionId: String): CustomShortcutSet {
        val diffNavigationShortcuts = DIFF_NAVIGATION_ACTION_IDS
            .flatMap { KeymapUtil.getActiveKeymapShortcuts(it).shortcuts.asIterable() }
            .toSet()
        val shortcuts = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
            .filterNot { it in diffNavigationShortcuts }
            .toTypedArray()
        return CustomShortcutSet(*shortcuts)
    }

    private fun keyStrokesForAction(actionId: String): Set<KeyStroke> =
        KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
            .mapNotNull { (it as? KeyboardShortcut)?.firstKeyStroke }
            .toSet()

    private fun installDiffNavigationKeyDispatcher() {
        if (diffNavigationDispatcherInstalled) return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(diffNavigationKeyDispatcher)
        diffNavigationDispatcherInstalled = true
    }

    private fun uninstallDiffNavigationKeyDispatcher() {
        if (!diffNavigationDispatcherInstalled) return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(diffNavigationKeyDispatcher)
        diffNavigationDispatcherInstalled = false
    }

    private fun isFocusInsideDiffEditor(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return navigationEditors.any { editor ->
            focusOwner == editor.contentComponent || SwingUtilities.isDescendingFrom(focusOwner, editor.contentComponent)
        }
    }

    private fun diffNavigationDirection(event: KeyEvent): Boolean? {
        val keyStroke = KeyStroke.getKeyStrokeForEvent(event)
        return when {
            keyStroke in nextDifferenceKeyStrokes -> true
            keyStroke in previousDifferenceKeyStrokes -> false
            event.keyCode == KeyEvent.VK_F7 && event.isShiftDown -> false
            event.keyCode == KeyEvent.VK_F7 -> true
            else -> null
        }
    }

    private fun navigateDifferenceOrFile(next: Boolean, e: AnActionEvent): Boolean {
        val iterable = e.getData(DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE) ?: currentDifferenceIterable()
        return navigateDifferenceOrFile(next, iterable)
    }

    private fun navigateDifferenceOrFile(next: Boolean): Boolean =
        navigateDifferenceOrFile(next, currentDifferenceIterable())

    private fun navigateDifferenceOrFile(next: Boolean, iterable: PrevNextDifferenceIterable?): Boolean {
        if (iterable != null) {
            if (next && iterable.canGoNext()) {
                iterable.goNext()
                return true
            }
            if (!next && iterable.canGoPrev()) {
                iterable.goPrev()
                return true
            }
        }
        return navigateAdjacentChange(next)
    }

    private fun crossFileDifferenceIterable(): PrevNextDifferenceIterable =
        object : PrevNextDifferenceIterable {
            override fun canGoNext(): Boolean =
                currentDifferenceIterable()?.canGoNext() == true || canNavigateAdjacentChange(next = true)

            override fun canGoPrev(): Boolean =
                currentDifferenceIterable()?.canGoPrev() == true || canNavigateAdjacentChange(next = false)

            override fun goNext() {
                navigateDifferenceOrFile(next = true, currentDifferenceIterable())
            }

            override fun goPrev() {
                navigateDifferenceOrFile(next = false, currentDifferenceIterable())
            }
        }

    private fun currentDifferenceIterable(): PrevNextDifferenceIterable? =
        currentViewer()?.let { viewer ->
            viewer.javaClass.methods
                .firstOrNull { it.name == "getDifferenceIterable" && it.parameterTypes.isEmpty() }
                ?.invoke(viewer) as? PrevNextDifferenceIterable
        }

    private fun canNavigateAdjacentChange(next: Boolean): Boolean {
        val activeSession = session ?: return false
        val targetIndex = if (next) selectedChangeIndex + 1 else selectedChangeIndex - 1
        return targetIndex in activeSession.changes.indices
    }

    private fun navigateAdjacentChange(next: Boolean): Boolean {
        if (!canNavigateAdjacentChange(next)) return false
        val activeSession = session ?: return false
        val targetIndex = if (next) selectedChangeIndex + 1 else selectedChangeIndex - 1
        return service.navigateToChange(targetIndex)
    }

    private fun navigateFromDiffCaret(editor: EditorEx?): Boolean {
        val actualEditor = editor ?: currentRightEditor() ?: return false
        val offset = actualEditor.caretModel.offset.coerceAtLeast(0)
        val line = actualEditor.document.getLineNumber(offset)
        val column = offset - actualEditor.document.getLineStartOffset(line)
        return navigateFromDiffOffset(actualEditor, line, column)
    }

    private fun navigateToImplementationFromDiffCaret(editor: EditorEx?): Boolean {
        val actualEditor = editor ?: currentRightEditor() ?: return false
        val offset = actualEditor.caretModel.offset.coerceAtLeast(0)
        val line = actualEditor.document.getLineNumber(offset)
        val column = offset - actualEditor.document.getLineStartOffset(line)
        return navigateToImplementationFromDiffOffset(actualEditor, line, column)
    }

    private fun navigateToTypeDeclarationFromDiffCaret(editor: EditorEx?): Boolean {
        val actualEditor = editor ?: currentRightEditor() ?: return false
        val offset = actualEditor.caretModel.offset.coerceAtLeast(0)
        val line = actualEditor.document.getLineNumber(offset)
        val column = offset - actualEditor.document.getLineStartOffset(line)
        return navigateToTypeDeclarationFromDiffOffset(actualEditor, line, column)
    }

    private fun editorFromEvent(e: AnActionEvent): EditorEx? =
        (CommonDataKeys.EDITOR.getData(e.dataContext) as? EditorEx)

    private fun navigateFromDiffOffset(editor: EditorEx?, line: Int, column: Int): Boolean {
        val navigationContext = navigationContext(editor, line, column) ?: return false
        val target = resolveNavigationTarget(navigationContext.psiFile, navigationContext.offset) ?: return false
        return navigateToPsiTarget(target, line + 1, navigationContext.side)
    }

    private fun navigateToImplementationFromDiffOffset(editor: EditorEx?, line: Int, column: Int): Boolean {
        val actualEditor = editor ?: currentRightEditor() ?: return false
        val navigationContext = navigationContext(actualEditor, line, column) ?: return false
        val source = resolveImplementationSourceElement(navigationContext.psiFile, navigationContext.offset) ?: return false
        val targets = DefinitionsScopedSearch.search(source)
            .findAll()
            .distinctBy { target ->
                val filePath = target.containingFile?.virtualFile?.path.orEmpty()
                "$filePath:${target.textOffset}"
            }
            .toList()
        return when (targets.size) {
            0 -> false
            1 -> navigateToPsiTarget(targets.first(), line + 1, navigationContext.side)
            else -> showImplementationChooser(actualEditor, source, targets, line + 1, navigationContext.side)
        }
    }

    private fun navigateToTypeDeclarationFromDiffOffset(editor: EditorEx?, line: Int, column: Int): Boolean {
        val navigationContext = navigationContext(editor, line, column) ?: return false
        val source = resolveSourceElement(navigationContext.psiFile, navigationContext.offset) ?: return false
        val target = TypeDeclarationProvider.EP_NAME.extensionList
            .asSequence()
            .flatMap { provider -> (provider.getSymbolTypeDeclarations(source) ?: emptyArray()).asSequence() }
            .firstOrNull()
            ?: return false
        return navigateToPsiTarget(target, line + 1, navigationContext.side)
    }

    private fun navigationContext(editor: EditorEx?, line: Int, column: Int): NavigationContext? {
        val change = currentChange() ?: return null
        val side = sideForEditor(editor)
        val relativePath = when (side) {
            Side.LEFT -> change.oldPath ?: change.newPath
            Side.RIGHT -> change.newPath ?: change.oldPath
        } ?: return null
        val contextFile = ProjectFiles.find(project, relativePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(contextFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val safeLine = line.coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(safeLine)
        val lineEnd = document.getLineEndOffset(safeLine)
        return NavigationContext(psiFile, (lineStart + column).coerceIn(lineStart, lineEnd), side)
    }

    private fun navigateBackInDiff(): Boolean {
        if (!service.canNavigateBackInDiff()) return false
        return service.navigateBackInDiff()
    }

    private fun navigateToPsiTarget(target: PsiElement, sourceLine: Int, sourceSide: Side): Boolean {
        val targetPsiFile = target.containingFile ?: return false
        val targetFile = targetPsiFile.virtualFile ?: return false
        val targetDocument = PsiDocumentManager.getInstance(project).getDocument(targetPsiFile) ?: return false
        val targetLine = targetDocument.getLineNumber(target.textOffset.coerceAtLeast(0)) + 1
        return service.navigateToFileDiff(
            targetFile,
            targetLine,
            MrReviewService.ReviewNavigation(selectedChangeIndex, sourceLine, sourceSide)
        )
    }

    private fun showImplementationChooser(
        editor: EditorEx,
        source: PsiElement,
        targets: List<PsiElement>,
        sourceLine: Int,
        sourceSide: Side
    ): Boolean {
        val title = "Choose Implementation of ${source.text} (${targets.size} found)"
        val list = JBList(targets).apply {
            cellRenderer = DefaultPsiElementCellRenderer()
            selectedIndex = 0
        }
        val popup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle(title)
            .setItemChoosenCallback {
                list.selectedValue?.let { navigateToPsiTarget(it, sourceLine, sourceSide) }
            }
            .createPopup()
        popup.showInBestPositionFor(editor)
        return true
    }

    private fun resolveNavigationTarget(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val maxOffset = psiFile.textLength.coerceAtLeast(1) - 1
        val offsets = buildList {
            add(offset.coerceIn(0, maxOffset))
            if (offset > 0) add((offset - 1).coerceIn(0, maxOffset))
        }.distinct()

        offsets.forEach { candidateOffset ->
            resolveReferenceTarget(psiFile, candidateOffset)?.let { return it }
            val element = psiFile.findElementAt(candidateOffset) ?: return@forEach
            resolveElementTarget(element)?.let { return it }
        }
        return null
    }

    private fun resolveSourceElement(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val maxOffset = psiFile.textLength.coerceAtLeast(1) - 1
        val offsets = buildList {
            add(offset.coerceIn(0, maxOffset))
            if (offset > 0) add((offset - 1).coerceIn(0, maxOffset))
        }.distinct()

        offsets.forEach { candidateOffset ->
            psiFile.findReferenceAt(candidateOffset)?.element?.let { return it }
            psiFile.findElementAt(candidateOffset)?.let { return it }
        }
        return null
    }

    private fun resolveImplementationSourceElement(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val source = resolveSourceElement(psiFile, offset) ?: return null
        return TargetElementUtil.getNamedElement(source) ?: source
    }

    private fun resolveReferenceTarget(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val reference = psiFile.findReferenceAt(offset) ?: return null
        return when (reference) {
            is PsiPolyVariantReference -> reference.multiResolve(false).firstOrNull()?.element
            else -> reference.resolve()
        }
    }

    private fun resolveElementTarget(element: PsiElement): PsiElement? {
        element.reference?.resolve()?.let { return it }
        var current: PsiElement? = element.parent
        while (current != null) {
            current.reference?.resolve()?.let { return it }
            current = current.parent
        }
        return null
    }

    private data class NavigationContext(
        val psiFile: com.intellij.psi.PsiFile,
        val offset: Int,
        val side: Side
    )

    private fun sideForEditor(editor: EditorEx?): Side {
        val viewer = currentViewer() ?: return Side.RIGHT
        return when (editor) {
            viewer.editor1 -> Side.LEFT
            else -> Side.RIGHT
        }
    }

    private fun matchesFilter(change: MrReviewChange, query: String): Boolean {
        if (query.isBlank()) return true
        val path = change.displayPath().lowercase()
        val name = path.substringAfterLast('/')
        return query in path || query in name
    }

    private fun matchesChange(comment: ReviewComment, change: MrReviewChange): Boolean {
        val commentCandidates = linkedSetOf<String>()
        comment.diffNewPath?.let(commentCandidates::add)
        comment.diffOldPath?.let(commentCandidates::add)
        commentCandidates.add(comment.file)
        return change.newPath in commentCandidates || change.oldPath in commentCandidates
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
            (lineStart + comment.endColumn!!).coerceIn(lineStart, lineEnd)
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
        val base = scheme.defaultBackground ?: UIUtil.getEditorPaneBackground()
        val accent = if (selected) selectedCommentAccent() else passiveCommentAccent()
        val background = mix(base, accent, if (selected) 0.58f else 0.20f)
        return TextAttributes().apply {
            backgroundColor = background
            if (selected) {
                effectType = EffectType.BOXED
                effectColor = accent
            }
        }
    }

    private fun selectedCommentAccent(): Color =
        if (isDarkTheme()) Color(0xD89B2B) else Color(0xF0B429)

    private fun passiveCommentAccent(): Color =
        if (isDarkTheme()) Color(0x7A8FA8) else Color(0x8EA9C1)

    private fun mix(base: Color, accent: Color, weight: Float): Color {
        val normalized = weight.coerceIn(0f, 1f)
        val inverse = 1f - normalized
        return Color(
            (base.red * inverse + accent.red * normalized).toInt().coerceIn(0, 255),
            (base.green * inverse + accent.green * normalized).toInt().coerceIn(0, 255),
            (base.blue * inverse + accent.blue * normalized).toInt().coerceIn(0, 255)
        )
    }

    private fun isDarkTheme(): Boolean =
        luminance(UIUtil.getPanelBackground()) < luminance(UIUtil.getLabelForeground())

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private sealed interface TreeEntry {
        data object Root : TreeEntry
        data class Directory(val name: String, val path: String) : TreeEntry
        data class File(val index: Int, val change: MrReviewChange) : TreeEntry
    }

    private data class State(val selectedChangeIndex: Int) : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean =
            otherState is State && otherState.selectedChangeIndex == selectedChangeIndex
    }

    private inner class ChangeTreeRenderer : ColoredTreeCellRenderer() {
        private val positive = Color(0x3FA76A)
        private val negative = Color(0xD05B5B)

        override fun customizeCellRenderer(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            border = JBUI.Borders.empty(0, 3)
            val node = (value as? DefaultMutableTreeNode)?.userObject as? TreeEntry ?: return
            when (node) {
                TreeEntry.Root -> Unit
                is TreeEntry.Directory -> {
                    icon = if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
                    append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is TreeEntry.File -> {
                    val change = node.change
                    val path = change.displayPath()
                    val fileName = path.substringAfterLast('/')
                    icon = fileIcon(fileName)
                    append(fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (change.addedLines > 0 || change.removedLines > 0) {
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("+${change.addedLines}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, positive))
                        append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("-${change.removedLines}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, negative))
                    }
                }
            }
        }
    }

    private class CommentGutterRenderer(
        private val project: Project,
        private val editor: EditorEx,
        private val lineIndex: Int,
        private val icon: javax.swing.Icon,
        private val comments: List<ReviewComment>
    ) : GutterIconRenderer() {

        override fun getIcon(): javax.swing.Icon = icon

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

    private fun fileIcon(fileName: String) =
        com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type

    private fun MrReviewChange.displayPath(): String =
        newPath ?: oldPath ?: "unknown"

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

        private val DIFF_NAVIGATION_ACTION_IDS = setOf(
            "PreviousDiff",
            "NextDiff",
            "Diff.PrevChange",
            "Diff.NextChange"
        )
    }
}
