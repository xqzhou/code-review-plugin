package com.codereview.model

data class ReviewComment(
    var id: String,
    var file: String,
    var startLine: Int,
    var endLine: Int,
    var codeSnippet: String,
    var comment: String,
    var createdAt: String,
    var updatedAt: String,
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
    var findingId: String? = null
)

data class CommentFile(
    var branch: String,
    var comments: MutableList<ReviewComment> = mutableListOf(),
    var reviewDoc: String? = null
)
