package com.qishimeng.game.device

import android.content.res.Configuration

class WelfareScreenModeSession(
    private val currentMode: () -> ScreenMode,
    private val selectHorizontal: () -> Unit,
    private val requestOrientation: (Int) -> Unit
) {
    private var waitingForLandscape = false
    private var entryMode: ScreenMode? = null

    fun shouldOpenNow(orientation: Int): Boolean {
        if (waitingForLandscape) return false
        entryMode = currentMode()
        selectHorizontal()
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) return true

        requestOrientation(ScreenMode.HORIZONTAL.requestedOrientation)
        waitingForLandscape = true
        return false
    }

    fun onConfigurationChanged(orientation: Int): Boolean {
        if (!waitingForLandscape || orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        waitingForLandscape = false
        return true
    }

    fun cancelPendingOpen() {
        waitingForLandscape = false
        entryMode = null
    }

    fun info(): WelfarePageInfo? = entryMode?.let { WelfarePageInfo(it, currentMode()) }
}

data class WelfarePageInfo(
    val entryMode: ScreenMode,
    val currentMode: ScreenMode
)
