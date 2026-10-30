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
    fun toPixels(value: Int, screenWidthDp: Int, density: Float): Int =
        (value * screenWidthDp / 360f * density).toInt()
}
