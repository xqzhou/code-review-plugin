package com.codereview.service

import com.codereview.actions.AddCommentAction
import com.codereview.diff.ReviewDiffCommentTarget
import com.codereview.diff.ReviewDiffDataKeys
import com.codereview.model.CommentDraft
import com.codereview.model.CommentFile
import com.codereview.model.MrReviewChange
import com.codereview.model.MrReviewSession
import com.codereview.model.ReviewComment
import com.codereview.mr.MrReviewVirtualFile
import com.codereview.storage.CommentStore
import com.codereview.ui.CommentNotifications
import com.codereview.util.ProjectFiles
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContentFactoryEx
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class MrReviewService(private val project: Project) {

    interface Listener {
        fun onReviewSessionChanged(session: MrReviewSession?, navigation: ReviewNavigation?)
    }

    data class ReviewNavigation(val changeIndex: Int, val line: Int? = null, val side: Side = Side.RIGHT)

    private val store = CommentStore(project)

    private var currentSession: MrReviewSession? = null
    private var currentNavigation: ReviewNavigation? = null
    private var reviewFile: MrReviewVirtualFile? = null

    fun openReview() {
        val session = prepareSession() ?: return
        if (session.changes.isEmpty()) {
            CommentNotifications.info(
                project,
                "No Diff To Review",
                "No changed files between `${session.targetRef}` and `${session.sourceBranch}`."
            )
            return
        }
        showSession(session, null)
        if (session.skippedBinaryFiles > 0) {
            CommentNotifications.info(
                project,
                "Binary Files Skipped",
                "Skipped ${session.skippedBinaryFiles} binary file(s) while building the MR diff view."
            )
        }
    }

    fun currentSession(): MrReviewSession? = currentSession

    fun currentNavigation(): ReviewNavigation? = currentNavigation

    fun createRequest(changeIndex: Int, line: Int? = null, side: Side = Side.RIGHT): SimpleDiffRequest? {
        val session = currentSession ?: return null
        val change = session.changes.getOrNull(changeIndex) ?: return null
        return buildRequest(session, change, line, side)
    }

    fun navigateToFileDiff(targetFile: VirtualFile, line: Int): Boolean {
        val session = currentSession ?: return false
        val relativePath = ProjectFiles.relativePath(project, targetFile) ?: return false
        val existingIndex = findChangeIndex(session, relativePath, relativePath, relativePath)
        if (existingIndex >= 0) {
            val change = session.changes[existingIndex]
            showSession(session, ReviewNavigation(existingIndex, line, preferredSide(change)))
            return true
        }

        val runtimeChange = buildRuntimeChange(session, targetFile) ?: return false
        val updatedSession = session.copy(changes = session.changes + runtimeChange)
        val nextIndex = updatedSession.changes.lastIndex
        showSession(updatedSession, ReviewNavigation(nextIndex, line, preferredSide(runtimeChange)))
        return true
    }

    fun navigateToComment(commentId: String): Boolean {
        val comment = CommentService.getInstance(project).find(commentId) ?: return false
        return navigateToComment(comment)
    }

    fun navigateToComment(comment: ReviewComment): Boolean {
        currentSession?.let { active ->
            val activeIndex = findChangeIndex(active, comment.diffOldPath, comment.diffNewPath, comment.file)
            if (activeIndex >= 0) {
                showSession(active, ReviewNavigation(activeIndex, comment.startLine))
                return true
            }
        }

        val targetRef = comment.reviewTargetRef ?: return false
        val headCommit = comment.diffHeadCommit ?: return false
        val session = ensureSession(targetRef, headCommit, warnOnFailure = false) ?: return false
        val index = findChangeIndex(session, comment.diffOldPath, comment.diffNewPath, comment.file)
        if (index < 0) return false
        showSession(session, ReviewNavigation(index, comment.startLine))
        return true
    }

    fun navigateToDraft(draft: CommentDraft): Boolean {
        val targetRef = draft.reviewTargetRef ?: return false
        val headCommit = draft.diffHeadCommit ?: return false
        val session = ensureSession(targetRef, headCommit, warnOnFailure = false) ?: return false
        val index = findChangeIndex(session, draft.diffOldPath, draft.diffNewPath, draft.file)
        if (index < 0) return false
        showSession(session, ReviewNavigation(index, draft.startLine))
        return true
    }

    private fun ensureSession(targetRef: String, headCommit: String, warnOnFailure: Boolean = true): MrReviewSession? {
        val active = currentSession
        if (active != null && matchesSession(active, targetRef, headCommit)) {
            return active
        }
        return prepareSession(expectedTargetRef = targetRef, expectedHeadCommit = headCommit, warnOnFailure = warnOnFailure)
    }

    private fun prepareSession(
        expectedTargetRef: String? = null,
        expectedHeadCommit: String? = null,
        warnOnFailure: Boolean = true
    ): MrReviewSession? {
        val repository = repository() ?: run {
            if (warnOnFailure) {
                CommentNotifications.warn(project, "Git Repository Not Found", "Open a git-backed project to review an MR diff.")
            }
            return null
        }
        val branchData = store.load()
        val reviewContext = validateReviewContext(branchData, warnOnFailure) ?: return null
        if (expectedTargetRef != null && reviewContext.targetRef != expectedTargetRef) {
            if (warnOnFailure) {
                CommentNotifications.warn(
                    project,
                    "MR Review Context Mismatch",
                    "The current branch review context points to `${reviewContext.targetRef}`, not `$expectedTargetRef`."
                )
            }
            return null
        }

        var session: MrReviewSession? = null
        var failure: String? = null
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    session = buildSession(repository, reviewContext)
                } catch (t: Throwable) {
                    failure = t.message ?: "Failed to prepare MR diff review."
                }
            },
            "Preparing MR Diff Review",
            true,
            project
        )
        if (!completed) return null
        if (failure != null) {
            if (warnOnFailure) {
                CommentNotifications.warn(project, "MR Diff Review Failed", failure!!)
            }
            return null
        }

        val prepared = session ?: return null
        if (expectedHeadCommit != null && prepared.headCommit != expectedHeadCommit) {
            if (warnOnFailure) {
                CommentNotifications.warn(
                    project,
                    "MR Review Context Outdated",
                    "The current branch no longer matches the stored diff head `${expectedHeadCommit.take(8)}`."
                )
            }
            return null
        }
        return prepared
    }

    private fun showSession(session: MrReviewSession, navigation: ReviewNavigation?) {
        currentSession = session
        currentNavigation = navigation
        openReviewFile(session, navigation)
        project.messageBus.syncPublisher(TOPIC).onReviewSessionChanged(currentSession, currentNavigation)
    }

    private fun openReviewFile(session: MrReviewSession, navigation: ReviewNavigation?) {
        ApplicationManager.getApplication().invokeLater {
            val manager = FileEditorManager.getInstance(project)
            val nextFile = reviewFile(session)
            val previous = reviewFile
            if (previous != null && previous !== nextFile) {
                manager.closeFile(previous)
            }
            reviewFile = nextFile
            val editors = manager.openFile(nextFile, true)
            editors.filterIsInstance<com.codereview.mr.MrReviewFileEditor>().forEach { editor ->
                editor.applySession(session, navigation)
            }
        }
    }

    private fun reviewFile(session: MrReviewSession): MrReviewVirtualFile {
        val existing = reviewFile
        if (existing != null && existing.sessionKey == session.sessionKey) {
            return existing
        }
        return MrReviewVirtualFile(diffTabTitle(session), session.sessionKey)
    }

    private fun validateReviewContext(data: CommentFile, warnOnFailure: Boolean = true): ReviewContext? {
        val targetRef = data.targetBranch?.trim().orEmpty()
        if (targetRef.isBlank()) {
            if (warnOnFailure) {
                CommentNotifications.warn(
                    project,
                    "MR Review Context Missing",
                    "`${store.currentBranchFile().fileName}` does not contain MR metadata. Use the code-review skill to set up the MR metadata first."
                )
            }
            return null
        }
        if (!targetRef.startsWith("origin/")) {
            if (warnOnFailure) {
                CommentNotifications.warn(
                    project,
                    "Invalid MR Target Ref",
                    "Expected `targetBranch` to be stored as `origin/<branch>`, got `$targetRef`."
                )
            }
            return null
        }

        val sourceBranch = data.sourceBranch?.takeIf { it.isNotBlank() } ?: data.branch
        return ReviewContext(
            mrUrl = data.mrUrl,
            mrId = data.mrId,
            title = data.title,
            sourceBranch = sourceBranch,
            targetRef = targetRef,
            baseCommit = data.baseCommit?.takeIf { it.isNotBlank() },
            headCommit = data.headCommit?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildSession(repository: GitRepository, context: ReviewContext): MrReviewSession {
        val headCommit = context.headCommit?.takeIf { canResolve(repository, it) }
            ?: git(repository, GitCommand.REV_PARSE, "HEAD").singleLine("Could not resolve `HEAD`.")
        val baseCommit = context.baseCommit?.takeIf { canResolve(repository, it) }

        val diffAnchor = if (baseCommit != null) {
            DiffAnchor.Revisions(baseCommit = baseCommit, headCommit = headCommit)
        } else {
            if (!canResolve(repository, context.targetRef)) {
                error("Current branch review context points to `${context.targetRef}`, but that ref is not available locally.")
            }
            DiffAnchor.TargetRef(targetRef = context.targetRef, headCommit = headCommit)
        }

        val nameStatusLines = when (diffAnchor) {
            is DiffAnchor.Revisions -> git(
                repository,
                GitCommand.DIFF,
                "--name-status",
                "--find-renames",
                diffAnchor.baseCommit,
                diffAnchor.headCommit
            ).lines

            is DiffAnchor.TargetRef -> git(
                repository,
                GitCommand.DIFF,
                "--name-status",
                "--find-renames",
                diffAnchor.targetRef,
                diffAnchor.headCommit
            ).lines
        }

        var skippedBinaryFiles = 0
        val changes = parseNameStatus(nameStatusLines).mapNotNull { entry ->
            val change = when (diffAnchor) {
                is DiffAnchor.Revisions ->
                    buildChange(repository, diffAnchor.baseCommit, diffAnchor.headCommit, entry)

                is DiffAnchor.TargetRef ->
                    buildChange(repository, diffAnchor.targetRef, diffAnchor.headCommit, entry)
            }
            if (change == null) skippedBinaryFiles += 1
            change
        }

        return MrReviewSession(
            mrUrl = context.mrUrl,
            mrId = context.mrId,
            title = context.title,
            sourceBranch = context.sourceBranch,
            targetRef = context.targetRef,
            mergeBaseCommit = baseCommit ?: git(repository, GitCommand.MERGE_BASE, context.targetRef, headCommit)
                .singleLine("Could not resolve merge-base for `${context.targetRef}`."),
            headCommit = headCommit,
            changes = changes,
            skippedBinaryFiles = skippedBinaryFiles
        )
    }

    private fun buildRequest(session: MrReviewSession, change: MrReviewChange, line: Int?, side: Side): SimpleDiffRequest {
        val displayPath = change.newPath ?: change.oldPath ?: "unknown"
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(displayPath)
        val oldContent = createDiffContent(change.oldContent, change.oldPath, referentForOld(change), fileType)
        val newContent = createDiffContent(
            change.newContent,
            change.newPath ?: change.oldPath,
            referentForNew(change),
            fileType,
            preferProjectDocument = true
        )

        return SimpleDiffRequest(
            requestTitle(change),
            oldContent,
            newContent,
            "${session.targetRef} (${session.mergeBaseCommit.take(8)})",
            "${session.sourceBranch} (${session.headCommit.take(8)})"
        ).apply {
            putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, side)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
            putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(AddCommentAction()))
            if (line != null) {
                putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, (line - 1).coerceAtLeast(0)))
            }
            putUserData(DiffUserDataKeys.DATA_PROVIDER, DataProvider { dataId ->
                val target = ReviewDiffCommentTarget(
                    targetRef = session.targetRef,
                    mergeBaseCommit = session.mergeBaseCommit,
                    headCommit = session.headCommit,
                    oldPath = change.oldPath,
                    newPath = change.newPath,
                    newContent = newContent
                )
                when {
                    ReviewDiffDataKeys.COMMENT_TARGET.`is`(dataId) -> target
                    else -> null
                }
            })
        }
    }

    private fun createDiffContent(
        text: String,
        displayPath: String?,
        referent: VirtualFile?,
        fileType: com.intellij.openapi.fileTypes.FileType,
        preferProjectDocument: Boolean = false
    ): DocumentContent {
        if (preferProjectDocument) {
            createProjectBackedContent(text, referent)?.let { return it }
        }

        if (referent != null) {
            return DiffContentFactory.getInstance().create(project, text, referent)
        }

        val builder = DiffContentFactoryEx.getInstanceEx()
            .documentContent(project, false)
            .withFileName((displayPath ?: referent?.name ?: "diff").substringAfterLast('/'))

        val absolutePath = absolutePath(displayPath)
        if (absolutePath != null) {
            builder.contextByFilePath(LocalFilePath(absolutePath, false))
        } else {
            builder.contextByFileType(fileType)
        }
        return builder.buildFromText(text, false)
    }

    private fun createProjectBackedContent(text: String, referent: VirtualFile?): DocumentContent? {
        if (referent == null) return null
        val document = FileDocumentManager.getInstance().getDocument(referent) ?: return null
        val currentText = StringUtil.convertLineSeparators(document.text)
        val targetText = StringUtil.convertLineSeparators(text)
        if (currentText != targetText) return null
        return DiffContentFactory.getInstance().create(project, document, referent)
    }

    private fun absolutePath(displayPath: String?): String? {
        val relative = displayPath?.takeIf { it.isNotBlank() } ?: return null
        val basePath = project.basePath ?: return null
        return java.nio.file.Path.of(basePath).resolve(relative).toString()
    }

    private fun requestTitle(change: MrReviewChange): String =
        when {
            change.status.startsWith("R") && change.oldPath != null && change.newPath != null ->
                "${change.oldPath} -> ${change.newPath}"

            else -> change.newPath ?: change.oldPath ?: "unknown"
        }

    private fun buildChange(
        repository: GitRepository,
        oldRevision: String,
        headCommit: String,
        entry: NameStatusEntry
    ): MrReviewChange? {
        val oldPath = when {
            entry.status.startsWith("R") || entry.status.startsWith("C") -> entry.paths.getOrNull(0)
            entry.status.startsWith("A") -> null
            else -> entry.paths.firstOrNull()
        }
        val newPath = when {
            entry.status.startsWith("R") || entry.status.startsWith("C") -> entry.paths.getOrNull(1)
            entry.status.startsWith("D") -> null
            else -> entry.paths.firstOrNull()
        }

        val oldContent = if (oldPath == null) "" else gitShow(repository, oldRevision, oldPath)
        val newContent = if (newPath == null) "" else gitShow(repository, headCommit, newPath)
        if (containsBinaryMarker(oldContent) || containsBinaryMarker(newContent)) return null
        val stats = diffStats(repository, oldRevision, headCommit, oldPath, newPath)

        return MrReviewChange(
            status = entry.status,
            oldPath = oldPath,
            newPath = newPath,
            oldContent = oldContent,
            newContent = newContent,
            addedLines = stats.first,
            removedLines = stats.second
        )
    }

    private fun buildRuntimeChange(session: MrReviewSession, targetFile: VirtualFile): MrReviewChange? {
        val repository = repository() ?: return null
        val relativePath = ProjectFiles.relativePath(project, targetFile) ?: return null
        val existsOnTarget = gitFileExists(repository, session.targetRef, relativePath)
        val existsOnHead = gitFileExists(repository, session.headCommit, relativePath)
        if (!existsOnTarget && !existsOnHead) return null

        val oldPath = relativePath.takeIf { existsOnTarget }
        val newPath = relativePath.takeIf { existsOnHead }
        val oldContent = oldPath?.let { gitShow(repository, session.targetRef, it) }.orEmpty()
        val newContent = newPath?.let { gitShow(repository, session.headCommit, it) }.orEmpty()
        val stats = diffStats(repository, session.targetRef, session.headCommit, oldPath, newPath)
        val status = when {
            oldPath == null -> "A"
            newPath == null -> "D"
            else -> "M"
        }

        return MrReviewChange(
            status = status,
            oldPath = oldPath,
            newPath = newPath,
            oldContent = oldContent,
            newContent = newContent,
            addedLines = stats.first,
            removedLines = stats.second
        )
    }

    private fun containsBinaryMarker(text: String): Boolean =
        text.any { it == '\u0000' }

    private fun gitShow(repository: GitRepository, revision: String, path: String): String {
        val result = git(repository, GitCommand.SHOW, "$revision:$path")
        return if (result.success) result.lines.joinToString("\n") else ""
    }

    private fun gitFileExists(repository: GitRepository, revision: String, path: String): Boolean =
        git(repository, GitCommand.CAT_FILE, "-e", "$revision:$path").success

    private fun diffStats(
        repository: GitRepository,
        oldRevision: String,
        headCommit: String,
        oldPath: String?,
        newPath: String?
    ): Pair<Int, Int> {
        val params = mutableListOf("--numstat", "--find-renames", oldRevision, headCommit, "--")
        if (!oldPath.isNullOrBlank()) params += oldPath
        if (!newPath.isNullOrBlank() && newPath != oldPath) params += newPath
        val output = git(repository, GitCommand.DIFF, *params.toTypedArray())
        var added = 0
        var removed = 0
        output.lines.forEach { raw ->
            val parts = raw.split('\t')
            if (parts.size < 3) return@forEach
            added += parts[0].toIntOrNull() ?: 0
            removed += parts[1].toIntOrNull() ?: 0
        }
        return Pair(added, removed)
    }

    private fun referentForNew(change: MrReviewChange): VirtualFile? =
        change.newPath?.let { ProjectFiles.find(project, it) }
            ?: change.oldPath?.let { ProjectFiles.find(project, it) }

    private fun referentForOld(change: MrReviewChange): VirtualFile? =
        change.oldPath?.let { ProjectFiles.find(project, it) }
            ?: change.newPath?.let { ProjectFiles.find(project, it) }

    private fun canResolve(repository: GitRepository, ref: String): Boolean =
        git(repository, GitCommand.REV_PARSE, "--verify", ref).success

    private fun parseNameStatus(lines: List<String>): List<NameStatusEntry> =
        lines.mapNotNull { raw ->
            val parts = raw.split('\t')
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            NameStatusEntry(parts[0], parts.drop(1))
        }

    private fun git(repository: GitRepository, command: GitCommand, vararg params: String): GitOutput {
        val handler = GitLineHandler(project, repository.root, command)
        handler.addParameters(*params)
        handler.withNoTty()
        val result = Git.getInstance().runCommand(handler)
        return GitOutput(
            success = result.success(),
            lines = result.output,
            error = result.errorOutputAsJoinedString
        )
    }

    private fun repository(): GitRepository? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()

    private fun diffTabTitle(session: MrReviewSession): String =
        session.title?.takeIf { it.isNotBlank() }
            ?: session.mrId?.let { "MR !$it" }
            ?: "MR Diff Review"

    private fun preferredSide(change: MrReviewChange): Side =
        if (change.newPath != null) Side.RIGHT else Side.LEFT

    private fun matchesSession(session: MrReviewSession, targetRef: String?, headCommit: String?): Boolean =
        targetRef == session.targetRef && headCommit == session.headCommit

    private fun findChangeIndex(session: MrReviewSession, oldPath: String?, newPath: String?, file: String): Int =
        session.changes.indexOfFirst { change ->
            matchesPath(change.newPath, newPath) ||
                matchesPath(change.oldPath, oldPath) ||
                matchesPath(change.newPath, file) ||
                matchesPath(change.oldPath, file)
        }

    private fun matchesPath(changePath: String?, candidate: String?): Boolean {
        if (changePath.isNullOrBlank() || candidate.isNullOrBlank()) return false
        if (changePath == candidate) return true
        return changePath.endsWith("/$candidate") || candidate.endsWith("/$changePath")
    }

    private data class NameStatusEntry(val status: String, val paths: List<String>)

    private data class GitOutput(
        val success: Boolean,
        val lines: List<String>,
        val error: String
    ) {
        fun singleLine(errorMessage: String): String {
            if (!success) error(error.ifBlank { errorMessage })
            return lines.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                ?: error(errorMessage)
        }
    }

    private data class ReviewContext(
        val mrUrl: String?,
        val mrId: String?,
        val title: String?,
        val sourceBranch: String,
        val targetRef: String,
        val baseCommit: String?,
        val headCommit: String?
    )

    private sealed interface DiffAnchor {
        data class Revisions(val baseCommit: String, val headCommit: String) : DiffAnchor
        data class TargetRef(val targetRef: String, val headCommit: String) : DiffAnchor
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("CodeReview.MrReview", Listener::class.java)

        fun getInstance(project: Project): MrReviewService = project.service()
    }
}
