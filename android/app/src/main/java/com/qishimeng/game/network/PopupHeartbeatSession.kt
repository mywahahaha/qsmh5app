package com.qishimeng.game.network

import com.qishimeng.game.web.UrlPolicy

/** update/stop/close 和 dispatch 的任务均在宿主主线程执行。 */
class PopupHeartbeatSession(
    url: String?,
    intervalSeconds: Int?,
    poll: (String) -> HeartbeatResponse,
    private val dispatch: (() -> Unit) -> Unit,
    private val onResponse: (HeartbeatResponse) -> Unit,
    scheduler: HeartbeatScheduler? = null
) {
    @Volatile private var epoch = 0L
    private var started = false
    private val service: PopupHeartbeatService?

    init {
        val endpoint = url?.trim()?.takeIf(UrlPolicy::isAllowedPageUrl)
        service = if (endpoint != null && intervalSeconds != null && intervalSeconds > 0) {
            val deliver: (HeartbeatResponse) -> Unit = { response ->
                val generation = epoch
                if (response.code == 0) dispatch {
                    if (started && epoch == generation) onResponse(response)
                }
            }
            if (scheduler == null) PopupHeartbeatService({ poll(endpoint) }, intervalSeconds, deliver)
            else PopupHeartbeatService({ poll(endpoint) }, intervalSeconds, deliver, scheduler)
        } else null
    }

    fun update(sessionReady: Boolean, floatingBallVisible: Boolean) {
        if (!sessionReady) {
            stop()
        } else if (floatingBallVisible && !started && service != null) {
            started = true
            service.start()
        }
        // 已启动后，服务端隐藏球或功能页临时遮挡球都不能停止心跳，否则无法恢复显示。
    }

    fun stop() {
        started = false
        epoch++
        service?.stop()
    }

    fun close() {
        stop()
        service?.close()
    }
}
