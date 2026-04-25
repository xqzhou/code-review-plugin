package com.codereview.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object ProjectFiles {

    fun relativePath(project: Project, file: VirtualFile): String? {
        val baseDir = projectBaseDir(project) ?: return file.path
        return VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
    }

    fun find(project: Project, relativePath: String): VirtualFile? {
        val baseDir = projectBaseDir(project)
        return baseDir?.findFileByRelativePath(relativePath)
            ?: LocalFileSystem.getInstance().findFileByPath(relativePath)
    }

    private fun projectBaseDir(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(basePath)
    }
}
