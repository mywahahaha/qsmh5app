package com.qishimeng.game.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitConfigParserTest {
    @Test
    fun malformedUpdateConfigDoesNotTurnSuccessfulInitializationIntoFailure() {
        val absent = InitConfigParser.parse("""{"code":0,"data":{"updateInfo":null}}""")
        assertTrue(absent is ParseResult.Success)
        assertTrue((absent as ParseResult.Success).value.updateInfo.isEmpty())
        val malformed = InitConfigParser.parse("""{"code":0,"data":{"updateInfo":true}}""")
        assertTrue(malformed is ParseResult.Success)
        assertTrue(com.qishimeng.game.update.UpdatePolicy.evaluate((malformed as ParseResult.Success).value.updateInfo) is com.qishimeng.game.update.UpdateDecision.Abort)
    }

    @Test
    fun parsesKnownInitializationFieldsAndPreservesPageInstances() {
        val result = InitConfigParser.parse(
            """{"code":0,"msg":"success","data":{"bgImage":"https://box.74meng.com/assets/game-bg.jpg","privacyAuto":true,"jumpDomain":["www.baidu.com","PAY.74MENG.COM","  ","www.baidu.com"],"aliyunOnekeyConfig":{"open":1,"config":{"exchange_url":"https://box.74meng.com/h5_app/login_by_onekey","screen_code":2,"launch_total_timeout_ms":6000,"app_protocols":[{"name":"用户协议","url":"https://box.74meng.com/protocol"}],"ui":{"title":"快捷登录","login_text":"一键登录","other_login_text":"其他登录","primary_color":"#CC0000","dialog_width_percent":70,"dialog_height_percent":80}}},"popupInterval":{"interval":30,"url":"https://box.74meng.com/h5_app/popup_interval"},"h5Pages":{"game":{"instance":"web_game","url":"https://box.74meng.com/h5?gid=266","width":"100%","height":"100%","left":0,"top":0}}}}"""
        )

        assertTrue(result is ParseResult.Success)
        val config = (result as ParseResult.Success).value
        assertEquals(true, config.privacyAuto)
        assertEquals("https://box.74meng.com/assets/game-bg.jpg", config.bgImage)
        assertEquals(setOf("www.baidu.com", "pay.74meng.com"), config.jumpDomains)
        assertEquals("web_game", config.pages.getValue("game").instance)
        assertEquals("https://box.74meng.com/h5_app/login_by_onekey", config.aliyunExchangeUrl)
        assertEquals(true, config.aliyunOnekeyOpen)
        assertEquals(30, config.heartbeatIntervalSeconds)
        assertEquals(2, config.aliyunScreenCode)
        assertEquals(70, config.aliyunDialogWidthPercent)
        assertEquals(80, config.aliyunDialogHeightPercent)
        assertEquals("快捷登录", config.aliyunTitle)
        assertEquals("一键登录", config.aliyunLoginText)
        assertEquals("其他登录", config.aliyunOtherLoginText)
        assertEquals("#CC0000", config.aliyunPrimaryColor)
        assertEquals("用户协议", config.aliyunProtocols.single().first)
        assertEquals(6_000, config.aliyunTotalTimeoutMillis)
    }

    @Test
    fun parsesCurrentOnlineOnekeySettings() {
        // 2026-10-27 游戏 92 / 1.0.1 的一键登录配置，SDK 密钥以测试值替换。
        val json = """{"code":0,"msg":"success","data":{"aliyunOnekeyConfig":{"open":1,"config":{
            "screen_code":1,"launch_total_timeout_ms":5000,"launch_attempt_timeout_ms":2000,
            "launch_max_attempts":2,"launch_retry_interval_ms":500,"scheme_secret":"fixture-sdk-secret",
            "exchange_url":"https://box.74meng.com/h5_app/login_by_onekey",
            "app_protocols":[{"name":"《用户协议》","url":"https://box.74meng.com/h5_app/privacy?type=1&sid=9&gid=92"},
            {"name":"《隐私政策》","url":"https://box.74meng.com/h5_app/privacy?type=2&sid=9&gid=92"}],
            "ui":{"title":"手机快捷登录","login_text":"手机号一键登录","other_login_text":"使用其他方式登录",
            "primary_color":"#CC0000","dialog_width_percent":60,"dialog_height_percent":75}}}}}"""
        val result = InitConfigParser.parse(json) as ParseResult.Success
        val config = result.value

        assertTrue(config.aliyunOnekeyOpen)
        assertEquals("fixture-sdk-secret", config.aliyunSchemeSecret)
        assertEquals("https://box.74meng.com/h5_app/login_by_onekey", config.aliyunExchangeUrl)
        assertEquals(1, config.aliyunScreenCode)
        assertEquals(5_000, config.aliyunTotalTimeoutMillis)
        assertEquals(60, config.aliyunDialogWidthPercent)
        assertEquals(75, config.aliyunDialogHeightPercent)
        assertEquals("手机快捷登录", config.aliyunTitle)
        assertEquals("手机号一键登录", config.aliyunLoginText)
        assertEquals("使用其他方式登录", config.aliyunOtherLoginText)
        assertEquals("#CC0000", config.aliyunPrimaryColor)
        assertEquals(listOf(
            "《用户协议》" to "https://box.74meng.com/h5_app/privacy?type=1&sid=9&gid=92",
            "《隐私政策》" to "https://box.74meng.com/h5_app/privacy?type=2&sid=9&gid=92"
        ), config.aliyunProtocols)
    }

    @Test
    fun treatsMissingOrBlankBackgroundImageAsNotConfigured() {
        val missing = InitConfigParser.parse("""{"code":0,"data":{}}""") as ParseResult.Success
        val blank = InitConfigParser.parse("""{"code":0,"data":{"bgImage":"  "}}""") as ParseResult.Success

        assertEquals(null, missing.value.bgImage)
        assertEquals(null, blank.value.bgImage)
    }

    @Test
    fun rejectsInvalidTopLevelCodeAndWrongFieldTypes() {
        assertEquals(ParseResult.Failure("游戏已下架"), InitConfigParser.parse("""{"code":2,"msg":"游戏已下架"}"""))
        assertEquals(ParseResult.Failure("游戏维护中，请稍后重试"), InitConfigParser.parse("""{"code":-1,"msg":"游戏维护中，请稍后重试","data":null}"""))
        assertEquals(ParseResult.Failure("初始化接口返回 code=2"), InitConfigParser.parse("""{"code":2}"""))
        assertTrue(InitConfigParser.parse("""{"code":0,"msg":"ok","data":{"privacyAuto":"yes"}}""") is ParseResult.Failure)
    }
}
