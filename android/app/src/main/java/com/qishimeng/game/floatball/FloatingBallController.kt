package com.qishimeng.game.floatball

data class FloatingBallState(val visible: Boolean, val active: Boolean)

class FloatingBallController(
    private val initiallyEnabled: Boolean,
    private val onIconStateChanged: (Boolean) -> Unit = {}
) {
    private var enabled = initiallyEnabled
    private var sessionReady = false
    private var welfareOpen = false
    private var active = false

    fun setSessionReady(ready: Boolean) {
        sessionReady = ready
    }

    fun setWelfareOpen(open: Boolean) {
        welfareOpen = open
    }

    fun setRuntimeState(show: Boolean, active: Boolean) {
        enabled = show
        if (this.active != active) {
            this.active = active
            onIconStateChanged(active)
        }
    }

    fun resetSession() {
        sessionReady = false
        welfareOpen = false
        setRuntimeState(initiallyEnabled, false)
    }

    fun state(): FloatingBallState = FloatingBallState(enabled && sessionReady && !welfareOpen, active)
}

object FloatingBallLayout {
    fun toPixels(value: Int, density: Float): Int = (value * density).toInt()

    fun horizontalOffsetToPixels(value: Int, screenWidthDp: Int, density: Float): Int =
        (value * screenWidthDp / 360f * density).toInt()

    fun resolveTop(top: Double?, bottom: Double?, viewportHeightPx: Int, ballHeightPx: Int, density: Float = 1f): Int {
        val offset = top ?: bottom ?: 0.0
        // 仅 (0, 1) 为容器高度比例；1 及以上按 dp 转为布局所需的 px。
        val offsetPx = if (offset > 0.0 && offset < 1.0) offset * viewportHeightPx else offset * density
        val position = if (top != null || bottom == null) offsetPx
            else viewportHeightPx.toDouble() - ballHeightPx - offsetPx
        return position.coerceIn(0.0, (viewportHeightPx - ballHeightPx).coerceAtLeast(0).toDouble()).toInt()
    }
}
