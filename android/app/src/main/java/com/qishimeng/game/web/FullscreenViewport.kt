package com.qishimeng.game.web

data class FullscreenViewport(
    val widthPx: Int,
    val heightPx: Int
) {
    companion object {
        fun resolve(hostWidthPx: Int, hostHeightPx: Int, displayWidthPx: Int, displayHeightPx: Int): FullscreenViewport =
            FullscreenViewport(
                widthPx = hostWidthPx.takeIf { it > 0 } ?: displayWidthPx,
                heightPx = hostHeightPx.takeIf { it > 0 } ?: displayHeightPx
            )
    }
}
