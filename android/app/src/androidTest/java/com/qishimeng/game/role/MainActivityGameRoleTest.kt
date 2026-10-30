package com.qishimeng.game.role

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.qishimeng.game.MainActivity
import com.qishimeng.game.network.ApiClient
import com.qishimeng.game.network.ApiResponse
import com.qishimeng.game.network.HttpTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 真实 MainActivity、Bridge、WebView 和角色持久化；仅初始化接口使用本地配置。 */
@RunWith(AndroidJUnit4::class)
class MainActivityGameRoleTest {
    @get:Rule val activityRule = ActivityTestRule(MainActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val workers = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket
    private lateinit var storage: SharedPreferencesRoleStorage
    private var launched = false
    private val gameRequested = CountDownLatch(1)
    private val releaseGame = CountDownLatch(1)
    private val nextRequested = CountDownLatch(1)
    private val releaseNext = CountDownLatch(1)
    private val releaseFailed = CountDownLatch(1)
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.PRE_ON_CREATE) {
            val api = ApiClient(HttpTransport { request ->
                check(request.url == ApiClient.INIT_URL) { "角色回归不得访问线上接口" }
                ApiResponse(200, configJson())
            })
            MainActivity::class.java.getDeclaredField("apiClient").apply { isAccessible = true }.set(activity, api)
        }
    }

    @Before fun setUp() {
        for (name in listOf("account_token", "privacy_state", "game_roles", "h5_local_storage")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
        storage = SharedPreferencesRoleStorage(context)
        server = ServerSocket(0)
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { respond(socket) } }
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
    }

    @After fun tearDown() {
        releaseGame.countDown()
        releaseNext.countDown()
        releaseFailed.countDown()
        if (launched) activityRule.finishActivity()
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        server.close()
        workers.shutdownNow()
        for (name in listOf("account_token", "privacy_state", "game_roles", "h5_local_storage")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun bridgeGetsRoleByGameIdAndFullyReplacesOnlyThatGamesData() {
        val login = launchLogin()
        assertTrue(call(login, "saveGameRole", "{gameId:266,roleId:'old',roleName:'旧角色',roleLevel:10}").get("data").asBoolean)
        assertTrue(call(login, "saveGameRole", "{gameId:267,roleId:'other'}").get("data").asBoolean)
        assertTrue(call(login, "saveGameRole", "{gameId:266,roleId:'new',serverId:'s1'}").get("data").asBoolean)
        val result = call(login, "getGameRole", "{gameId:266}")
        assertEquals(0, result.get("code").asInt)
        assertEquals(JsonParser.parseString("""{"gameId":266,"roleId":"new","serverId":"s1"}"""), result.get("data"))
        assertEquals("other", call(login, "getGameRole", "{gameId:267}").getAsJsonObject("data").get("roleId").asString)
        assertEquals(listOf(GameRole(267, "other"), GameRole(266, "new", serverId = "s1")), SharedPreferencesRoleStorage(context).load())
        for (args in listOf("{gameId:999}", "{}", "{gameId:'266'}", "{gameId:266.5}", "{gameId:4294967562}")) {
            assertEquals("无记录或无效 gameId 必须返回 data:false：$args", JsonParser.parseString("false"), call(login, "getGameRole", args).get("data"))
        }
    }

    @Test fun successfulGameLoadsClearAllGamesIncludingReloadAndChangeGameUrl() {
        seedRoles()
        context.getSharedPreferences("h5_local_storage", 0).edit().putString("sentinel", "keep").commit()
        val login = launchLogin()
        assertEquals("登录页加载不应清空角色", 2, storage.load().size)
        call(login, "notifyLoginSuccess", "{accountToken:'fixture-role-token'}")
        assertTrue(gameRequested.await(10, TimeUnit.SECONDS))
        assertEquals("游戏加载中应保留角色", 2, storage.load().size)
        releaseGame.countDown()
        val game = awaitWeb("game")
        awaitMain("首次游戏加载成功应清空所有游戏角色") { storage.load().isEmpty() }
        assertEquals("keep", context.getSharedPreferences("h5_local_storage", 0).getString("sentinel", null))

        saveTwoGames(game)
        val oldDocument = evaluate(game, "performance.timeOrigin")
        onMain { game.reload() }
        awaitJs(game, "performance.timeOrigin !== $oldDocument && document.readyState === 'complete'")
        awaitMain("原页面刷新成功应清空所有游戏角色") { storage.load().isEmpty() }

        saveTwoGames(game)
        assertTrue(call(game, "changeGameUrl", "{url:'${url("game-next")}'}").get("data").asBoolean)
        assertTrue(nextRequested.await(10, TimeUnit.SECONDS))
        assertEquals("changeGameUrl 接受导航但尚未成功时不可清空角色", 2, storage.load().size)
        releaseNext.countDown()
        awaitJs(game, "location.pathname === '/game-next' && document.readyState === 'complete'")
        awaitMain("changeGameUrl 加载成功应清空所有游戏角色") { storage.load().isEmpty() }
        assertFalse(call(game, "getGameRole", "{gameId:266}").get("data").asBoolean)
        assertFalse(call(game, "getGameRole", "{gameId:267}").get("data").asBoolean)
        saveTwoGames(game)
        val welfare = call(game, "openMiniWindow", "{page:'web_welfare'}")
        assertTrue(welfare.get("data").asBoolean)
        awaitWeb("welfare")
        assertEquals("福利页加载不得清空角色", 2, storage.load().size)
    }

    @Test fun failedGameNavigationDoesNotClearStoredRoles() {
        seedRoles()
        releaseGame.countDown()
        val login = launchLogin()
        call(login, "notifyLoginSuccess", "{accountToken:'fixture-role-token'}")
        val game = awaitWeb("game")
        awaitMain("首次游戏加载成功应清空角色") { storage.load().isEmpty() }
        call(game, "saveGameRole", "{gameId:266,roleId:'saved'}")
        assertTrue(call(game, "changeGameUrl", "{url:'${url("game-failed")}'}").get("data").asBoolean)
        releaseFailed.countDown()
        awaitMain("404 应显示原生错误面板") { descendants(activityRule.activity.window.decorView).any { it is android.widget.TextView && it.text.contains("H5_HTTP_404") && it.isShown } }
        assertEquals(listOf(GameRole(266, "saved")), storage.load())
    }

    private fun seedRoles() = storage.save(listOf(GameRole(266, "before"), GameRole(267, "other")))

    private fun saveTwoGames(view: WebView) {
        assertTrue(call(view, "saveGameRole", "{gameId:266,roleId:'fresh'}").get("data").asBoolean)
        assertTrue(call(view, "saveGameRole", "{gameId:267,roleId:'fresh-other'}").get("data").asBoolean)
    }

    private fun launchLogin(): WebView {
        activityRule.launchActivity(null)
        launched = true
        return awaitWeb("login")
    }

    private fun configJson() = """{"code":0,"msg":"ok","data":{"privacyAuto":false,"aliyunOnekeyConfig":{"open":0},
        "h5Pages":{${listOf("login", "game", "welfare").joinToString(",") { name ->
            """"$name":{"instance":"web_$name","url":"${url(name)}","width":"100%","height":"100%","left":0,"top":0}"""
        }}}}}"""

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"

    private fun respond(socket: Socket) = socket.use {
        val reader = it.getInputStream().bufferedReader()
        val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
        while (!reader.readLine().isNullOrEmpty()) { }
        if (path == "/game") { gameRequested.countDown(); releaseGame.await(20, TimeUnit.SECONDS) }
        if (path == "/game-next") { nextRequested.countDown(); releaseNext.await(20, TimeUnit.SECONDS) }
        if (path == "/game-failed") releaseFailed.await(20, TimeUnit.SECONDS)
        val status = if (path == "/game-failed") "404 Not Found" else "200 OK"
        val body = "<html><body>本地角色存储回归页面</body></html>".toByteArray()
        it.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun awaitWeb(name: String): WebView {
        var result: WebView? = null
        awaitMain("未打开 $name 页面") {
            result = descendants(activityRule.activity.window.decorView).filterIsInstance<WebView>().firstOrNull { it.url == url(name) }
            result != null
        }
        awaitJs(result!!, "typeof QSMH5APP === 'object' && document.readyState === 'complete'")
        return result!!
    }

    private fun call(view: WebView, method: String, args: String): JsonObject {
        evaluate(view, "window.roleResult=null;QSMH5APP.$method($args,function(r){window.roleResult=r;});")
        awaitJs(view, "window.roleResult != null")
        return JsonParser.parseString(evaluate(view, "window.roleResult")).asJsonObject
    }

    private fun awaitJs(view: WebView, expression: String) = awaitCondition("等待 H5 条件超时：$expression") { evaluate(view, "Boolean($expression)") == "true" }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        onMain { view.evaluateJavascript(script) { result = it; done.countDown() } }
        check(done.await(3, TimeUnit.SECONDS)) { "JS 执行超时" }
        return result
    }

    private fun awaitMain(message: String, condition: () -> Boolean) {
        awaitCondition(message) {
            var ready = false
            onMain { ready = condition() }
            ready
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
