package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlatformBundleOpsTest {
    @Test
    fun `resume wait policy applies guard for page settling on first resume`() {
        val decision =
            SessionRuntimeResumeSupport.resolveResumeWaitPolicy(
                session =
                    RuntimeSession(
                        resumeContext =
                            ResumeContext(
                                active = true,
                                source = "manual_resume",
                                resumeEvent = "page_settling",
                                resumeHint = "继续当前子目标",
                            ),
                    ),
                taskPlanState =
                    TaskPlanState(
                        currentStage = "confirm_route",
                        currentSubgoalId = "route",
                        nextObjective = "确认路线",
                        waitingForExternal = true,
                        waitingForEvent = "page_settling",
                        suspendReason = "页面仍在稳定",
                        resumeContext =
                            ResumeContext(
                                active = true,
                                source = "manual_resume",
                                resumeEvent = "page_settling",
                                resumeHint = "继续当前子目标",
                            ),
                    ),
            )

        assertTrue(decision is ResumeWaitPolicyDecision.ApplyGuard)
        val guarded = (decision as ResumeWaitPolicyDecision.ApplyGuard).guardedTaskPlanState
        assertEquals(false, guarded.waitingForExternal)
        assertEquals("", guarded.waitingForEvent)
        assertEquals(1, guarded.resumeContext.resumeAttempt)
    }

    @Test
    fun `build resolved resume context keeps subgoal and next objective`() {
        val context =
            SessionRuntimeResumeSupport.buildResolvedResumeContext(
                previousWaitState =
                    RuntimeExternalWaitState(
                        event = "manual_verification",
                        subgoalId = "verify_login",
                    ),
                taskPlanState =
                    TaskPlanState(
                        currentSubgoalId = "fallback_subgoal",
                        nextObjective = "继续确认登录完成",
                    ),
                previousEvent = "",
            )

        assertEquals("external_wait_resolved", context.source)
        assertEquals("manual_verification", context.resumeEvent)
        assertEquals("verify_login", context.resumedSubgoalId)
        assertTrue(context.resumeHint.contains("继续确认登录完成"))
    }
}
