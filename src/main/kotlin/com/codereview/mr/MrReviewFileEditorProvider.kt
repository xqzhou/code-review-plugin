package com.codereview.mr

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class MrReviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is MrReviewVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        MrReviewFileEditor(project, file as MrReviewVirtualFile)

    override fun getEditorTypeId(): String = "code-review-mr-review"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
