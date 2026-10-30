package com.qishimeng.game.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupHeartbeatServiceTest {
    @Test
    fun waitsConfiguredIntervalBeforeFirstPollAndRepeatsAtSameInterval() {
        val scheduler = ManualHeartbeatScheduler()
        var polls = 0
        val service = PopupHeartbeatService({ polls++; HeartbeatResponse(emptyList(), null) }, 30, {}, scheduler)

        service.start()
        assertEquals(listOf(30_000L), scheduler.delays)
        scheduler.runNext()
        assertEquals(1, polls)
        assertEquals(30_000L, scheduler.delays.last())
        service.stop()
        assertFalse(service.isRunning())
        assertTrue(scheduler.cancelled)
    }

    @Test
    fun failedPollDoesNotStopFutureHeartbeats() {
        val scheduler = ManualHeartbeatScheduler()
        var attempts = 0
        val service = PopupHeartbeatService({ attempts++; error("offline") }, 10, {}, scheduler)
        service.start()
        scheduler.runNext()
        assertEquals(1, attempts)
        assertEquals(10_000L, scheduler.delays.last())
    }

    @Test
    fun stoppingDuringRequestDiscardsResponseAndDoesNotScheduleAgain() {
        val scheduler = ManualHeartbeatScheduler()
        var responses = 0
        lateinit var service: PopupHeartbeatService
        service = PopupHeartbeatService({
            service.stop()
            HeartbeatResponse(emptyList(), null)
        }, 10, { responses++ }, scheduler)
        service.start()
        scheduler.runNext()

        assertEquals(0, responses)
        assertEquals(listOf(10_000L), scheduler.delays)
    }

    @Test
    fun cancelledTaskCannotPollOrChangeRestartedSchedule() {
        val scheduler = ManualHeartbeatScheduler()
        var polls = 0
        val service = PopupHeartbeatService({ polls++; HeartbeatResponse(null, null) }, 10, {}, scheduler)
        service.start()
        val stale = scheduler.next!!
        service.stop()
        service.start()
        stale()

        assertEquals(0, polls)
        assertEquals(2, scheduler.delays.size)
        scheduler.runNext()
        assertEquals(1, polls)
    }
}

internal class ManualHeartbeatScheduler : HeartbeatScheduler {
    val delays = mutableListOf<Long>()
    var next: (() -> Unit)? = null
    var cancelled = false
    override fun schedule(delayMillis: Long, action: () -> Unit): HeartbeatCancellation {
        delays += delayMillis
        next = action
        return HeartbeatCancellation { cancelled = true }
    }
    fun runNext() { val action = next; next = null; action?.invoke() }
}
