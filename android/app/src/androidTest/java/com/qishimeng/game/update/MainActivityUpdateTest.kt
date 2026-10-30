package com.qishimeng.game.update

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.qishimeng.game.MainActivity
import com.qishimeng.game.createExternalBrowserIntent
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.HttpTransport
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUpdateTest {
    @get:Rule val rule = ActivityTestRule(MainActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool()
    private val logins = AtomicInteger()
    private val downloads = AtomicInteger()
    private val agreements = AtomicInteger()
    private var enabled = 1
    private var force = 0
    private var privacy = false
    private var launched = false
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.PRE_ON_CREATE) {
            MainActivity::class.java.getDeclaredField("apiClient").apply { isAccessible = true }.set(activity,
                ApiClient(HttpTransport { ApiResponse(200, config()) }))
        }
    }

    @Before fun setUp() {
        context.getSharedPreferences("privacy_state", 0).edit().clear().commit()
        context.getSharedPreferences("account_token", 0).edit().clear().commit()
        server = ServerSocket(0)
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute {
                    runCatching {
                        socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            val path = reader.readLine().split(" ")[1]
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val bytes = if (path == "/apk") {
                                downloads.incrementAndGet()
                                File(context.applicationInfo.sourceDir).readBytes()
                            } else {
                                if (path == "/login") logins.incrementAndGet()
                                if (path == "/agreement") agreements.incrementAndGet()
                                "<html><body>fixture</body></html>".toByteArray()
                            }
                            it.getOutputStream().use { output ->
                                output.write("HTTP/1.0 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: ${if (path == "/apk") "application/vnd.android.package-archive" else "text/html"}\r\n\r\n".toByteArray())
                                output.write(bytes)
                            }
                        }
                    }
                }
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
    }

    @After fun tearDown() {
        if (launched) rule.finishActivity()
        monitor?.let { instrumentation.removeMonitor(it) }
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        server.close(); workers.shutdownNow()
        context.getSharedPreferences("privacy_state", 0).edit().clear().commit()
        context.getSharedPreferences("account_token", 0).edit().clear().commit()
    }

    @Test fun enabledOfferStopsLoginUntilUserClosesAndDisabledConfigSkipsOffer() {
        launch()
        await("应显示原生更新窗口") { hasText("更新测试说明") }
        assertEquals(0, logins.get())
        onView(withContentDescription("关闭更新")).inRoot(isDialog()).perform(click())
        await("关闭后应继续 H5 登录") { logins.get() == 1 }
        assertEquals(0, downloads.get())
    }

    @Test fun disabledConfigContinuesLoginWithoutValidatingEmptyUrl() {
        enabled = 0; launch()
        await("open=0 应直接登录") { logins.get() == 1 }
        assertFalse(hasText("更新测试说明")); assertEquals(0, downloads.get())
    }

    @Test fun externalBrowserIntentUsesViewBrowsableWithoutBrowserHomeSelector() {
        val intent = createExternalBrowserIntent("https://www.baidu.com/")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data?.scheme)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
        assertNull(intent.selector)
    }

    @Test fun externalBrowserTemporarilyRequestsPortraitAndRestoresOriginalOrientationOnReturn() {
        enabled = 0
        launch()
        await("登录页面应加载") { logins.get() == 1 }
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(0, null), true)
        var original = 0
        instrumentation.runOnMainSync {
            original = rule.activity.requestedOrientation
            MainActivity::class.java.getDeclaredMethod("openExternalUrl", String::class.java).apply { isAccessible = true }
                .invoke(rule.activity, "https://www.baidu.com/")
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, rule.activity.requestedOrientation)
        }
        instrumentation.runOnMainSync {
            instrumentation.callActivityOnPause(rule.activity)
            instrumentation.callActivityOnResume(rule.activity)
            assertEquals(original, rule.activity.requestedOrientation)
        }
    }

    @Test fun privacyPageComesBeforeUpdateAndAgreeThenShowsUpdate() {
        privacy = true; launch()
        await("先加载隐私页") { agreements.get() == 1 }
        assertFalse(hasText("更新测试说明")); assertEquals(0, logins.get())
        await("隐私页面 Bridge 应完成注入") {
            val latch = java.util.concurrent.CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync {
                findWeb(rule.activity.findViewById(android.R.id.content))!!.evaluateJavascript("Boolean(window.QSMH5APP)") {
                    ready = it == "true"; latch.countDown()
                }
            }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS) && ready
        }
        instrumentation.runOnMainSync {
            findWeb(rule.activity.findViewById(android.R.id.content))!!.evaluateJavascript(
                "window.QSMH5APP.agreePrivacyPolicy({}, function(){})", null
            )
        }
        await("同意隐私后显示更新") { hasText("更新测试说明") }
        assertEquals(0, logins.get())
    }

    @Test fun internalDownloadAutomaticallyRequestsInstallAndButtonCanRequestAgain() {
        // 仅测试包授权；Gradle 设备测试结束会卸载测试应用。不能在测试进程内撤销此授权，否则系统会杀掉进程。
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        ).use { it.readBytes() }
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataType("application/vnd.android.package-archive"); addDataScheme("content")
        }
        monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(0, null), true)
        force = 1; launch()
        await("应显示更新") { hasText("更新测试说明") }
        onView(withText("立即更新")).inRoot(isDialog()).perform(click())
        await("下载完成后应请求系统安装") { monitor!!.hits == 1 }
        assertEquals(1, downloads.get()); assertEquals(0, logins.get())
        onView(withText("立即安装")).inRoot(isDialog()).perform(click())
        await("可再次请求安装") { monitor!!.hits == 2 }
        assertEquals(1, downloads.get())
    }

    private fun launch() { rule.launchActivity(null); launched = true }
    private fun config(): String {
        val base = "http://127.0.0.1:${server.localPort}"
        return """{"code":0,"msg":"success","data":{"privacyAuto":$privacy,"aliyunOnekeyConfig":{"open":0},"updateInfo":{"open":$enabled,"width":"50","height":"auto","popup":0,"force":$force,"title":"邀您体验新版本","time":"2026-10-28","desc":"更新测试说明","version":"1.0.12","url":"${if (enabled == 1) "$base/apk" else ""}"},"h5Pages":{"agreement":{"instance":"web_agreement","url":"$base/agreement","width":"100%","height":"100%","left":0,"top":0},"login":{"instance":"web_login","url":"$base/login","width":"100%","height":"100%","left":0,"top":0}}}}"""
    }
    private fun hasText(text: String): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            fun contains(view: View): Boolean {
                if (view is android.widget.TextView && view.isShown && view.text.toString() == text) return true
                return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
            }
            found = android.view.inspector.WindowInspector.getGlobalWindowViews().any { contains(it) }
        }
        return found
    }
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(80) }
        fail(message)
    }
}
