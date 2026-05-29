package com.codereview.mr

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile

class MrReviewVirtualFile(name: String, val sessionKey: String) : LightVirtualFile(name, PlainTextFileType.INSTANCE, "") {
    init {
        isWritable = false
    }

    override fun getFileType(): FileType = PlainTextFileType.INSTANCE
}
