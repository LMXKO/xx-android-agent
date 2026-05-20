package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemorySkillTest {
    @Test
    fun `personal memory rewrites alias input to remembered canonical contact`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给小韩发一条晚安的消息",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        topTexts = listOf("搜索", "联系人"),
                        primaryEditableId = "e1",
                        elements = listOf(element(id = "e1", text = "", editable = true)),
                    ),
                memoryContext =
                    PlanningMemoryContext(
                        contacts = listOf("韩威 (别名=小韩/韩老师) | 常联系"),
                    ),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision =
                    AgentDecision(
                        action = AgentAction.SetText("e1", "小韩"),
                        reason = "输入联系人",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("personal_memory"),
            )

        assertTrue(refined.action is AgentAction.PopulatePrimaryInput)
        assertEquals("韩威", (refined.action as AgentAction.PopulatePrimaryInput).text)
    }

    @Test
    fun `personal memory rewrites click to remembered canonical candidate`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给小韩发一条晚安的消息",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("聊天", "搜索"),
                        elements =
                            listOf(
                                element(id = "e1", text = "公众号"),
                                element(id = "e2", text = "韩威"),
                            ),
                    ),
                memoryContext =
                    PlanningMemoryContext(
                        contacts = listOf("韩威 (别名=小韩) | 常联系"),
                    ),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("e1"),
                        reason = "点击候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("personal_memory"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    private fun observation(
        packageName: String = "com.example.app",
        topTexts: List<String>,
        primaryEditableId: String? = null,
        elements: List<UiElementObservation>,
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = "UNKNOWN",
            signature = "sig",
            screenSummary = topTexts.joinToString(" "),
            topTexts = topTexts,
            primaryEditableId = primaryEditableId,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = elements,
        )

    private fun element(
        id: String,
        text: String,
        editable: Boolean = false,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "",
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            bounds = "[0,0][100,100]",
            clickable = true,
            editable = editable,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )
}
