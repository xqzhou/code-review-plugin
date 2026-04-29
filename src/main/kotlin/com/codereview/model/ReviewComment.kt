package com.codereview.model

data class ReviewThread(
    var id: String = "",
    var file: String = "",
    var side: String = "new",
    var startLine: Int = 1,
    var endLine: Int = 1,
    var startColumn: Int? = null,
    var endColumn: Int? = null,
    var createdAt: String = "",
    var updatedAt: String = "",
    var status: String = "open",
    var outdated: Boolean = false,
    var reviewTargetRef: String? = null,
    var diffBaseCommit: String? = null,
    var diffHeadCommit: String? = null,
    var diffOldPath: String? = null,
    var diffNewPath: String? = null,
    var syncStatus: String = "local_only",
    var deletedAt: String? = null,
    var remoteThreadId: String? = null,
    var findingId: String? = null,
    var comments: MutableList<ReviewComment> = mutableListOf()
)

data class ReviewComment(
    var id: String = "",
    var threadId: String = "",
    var content: String = "",
    var createdAt: String = "",
    var updatedAt: String = "",
    var createdByName: String? = null,
    var createdByUsername: String? = null,
    var syncStatus: String = "local_only",
    var deletedAt: String? = null,
    var remoteCommentId: String? = null
) {
    @Transient
    var file: String = ""

    @Transient
    var side: String = "new"

    @Transient
    var startLine: Int = 1

    @Transient
    var endLine: Int = 1

    @Transient
    var startColumn: Int? = null

    @Transient
    var endColumn: Int? = null

    @Transient
    var reviewTargetRef: String? = null

    @Transient
    var diffBaseCommit: String? = null

    @Transient
    var diffHeadCommit: String? = null

    @Transient
    var diffOldPath: String? = null

    @Transient
    var diffNewPath: String? = null

    @Transient
    var remoteThreadId: String? = null

    @Transient
    var threadStatus: String = "open"

    @Transient
    var outdated: Boolean = false

    @Transient
    var findingId: String? = null

    var comment: String
        get() = content
        set(value) {
            content = value
        }

    val remoteAuthorName: String?
        get() = createdByName

    val remoteDiscussionId: String?
        get() = remoteThreadId

    val remoteNoteId: String?
        get() = remoteCommentId

    val remoteProvider: String?
        get() = null

    val remoteUrl: String?
        get() = null

    val lastSyncedAt: String?
        get() = null

    val codeSnippet: String
        get() = ""
}

data class LegacyStoredComment(
    var id: String = "",
    var file: String = "",
    var startLine: Int = 1,
    var endLine: Int = 1,
    var codeSnippet: String = "",
    var comment: String = "",
    var createdAt: String = "",
    var updatedAt: String = "",
    var status: String = "pending",
    var syncStatus: String = "local_only",
    var createdByName: String? = null,
    var deletedAt: String? = null,
    var remoteProvider: String? = null,
    var remoteMergeRequestId: String? = null,
    var remoteDiscussionId: String? = null,
    var remoteNoteId: String? = null,
    var remoteAuthorName: String? = null,
    var remoteUrl: String? = null,
    var lastSyncedAt: String? = null,
    var findingId: String? = null,
    var reviewTargetRef: String? = null,
    var diffBaseCommit: String? = null,
    var diffHeadCommit: String? = null,
    var diffOldPath: String? = null,
    var diffNewPath: String? = null
)

data class CommentFile(
    var branch: String = "",
    var threads: MutableList<ReviewThread> = mutableListOf(),
    var comments: MutableList<LegacyStoredComment>? = null,
    var reviewDoc: String? = null,
    var mrUrl: String? = null,
    var mrId: String? = null,
    var sourceBranch: String? = null,
    var targetBranch: String? = null,
    var baseCommit: String? = null,
    var headCommit: String? = null,
    var title: String? = null
)
