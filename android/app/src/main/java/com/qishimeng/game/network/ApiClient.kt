package com.qishimeng.game.network

import com.google.gson.Gson
import com.qishimeng.game.auth.GameInfo
import java.net.HttpURLConnection
import java.net.URL

class ApiClient(
    private val transport: HttpTransport = UrlConnectionTransport()
) {
    fun post(url: String, body: String, gameInfo: GameInfo, privacyAgreed: Boolean): JsonApiResult {
        val request = ApiRequest(
            method = "POST",
            url = url,
            body = body,
            headers = GameHeaders.toMap(gameInfo, privacyAgreed) + ("Content-Type" to "application/json; charset=utf-8")
        )
        val response = transport.execute(request)
        return Gson().fromJson(response.body, JsonApiResult::class.java)
    }

    fun postHeartbeat(url: String, gameInfo: GameInfo, privacyAgreed: Boolean): HeartbeatResponse {
        val request = ApiRequest(
            method = "POST",
            url = url,
            body = "{}",
            headers = GameHeaders.toMap(gameInfo, privacyAgreed) + ("Content-Type" to "application/json; charset=utf-8")
        )
        val response = transport.execute(request)
        if (response.httpStatus !in 200..299) throw java.io.IOException("心跳请求失败 HTTP ${response.httpStatus}")
        return HeartbeatResponseParser.parse(response.body)
    }

    companion object {
        const val INIT_URL = "https://box.74meng.com/h5_app/init"
    }
}

private class UrlConnectionTransport : HttpTransport {
    override fun execute(request: ApiRequest): ApiResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = request.method == "POST"
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            if (connection.doOutput) connection.outputStream.use { stream ->
                stream.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ApiResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
