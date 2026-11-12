package com.qishimeng.game.web

class PendingGameNavigation {
    private var url: String? = null

    @Synchronized
    fun defer(value: String) {
        url = value
    }

    @Synchronized
    fun take(): String? = url.also { url = null }

    @Synchronized
    fun clear() {
        url = null
    }
}
