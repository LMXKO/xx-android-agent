package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulingSkillTest {
    private fun ctx(
        task: String,
        history: List<AgentTurnRecord> = emptyList(),
    ): SkillDecisionContext =
        SkillDecisionContext(
            task = task,
            profileId = "p",
            observation =
                ScreenObservation(
                    packageName = "com.x",
                    pageState = "UNKNOWN",
                    signature = "s",
                    screenSummary = "",
                    topTexts = emptyList(),
                    primaryEditableId = null,
                    focusedElementId = null,
                    defaultScrollableId = null,
                    primaryInterruptActionId = null,
                    interruptiveHints = emptyList(),
                    elements = emptyList(),
                ),
            memoryContext = PlanningMemoryContext(),
            history = history,
        )

    @Test
    fun `activates only on scheduling tasks`() {
        assertTrue(ReminderSchedulingSkill.shouldActivate(ctx("提醒我明天8点开会")))
        assertTrue(ReminderSchedulingSkill.shouldActivate(ctx("帮我设个5分钟倒计时")))
        assertFalse(ReminderSchedulingSkill.shouldActivate(ctx("比较京东淘宝的 AirPods 价格")))
    }

    @Test
    fun `stopwatch task emits OpenStopwatch`() {
        assertEquals(AgentAction.OpenStopwatch, ReminderSchedulingSkill.maybePrePlan(ctx("打开秒表"))?.action)
    }

    @Test
    fun `timer task with duration emits CreateTimer`() {
        val action = ReminderSchedulingSkill.maybePrePlan(ctx("帮我设个5分钟倒计时"))?.action
        assertTrue(action is AgentAction.CreateTimer)
        assertEquals("5分钟", (action as AgentAction.CreateTimer).durationLabel)
    }

    @Test
    fun `alarm task with clock time emits CreateAlarm`() {
        val action = ReminderSchedulingSkill.maybePrePlan(ctx("明天早上8点叫我起床"))?.action
        assertTrue(action is AgentAction.CreateAlarm)
    }

    @Test
    fun `alarm task without a time defers to the planner`() {
        assertNull(ReminderSchedulingSkill.maybePrePlan(ctx("提醒我多喝水")))
    }

    @Test
    fun `calendar task with a time emits InsertCalendarEvent`() {
        val action = ReminderSchedulingSkill.maybePrePlan(ctx("在日历里安排明天下午3点的项目评审"))?.action
        assertTrue(action is AgentAction.InsertCalendarEvent)
    }

    @Test
    fun `does not re-emit once the system action already ran`() {
        val history =
            listOf(
                AgentTurnRecord(
                    observationSignature = "s",
                    pageState = "UNKNOWN",
                    action = "open_stopwatch()",
                    result = "done",
                ),
            )
        assertNull(ReminderSchedulingSkill.maybePrePlan(ctx("打开秒表", history)))
    }
}
