package com.qishimeng.game.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration

enum class ScreenMode(val value: String, val requestedOrientation: Int) {
    HORIZONTAL("horizontal", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    VERTICAL("vertical", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

    companion object {
        fun fromConfiguration(orientation: Int): ScreenMode =
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) HORIZONTAL else VERTICAL

        fun fromValue(value: Any?): ScreenMode = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("参数无效")
    }
}

class ScreenModeController(
    private val currentOrientation: () -> Int,
    private val requestOrientation: (Int) -> Unit
) {
    private var pendingMode: ScreenMode? = null

    fun change(value: Any?): Boolean {
        val target = ScreenMode.fromValue(value)
        val current = ScreenMode.fromConfiguration(currentOrientation())
        if (current != target || pendingMode?.let { it != target } == true) {
            requestOrientation(target.requestedOrientation)
            pendingMode = target
        }
        return true
    }
}
