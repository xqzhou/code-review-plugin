package com.codereview.model

data class CommentDraft(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val codeSnippet: String,
    val initialComment: String = "",
    val existingCommentId: String? = null
) {
    val location: String
        get() = "$file:${if (startLine == endLine) "$startLine" else "$startLine-$endLine"}"
}
