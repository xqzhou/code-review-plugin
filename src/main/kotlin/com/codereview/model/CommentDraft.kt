package com.codereview.model

data class CommentDraft(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val codeSnippet: String,
    val side: String = "new",
    val startColumn: Int? = null,
    val endColumn: Int? = null,
    val initialComment: String = "",
    val existingCommentId: String? = null,
    val reviewTargetRef: String? = null,
    val diffBaseCommit: String? = null,
    val diffHeadCommit: String? = null,
    val diffOldPath: String? = null,
    val diffNewPath: String? = null
) {
    val location: String
        get() = "$file:${lineColumnRange()}"

    private fun lineColumnRange(): String {
        val startColumn = startColumn
        val endColumn = endColumn
        if (startColumn != null && endColumn != null) {
            return if (startLine == endLine) {
                "$startLine:$startColumn-$endColumn"
            } else {
                "$startLine:$startColumn-$endLine:$endColumn"
            }
        }
        return if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    }
}
