package com.qishimeng.game.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/** 阻塞下载器，只在后台线程使用；未完成文件不会作为 APK 暴露。 */
class ApkDownloader {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { cancelled = true; connection?.disconnect() }

    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): File {
        require(UpdatePolicy.isHttpUrl(url)) { "更新地址无效" }
        val parent = requireNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建更新目录")
        val partial = File.createTempFile("update-", ".part", parent)
        try {
            var current = url
            for (redirects in 0..5) {
                checkCancelled()
                val conn = URL(current).openConnection() as HttpURLConnection
                connection = conn
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    val status = conn.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (redirects == 5) throw IOException("更新地址重定向过多")
                        val location = conn.getHeaderField("Location") ?: throw IOException("更新重定向地址缺失")
                        val next = URL(URL(current), location).toExternalForm()
                        if (!UpdatePolicy.isHttpUrl(next)) throw IOException("更新重定向地址无效")
                        current = next
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw IOException("更新下载失败 HTTP $status")
                    val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    var received = 0L
                    conn.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                onProgress(received, total)
                            }
                        }
                    }
                    checkCancelled()
                    if (received == 0L || total >= 0 && received != total) throw IOException("更新安装包不完整")
                    if (target.exists() && !target.delete()) throw IOException("无法替换旧更新文件")
                    if (!partial.renameTo(target)) throw IOException("无法保存更新文件")
                    return target
                } finally {
                    connection = null
                    conn.disconnect()
                }
            }
            throw IOException("更新下载失败")
        } catch (error: Exception) {
            checkCancelled()
            throw error
        } finally {
            partial.delete()
        }
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("更新下载已取消")
    }
}
