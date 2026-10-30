package com.qishimeng.game.network

import com.google.gson.Gson
import com.qishimeng.game.auth.GameInfo
import com.qishimeng.game.config.InitConfigParser
import com.qishimeng.game.config.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientTest {
    @Test
    fun initializationFailurePreservesEscapedServerMessageThroughResponseConversion() {
        val client = ApiClient(HttpTransport {
            ApiResponse(200, """{"code":100,"msg":"\u5f53\u524d\u7248\u672c\u5df2\u4f5c\u5e9f,\u8bf7\u8054\u7cfb\u5ba2\u670d\u4e0b\u8f7d\u6700\u65b0\u7248\u672c","data":[]}""")
        })

        val response = client.post(ApiClient.INIT_URL, "{}", GameInfo("", 92, "1.0.0", "", "android", ""), false)
        val result = InitConfigParser.parse(Gson().toJson(response))

        assertEquals(ParseResult.Failure("当前版本已作废,请联系客服下载最新版本"), result)
    }

    @Test
    fun initAddressIsFixedAndRequestCarriesHeaders() {
        var captured: ApiRequest? = null
        val client = ApiClient(HttpTransport { request ->
            captured = request
            ApiResponse(200, "{\"code\":0,\"msg\":\"success\",\"data\":{}}")
        })

        val response = client.post(ApiClient.INIT_URL, "{}", GameInfo("", 1, "1.0", "", "android", ""), false)

        assertEquals("https://box.74meng.com/h5_app/init", captured?.url)
        assertEquals("POST", captured?.method)
        assertEquals("{}", captured?.body)
        assertEquals("", captured?.headers?.get("Accountoken"))
        assertEquals(0, response.code)
    }

    @Test
    fun heartbeatParsesPopupPagesAndFloatingBallAndCarriesSameHeaders() {
        var captured: ApiRequest? = null
        val client = ApiClient(HttpTransport { request ->
            captured = request
            ApiResponse(200, """
                {"code":0,"msg":"success","data":{"popup_pages":[{"instance":"promo","url":"https://box.74meng.com/promo","width":300,"height":400,"left":0,"top":0}],"floating_ball":{"show":true,"active":false}}}
            """.trimIndent())
        })

        val response = client.postHeartbeat("https://example.com/popup", GameInfo("", 1, "1.0", "token", "android", "device"), true)

        assertEquals("https://example.com/popup", captured?.url)
        assertEquals("POST", captured?.method)
        assertEquals("{}", captured?.body)
        assertEquals(0, response.code)
        assertEquals("promo", response.popupPages?.single()?.instance)
        assertEquals(false, response.floatingBall?.active)
    }

    @Test
    fun heartbeatNonZeroCodeDoesNotParseBusinessData() {
        val client = ApiClient(HttpTransport {
            ApiResponse(200, "{\"code\":100,\"msg\":\"failed\",\"data\":[]}")
        })

        val response = client.postHeartbeat("https://example.com/popup", GameInfo("", 1, "1.0", "", "android", ""), false)

        assertEquals(100, response.code)
        assertEquals(null, response.popupPages)
        assertEquals(null, response.floatingBall)
    }

    @Test(expected = java.io.IOException::class)
    fun heartbeatHttpErrorCannotApplySuccessfulLookingBusinessData() {
        val client = ApiClient(HttpTransport {
            ApiResponse(503, "{\"code\":0,\"msg\":\"success\",\"data\":{\"popup_pages\":[]}}")
        })
        client.postHeartbeat("https://example.com/popup", GameInfo("", 1, "1.0", "", "android", ""), false)
    }

    @Test
    fun emptyDataArrayDoesNotClearPreviouslyOpenedPopups() {
        val client = ApiClient(HttpTransport { ApiResponse(200, "{\"code\":0,\"msg\":\"success\",\"data\":[]}") })
        val result = client.postHeartbeat("https://example.com/popup", GameInfo("", 1, "1.0", "", "android", ""), false)
        assertEquals(null, result.popupPages)
    }
}
