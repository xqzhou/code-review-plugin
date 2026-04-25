package com.codereview.mr

import com.intellij.testFramework.LightVirtualFile

class MrReviewVirtualFile(name: String, val sessionKey: String) : LightVirtualFile(name) {
    init {
        isWritable = false
    }
}
