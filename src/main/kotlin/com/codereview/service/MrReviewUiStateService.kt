package com.codereview.service

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = "CodeReviewMrReviewUiState",
    storages = [Storage("code-review-plugin.xml")]
)
@Service(Service.Level.PROJECT)
class MrReviewUiStateService : SimplePersistentStateComponent<MrReviewUiStateService.UiState>(UiState()) {

    class UiState : BaseState() {
        var fileListVisible by property(true)
        var fileListProportion by property(0.24f)
    }

    fun fileListVisible(): Boolean = state.fileListVisible

    fun fileListProportion(): Float = state.fileListProportion

    fun setFileListState(visible: Boolean, proportion: Float) {
        state.fileListVisible = visible
        state.fileListProportion = proportion
    }

    companion object {
        fun getInstance(project: Project): MrReviewUiStateService = project.service()
    }
}
