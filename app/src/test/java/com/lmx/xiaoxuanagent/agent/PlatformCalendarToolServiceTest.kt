package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class PlatformCalendarToolServiceTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `calendar tool parses Chinese relative meeting time`() {
        val now = ZonedDateTime.of(2026, 5, 22, 8, 30, 0, 0, zone)

        val window = PlatformCalendarToolService.resolveEventWindow("明天下午三点开会", now) ?: error("window expected")
        val begin = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(window.beginAtMs), zone)
        val end = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(window.endAtMs), zone)

        assertEquals(2026, begin.year)
        assertEquals(5, begin.monthValue)
        assertEquals(23, begin.dayOfMonth)
        assertEquals(15, begin.hour)
        assertEquals(0, begin.minute)
        assertEquals(60, Duration.between(begin, end).toMinutes())
        assertTrue(window.normalizedLabel.contains("2026-05-23 15:00"))
    }

    @Test
    fun `calendar tool parses explicit time range`() {
        val now = ZonedDateTime.of(2026, 5, 22, 8, 30, 0, 0, zone)

        val window = PlatformCalendarToolService.resolveEventWindow("2026-06-01 10:30-11:15", now) ?: error("window expected")
        val begin = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(window.beginAtMs), zone)
        val end = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(window.endAtMs), zone)

        assertEquals(2026, begin.year)
        assertEquals(6, begin.monthValue)
        assertEquals(1, begin.dayOfMonth)
        assertEquals(10, begin.hour)
        assertEquals(30, begin.minute)
        assertEquals(11, end.hour)
        assertEquals(15, end.minute)
    }

    @Test
    fun `calendar receipt keeps handoff explicit when time is unresolved`() {
        val lines =
            PlatformCalendarToolService.receiptLines(
                title = "周会",
                whenLabel = "客户方便的时候",
                eventWindow = null,
            )

        assertTrue(lines.contains("receipt=calendar_event_draft_opened"))
        assertTrue(lines.contains("time_parse=unresolved"))
        assertTrue(lines.contains("mode=user_confirm_save"))
    }

    @Test
    fun `planner policy prefers native calendar tool for schedule tasks`() {
        val policy =
            AgentToolSelectionPolicy.analyze(
                task = "明天下午三点创建一个周会日程",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.android.calendar",
            )

        assertTrue(policy.preferredTools.contains("system.insert_calendar_event"))
    }

    @Test
    fun `calendar action is parsed and reviewed as confirmed write`() {
        val decision =
            OpenAiPlanner().debugParseDecision(
                rawContent = """{"action":"insert_calendar_event","title":"周会","details":"准备周报","when_label":"明天下午3点","reason":"走系统日历"}""",
                observation = emptyObservation(),
                targetPackageName = "com.android.calendar",
            )

        assertTrue(decision.action is AgentAction.InsertCalendarEvent)
        val action = decision.action as AgentAction.InsertCalendarEvent
        assertEquals("周会", action.title)
        assertEquals("准备周报", action.details)
        assertEquals("明天下午3点", action.whenLabel)

        val review = AgentToolRuntimeProtocol.evaluate(action = action, observation = emptyObservation())
        assertTrue(review.valid)
        assertTrue(!review.readOnly)
        assertTrue(review.detailLines.any { it.contains("permission_family=confirm_calendar_write") })
    }

    @Test
    fun `calendar details with sensitive payload require runtime review`() {
        val runtimeObject =
            AgentToolRuntimeObjectRegistry.resolve(
                AgentAction.InsertCalendarEvent(
                    title = "账号交接",
                    details = "密码 123456",
                    whenLabel = "明天上午",
                ),
            )

        val permission =
            runtimeObject.checkPermissions(
                action =
                    AgentAction.InsertCalendarEvent(
                        title = "账号交接",
                        details = "密码 123456",
                        whenLabel = "明天上午",
                    ),
                observation = emptyObservation(),
            )

        assertEquals("ask", permission.behavior)
        assertTrue(permission.detailLines.any { it.contains("calendar_payload_sensitive") })
    }

    private fun emptyObservation(): ScreenObservation =
        ScreenObservation(
            packageName = "com.lmx.xiaoxuanagent",
            pageState = "UNKNOWN",
            signature = "empty",
            screenSummary = "empty",
            topTexts = emptyList(),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )
}
