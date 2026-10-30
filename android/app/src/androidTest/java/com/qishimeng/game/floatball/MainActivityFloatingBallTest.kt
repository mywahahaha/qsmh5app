package com.qishimeng.game.floatball

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.widget.ImageView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.qishimeng.game.MainActivity
import com.qishimeng.game.auth.EncryptedAccountTokenStore
import com.qishimeng.game.auth.OnekeyAuthGateway
import com.qishimeng.game.auth.OnekeyAuthResult
import com.qishimeng.game.auth.OnekeyConfig
import com.qishimeng.game.auth.TokenLoginService
import com.qishimeng.game.bridge.BridgeLocalStorage
import com.qishimeng.game.bridge.SharedPreferencesLocalStorage
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.ApiRequest
import com.qishimeng.game.network.HttpTransport
import com.qishimeng.game.web.WebContainer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 运行真实 MainActivity，只将初始化/Token 接口替换为本地夹具，页面与图标仍走真实 HTTP。 */
@RunWith(AndroidJUnit4::class)
class MainActivityFloatingBallTest {
    @get:Rule val activityRule = ActivityTestRule(MainActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool()
    private val gameRequested = CountDownLatch(1)
    private val releaseGame = CountDownLatch(1)
    private var initiallyShown = true
    private var ballBottom = 20
    private var ballSize = 36
    private var launched = false
    private var onekeyEnabled = false
    private var exchangeSucceeds = true
    private val initRequests = AtomicInteger()
    private val tokenRequests = AtomicInteger()
    private val authAttempts = AtomicInteger()
    private var authCompletion: ((OnekeyAuthResult) -> Unit)? = null
    private var receivedAuthConfig: OnekeyConfig? = null
    private var tokenAtAuthStart: String? = "not-observed"
    private var windowsAtAuthStart: List<String>? = null
    @Volatile private var exchangeRequest: ApiRequest? = null
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.PRE_ON_CREATE) {
            val transport = HttpTransport { request ->
                when (request.url) {
                    ApiClient.INIT_URL -> {
                        initRequests.incrementAndGet()
                        ApiResponse(200, configJson())
                    }
                    TokenLoginService.LOGIN_BY_TOKEN_URL -> {
                        tokenRequests.incrementAndGet()
                        ApiResponse(200, """{"code":0,"msg":"ok","data":{"token":"fixture-refreshed-token"}}""")
                    }
                    url("exchange") -> {
                        exchangeRequest = request
                        ApiResponse(200, if (exchangeSucceeds) """{"code":0,"msg":"ok","data":{"token":"fixture-onekey-token"}}"""
                        else """{"code":1001,"msg":"认证失败","data":null}""")
                    }
                    else -> error("测试不得访问线上接口")
                }
            }
            MainActivity::class.java.getDeclaredField("apiClient").apply { isAccessible = true }
                .set(activity, ApiClient(transport))
            MainActivity::class.java.getDeclaredField("onekeyAuthGateway").apply { isAccessible = true }
                .set(activity, OnekeyAuthGateway { config, completion ->
                    receivedAuthConfig = config
                    tokenAtAuthStart = EncryptedAccountTokenStore(context).token
                    val container = MainActivity::class.java.getDeclaredField("webContainer")
                        .apply { isAccessible = true }.get(activity) as WebContainer
                    windowsAtAuthStart = container.windowNames()
                    authCompletion = completion
                    authAttempts.incrementAndGet()
                })
        }
    }

    @Before fun setUp() {
        server = ServerSocket(0)
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { respond(socket) } }
            }
        }
        context.getSharedPreferences("account_token", 0).edit().clear().commit()
        context.getSharedPreferences("privacy_state", 0).edit().clear().commit()
        context.getSharedPreferences("h5_local_storage", 0).edit().remove("logout-session-marker").commit()
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
    }

    @After fun tearDown() {
        releaseGame.countDown()
        if (launched) activityRule.finishActivity()
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        server.close()
        workers.shutdownNow()
        context.getSharedPreferences("account_token", 0).edit().clear().commit()
        context.getSharedPreferences("privacy_state", 0).edit().clear().commit()
        context.getSharedPreferences("h5_local_storage", 0).edit().remove("logout-session-marker").commit()
    }

    @Test fun loginAndLoadedGameShowBallAndBridgeWelfareAndLogoutControlIt() {
        launch()
        val login = awaitWeb("login")
        onMain { assertNull("登录前不应创建悬浮球", ball()) }
        call(login, "notifyLoginSuccess", "{accountToken:'fixture-login-token'}")
        assertTrue("登录后应加载游戏", gameRequested.await(10, TimeUnit.SECONDS))
        onMain { assertNull("游戏文档加载前不应创建悬浮球", ball()) }
        releaseGame.countDown()
        val game = awaitWeb("game")
        awaitMain("游戏加载后应显示默认悬浮球图标") { ballIsVisible(Color.GREEN) }
        assertBallPixel(Color.GREEN)
        call(game, "setFloatingBall", "{show:false,active:true}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.code === 0 && window.bridgeResult.data === true")
        onMain { assertFalse(ball()!!.isShown) }
        call(game, "setFloatingBall", "{show:true,active:true}")
        awaitMain("激活时应显示激活图标") { ballIsVisible(Color.RED) }
        onView(withContentDescription("游戏悬浮球")).perform(click())
        val welfare = awaitWeb("welfare")
        onMain { assertFalse("福利大厅打开期间应隐藏悬浮球", ball()!!.isShown) }
        call(welfare, "closePage", "{}")
        awaitMain("福利大厅关闭后恢复最新悬浮球状态") { ballIsVisible(Color.RED) }
        call(game, "logout", "{}")
        awaitMain("退出登录后悬浮球应隐藏") { ball()?.isShown != true }
    }

    @Test fun initialHideAndEarlyBridgeStateWaitForGameAndClosingGameHidesBall() {
        initiallyShown = false
        launch()
        val login = awaitWeb("login")
        call(login, "setFloatingBall", "{show:true,active:true}")
        awaitJs(login, "window.bridgeResult && window.bridgeResult.data === true")
        onMain { assertTrue("H5 不能绕过登录条件显示悬浮球", ball()?.isShown != true) }
        call(login, "notifyLoginSuccess", "{accountToken:'fixture-login-token'}")
        assertTrue(gameRequested.await(10, TimeUnit.SECONDS))
        releaseGame.countDown()
        val game = awaitWeb("game")
        awaitMain("加载完成后应使用登录前设置的运行态") { ballIsVisible(Color.RED) }
        call(game, "closePage", "{}")
        awaitMain("游戏窗口关闭后必须隐藏悬浮球") { ball()?.isShown != true }
    }

    @Test fun loginDestroysLoginWindowAndLogoutCreatesFreshLoginAndGameWindows() {
        releaseGame.countDown()
        launch()
        val firstLogin = awaitWeb("login")
        val firstLoginPage = onMainValue { firstLogin.parent as View }
        call(firstLogin, "notifyLoginSuccess", "{accountToken:'fixture-login-token'}")
        val firstGame = awaitWeb("game")
        awaitMain("登录成功后必须移除并销毁登录页面") {
            !container().hasWindow("web_login") && firstLoginPage.parent == null
        }
        val firstGamePage = onMainValue { firstGame.parent as View }
        call(firstGame, "openMiniWindow", "{page:'web_welfare'}")
        val welfare = awaitWeb("welfare")
        val welfarePage = onMainValue { welfare.parent as View }
        call(welfare, "logout", "{}")
        val secondLogin = awaitWeb("login")
        onMain { assertNotSame("退出后必须创建新的登录 WebView", firstLogin, secondLogin) }
        awaitMain("退出必须销毁游戏和福利页面") {
            container().windowNames() == listOf("web_login") &&
                firstGamePage.parent == null && welfarePage.parent == null
        }
        call(secondLogin, "notifyLoginSuccess", "{accountToken:'fixture-second-token'}")
        val secondGame = awaitWeb("game")
        onMain { assertNotSame("重新登录必须创建新游戏 WebView", firstGame, secondGame) }
    }

    @Test fun logoutRestartsOnekeyAfterClearingSessionAndSuccessCreatesNewGame() {
        onekeyEnabled = true
        launchSavedTokenGame()
        val oldGame = awaitWeb("game")
        val storage = BridgeLocalStorage(SharedPreferencesLocalStorage(context))
        storage.save(mapOf("key" to "logout-session-marker", "value" to "keep"))
        call(oldGame, "openMiniWindow", "{page:'web_welfare'}")
        val welfare = awaitWeb("welfare")
        val oldGamePage = onMainValue { oldGame.parent as View }
        val welfarePage = onMainValue { welfare.parent as View }
        call(welfare, "logout", "{}")
        awaitMain("开启一键登录时退出必须重新调用认证网关") { authAttempts.get() == 1 }
        onMain {
            assertNull("认证前旧 Token 必须已从持久化存储删除", tokenAtAuthStart)
            assertEquals("认证前旧会话窗口必须已关闭", emptyList<String>(), windowsAtAuthStart)
            assertTrue("授权过程中不能抢先打开 H5 登录页", container().windowNames().isEmpty())
            assertTrue(ball()?.isShown != true)
            assertEquals("fixture-scheme-secret", receivedAuthConfig!!.schemeSecret)
            authCompletion!!(OnekeyAuthResult.Presented)
        }
        awaitMain("旧游戏和大厅必须从视图树移除") { oldGamePage.parent == null && welfarePage.parent == null }
        onMain { authCompletion!!(OnekeyAuthResult.Success("fixture-auth-token")) }
        val newGame = awaitWeb("game")
        onMain {
            assertNotSame(oldGame, newGame)
            assertEquals(listOf("web_game"), container().windowNames())
            assertEquals("fixture-onekey-token", EncryptedAccountTokenStore(context).token)
        }
        assertEquals("{\"auth_token\":\"fixture-auth-token\"}", exchangeRequest!!.body)
        assertEquals("", exchangeRequest!!.headers["Accountoken"])
        assertEquals("keep", storage.get("logout-session-marker"))
        assertEquals("退出不能重跑初始化", 1, initRequests.get())
        assertEquals("退出不能再用旧 Token 自动登录", 1, tokenRequests.get())
    }

    @Test fun logoutOnekeyCancellationOpensH5WithoutRestoringOldToken() {
        assertLogoutOnekeyFallback(OnekeyAuthResult.Cancelled)
    }

    @Test fun logoutOnekeyFailureOpensH5WithoutRestoringOldToken() {
        assertLogoutOnekeyFallback(OnekeyAuthResult.Failed)
    }

    @Test fun logoutOnekeyExchangeFailureOpensH5WithoutRestoringOldToken() {
        exchangeSucceeds = false
        assertLogoutOnekeyFallback(OnekeyAuthResult.Success("fixture-auth-token"))
        assertNotNull(exchangeRequest)
        assertEquals("", exchangeRequest!!.headers["Accountoken"])
    }

    @Test fun logoutWithOnekeyDisabledOpensH5AndSkipsSavedTokenLogin() {
        launchSavedTokenGame()
        call(awaitWeb("game"), "logout", "{}")
        val login = awaitWeb("login")
        call(login, "getAccountToken", "{}")
        awaitJs(login, "window.bridgeResult && window.bridgeResult.code === 0 && window.bridgeResult.data.accountToken === ''")
        onMain {
            assertEquals(listOf("web_login"), container().windowNames())
            assertNull(EncryptedAccountTokenStore(context).token)
        }
        assertEquals(0, authAttempts.get())
        assertEquals(1, initRequests.get())
        assertEquals(1, tokenRequests.get())
    }

    private fun assertLogoutOnekeyFallback(outcome: OnekeyAuthResult) {
        onekeyEnabled = true
        launchSavedTokenGame()
        call(awaitWeb("game"), "logout", "{}")
        awaitMain("退出必须先尝试一键登录") { authAttempts.get() == 1 }
        onMain {
            assertNull(tokenAtAuthStart)
            assertEquals(emptyList<String>(), windowsAtAuthStart)
            authCompletion!!(OnekeyAuthResult.Presented)
            authCompletion!!(outcome)
        }
        val login = awaitWeb("login")
        call(login, "getAccountToken", "{}")
        awaitJs(login, "window.bridgeResult && window.bridgeResult.code === 0 && window.bridgeResult.data.accountToken === ''")
        onMain {
            assertEquals(listOf("web_login"), container().windowNames())
            assertNull(EncryptedAccountTokenStore(context).token)
            assertTrue(ball()?.isShown != true)
        }
        assertEquals(1, authAttempts.get())
        assertEquals(1, initRequests.get())
        assertEquals(1, tokenRequests.get())
    }

    @Test fun welfarePreservesGameAndChangingUrlReusesGameAndRestoresBall() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        onMain { game.evaluateJavascript("window.preservedGameState='alive'", null) }
        call(game, "openMiniWindow", "{page:'web_welfare'}")
        val welfare = awaitWeb("welfare")
        onMain {
            assertSame(game, container().get("web_game")!!.webView)
            assertEquals("web_welfare", container().windowNames().last())
            assertFalse(ball()!!.isShown)
        }
        awaitJs(game, "window.preservedGameState === 'alive'")
        call(welfare, "openMiniWindow", "{page:'web_welfare'}")
        awaitJs(welfare, "window.bridgeResult && window.bridgeResult.data === true")
        onMain { assertSame(welfare, container().get("web_welfare")!!.webView) }
        call(welfare, "changeGameUrl", "{url:'${url("next-game")}'}")
        awaitJs(welfare, "window.bridgeResult && window.bridgeResult.data === true")
        val navigatedGame = awaitWeb("next-game")
        onMain {
            assertSame("大厅切游戏地址不能重建游戏 WebView", game, navigatedGame)
            assertSame("切地址不能关闭或重建大厅", welfare, container().get("web_welfare")!!.webView)
            assertEquals("web_game", container().windowNames().last())
        }
        awaitJs(game, "typeof window.preservedGameState === 'undefined' && document.readyState === 'complete'")
        awaitMain("游戏置顶后应恢复悬浮球，以便再次打开大厅") { ballIsVisible(Color.GREEN) }
        call(game, "openMiniWindow", "{page:'web_welfare'}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.data === true")
        onMain {
            assertSame(welfare, container().get("web_welfare")!!.webView)
            assertSame(game, container().get("web_game")!!.webView)
            assertEquals("web_welfare", container().windowNames().last())
            assertFalse(ball()!!.isShown)
        }
    }

    @Test fun closedGameBridgeCannotReopenGameOrOverwriteSessionFromLogoutCallback() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        val gamePage = onMainValue { game.parent as View }
        val attempted = CountDownLatch(1)
        onMain {
            game.addJavascriptInterface(object {
                @JavascriptInterface fun done() { attempted.countDown() }
            }, "LifecycleTestProbe")
            game.reload()
        }
        awaitJs(game, "typeof window.LifecycleTestProbe === 'object' && typeof window.QSMH5APP === 'object'")
        onMain {
            game.evaluateJavascript("""
                QSMH5APP.logout({}, function () {
                    QSMH5APP.openMiniWindow({page:'web_game'});
                    QSMH5APP.notifyLoginSuccess({accountToken:'stale-game-token'});
                    LifecycleTestProbe.done();
                });
            """.trimIndent(), null)
        }
        assertTrue("必须实际执行旧文档的退出回调", attempted.await(10, TimeUnit.SECONDS))
        awaitMain("退出应完成旧游戏页面销毁") { gamePage.parent == null }
        onMain {
            assertEquals("旧页面不能再次打开游戏或关闭新登录页", listOf("web_login"), container().windowNames())
            assertNull("旧页面不能覆盖已清理的登录令牌", EncryptedAccountTokenStore(context).token)
        }
    }

    private fun container(): WebContainer = MainActivity::class.java.getDeclaredField("webContainer")
        .apply { isAccessible = true }.get(activityRule.activity) as WebContainer

    private fun <T> onMainValue(action: () -> T): T {
        var result: T? = null
        onMain { result = action() }
        return result!!
    }

    @Test fun savedTokenLoginHonorsInitialShowFalseUntilH5EnablesIt() {
        initiallyShown = false
        context.getSharedPreferences("privacy_state", 0).edit().putBoolean("agreed", true).commit()
        EncryptedAccountTokenStore(context).token = "fixture-saved-token"
        releaseGame.countDown()
        launch()
        val game = awaitWeb("game")
        awaitJs(game, "document.readyState === 'complete'")
        onMain { assertTrue("初始化 show=false 时不可显示", ball()?.isShown != true) }
        call(game, "setFloatingBall", "{show:true,active:false}")
        awaitMain("Token 登录后 H5 可启用悬浮球") { ballIsVisible(Color.GREEN) }
        assertBallPixel(Color.GREEN)
    }

    @Test fun landscapeGameKeepsLargeBottomOffsetBallFullyAboveGame() {
        ballBottom = 200
        launchSavedTokenGame()
        onMain {
            val image = ball()!!
            val host = image.parent as ViewGroup
            assertTrue("回归场景必须为横屏", host.width > host.height)
            assertBallInsideHost(image, host)
        }
        assertBallPixel(Color.GREEN)
    }

    @Test fun ballRepositionsWhenItsHostViewportShrinks() {
        launchSavedTokenGame()
        onMain {
            val host = ball()!!.parent as ViewGroup
            host.layoutParams = host.layoutParams.apply { width = 600; height = 300 }
        }
        awaitMain("悬浮层应完成窗口尺寸变化") { (ball()!!.parent as ViewGroup).let { it.width == 600 && it.height == 300 } }
        onMain { assertBallInsideHost(ball()!!, ball()!!.parent as ViewGroup) }
        assertBallPixel(Color.GREEN)
    }

    @Test fun oversizedBallIsLimitedToVisibleHostSize() {
        ballSize = 400
        launchSavedTokenGame()
        onMain { assertBallInsideHost(ball()!!, ball()!!.parent as ViewGroup) }
        assertBallPixel(Color.GREEN)
    }

    private fun launchSavedTokenGame() {
        context.getSharedPreferences("privacy_state", 0).edit().putBoolean("agreed", true).commit()
        EncryptedAccountTokenStore(context).token = "fixture-saved-token"
        releaseGame.countDown()
        launch()
        onMain { activityRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitWeb("game")
        awaitMain("游戏加载后应创建悬浮球") { ballIsVisible(Color.GREEN) }
    }

    private fun assertBallInsideHost(image: ImageView, host: ViewGroup) {
        assertTrue("悬浮球必须完整位于可见区域内，实际球框=[${image.left},${image.top},${image.right},${image.bottom}]，窗口=${host.width}x${host.height}",
            image.left >= 0 && image.top >= 0 && image.right <= host.width && image.bottom <= host.height)
    }

    private fun launch() {
        activityRule.launchActivity(null)
        launched = true
    }

    private fun configJson() = """{"code":0,"msg":"ok","data":{
        "privacyAuto":false,"aliyunOnekeyConfig":{"open":${if (onekeyEnabled) 1 else 0},"config":{"scheme_secret":"fixture-scheme-secret","exchange_url":"${url("exchange")}"}},
        "floatball":{"show":$initiallyShown,"image_default":"${url("default.png")}","image_active":"${url("active.png")}","width":$ballSize,"height":$ballSize,"right":10,"bottom":$ballBottom},
        "h5Pages":{${listOf("login", "game", "welfare").joinToString(",") { name ->
            """"$name":{"instance":"web_$name","url":"${url(name)}","width":"100%","height":"100%","left":0,"top":0}"""
        }}}
    }}"""

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"

    private fun respond(socket: Socket) = socket.use {
        val reader = it.getInputStream().bufferedReader()
        val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
        while (!reader.readLine().isNullOrEmpty()) { }
        if (path == "/game") {
            gameRequested.countDown()
            releaseGame.await(15, TimeUnit.SECONDS)
        }
        val isImage = path.endsWith(".png")
        val body = if (isImage) {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (path == "/active.png") Color.RED else Color.GREEN)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        } else "<html><body style='background:#000;color:#fff'>本地悬浮球回归页面</body></html>".toByteArray()
        it.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: ${if (isImage) "image/png" else "text/html"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun ball(): ImageView? = descendants(activityRule.activity.window.decorView)
        .filterIsInstance<ImageView>().firstOrNull { it.contentDescription == "游戏悬浮球" }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun ballIsVisible(color: Int): Boolean {
        val image = ball() ?: return false
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return false
        return image.isShown && image.width > 0 && bitmap.getPixel(0, 0) == color
    }

    private fun assertBallPixel(color: Int) {
        // Drawable 更新和启动闪图退出均先于下一帧合成，等待实际屏幕像素而非仅等待视图属性。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var actual: Int? = null
        while (System.nanoTime() < deadline) {
            val location = IntArray(2)
            onMain { ball()!!.let { it.getLocationOnScreen(location); location[0] += it.width / 2; location[1] += it.height / 2 } }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try {
                    if (location[0] in 0 until screenshot.width && location[1] in 0 until screenshot.height) {
                        actual = screenshot.getPixel(location[0], location[1])
                    }
                } finally { screenshot.recycle() }
            }
            if (actual == color) return
            Thread.sleep(50)
        }
        assertEquals("悬浮球必须实际绘制在游戏页面上方", color, actual)
    }

    private fun awaitWeb(name: String): WebView {
        var result: WebView? = null
        awaitMain("未打开 $name 页面") {
            result = descendants(activityRule.activity.window.decorView).filterIsInstance<WebView>().firstOrNull { it.url == url(name) }
            result != null
        }
        awaitJs(result!!, "typeof window.QSMH5APP === 'object'")
        return result!!
    }

    private fun call(view: WebView, method: String, arguments: String) {
        onMain { view.evaluateJavascript("window.bridgeResult=null;QSMH5APP.$method($arguments,function(r){window.bridgeResult=r;});", null) }
    }

    private fun awaitJs(view: WebView, expression: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val done = CountDownLatch(1)
            var ready = false
            onMain { view.evaluateJavascript("Boolean($expression)") { ready = it == "true"; done.countDown() } }
            if (done.await(2, TimeUnit.SECONDS) && ready) return
            Thread.sleep(50)
        }
        fail("等待 H5 条件超时：$expression")
    }

    private fun awaitMain(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
