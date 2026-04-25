package com.codereview.model

data class CommentDraft(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val codeSnippet: String,
    val initialComment: String = "",
    val existingCommentId: String? = null,
    val reviewTargetRef: String? = null,
    val diffBaseCommit: String? = null,
    val diffHeadCommit: String? = null,
    val diffOldPath: String? = null,
    val diffNewPath: String? = null
) {
    val location: String
        get() = "$file:${if (startLine == endLine) "$startLine" else "$startLine-$endLine"}"
}
