package com.qishimeng.game.web

import com.qishimeng.game.config.LayoutValue
import kotlin.math.roundToInt

data class ResolvedWindowLayout(
    val widthPx: Int,
    val heightPx: Int,
    val leftPx: Int,
    val topPx: Int
)

object WindowLayout {
    fun resolve(
        width: LayoutValue,
        height: LayoutValue,
        left: LayoutValue,
        top: LayoutValue,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Float
    ): ResolvedWindowLayout {
        val widthPx = toPixels(width, viewportWidthPx, density)
        val heightPx = toPixels(height, viewportHeightPx, density)
        val leftPx = if (left == LayoutValue.Center) ((viewportWidthPx - widthPx) / 2).coerceAtLeast(0) else toPixels(left, viewportWidthPx, density)
        val topPx = if (top == LayoutValue.Center) ((viewportHeightPx - heightPx) / 2).coerceAtLeast(0) else toPixels(top, viewportHeightPx, density)
        return ResolvedWindowLayout(widthPx, heightPx, leftPx, topPx)
    }

    fun toPixels(value: LayoutValue, viewportPx: Int, density: Float): Int = when (value) {
        LayoutValue.Center -> 0
        is LayoutValue.Pixels -> (value.value * density).roundToInt()
        is LayoutValue.Percent -> (viewportPx * value.value / 100f).roundToInt()
    }
}
