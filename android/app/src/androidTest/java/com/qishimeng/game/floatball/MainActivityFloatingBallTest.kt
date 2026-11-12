package com.qishimeng.game.floatball

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.widget.ImageView
import android.widget.FrameLayout
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
import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.auth.OnekeyAuthGateway
import com.qishimeng.game.auth.OnekeyAuthResult
import com.qishimeng.game.auth.OnekeyConfig
import com.qishimeng.game.auth.TokenLoginService
import com.qishimeng.game.bridge.BridgeLocalStorage
import com.qishimeng.game.bridge.SharedPreferencesLocalStorage
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.ApiRequest
import com.qishimeng.game.network.GameHeaders
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
    private var ballTop: Double? = null
    private var ballBottom: Number = 20
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
    private var orientationAtAuthStart: Int? = null
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
                    orientationAtAuthStart = activity.resources.configuration.orientation
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
        onMain {
            val expectedMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            assertEquals("WebView 宿主窗口必须允许绘制到刘海区域",
                expectedMode, activityRule.activity.window.attributes.layoutInDisplayCutoutMode)
        }
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

    @Test fun logoutRestoresPackagedLandscapeBeforeOpeningH5Login() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitMain("退出前游戏应已切为竖屏") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        call(game, "logout", "{}")

        val login = awaitWeb("login")
        awaitMain("退出后必须先恢复打包横屏再显示登录页") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                pageFillsCurrentHost("web_login", landscape = true)
        }
        call(login, "getGameInfo", "{}")
        awaitJs(login, "window.bridgeResult && window.bridgeResult.data.screenMode === 'horizontal'")
        assertBusinessScreenMode("horizontal")
    }

    @Test fun logoutRestoresPackagedLandscapeBeforeStartingOnekey() {
        onekeyEnabled = true
        launchSavedTokenGame()
        val game = awaitWeb("game")
        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitMain("退出前一键登录场景应已切为竖屏") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        call(game, "logout", "{}")

        awaitMain("恢复打包横屏后才能启动一键登录") { authAttempts.get() == 1 }
        onMain {
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientationAtAuthStart)
            assertEquals(emptyList<String>(), windowsAtAuthStart)
        }
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

    @Test fun welfareDefersGameUrlUntilDedicatedCloseAndThenRestoresBall() {
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
        call(welfare, "changeGameUrl", "{url:'${url("next-game")}',waitCloseWelfarepage:true}")
        awaitJs(welfare, "window.bridgeResult && window.bridgeResult.data === true")
        onMain {
            assertSame("延迟切地址时大厅必须继续置顶", welfare, container().get("web_welfare")!!.webView)
            assertEquals("延迟切地址时游戏页不能提前导航", "game", game.url?.substringAfterLast('/'))
            assertFalse(ball()!!.isShown)
        }
        call(welfare, "closeWelfarepage", "{mode:'horizontal',loadPendingGameUrl:true}")
        val navigatedGame = awaitWeb("next-game")
        onMain {
            assertSame("大厅切游戏地址不能重建游戏 WebView", game, navigatedGame)
            assertFalse("专用关闭必须关闭大厅", container().hasWindow("web_welfare"))
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

    @Test fun showingFloatingBallAgainReappliesInitializationPositionBeforeVisible() {
        ballBottom = 80
        launchSavedTokenGame()
        val game = awaitWeb("game")
        val image = onMainValue { ball()!! }
        val ballHost = onMainValue { image.parent as ViewGroup }
        onMain {
            image.layoutParams = (image.layoutParams as FrameLayout.LayoutParams).apply { topMargin = 0 }
        }

        call(game, "setFloatingBall", "{show:false,active:false}")
        awaitMain("隐藏悬浮球") { !image.isShown }
        call(game, "setFloatingBall", "{show:true,active:false}")
        awaitMain("重新定位完成后才显示悬浮球") { image.isShown && image.top > 0 }

        onMain {
            val expectedTop = FloatingBallLayout.resolveTop(
                null,
                ballBottom.toDouble(),
                ballHost.height,
                image.height,
                activityRule.activity.resources.displayMetrics.density
            )
            assertEquals(expectedTop, image.top)
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
            val bottomPx = 200.0 * activityRule.activity.resources.displayMetrics.density
            val expectedTop = (host.height - image.height - bottomPx).coerceAtLeast(0.0).toInt()
            assertEquals("bottom=200 应按 200dp 间距布局，空间不足时限制在可见范围", expectedTop, image.top)
        }
        assertBallPixel(Color.GREEN)
    }

    @Test fun ballRepositionsWhenItsHostViewportShrinks() {
        ballBottom = 0.2
        launchSavedTokenGame()
        onMain {
            val host = ball()!!.parent as ViewGroup
            host.layoutParams = host.layoutParams.apply { width = 600; height = 300 }
        }
        awaitMain("悬浮层缩小后应重新按高度计算底部 20% 间距") {
            val image = ball()!!
            (image.parent as ViewGroup).let { it.width == 600 && it.height == 300 && image.bottom == 240 }
        }
        onMain { assertBallInsideHost(ball()!!, ball()!!.parent as ViewGroup) }
        assertBallPixel(Color.GREEN)
    }

    @Test fun fractionalTopWinsOverBottomInActualView() {
        ballTop = 0.2
        ballBottom = 200
        launchSavedTokenGame()
        onMain {
            val host = ball()!!.parent as ViewGroup
            host.layoutParams = host.layoutParams.apply { width = 600; height = 300 }
        }
        awaitMain("同时配置时应使用 top=20%，即距 300px 高窗口顶部 60px") {
            val image = ball()!!
            (image.parent as ViewGroup).let { it.width == 600 && it.height == 300 && image.top == 60 }
        }
        onMain { assertBallInsideHost(ball()!!, ball()!!.parent as ViewGroup) }
        assertBallPixel(Color.GREEN)
    }

    @Test fun configuredDpSizeDoesNotGrowWithHostWidth() {
        ballSize = 20
        launchSavedTokenGame()
        val expectedSize = (20 * activityRule.activity.resources.displayMetrics.density).toInt()
        onMain {
            assertEquals("初始宽度应为 20dp", expectedSize, ball()!!.width)
            assertEquals("初始高度应为 20dp", expectedSize, ball()!!.height)
            val host = ball()!!.parent as ViewGroup
            host.layoutParams = host.layoutParams.apply { width = 600; height = 300 }
        }
        awaitMain("容器宽度变化后，球体应保持 20dp 宽高") {
            val image = ball()!!
            (image.parent as ViewGroup).let {
                it.width == 600 && it.height == 300 && image.width == expectedSize && image.height == expectedSize
            }
        }
        assertBallPixel(Color.GREEN)
    }

    @Test fun oversizedBallIsLimitedToVisibleHostSize() {
        ballSize = 400
        launchSavedTokenGame()
        onMain { assertBallInsideHost(ball()!!, ball()!!.parent as ViewGroup) }
        assertBallPixel(Color.GREEN)
    }

    @Test fun loadedGameKeepsScreenAwakeUntilGameCloses() {
        initiallyShown = false
        launch()
        val login = awaitWeb("login")
        onMain { assertFalse("登录页应保留系统自动息屏策略", keepsScreenAwake()) }
        call(login, "notifyLoginSuccess", "{accountToken:'fixture-login-token'}")
        assertTrue(gameRequested.await(10, TimeUnit.SECONDS))
        onMain { assertFalse("游戏文档加载前不应保持常亮", keepsScreenAwake()) }
        releaseGame.countDown()
        val game = awaitWeb("game")
        awaitMain("游戏加载后应保持屏幕常亮，且不依赖悬浮球是否显示") { keepsScreenAwake() }
        call(game, "openMiniWindow", "{page:'web_welfare'}")
        val welfare = awaitWeb("welfare")
        onMain { assertTrue("游戏期间打开福利大厅应继续保持常亮", keepsScreenAwake()) }
        call(welfare, "closePage", "{}")
        call(game, "closePage", "{}")
        awaitMain("退出游戏后应恢复系统自动息屏策略") { !keepsScreenAwake() }
    }

    @Test fun verticalGameOpeningWelfareSelectsHorizontalAndOnlyDedicatedCloseCanRestoreVertical() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.code === 0 && window.bridgeResult.data === true")
        awaitMain("changeScreenmode 应将游戏切为竖屏") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        call(game, "getGameInfo", "{}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.data.screenMode === 'vertical'")
        assertBusinessScreenMode("vertical")
        onView(withContentDescription("游戏悬浮球")).perform(click())
        val welfare = awaitWeb("welfare")
        awaitMain("竖屏游戏点击悬浮球后应先切为横屏再显示大厅") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        awaitMain("横屏后的福利大厅必须铺满宿主窗口") {
            val page = container().get("web_welfare")?.pageView ?: return@awaitMain false
            val parent = page.parent as? ViewGroup ?: return@awaitMain false
            parent.width > parent.height && parent.height > 0 &&
                page.width == parent.width && page.height == parent.height
        }
        call(welfare, "getGameInfo", "{}")
        awaitJs(welfare, "window.bridgeResult && window.bridgeResult.data.screenMode === 'horizontal'")
        assertBusinessScreenMode("horizontal")
        call(welfare, "getWelfareInfo", "{}")
        awaitJs(welfare, "window.bridgeResult && window.bridgeResult.code === 0 && window.bridgeResult.data.entryMode === 'vertical' && window.bridgeResult.data.currentMode === 'horizontal'")

        call(welfare, "closePage", "{}")
        awaitMain("closePage 只关闭大厅，不能恢复竖屏") {
            !container().hasWindow("web_welfare") &&
                activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        call(game, "getGameInfo", "{}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.data.screenMode === 'horizontal'")
        assertBusinessScreenMode("horizontal")

        call(game, "openMiniWindow", "{page:'web_welfare'}")
        awaitWeb("welfare")
        call(game, "closeMiniWindow", "{page:'web_welfare'}")
        awaitMain("closeMiniWindow 只关闭大厅，不能处理屏幕旋转") {
            !container().hasWindow("web_welfare") &&
                activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        call(game, "openMiniWindow", "{page:'web_welfare'}")
        val finalWelfare = awaitWeb("welfare")
        call(finalWelfare, "closeWelfarepage", "{mode:'vertical'}")
        awaitMain("closeWelfarepage(vertical) 应在关闭大厅后切回竖屏") {
            !container().hasWindow("web_welfare") &&
                activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        call(game, "getGameInfo", "{}")
        awaitJs(game, "window.bridgeResult && window.bridgeResult.data.screenMode === 'vertical'")
        assertBusinessScreenMode("vertical")
    }

    @Test fun actualScreenChangesRelayoutAllOpenH5Pages() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        call(game, "openMiniWindow", "{page:'web_welfare'}")
        awaitWeb("welfare")

        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitMain("应完成竖屏布局") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                pageFillsCurrentHost("web_game", landscape = false) &&
                pageFillsCurrentHost("web_welfare", landscape = false)
        }
        call(game, "changeScreenmode", "{mode:'horizontal'}")
        awaitMain("应完成横屏布局") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                pageFillsCurrentHost("web_game", landscape = true) &&
                pageFillsCurrentHost("web_welfare", landscape = true)
        }
        call(container().get("web_welfare")!!.webView, "closePage", "{}")
        awaitMain("大厅应已关闭") { !container().hasWindow("web_welfare") }
        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitMain("游戏页应按竖屏宿主重排") { pageFillsCurrentHost("web_game", landscape = false) }
        call(game, "openMiniWindow", "{page:'web_login'}")
        awaitWeb("login")
        awaitMain("切屏后新打开的 H5 也必须按当前竖屏尺寸布局") {
            pageFillsCurrentHost("web_login", landscape = false)
        }
    }

    @Test fun pendingWelfareOpenCanBeCancelledBeforeRotationAndThenRequestedAgain() {
        launchSavedTokenGame()
        val game = awaitWeb("game")
        call(game, "changeScreenmode", "{mode:'vertical'}")
        awaitMain("测试前应进入竖屏") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        call(game, "openMiniWindow", "{page:'web_welfare'}")
        call(game, "closeWelfarepage", "{mode:'vertical'}")
        awaitMain("立即关闭应取消待打开大厅且保持竖屏") {
            !container().hasWindow("web_welfare") &&
                activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        call(game, "openMiniWindow", "{page:'web_welfare'}")
        awaitWeb("welfare")
        awaitMain("取消后的新请求仍应重新切横屏并铺满大厅") {
            activityRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                pageFillsCurrentHost("web_welfare", landscape = true)
        }
    }

    @Test fun logoutRestoresAutomaticScreenTimeout() {
        launchSavedTokenGame()
        onMain { assertTrue("进入游戏后应保持屏幕常亮", keepsScreenAwake()) }
        call(awaitWeb("game"), "logout", "{}")
        awaitWeb("login")
        onMain { assertFalse("退出登录后应恢复系统自动息屏策略", keepsScreenAwake()) }
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

    private fun keepsScreenAwake(): Boolean =
        activityRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun assertBusinessScreenMode(expected: String) {
        val info = onMainValue {
            MainActivity::class.java.getDeclaredMethod("gameInfo").apply { isAccessible = true }
                .invoke(activityRule.activity) as GameInfo
        }
        assertEquals(expected, info.screenMode)
        assertEquals(expected, GameHeaders.toMap(info, true).getValue("Screenmode"))
    }

    private fun pageFillsCurrentHost(instance: String, landscape: Boolean): Boolean {
        val page = container().get(instance)?.pageView ?: return false
        val parent = page.parent as? ViewGroup ?: return false
        val directionMatches = if (landscape) parent.width > parent.height else parent.height > parent.width
        return directionMatches && parent.width > 0 && parent.height > 0 &&
            page.width == parent.width && page.height == parent.height
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
        "floatball":{"show":$initiallyShown,"image_default":"${url("default.png")}","image_active":"${url("active.png")}","width":$ballSize,"height":$ballSize,"right":10,${ballTop?.let { "\"top\":$it," }.orEmpty()}"bottom":$ballBottom},
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
