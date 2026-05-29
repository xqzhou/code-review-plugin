package com.codereview.mr

import com.intellij.openapi.fileTypes.PlainTextFileType
import org.junit.Assert.assertEquals
import org.junit.Test

class MrReviewVirtualFileTest {
    @Test
    fun `uses neutral file type even when title ends with markdown extension`() {
        val file = MrReviewVirtualFile(
            "feat: implement rescind based on docs/design/rescind.md",
            "335::origin/master::base::head"
        )

        assertEquals(PlainTextFileType.INSTANCE, file.fileType)
    }
}
