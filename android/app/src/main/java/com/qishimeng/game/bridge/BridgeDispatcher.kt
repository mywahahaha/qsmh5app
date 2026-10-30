package com.qishimeng.game.bridge

typealias BridgeHandler = (Map<String, Any?>) -> Any?

class BridgeDispatcher(private val handlers: Map<String, BridgeHandler>) {
    fun dispatch(method: String, arguments: Map<String, Any?>): BridgeResult {
        val handler = handlers[method] ?: return BridgeResult(9999, "不支持的 Bridge 方法", null)
        return try {
            BridgeResult(0, "ok", handler(arguments))
        } catch (exception: IllegalArgumentException) {
            BridgeResult(1001, exception.message ?: "参数错误", null)
        } catch (_: Exception) {
            BridgeResult(9999, "操作失败", null)
        }
    }
}
