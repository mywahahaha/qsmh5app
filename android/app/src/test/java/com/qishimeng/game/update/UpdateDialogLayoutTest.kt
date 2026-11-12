package com.qishimeng.game.update

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateDialogLayoutTest {
    @Test fun blankServerVersionStillShowsHonestVersionLabel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialog(activity, UpdateInfo(
            "邀您体验新版本", "游戏有更新啦!", "", "https://example.com/app.apk", false,
            width = UpdateDimension.Percent(40f), time = " 2026-11-05"
        ), {}, {}, {})
        try {
            dialog.show()
            val version = dialog.window!!.decorView.findViewWithTag<TextView>("update_metadata_version")
            val time = dialog.window!!.decorView.findViewWithTag<TextView>("update_metadata_time")
            val metadata = dialog.window!!.decorView.findViewWithTag<View>("update_metadata")
            assertEquals(View.VISIBLE, metadata.visibility)
            assertEquals(View.VISIBLE, version.visibility)
            assertEquals("最新版本：未提供", version.text.toString())
            assertEquals("更新时间：2026-11-05", time.text.toString())
        } finally {
            dialog.dismiss()
            activity.finish()
        }
    }

    @Test fun wideDialogKeepsVersionAndTimeTogetherWithFifteenPixelGap() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialog(activity, UpdateInfo(
            "邀您体验新版本", "游戏有更新啦!", "1.0.2", "https://example.com/app.apk", false,
            width = UpdateDimension.Percent(80f), time = "2026-11-05"
        ), {}, {}, {})
        try {
            dialog.show()
            val decor = dialog.window!!.decorView
            UpdateDialog::class.java.getDeclaredMethod("layoutFor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(dialog, 1000, 700)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY)
            repeat(2) {
                decor.measure(widthSpec, heightSpec)
                decor.layout(0, 0, 1000, 700)
            }
            val metadata = decor.findViewWithTag<LinearLayout>("update_metadata")
            val version = decor.findViewWithTag<TextView>("update_metadata_version")
            val time = decor.findViewWithTag<TextView>("update_metadata_time")
            assertEquals(LinearLayout.HORIZONTAL, metadata.orientation)
            assertEquals(15, time.left - version.right)
        } finally {
            dialog.dismiss()
            activity.finish()
        }
    }

    @Test fun narrowWidthStacksMissingVersionPlaceholderAboveTime() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialog(activity, UpdateInfo(
            "邀您体验新版本", "游戏有更新啦!", "", "https://example.com/app.apk", false,
            width = UpdateDimension.Dp(25f), time = "2026-11-05"
        ), {}, {}, {})
        try {
            dialog.show()
            val decor = dialog.window!!.decorView
            UpdateDialog::class.java.getDeclaredMethod("layoutFor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(dialog, 1000, 700)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY)
            repeat(2) {
                decor.measure(widthSpec, heightSpec)
                decor.layout(0, 0, 1000, 700)
            }
            val metadata = decor.findViewWithTag<LinearLayout>("update_metadata")
            assertEquals(LinearLayout.VERTICAL, metadata.orientation)
        } finally {
            dialog.dismiss()
            activity.finish()
        }
    }

    @Test fun updatedGeometryShortensHeaderAndBottomSpaceAndEnlargesControls() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialog(activity, UpdateInfo(
            "邀您体验新版本", "游戏有更新啦!", "1.0.2", "https://example.com/app.apk", false,
            width = UpdateDimension.Percent(40f), time = "2026-11-05"
        ), {}, {}, {})
        try {
            dialog.show()
            UpdateDialog::class.java.getDeclaredMethod("layoutFor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(dialog, 1000, 700)
            val decor = dialog.window!!.decorView
            val title = decor.findViewWithTag<TextView>("update_title")
            val action = decor.findViewWithTag<TextView>("update_action")
            val close = decor.findViewWithTag<View>("update_close")
            val body = action.parent as LinearLayout
            val scale = 400f / 530f
            val oldHeader = (198f * scale).toInt()
            val oldBottom = (57f * scale).toInt()
            val oldAction = maxOf((25f * activity.resources.displayMetrics.density).toInt(), (44f * scale).toInt())
            val oldClose = maxOf((22f * activity.resources.displayMetrics.density).roundToInt(), (34f * scale).roundToInt())
            assertTrue((title.parent as View).layoutParams.height <= oldHeader * 0.82f)
            assertTrue(body.paddingBottom <= oldBottom / 2 + 1)
            assertEquals(oldAction + 10, action.layoutParams.height)
            assertEquals((oldClose * 2 * 0.8f).roundToInt(), close.layoutParams.width)
            assertEquals(close.layoutParams.width, close.layoutParams.height)
            assertEquals(0xff000000.toInt(), (close.background as GradientDrawable).color!!.defaultColor)
        } finally {
            dialog.dismiss()
            activity.finish()
        }
    }
}
