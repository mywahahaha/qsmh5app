package com.qishimeng.game.network

import com.google.gson.JsonElement

data class ApiResult<T>(val code: Int, val msg: String, val data: T?)

data class ApiRequest(
    val method: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>
)

data class ApiResponse(val httpStatus: Int, val body: String)

data class JsonApiResult(val code: Int, val msg: String, val data: JsonElement?)

fun interface HttpTransport {
    fun execute(request: ApiRequest): ApiResponse
}
