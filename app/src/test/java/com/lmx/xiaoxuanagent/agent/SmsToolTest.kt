package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsToolTest {
    @Test
    fun `sms reader is a read-only system utility`() {
        val descriptor = AgentToolCatalog.find("system.read_sms")

        assertEquals("sms_read", descriptor?.permissionFamily)
        assertTrue(descriptor?.concurrencySafe == true)
        assertTrue(ActionExecutor.registeredTools().contains("system.read_sms"))

        val review =
            AgentToolRuntimeProtocol.evaluate(
                action = AgentAction.ReadSms(query = "验证码", box = "inbox", limit = 3),
                observation = emptyObservation(),
            )

        assertTrue(review.valid)
        assertTrue(review.readOnly)
        assertTrue(review.detailLines.any { it.contains("permission_family=sms_read") })
    }

    @Test
    fun `sms inbox tasks prefer sms reader before gui exploration`() {
        val policy =
            AgentToolSelectionPolicy.analyze(
                task = "查看最近短信验证码",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.android.mms",
            )

        assertTrue(policy.preferredTools.contains("system.read_sms"))
        assertTrue(policy.avoidTools.contains("gui.tap_point"))
        assertTrue(policy.avoidTools.contains("gui.swipe"))
    }

    @Test
    fun `planner parses read sms action`() {
        val decision =
            OpenAiPlanner().debugParseDecision(
                rawContent = """{"action":"read_sms","query":"验证码","box":"inbox","limit":4,"reason":"先看最近短信"}""",
                observation = emptyObservation(),
                targetPackageName = "com.android.mms",
            )

        assertTrue(decision.action is AgentAction.ReadSms)
        val action = decision.action as AgentAction.ReadSms
        assertEquals("验证码", action.query)
        assertEquals("inbox", action.box)
        assertEquals(4, action.limit)
    }

    @Test
    fun `sms detail lines mask sender and verification codes`() {
        val nowMs = 1_200_000L
        val lines =
            PlatformSmsToolService.formatSmsDetailLines(
                entries =
                    listOf(
                        PlatformSmsEntry(
                            address = "13800138000",
                            body = "验证码 123456，请勿泄露。",
                            box = "inbox",
                            dateMs = nowMs - 600_000L,
                            read = false,
                        ),
                    ),
                nowMs = nowMs,
            )

        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("138****8000"))
        assertTrue(lines.first().contains("box=inbox"))
        assertTrue(lines.first().contains("read=false"))
        assertTrue(lines.first().contains("age_min=10"))
        assertTrue(lines.first().contains("[code]"))
        assertFalse(lines.first().contains("123456"))
        assertEquals("inbox", PlatformSmsToolService.normalizeBox("收件箱"))
        assertEquals(PlatformSmsToolService.DEFAULT_LIMIT, PlatformSmsToolService.normalizeLimit(0))
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
