package com.qishimeng.game.bridge

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** 仅用于 Debug 设备测试，不进入 Release APK。 */
class BridgeTestActivity : Activity() {
    lateinit var host: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
