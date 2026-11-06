package com.fastdrop.desktop

import com.fastdrop.DesktopFastDropRuntime
import com.fastdrop.DesktopUiState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class DesktopFastDropViewModel(private val runtime: DesktopFastDropRuntime = DesktopFastDropRuntime()) {
    val state: StateFlow<DesktopUiState> = runtime.state

    fun start() = runtime.start()
    fun send(file: File) = runtime.send(file)
    fun confirmSas(accepted: Boolean) = runtime.confirmSas(accepted)
    suspend fun stop() = runtime.stop()
}
