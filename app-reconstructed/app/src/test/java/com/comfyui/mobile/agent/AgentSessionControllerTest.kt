package com.comfyui.mobile.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class AgentSessionControllerTest {
    @Test fun reservationIsBusyBeforeAnyCoroutineStarts() {
        val session = AgentSessionController()
        assertNotNull(session.begin("local"))
        assertTrue(session.busy.value)
        assertNull(session.begin("local"))
    }

    @Test fun concurrentReservationsHaveExactlyOneWinner() {
        val session = AgentSessionController()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { pool.submit<Boolean> { gate.await(); session.begin("local") != null } }
            gate.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }

    @Test fun oldCompletionAndFailureCannotEndNewTurn() {
        val session = AgentSessionController()
        val old = session.begin("one")!!
        session.stop()
        val next = session.begin("two")!!
        session.complete(old)
        session.fail(old, "late failure")
        session.advance(old, AgentPhase.SUBMITTING)
        assertTrue(session.isCurrent(next))
        assertEquals(AgentPhase.PREPARING, session.state.value.turn!!.phase)
    }

    @Test fun stopCancelsOnlyOwnedRequestAndJob() {
        val cancelled = mutableListOf<String>()
        val session = AgentSessionController { cancelled += it }
        val turn = session.begin("local")!!
        val job = Job()
        session.attach(turn, job)
        session.stop()
        assertEquals(listOf(turn.id), cancelled)
        assertTrue(job.isCancelled)
        assertFalse(session.busy.value)
        assertEquals(AgentPhase.STOPPED, session.state.value.turn!!.phase)
    }

    @Test fun lateAttachIsCancelledAndDoesNotReplaceNewJob() {
        val session = AgentSessionController()
        val old = session.begin("local")!!
        session.stop()
        val next = session.begin("local")!!
        val currentJob = Job()
        session.attach(next, currentJob)
        val staleJob = Job()
        session.attach(old, staleJob)
        assertTrue(staleJob.isCancelled)
        assertFalse(currentJob.isCancelled)
        session.stop()
        assertTrue(currentJob.isCancelled)
    }

    @Test fun twoInstancesDoNotShareStopFlagsOrBusy() {
        val first = AgentSessionController()
        val second = AgentSessionController()
        val a = first.begin("local")!!
        val b = second.begin("local")!!
        assertNotEquals(a.id, b.id)
        first.stop()
        assertTrue(second.isCurrent(b))
        second.complete(b)
        assertEquals(AgentPhase.COMPLETED, second.state.value.turn!!.phase)
    }

    @Test fun contextInvalidatesEvenWhileIdleAndFailedStateIsExplicit() {
        val session = AgentSessionController()
        val oldVersion = session.contextVersion()
        session.stop(invalidateContext = true)
        assertEquals(oldVersion + 1, session.contextVersion())
        val turn = session.begin("local")!!
        session.fail(turn, "TIMEOUT_TOTAL")
        assertFalse(session.busy.value)
        assertEquals(AgentPhase.FAILED, session.state.value.turn!!.phase)
        assertEquals("TIMEOUT_TOTAL", session.state.value.turn!!.failure)
    }

    @Test fun stopIsIdempotentAndLateCallbacksAreRejected() {
        val cancelled = mutableListOf<String>()
        val session = AgentSessionController { cancelled += it }
        val turn = session.begin("local")!!
        session.stop()
        assertNull(session.stop())
        assertEquals(1, cancelled.size)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { session.requireCurrent(turn) }
    }
}
