package com.qishimeng.game.update

import java.net.ServerSocket
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApkDownloaderTest {
    private lateinit var server: ServerSocket
    private lateinit var directory: File
    private val payload = ByteArray(100_000) { (it % 251).toByte() }
    @Before fun setUp() {
        directory = Files.createTempDirectory("qsm-download-test").toFile()
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().split(" ")[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val output = it.getOutputStream()
                        fun header(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
                        when (path) {
                            "/redirect", "/loop" -> header("HTTP/1.0 302 Found\r\nLocation: ${if (path == "/loop") "/loop" else "/apk"}\r\nContent-Length: 0\r\n\r\n")
                            "/bad" -> header("HTTP/1.0 503 Unavailable\r\nContent-Length: 0\r\n\r\n")
                            "/short" -> { header("HTTP/1.0 200 OK\r\nContent-Length: 50\r\n\r\n"); output.write(byteArrayOf(1, 2)) }
                            else -> {
                                header("HTTP/1.0 200 OK\r\n" + if (path == "/unknown") "\r\n" else "Content-Length: ${payload.size}\r\n\r\n")
                                output.write(payload)
                            }
                        }
                        output.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }
    @After fun tearDown() { server.close(); directory.deleteRecursively() }
    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    @Test fun followsRelativeRedirectAndReportsRealBytesBeforePublishingFile() {
        val target = File(directory, "ready.apk")
        var received = 0L
        var total = 0L
        val result = ApkDownloader().download(url("/redirect"), target) { count, length ->
            assertFalse("下载中不能暴露完成文件", target.exists())
            received = count; total = length
        }
        assertArrayEquals(payload, result.readBytes())
        assertEquals(payload.size.toLong(), received); assertEquals(received, total)
        assertEquals(listOf("ready.apk"), directory.listFiles()!!.map { it.name })
    }

    @Test fun acceptsUnknownLengthWithoutInventingTotal() {
        var total = 0L
        val result = ApkDownloader().download(url("/unknown"), File(directory, "ready.apk")) { _, length -> total = length }
        assertEquals(-1L, total); assertArrayEquals(payload, result.readBytes())
    }

    @Test fun failedHttpRedirectLoopAndTruncatedBodyNeverLeavePartialApk() {
        for (path in listOf("/bad", "/loop", "/short")) {
            assertTrue(runCatching { ApkDownloader().download(url(path), File(directory, "failed.apk")) { _, _ -> } }.isFailure)
            assertEquals(emptyList<String>(), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun cancellationRemovesPartialFileAndNeverReturnsInstallableResult() {
        val downloader = ApkDownloader()
        val failure = runCatching {
            downloader.download(url("/apk"), File(directory, "cancelled.apk")) { _, _ -> downloader.cancel() }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(emptyList<String>(), directory.listFiles()!!.map { it.name })
    }
}
