package com.qishimeng.game.device

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager

/** 系统报告的屏幕缺口包含刘海和挖孔；旧版 Android 没有统一的检测接口。 */
object NotchScreenDetector {
    @Suppress("DEPRECATION")
    fun fromDisplay(activity: Activity): Int = if (Build.VERSION.SDK_INT >= 29) {
        fromCutout(activity.windowManager.defaultDisplay.cutout)
    } else 0

    fun fromInsets(insets: WindowInsets): Int = if (Build.VERSION.SDK_INT >= 28) {
        fromCutout(insets.displayCutout)
    } else 0

    internal fun fromCutout(cutout: DisplayCutout?): Int = fromBoundingRects(cutout?.boundingRects)

    internal fun fromBoundingRects(rects: List<Rect>?): Int =
        if (rects?.any { !it.isEmpty } == true) 1 else 0

    internal fun layoutMode(sdkInt: Int): Int =
        if (sdkInt >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
}
