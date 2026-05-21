package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactLookupToolTest {
    @Test
    fun `contact lookup is a read-only system utility`() {
        val descriptor = AgentToolCatalog.find("system.lookup_contact")

        assertEquals("contact_read", descriptor?.permissionFamily)
        assertTrue(descriptor?.concurrencySafe == true)
        assertTrue(ActionExecutor.registeredTools().contains("system.lookup_contact"))

        val review =
            AgentToolRuntimeProtocol.evaluate(
                action = AgentAction.LookupContact(contactName = "张三"),
                observation = emptyObservation(),
            )

        assertTrue(review.valid)
        assertTrue(review.readOnly)
        assertTrue(review.detailLines.any { it.contains("permission_family=contact_read") })
    }

    @Test
    fun `phone and sms tasks prefer contact lookup before direct handoff`() {
        val phonePolicy =
            AgentToolSelectionPolicy.analyze(
                task = "给张三打电话",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.android.contacts",
            )
        val smsPolicy =
            AgentToolSelectionPolicy.analyze(
                task = "给李四发短信说我晚点到",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.android.mms",
            )

        assertTrue(phonePolicy.preferredTools.contains("system.lookup_contact"))
        assertTrue(phonePolicy.preferredTools.contains("system.dial_number"))
        assertTrue(smsPolicy.preferredTools.contains("system.lookup_contact"))
        assertTrue(smsPolicy.preferredTools.contains("system.draft_sms"))
    }

    @Test
    fun `planner parses lookup contact action`() {
        val decision =
            OpenAiPlanner().debugParseDecision(
                rawContent = """{"action":"lookup_contact","contact_name":"张三","limit":3,"reason":"先按姓名找号码"}""",
                observation = emptyObservation(),
                targetPackageName = "com.android.contacts",
            )

        assertTrue(decision.action is AgentAction.LookupContact)
        val action = decision.action as AgentAction.LookupContact
        assertEquals("张三", action.contactName)
        assertEquals(3, action.limit)
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
