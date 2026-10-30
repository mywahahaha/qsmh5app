package com.qishimeng.game.network

import com.qishimeng.game.auth.GameInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeartbeatHttpIntegrationTest {
    @Test
    fun actualTransportPostsToConfiguredEndpointWithCurrentSessionHeaders() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val received = worker.submit<ApiRequest> {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val requestBody = buildString {
                        repeat(headers.getValue("content-length").toInt()) { append(reader.read().toChar()) }
                    }
                    val body = """{"code":0,"msg":"success","data":{"popup_pages":[],"floating_ball":{"show":true,"active":true}}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                    ApiRequest(requestLine[0], requestLine[1], requestBody,
                        listOf("Gameid", "Devicecode", "Packagecode", "Devicetype", "Accountoken", "Gameversion")
                            .associateWith { headers[it.lowercase()].orEmpty() })
                }
            }
            val result = ApiClient().postHeartbeat("http://127.0.0.1:${server.localPort}/configured-heartbeat?scene=hall",
                GameInfo("channel-test", 266, "1.2.3", "session-test-token", "android", "device-test"), true)

            val request = received.get(5, TimeUnit.SECONDS)
            assertEquals("POST", request.method)
            assertEquals("/configured-heartbeat?scene=hall", request.url)
            assertEquals("{}", request.body)
            assertEquals(mapOf("Gameid" to "266", "Devicecode" to "device-test", "Packagecode" to "channel-test",
                "Devicetype" to "android", "Accountoken" to "session-test-token", "Gameversion" to "1.2.3"), request.headers)
            assertEquals(emptyList<Any>(), result.popupPages)
            assertEquals(FloatingBallHeartbeat(true, true), result.floatingBall)
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }
}
