package com.qishimeng.game.network

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.qishimeng.game.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实 Activity 验证转义的服务端错误文案不会在网络层和界面之间丢失。 */
@RunWith(AndroidJUnit4::class)
class InitializationFailureMessageTest {
    @get:Rule val activityRule = ActivityTestRule(MainActivity::class.java, false, false)

    @Test
    fun escapedServerMessageIsDisplayedInsteadOfCodeFallback() {
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.PRE_ON_CREATE) {
                val client = ApiClient(HttpTransport {
                    ApiResponse(200, """{"code":100,"msg":"\u5f53\u524d\u7248\u672c\u5df2\u4f5c\u5e9f,\u8bf7\u8054\u7cfb\u5ba2\u670d\u4e0b\u8f7d\u6700\u65b0\u7248\u672c","data":[]}""")
                })
                MainActivity::class.java.getDeclaredField("apiClient").apply { isAccessible = true }
                    .set(activity, client)
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(lifecycle)
        try {
            activityRule.launchActivity(null)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val expected = "游戏初始化失败：当前版本已作废,请联系客服下载最新版本"
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var actual = ""
            while (System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    val content = activityRule.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    fun findText(view: android.view.View): String = when (view) {
                        is TextView -> view.text.toString()
                        is android.view.ViewGroup -> (0 until view.childCount).joinToString("") { findText(view.getChildAt(it)) }
                        else -> ""
                    }
                    actual = findText(content)
                }
                if (actual == expected) break
                Thread.sleep(50)
            }
            assertEquals(expected, actual)
        } finally {
            activityRule.finishActivity()
            monitor.removeLifecycleCallback(lifecycle)
        }
    }
}
