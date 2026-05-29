package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.RuntimeExternalWaitState
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.runtime.SessionResumeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionWaitSchedulerTest {
    @Before
    fun setup() {
        AssistantSignalWaitStore.resetForTest()
    }

    private fun snapshot(
        sid: String,
        pkg: String,
        waiting: Boolean,
        nextPlanEligibleAtMs: Long = 0L,
    ): SessionResumeSnapshot =
        SessionResumeSnapshot(
            resumable = true,
            sessionId = sid,
            targetPackageName = pkg,
            externalWaitState =
                if (waiting) {
                    RuntimeExternalWaitState(event = "manual_verification", reason = "login")
                } else {
                    null
                },
            nextPlanEligibleAtMs = nextPlanEligibleAtMs,
        )

    private fun signal(
        type: AssistantExternalSignalType,
        pkg: String,
    ): AssistantExternalSignal =
        AssistantExternalSignal(
            id = "sig_$pkg",
            type = type,
            capability = SessionCapabilityKey.START_SESSION,
            title = "t",
            summary = "s",
            fireAtMs = 0L,
            createdAtMs = 0L,
            updatedAtMs = 0L,
            payload = mapOf("package_name" to pkg),
        )

    @Test
    fun `binds foreground signal for waiting session and resolves it`() {
        val result =
            SessionWaitScheduler.syncSuspendedSessionBindings(
                listOf(snapshot("s1", "com.taobao.taobao", waiting = true)),
            )
        assertTrue(result.signalBindings >= 1)
        val binding =
            SessionWaitScheduler.resolveSignalBinding(
                signal(AssistantExternalSignalType.APP_FOREGROUND, "com.taobao.taobao"),
            )
        assertNotNull(binding)
        assertEquals("s1", binding!!.sessionId)
    }

    @Test
    fun `skips session without external wait`() {
        val result =
            SessionWaitScheduler.syncSuspendedSessionBindings(
                listOf(snapshot("s2", "com.taobao.taobao", waiting = false)),
            )
        assertEquals(0, result.signalBindings)
        assertNull(
            SessionWaitScheduler.resolveSignalBinding(
                signal(AssistantExternalSignalType.APP_FOREGROUND, "com.taobao.taobao"),
            ),
        )
    }

    @Test
    fun `schedules time resume from future next-plan-eligible`() {
        val future = System.currentTimeMillis() + 60 * 60 * 1000L
        val result =
            SessionWaitScheduler.syncSuspendedSessionBindings(
                listOf(snapshot("s3", "com.jingdong.app.mall", waiting = true, nextPlanEligibleAtMs = future)),
            )
        assertTrue(result.timeResumes >= 1)
    }

    @Test
    fun `scheduleTimeResume creates a SCHEDULED_TASK that is a time-resume task`() {
        val future = System.currentTimeMillis() + 60 * 60 * 1000L
        val task = SessionWaitScheduler.scheduleTimeResume("s9", "com.taobao.taobao", future, "test")
        assertNotNull(task)
        assertEquals(AssistantProactiveTaskType.SCHEDULED_TASK, task!!.type)
        assertEquals(SessionCapabilityKey.RESUME_SESSION, task.capability)
        assertTrue(SessionWaitScheduler.isTimeResumeTask(task))
        val next = SessionWaitScheduler.computeNextWakeAtMs(System.currentTimeMillis())
        assertNotNull(next)
        assertTrue(next!! <= future)
    }
}
