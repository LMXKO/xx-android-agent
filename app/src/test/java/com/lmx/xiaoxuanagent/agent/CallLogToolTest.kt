package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogToolTest {
    @Test
    fun `call log is a read-only system utility`() {
        val descriptor = AgentToolCatalog.find("system.read_call_log")

        assertEquals("call_log_read", descriptor?.permissionFamily)
        assertTrue(descriptor?.concurrencySafe == true)
        assertTrue(ActionExecutor.registeredTools().contains("system.read_call_log"))

        val review =
            AgentToolRuntimeProtocol.evaluate(
                action = AgentAction.ReadCallLog(query = "张三", type = "missed", limit = 3),
                observation = emptyObservation(),
            )

        assertTrue(review.valid)
        assertTrue(review.readOnly)
        assertTrue(review.detailLines.any { it.contains("permission_family=call_log_read") })
    }

    @Test
    fun `missed call tasks prefer call log before gui exploration`() {
        val policy =
            AgentToolSelectionPolicy.analyze(
                task = "查看最近未接来电",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.android.dialer",
            )

        assertTrue(policy.preferredTools.contains("system.read_call_log"))
        assertTrue(policy.avoidTools.contains("gui.tap_point"))
        assertTrue(policy.avoidTools.contains("gui.swipe"))
    }

    @Test
    fun `planner parses read call log action`() {
        val decision =
            OpenAiPlanner().debugParseDecision(
                rawContent = """{"action":"read_call_log","query":"张三","type":"missed","limit":4,"reason":"先看未接来电"}""",
                observation = emptyObservation(),
                targetPackageName = "com.android.dialer",
            )

        assertTrue(decision.action is AgentAction.ReadCallLog)
        val action = decision.action as AgentAction.ReadCallLog
        assertEquals("张三", action.query)
        assertEquals("missed", action.type)
        assertEquals(4, action.limit)
    }

    @Test
    fun `call log detail lines mask numbers and expose call metadata`() {
        val nowMs = 1_200_000L
        val lines =
            PlatformCallLogToolService.formatCallLogDetailLines(
                entries =
                    listOf(
                        PlatformCallLogEntry(
                            displayName = "张三",
                            phoneNumber = "13800138000",
                            callType = "missed",
                            dateMs = nowMs - 600_000L,
                            durationSeconds = 0L,
                        ),
                    ),
                nowMs = nowMs,
            )

        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("张三"))
        assertTrue(lines.first().contains("138****8000"))
        assertTrue(lines.first().contains("type=missed"))
        assertTrue(lines.first().contains("duration_s=0"))
        assertTrue(lines.first().contains("age_min=10"))
        assertEquals("missed", PlatformCallLogToolService.normalizeType("未接来电"))
        assertEquals(PlatformCallLogToolService.DEFAULT_LIMIT, PlatformCallLogToolService.normalizeLimit(0))
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
