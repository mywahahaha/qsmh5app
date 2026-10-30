package com.qishimeng.game.update

import java.io.File

interface UpdateDownloadTask {
    fun start(url: String, onProgress: (Long, Long) -> Unit, onComplete: (Result<File>) -> Unit)
    fun cancel()
}

sealed interface UpdateState {
    data object Offered : UpdateState
    data class Downloading(val percent: Int?) : UpdateState
    data class Ready(val apk: File) : UpdateState
    data object Closed : UpdateState
}

/** 由主线程调用；下载器也在主线程交付回调，关闭后所有迟到结果均忽略。 */
class UpdateService(
    private val info: UpdateInfo,
    private val downloader: UpdateDownloadTask,
    private val installer: (File) -> Unit,
    private val openBrowser: (String) -> Unit,
    private val render: (UpdateState) -> Unit,
    private val dismiss: () -> Unit,
    private val showError: (String) -> Unit,
    private val continueStartup: () -> Unit
) {
    var state: UpdateState = UpdateState.Offered
        private set

    fun start() { if (state != UpdateState.Closed) render(state) }

    fun update() {
        if (state != UpdateState.Offered) return
        try {
            if (info.popup) {
                openBrowser(info.downloadUrl)
                if (!info.force) finish()
            } else {
                setState(UpdateState.Downloading(null))
                downloader.start(info.downloadUrl, { received, total ->
                    if (state is UpdateState.Downloading) {
                        val percent = if (total > 0) ((received.toDouble() / total) * 100).toInt().coerceIn(0, 99) else null
                        setState(UpdateState.Downloading(percent))
                    }
                }, { result ->
                    if (state is UpdateState.Downloading) result.fold(
                        onSuccess = { apk -> setState(UpdateState.Ready(apk)); installReady() },
                        onFailure = { fail("更新下载失败，请稍后重试") }
                    )
                })
            }
        } catch (_: Exception) {
            fail("更新处理失败，请稍后重试")
        }
    }

    fun installReady() {
        val ready = state as? UpdateState.Ready ?: return
        try { installer(ready.apk) } catch (_: Exception) { fail("无法启动安装，请稍后重试") }
    }

    fun close() { if (!info.force) finish() }

    fun fail(message: String) {
        if (state == UpdateState.Closed) return
        runCatching { showError(message) }
        finish()
    }

    fun destroy() {
        if (state == UpdateState.Closed) return
        state = UpdateState.Closed
        downloader.cancel()
        dismiss()
    }

    private fun setState(value: UpdateState) { state = value; render(value) }

    private fun finish() {
        if (state == UpdateState.Closed) return
        destroy()
        continueStartup()
    }
}
