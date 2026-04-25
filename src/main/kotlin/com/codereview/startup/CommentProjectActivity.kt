package com.codereview.startup

import com.codereview.editor.CommentEditorDecorations
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CommentProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CommentEditorDecorations>()
    }
}
