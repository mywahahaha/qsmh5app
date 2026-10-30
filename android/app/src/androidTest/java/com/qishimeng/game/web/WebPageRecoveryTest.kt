package com.qishimeng.game.web

import android.view.View
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.qishimeng.game.bridge.BridgeTestActivity
import com.qishimeng.game.config.LayoutValue
import com.qishimeng.game.config.PageSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.io.File
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 通过本地真实 HTTP 响应验证应用面板，避免将故障模拟成 WebView 回调。 */
@RunWith(AndroidJUnit4::class)
class WebPageRecoveryTest {
    @get:Rule val activityRule = ActivityTestRule(BridgeTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: ServerSocket
    private lateinit var container: WebContainer
    private val workers = Executors.newCachedThreadPool()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val methods = ConcurrentHashMap<String, AtomicInteger>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var unavailableStatus = 503
    @Volatile private var holdSlowResponse = true
    private val releaseSlowResponse = CountDownLatch(1)
    @Volatile private var gateUnavailable = false
    private val releaseUnavailable = Semaphore(0)

    @Before fun setUp() {
        server = ServerSocket(0)
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                workers.execute { runCatching { respond(socket) }; sockets.remove(socket) }
            }
        }
        val paths = mapOf("agreement" to "/unavailable", "login" to "/missing", "game" to "/asset-page")
        val pages = paths.mapValues { (key, path) -> spec("web_$key", path) }
        onMain { container = WebContainer(activityRule.activity, activityRule.activity.host, pages, emptySet()) }
    }

    @After fun tearDown() {
        onMain { container.instances().forEach { container.close(it.spec.instance) } }
        server.close()
        releaseSlowResponse.countDown()
        releaseUnavailable.release(10)
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    @Test fun mainDocumentFailureRetriesThreeTimesAndManualRetryCanRecover() {
        lateinit var view: WebView
        onMain { view = container.openConfigured("agreement")!!.webView }
        await("主文档失败后应显示原生错误码") { hasText("H5_HTTP_503") }
        assertEquals("首次加载加三次自动重试", 4, count("/unavailable"))
        onMain { assertEquals("失败的 WebView 不得显示系统错误页", View.INVISIBLE, view.visibility) }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(activityRule.activity.externalCacheDir, "h5-load-error.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        unavailableStatus = 200
        onView(withText("重试")).perform(click())
        awaitPage(view)
        onMain {
            assertEquals(View.VISIBLE, view.visibility)
            assertFalse(hasTextOnMain("H5_HTTP_503"))
        }
        assertEquals(5, count("/unavailable"))
    }

    @Test fun loadingAndAllThreeAutomaticRetriesStaySilentUntilFinalFailure() {
        gateUnavailable = true
        lateinit var view: WebView
        onMain {
            view = container.openConfigured("agreement")!!.webView
            assertFalse("首次加载不能显示任何原生提示", hasNativeTextOnMain())
        }
        for (attempt in 1..4) {
            await("第 $attempt 次加载未发出") { count("/unavailable") == attempt }
            onMain { assertFalse("第 $attempt 次加载及自动重试期间不能显示提示", hasNativeTextOnMain()) }
            releaseUnavailable.release()
        }
        await("三次重试全部失败后才显示错误码") { hasText("H5_HTTP_503") }
        assertEquals(4, count("/unavailable"))
        onMain { assertEquals(View.INVISIBLE, view.visibility) }
        unavailableStatus = 200
        onMain {
            container.get("web_agreement")!!.let { it.pageView.load(it.spec.url) }
            assertFalse("手动重试时不能残留上次错误提示", hasNativeTextOnMain())
        }
        releaseUnavailable.release()
        awaitPage(view)
    }

    @Test fun missingMainDocumentShowsCodeWithoutAutomaticRetries() {
        onMain { container.openConfigured("login") }
        await("404 应显示原生错误码") { hasText("H5_HTTP_404") }
        assertEquals(1, count("/missing"))
    }

    @Test fun failedImageDoesNotReplaceSuccessfulGamePage() {
        lateinit var view: WebView
        onMain { view = container.openConfigured("game")!!.webView }
        awaitPage(view)
        await("图片请求未发出") { count("/missing.png") > 0 }
        onMain {
            assertEquals(View.VISIBLE, view.visibility)
            assertFalse("子资源失败不应显示整页错误", hasTextOnMain("页面暂时无法加载"))
        }
        assertEquals(1, count("/asset-page"))
    }

    @Test fun refusedNetworkConnectionShowsNativeCodeAndHidesSystemErrorDocument() {
        val refusedPort = ServerSocket(0).use { it.localPort }
        val page = spec("refused", "/refused").copy(url = "http://127.0.0.1:$refusedPort/refused")
        lateinit var view: WebView
        onMain { view = container.open(page)!!.webView }
        await("真实连接失败应显示原生连接错误码") { hasText("H5_CONNECT") }
        onMain { assertEquals(View.INVISIBLE, view.visibility) }
    }

    @Test fun stalledMainDocumentTimesOutAndAutomaticRetryCanRecover() {
        lateinit var view: WebView
        onMain { view = container.open(spec("slow", "/slow"))!!.webView }
        await("首个慢请求未发出") { count("/slow") == 1 }
        holdSlowResponse = false
        awaitPage(view, timeoutMillis = 6_500)
        assertEquals(2, count("/slow"))
        onMain { assertEquals(View.VISIBLE, view.visibility) }
    }

    @Test fun closingWindowCancelsPendingTimeoutAndFurtherRequests() {
        gateUnavailable = true
        onMain { container.openConfigured("agreement") }
        await("等待首个请求") { count("/unavailable") == 1 }
        val beforeClose = count("/unavailable")
        onMain { assertTrue(container.close("web_agreement")) }
        releaseUnavailable.release()
        // 超过单次超时时间，确认关闭后没有触发超时重试。
        Thread.sleep(5_500)
        assertEquals(beforeClose, count("/unavailable"))
        onMain { assertNull(container.get("web_agreement")) }
    }

    @Test fun sameHostRedirectCanDisplaySuccessfulMainDocument() {
        lateinit var view: WebView
        onMain { view = container.open(spec("redirect-success", "/redirect-success"))!!.webView }
        awaitPage(view)
        onMain { assertEquals(View.VISIBLE, view.visibility) }
    }

    @Test fun sameHostRedirectPreservesMainDocumentHttpError() {
        onMain { container.open(spec("redirect-error", "/redirect-error")) }
        await("重定向最终页面失败应显示HTTP错误码") { hasText("H5_HTTP_503") }
        assertEquals(4, count("/unavailable"))
    }

    @Test fun failedPostNavigationDoesNotAutomaticallyReplayAsGet() {
        lateinit var view: WebView
        onMain { view = container.open(spec("form", "/form"))!!.webView }
        awaitPage(view)
        onMain { view.evaluateJavascript("document.forms[0].submit()", null) }
        await("提交失败后应显示错误码") { hasText("H5_HTTP_503") }
        assertEquals(1, methods["POST /post-action"]?.get() ?: 0)
        assertEquals("POST 失败不得被自动改为 GET", 0, methods["GET /post-action"]?.get() ?: 0)
        onView(withText("重试")).perform(click())
        awaitPage(view)
        assertEquals("手动恢复应回到原始 GET 表单页", 2, count("/form"))
    }

    @Test fun configuredExternalLinksOpenBrowserAndUnconfiguredLocationsStayBlocked() {
        val externalUrls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var view: WebView
        onMain {
            container = WebContainer(
                activityRule.activity,
                activityRule.activity.host,
                mapOf("test" to spec("external-link", "/asset-page")),
                setOf("www.baidu.com"),
                onExternalNavigation = { externalUrls += it }
            )
            view = container.openConfigured("test")!!.webView
        }
        awaitPage(view)

        onMain {
            view.evaluateJavascript(
                """var link = document.createElement('a'); link.href = 'http://www.baidu.com/link'; document.body.appendChild(link); link.click();""",
                null
            )
        }
        await("白名单 a 链接应交给外部浏览器") {
            synchronized(externalUrls) { externalUrls == listOf("http://www.baidu.com/link") }
        }

        val blockedUrl = "http://localhost:${server.localPort}/blocked-target"
        onMain {
            view.evaluateJavascript(
                "location.href = '$blockedUrl'; setTimeout(function() { document.body.dataset.navigationBlocked = 'true'; }, 100)",
                null
            )
        }
        await("非白名单 location.href 应留在当前文档") {
            javascriptResult(view, "document.body.dataset.navigationBlocked") == "\"true\"" &&
                synchronized(externalUrls) { externalUrls == listOf("http://www.baidu.com/link") }
        }
    }

    @Test fun whitelistedSameHostLinkAndBlankLinkOpenOutsideWebView() {
        val externalUrls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var view: WebView
        val page = spec("whitelist", "/asset-page")
        val sameHost = "http://127.0.0.1:${server.localPort}/same-host"
        onMain {
            container = WebContainer(activityRule.activity, activityRule.activity.host,
                mapOf("test" to page), setOf("127.0.0.1", "www.baidu.com"),
                onExternalNavigation = { externalUrls += it })
            view = container.openConfigured("test")!!.webView
        }
        awaitPage(view)
        onMain { view.evaluateJavascript("location.href = '$sameHost'", null) }
        await("白名单同域跳转应打开外部浏览器") { externalUrls.contains(sameHost) }
        onMain { view.evaluateJavascript("var a=document.createElement('a');a.href='http://www.baidu.com/blank';a.target='_blank';document.body.appendChild(a);a.click()", null) }
        await("白名单新窗口链接应打开外部浏览器") { externalUrls.contains("http://www.baidu.com/blank") }
        assertEquals(0, count("/same-host"))
    }

    @Test fun reloadOfWhitelistedCurrentPageStaysInTheWebView() {
        val externalUrls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var view: WebView
        val page = spec("reload", "/asset-page")
        onMain {
            container = WebContainer(activityRule.activity, activityRule.activity.host,
                mapOf("test" to page), setOf("127.0.0.1"),
                onExternalNavigation = { externalUrls += it })
            view = container.openConfigured("test")!!.webView
        }
        awaitPage(view)
        onMain { view.reload() }
        await("reload 后当前 WebView 应重新完成加载") { urlOf(view)?.endsWith("/asset-page") == true && externalUrls.isEmpty() && count("/asset-page") >= 2 }
        onMain { view.evaluateJavascript("window.location.reload()", null) }
        await("window.location.reload() 后仍应留在当前 WebView") {
            urlOf(view)?.endsWith("/asset-page") == true && externalUrls.isEmpty() && count("/asset-page") >= 3
        }
    }

    @Test fun delayedFailureAfterSuccessfulCommitDoesNotHideWorkingPage() {
        lateinit var instance: WebInstance
        onMain { instance = container.open(spec("late-error", "/asset-page"))!! }
        awaitPage(instance.webView)
        onMain {
            instance.pageView.onNetworkError(instance.spec.url, -6, "net::ERR_CONNECTION_FAILED")
            assertEquals("已成功页面应忽略旧失败回调", View.VISIBLE, instance.webView.visibility)
            assertFalse(hasTextOnMain("页面暂时无法加载"))
        }
    }

    private fun respond(socket: Socket) = socket.use {
        socket.soTimeout = 2_000
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine().orEmpty().split(' ')
        val path = requestLine.getOrNull(1).orEmpty().substringBefore('?')
        var contentLength = 0
        var header = reader.readLine()
        while (!header.isNullOrEmpty()) {
            if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(':').trim().toInt()
            header = reader.readLine()
        }
        // 测试表单正文为 ASCII；完整读完再响应，避免关闭连接引入无关的 TCP reset。
        while (contentLength > 0) {
            val read = reader.read(CharArray(contentLength))
            if (read < 0) break
            contentLength -= read
        }
        val shouldHoldSlowResponse = path == "/slow" && holdSlowResponse
        requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        methods.computeIfAbsent("${requestLine.firstOrNull()} $path") { AtomicInteger() }.incrementAndGet()
        if (shouldHoldSlowResponse) releaseSlowResponse.await(30, TimeUnit.SECONDS)
        if (path == "/unavailable" && gateUnavailable) releaseUnavailable.tryAcquire(30, TimeUnit.SECONDS)
        if (path.startsWith("/redirect-")) {
            val location = if (path == "/redirect-success") "/asset-page" else "/unavailable"
            socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return@use
        }
        val status = when (path) {
            "/unavailable", "/post-action" -> unavailableStatus
            "/missing", "/missing.png" -> 404
            else -> 200
        }
        val html = if (status >= 400) "<html><body>服务器错误页面</body></html>" else
            "<html><body><div id='ready'>测试页面已加载</div>${if (path == "/asset-page") "<img src='/missing.png'>" else ""}${if (path == "/form") "<form method='post' action='/post-action'><input name='test' value='test'></form>" else ""}</body></html>"
        val body = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Test\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun spec(name: String, path: String) = PageSpec(name, "http://127.0.0.1:${server.localPort}$path",
        LayoutValue.Percent(100f), LayoutValue.Percent(100f), LayoutValue.Pixels(0), LayoutValue.Pixels(0))

    private fun count(path: String) = requests[path]?.get() ?: 0

    private fun hasText(part: String): Boolean {
        var found = false
        onMain { found = hasTextOnMain(part) }
        return found
    }

    private fun hasTextOnMain(part: String): Boolean {
        fun find(view: View): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (view is TextView && view.text.contains(part)) return true
            return view is ViewGroup && (0 until view.childCount).any { find(view.getChildAt(it)) }
        }
        return find(activityRule.activity.host)
    }

    private fun hasNativeTextOnMain(): Boolean {
        fun find(view: View): Boolean {
            if (view.visibility != View.VISIBLE) return false
            if (view is TextView && view.text.isNotBlank()) return true
            return view is ViewGroup && (0 until view.childCount).any { find(view.getChildAt(it)) }
        }
        return find(activityRule.activity.host)
    }

    private fun awaitPage(view: WebView, timeoutMillis: Long = 12_000) = await("重试后应显示成功页面", timeoutMillis) {
        val done = CountDownLatch(1)
        var ready = false
        onMain {
            view.evaluateJavascript("document.getElementById('ready') != null") {
                ready = it == "true"
                done.countDown()
            }
        }
        var visible = false
        onMain {
            val parent = view.parent as ViewGroup
            val covered = (0 until parent.childCount).any {
                val sibling = parent.getChildAt(it)
                sibling !== view && sibling.visibility == View.VISIBLE
            }
            visible = view.visibility == View.VISIBLE && !covered && !hasNativeTextOnMain()
        }
        done.await(2, TimeUnit.SECONDS) && ready && visible
    }

    private fun javascriptResult(view: WebView, script: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        onMain {
            view.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        if (!done.await(2, TimeUnit.SECONDS)) return null
        return result
    }

    private fun urlOf(view: WebView): String? {
        var value: String? = null
        onMain { value = view.url }
        return value
    }

    private fun await(message: String, timeoutMillis: Long = 12_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
