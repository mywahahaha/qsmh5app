package com.qishimeng.game.web

enum class PageLoadPhase { IDLE, LOADING, WAITING_RETRY, READY, ERROR, CLOSED }

data class PageLoadFailure(val code: String, val message: String, val retryable: Boolean = true) {
    companion object {
        fun network(systemCode: Int, description: String): PageLoadFailure = when {
            description.contains("PROXY", ignoreCase = true) -> PageLoadFailure("H5_PROXY", "网络代理连接失败，请检查代理设置或更换网络。")
            systemCode == -2 -> PageLoadFailure("H5_DNS", "无法解析服务器地址，请检查网络或更换网络。")
            systemCode == -8 -> timeout()
            systemCode == -11 -> ssl()
            systemCode == -6 || systemCode == -7 -> PageLoadFailure("H5_CONNECT", "无法连接服务器，请检查网络，并允许本游戏联网。")
            else -> PageLoadFailure("H5_NETWORK", "页面连接失败，请检查网络后重试。")
        }

        fun timeout() = PageLoadFailure("H5_TIMEOUT", "页面连接超时，请检查网络后重试。")
        fun ssl() = PageLoadFailure("H5_SSL", "安全连接校验失败，请检查设备时间或更换网络。", retryable = false)
        fun http(status: Int) = PageLoadFailure("H5_HTTP_$status", "服务器暂时无法提供此页面，请稍后重试。",
            retryable = status == 408 || status == 429 || status in 500..599)
    }
}

/** 每个主文档独立管理重试；错误页的完成回调、重复错误和关闭后的回调不得推进状态。 */
class PageLoadRecovery {
    var phase = PageLoadPhase.IDLE
        private set
    var attempt = 0
        private set
    var failure: PageLoadFailure? = null
        private set
    private var automaticRetry = true

    fun start(allowAutomaticRetry: Boolean = true): Boolean {
        if (phase == PageLoadPhase.CLOSED) return false
        attempt = 1
        failure = null
        automaticRetry = allowAutomaticRetry
        phase = PageLoadPhase.LOADING
        return true
    }

    fun fail(error: PageLoadFailure): Boolean {
        if (phase != PageLoadPhase.LOADING) return false
        failure = error
        phase = if (automaticRetry && error.retryable && attempt < MAX_ATTEMPTS) PageLoadPhase.WAITING_RETRY else PageLoadPhase.ERROR
        return true
    }

    fun retry(): Boolean {
        if (phase != PageLoadPhase.WAITING_RETRY) return false
        attempt++
        phase = PageLoadPhase.LOADING
        return true
    }

    fun succeed(): Boolean {
        if (phase != PageLoadPhase.LOADING) return false
        failure = null
        phase = PageLoadPhase.READY
        return true
    }

    fun close() { phase = PageLoadPhase.CLOSED }

    companion object {
        // 首次加载之外，最多自动重试三次。
        const val MAX_ATTEMPTS = 4
        const val ATTEMPT_TIMEOUT_MILLIS = 5_000L
    }
}
