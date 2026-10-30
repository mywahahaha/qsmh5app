package com.qishimeng.game.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class AndroidUpdateDownloadTask(context: Context) : UpdateDownloadTask {
    private val directory = File(context.filesDir, "updates")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "qsm-update-download").apply { isDaemon = true } }
    private val downloader = ApkDownloader()
    @Volatile private var closed = false
    private var started = false

    override fun start(url: String, onProgress: (Long, Long) -> Unit, onComplete: (Result<File>) -> Unit) {
        if (closed || started) return
        started = true
        worker.execute {
            // 每次启动至多一个更新下载；清理上次会话遗留的半包及已完成文件。
            directory.listFiles()?.forEach { it.delete() }
            var lastUpdate = 0L
            val result = runCatching {
                downloader.download(url, File(directory, "update-${UUID.randomUUID()}.apk")) { bytes, total ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUpdate >= 100 || bytes == total) {
                        lastUpdate = now
                        main.post { if (!closed) onProgress(bytes, total) }
                    }
                }
            }
            main.post { if (!closed) onComplete(result) }
        }
    }

    override fun cancel() {
        closed = true
        downloader.cancel()
        worker.shutdownNow()
    }
}
