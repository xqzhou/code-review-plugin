package com.codereview.model

data class MrReviewSession(
    val mrUrl: String?,
    val mrId: String?,
    val title: String?,
    val sourceBranch: String,
    val targetRef: String,
    val mergeBaseCommit: String,
    val headCommit: String,
    val changes: List<MrReviewChange>,
    val skippedBinaryFiles: Int
) {
    val sessionKey: String
        get() = listOf(mrId.orEmpty(), targetRef, mergeBaseCommit, headCommit).joinToString("::")
}

data class MrReviewChange(
    val status: String,
    val oldPath: String?,
    val newPath: String?,
    val oldContent: String,
    val newContent: String,
    val addedLines: Int,
    val removedLines: Int
)
