package com.qishimeng.game.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration

enum class ScreenMode(
    val value: String,
    val requestedOrientation: Int,
    val configurationOrientation: Int
) {
    HORIZONTAL("horizontal", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE),
    VERTICAL("vertical", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);

    companion object {
        fun fromConfiguration(orientation: Int): ScreenMode =
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) HORIZONTAL else VERTICAL

        fun fromValue(value: Any?): ScreenMode = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("参数无效")
    }
}

class ScreenModeController(
    private val currentOrientation: () -> Int,
    private val requestOrientation: (Int) -> Unit,
    val packageMode: ScreenMode = ScreenMode.fromConfiguration(currentOrientation())
) {
    private var selectedMode: ScreenMode? = null

    @Synchronized
    fun currentMode(): ScreenMode = selectedMode
        ?: ScreenMode.fromConfiguration(currentOrientation()).also { selectedMode = it }

    @Synchronized
    fun change(value: Any?): Boolean {
        val target = ScreenMode.fromValue(value)
        if (currentMode() != target) {
            requestOrientation(target.requestedOrientation)
            selectedMode = target
        }
        return true
    }

    fun restorePackageMode(): Boolean = change(packageMode.value)
}
