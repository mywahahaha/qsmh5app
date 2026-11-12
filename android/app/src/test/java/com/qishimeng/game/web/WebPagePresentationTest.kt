package com.qishimeng.game.web

import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** 验证真实窗口的显示时机；WebView 渲染及网络首帧另由设备 HTTP 用例覆盖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class WebPagePresentationTest {
    private lateinit var activity: ActivityController<AppCompatActivity>
    private lateinit var host: FrameLayout
    private lateinit var game: View
    private lateinit var container: WebContainer
    private lateinit var welfare: WebInstance
    private var contentReadyCount = 0
    private val url = "https://fixture.example/welfare"

    @Before fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().visible()
        host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        host.layout(0, 0, 600, 400)
        game = View(activity.get()).apply { setBackgroundColor(Color.GREEN) }
        host.addView(game, FrameLayout.LayoutParams(-1, -1))
        val spec = PageSpec("web_welfare", url, LayoutValue.Percent(100f), LayoutValue.Percent(100f),
            LayoutValue.Pixels(0), LayoutValue.Pixels(0))
        container = WebContainer(activity.get(), host, mapOf("welfare" to spec), emptySet(),
            onPageVisible = { contentReadyCount++ })
        welfare = container.openConfigured("welfare")!!
    }

    @After fun tearDown() {
        container.instances().forEach { container.close(it.spec.instance) }
        settleTransition()
        activity.pause().stop().destroy()
    }

    @Test fun loadingWindowDoesNotCoverGameEvenAfterNormalOpeningAnimationDuration() {
        settleTransition()
        assertTransparentLoading()
        assertSame(game, host.getChildAt(0))
        assertSame(host, game.parent)
        assertEquals(View.VISIBLE, game.visibility)
        assertEquals(0, contentReadyCount)
    }

    @Test fun committedContentFadesInAndDuplicateCommitDoesNotNotifyAgain() {
        settleTransition()
        assertTransparentLoading()
        welfare.pageView.onPageStarted(url)
        welfare.pageView.onPageCommitVisible(url)
        settleTransition()
        assertEquals(1f, welfare.pageView.alpha, 0.001f)
        assertEquals(View.GONE, welfare.pageView.getChildAt(1).visibility)
        assertEquals(View.VISIBLE, welfare.webView.visibility)
        assertEquals(1, contentReadyCount)
        welfare.pageView.onPageCommitVisible(url)
        assertEquals(1, contentReadyCount)
    }

    @Test fun unrelatedOrPrematureCommitCannotRevealLoadingWindow() {
        welfare.pageView.onPageCommitVisible(url)
        welfare.pageView.onPageStarted(url)
        welfare.pageView.onPageCommitVisible("https://fixture.example/old")
        settleTransition()
        assertTransparentLoading()
        assertEquals(0, contentReadyCount)
    }

    @Test fun automaticRetriesRemainTransparentAndFinalFailureRevealsErrorPanel() {
        for (attempt in 1..4) {
            settleTransition()
            assertTransparentLoading()
            welfare.pageView.onPageStarted(url)
            welfare.pageView.onHttpError(url, 503)
            shadowOf(Looper.getMainLooper()).idle()
        }
        settleTransition()
        assertEquals(1f, welfare.pageView.alpha, 0.001f)
        assertEquals(View.INVISIBLE, welfare.webView.visibility)
        val panel = welfare.pageView.getChildAt(1) as android.view.ViewGroup
        assertEquals(View.VISIBLE, panel.visibility)
        val content = panel.getChildAt(0) as android.view.ViewGroup
        assertTrue((content.getChildAt(1) as TextView).text.contains("H5_HTTP_503"))
        assertEquals(0, contentReadyCount)
    }

    @Test fun manualRetryHidesPreviousErrorUntilSuccessfulContentCommit() {
        welfare.pageView.onPageStarted(url)
        welfare.pageView.onHttpError(url, 404)
        settleTransition()
        assertEquals(1f, welfare.pageView.alpha, 0.001f)
        welfare.pageView.load(url)
        settleTransition()
        assertTransparentLoading()
        welfare.pageView.onPageStarted(url)
        welfare.pageView.onPageCommitVisible(url)
        settleTransition()
        assertEquals(1f, welfare.pageView.alpha, 0.001f)
        assertEquals(1, contentReadyCount)
    }

    @Test fun closingDuringLoadDoesNotAllowLateCommitToRevealRemovedWindow() {
        settleTransition()
        assertTransparentLoading()
        welfare.pageView.onPageStarted(url)
        assertTrue(container.close("web_welfare"))
        welfare.pageView.onPageCommitVisible(url)
        settleTransition()
        assertNull(welfare.pageView.parent)
        assertEquals(0f, welfare.pageView.alpha, 0.001f)
        assertEquals(0, contentReadyCount)
    }

    @Test fun fullscreenWindowTracksHostSizeAfterRotation() {
        val portrait = welfare.pageView.layoutParams as FrameLayout.LayoutParams
        assertEquals(600, portrait.width)
        assertEquals(400, portrait.height)

        host.layout(0, 0, 1_000, 500)

        val landscape = welfare.pageView.layoutParams as FrameLayout.LayoutParams
        assertEquals("横屏后全屏 H5 必须使用新的宿主宽度", 1_000, landscape.width)
        assertEquals("横屏后全屏 H5 必须使用新的宿主高度", 500, landscape.height)
        assertEquals(0, landscape.leftMargin)
        assertEquals(0, landscape.topMargin)
    }

    private fun assertTransparentLoading() {
        assertEquals("页面尚未准备好时不能用深色面板覆盖游戏", 0f, welfare.pageView.alpha, 0.001f)
        assertEquals("WebView 保持可布局与渲染，不能用 GONE 等待首帧", View.VISIBLE, welfare.webView.visibility)
    }

    private fun settleTransition() {
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
    }
}
