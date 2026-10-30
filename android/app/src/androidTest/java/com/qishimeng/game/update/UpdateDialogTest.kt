package com.qishimeng.game.update

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.qishimeng.game.bridge.BridgeTestActivity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var dialog: UpdateDialog? = null
    private var closed = 0
    private var updated = 0
    private var installed = 0
    private fun info(force: Boolean = false, height: Float? = null) = UpdateInfo(
        "邀您体验新版本", "发现新版本 V1.0.12", "1.0.2", "https://example.com/app.apk", force,
        widthPercent = 50f, heightPercent = height, time = "2026-06-10"
    )
    private fun show(info: UpdateInfo) {
        onMain {
            dialog = UpdateDialog(activityRule.activity, info, { updated++ }, { installed++ }, { closed++ })
            dialog!!.show()
        }
        instrumentation.waitForIdleSync()
    }
    @After fun tearDown() { onMain { dialog?.dismiss() } }

    @Test fun offerUsesReferenceImagePercentWidthDimAndExplicitClose() {
        show(info())
        onMain {
            val root = dialog!!.window!!.decorView
            val panel = root.findViewWithTag<View>("update_panel")
            assertEquals("接口宽度整体缩小 30%", root.width * 0.5f * 0.7f, panel.width.toFloat(), 2f)
            assertEquals(0.45f, dialog!!.window!!.attributes.dimAmount, 0.001f)
            assertTrue(root.findViewWithTag<View>("update_close").isShown)
            assertEquals("邀您体验新版本", root.findViewWithTag<TextView>("update_title").text.toString())
            root.findViewWithTag<View>("update_action").performClick()
            assertEquals(1, updated)
        }
        screenshot("update-offer.png")
        onMain { dialog!!.onBackPressed(); assertEquals(1, closed) }
    }

    @Test fun forcedWindowKeepsBackBlockedAndReadyHasInstallButtonBelowProgress() {
        show(info(force = true))
        onMain {
            val root = dialog!!.window!!.decorView
            assertFalse(root.findViewWithTag<View>("update_close").isShown)
            dialog!!.onBackPressed(); assertEquals(0, closed)
            dialog!!.render(UpdateState.Downloading(48))
            assertFalse(root.findViewWithTag<View>("update_action").isShown)
            assertTrue(root.findViewWithTag<TextView>("update_status").text.contains("48%"))
        }
        screenshot("update-progress.png")
        onMain {
            val root = dialog!!.window!!.decorView
            dialog!!.render(UpdateState.Ready(File("fixture.apk")))
            assertEquals("立即安装", root.findViewWithTag<TextView>("update_action").text.toString())
            assertTrue(root.findViewWithTag<View>("update_progress").isShown)
            root.findViewWithTag<View>("update_action").performClick()
            assertEquals(1, installed)
        }
        screenshot("update-ready.png")
    }

    @Test fun fixedHeightIsPercentageAndLongContentCanScrollWithoutHidingClose() {
        show(info(height = 60f).copy(description = "新版本更新内容\n".repeat(40)))
        onMain {
            val root = dialog!!.window!!.decorView
            assertEquals("接口固定高度整体缩小 30%", root.height * 0.6f * 0.7f, root.findViewWithTag<View>("update_panel").height.toFloat(), 2f)
            assertTrue(root.findViewWithTag<View>("update_close").isShown)
            val panel = root.findViewWithTag<android.widget.ScrollView>("update_panel")
            assertTrue("内容超过固定高度弹窗时必须可以滚动", panel.getChildAt(0).height > panel.height)
        }
    }

    @Test fun autoHeightFollowsStateAndBlankMetadataIsHidden() {
        show(info().copy(version = "", time = ""))
        var before = 0
        onMain {
            val root = dialog!!.window!!.decorView
            before = root.findViewWithTag<View>("update_panel").height
            assertFalse(root.findViewWithTag<View>("update_metadata").isShown)
            dialog!!.render(UpdateState.Ready(File("fixture.apk")))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val root = dialog!!.window!!.decorView
            assertTrue("auto 高度应随新增进度及安装按钮调整", root.findViewWithTag<View>("update_panel").height > before)
        }
    }

    @Test fun multilineTitleIsFullyMeasuredAndRemainsScrollable() {
        show(info().copy(title = "新版本更新邀您体验\n".repeat(9).trim()))
        onMain {
            val root = dialog!!.window!!.decorView
            val title = root.findViewWithTag<TextView>("update_title")
            assertEquals(9, title.lineCount)
            assertTrue("标题不能被头部裁切", title.height >= title.layout.height)
            val header = title.parent as View
            assertTrue("头部应容纳完整标题", header.height >= title.top + title.height)
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File("/sdcard/Download", name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
