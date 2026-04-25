package com.codereview.diff

import com.intellij.diff.contents.DiffContent
import com.intellij.openapi.actionSystem.DataKey

data class ReviewDiffCommentTarget(
    val targetRef: String,
    val mergeBaseCommit: String,
    val headCommit: String,
    val oldPath: String?,
    val newPath: String?,
    val newContent: DiffContent
)

object ReviewDiffDataKeys {
    val COMMENT_TARGET: DataKey<ReviewDiffCommentTarget> = DataKey.create("CodeReview.MrDiffCommentTarget")
}
