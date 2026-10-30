package com.qishimeng.game.network

import com.qishimeng.game.config.PageSpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class FloatingBallHeartbeat(val show: Boolean, val active: Boolean)

data class HeartbeatResponse(
    val popupPages: List<PageSpec>?,
    val floatingBall: FloatingBallHeartbeat?,
    val code: Int = 0
)

fun interface HeartbeatCancellation {
    fun cancel()
}

interface HeartbeatScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): HeartbeatCancellation
    fun close() {}
}

class PopupHeartbeatService(
    private val poll: () -> HeartbeatResponse,
    private val intervalSeconds: Int,
    private val onResponse: (HeartbeatResponse) -> Unit,
    private val scheduler: HeartbeatScheduler = ExecutorHeartbeatScheduler()
) {
    private var running = false
    private var generation = 0L
    private var closed = false
    private var pending: HeartbeatCancellation? = null

    @Synchronized
    fun start() {
        if (running || closed) return
        running = true
        generation++
        schedule(generation)
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        pending?.cancel()
        pending = null
    }

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun close() {
        if (closed) return
        stop()
        closed = true
        scheduler.close()
    }

    private fun schedule(epoch: Long) {
        pending = scheduler.schedule(intervalSeconds.coerceAtLeast(1) * 1_000L) {
            synchronized(this) {
                if (!running || generation != epoch) return@schedule
            }
            val response = runCatching(poll).getOrNull()
            synchronized(this) {
                if (!running || generation != epoch) return@schedule
                if (response != null) runCatching { onResponse(response) }
                if (running && generation == epoch) schedule(epoch)
            }
        }
    }
}

private class ExecutorHeartbeatScheduler : HeartbeatScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "qsm-popup-heartbeat").apply { isDaemon = true }
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): HeartbeatCancellation {
        val future: ScheduledFuture<*> = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return HeartbeatCancellation { future.cancel(false) }
    }

    override fun close() { executor.shutdownNow() }
}
