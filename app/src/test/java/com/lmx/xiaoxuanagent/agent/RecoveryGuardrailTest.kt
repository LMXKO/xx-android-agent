package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryGuardrailTest {
    @Test
    fun `context recovery rewrites repeated stalled click to scroll exploration`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开应用找目标内容",
                profileId = "generic_app",
                observation =
                    observation(
                        topTexts = listOf("列表页"),
                        defaultScrollableId = "list_1",
                        elements = listOf(element(id = "e1", text = "无关项")),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("e1"),
                        reason = "继续点击原候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("context_recovery"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "LIST",
                            action = "click(e1)",
                            result = "点击后无变化。",
                            recoveryCategory = RecoveryCategory.NO_PROGRESS.name,
                        ),
                    ),
            )

        assertTrue(refined.action is AgentAction.ScrollForMore)
        assertEquals("down", (refined.action as AgentAction.ScrollForMore).direction)
    }

    @Test
    fun `context recovery rewrites repeated downward scroll after overscroll to upward scroll`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开闲鱼看二手内存条价格",
                profileId = "xianyu_assistant",
                observation =
                    observation(
                        packageName = "com.taobao.idlefish",
                        topTexts = listOf("猜你喜欢"),
                        defaultScrollableId = "list_1",
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "shopping_research", currentStage = "browse_candidates"),
                decision =
                    AgentDecision(
                        action = AgentAction.Scroll("list_1", "down"),
                        reason = "继续下滑",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("context_recovery"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig2",
                            pageState = "RESULT",
                            action = "scroll(list_1,down)",
                            result = "滚动过头。",
                            recoveryCategory = RecoveryCategory.OVERSCROLLED.name,
                        ),
                    ),
            )

        assertTrue(refined.action is AgentAction.ScrollForMore)
        assertEquals("up", (refined.action as AgentAction.ScrollForMore).direction)
    }

    @Test
    fun `recovery follow through pre plan uses latest suggested recovery action`() {
        val decision =
            SkillExecutionEngine.prePlanDecision(
                task = "打开应用找目标内容",
                profileId = "generic_app",
                observation =
                    observation(
                        topTexts = listOf("列表页"),
                        defaultScrollableId = "list_1",
                        elements = listOf(element(id = "e1", text = "无关项")),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                activeSkillIds = listOf("recovery_follow_through"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "LIST",
                            action = "click(e1)",
                            result = "点击后无变化。",
                            recoveryCategory = RecoveryCategory.NO_PROGRESS.name,
                            recoverySummary = "页面无明显变化",
                            suggestedRecoveryAction = "scroll(list_1,down)",
                        ),
                    ),
            )

        assertTrue(decision?.action is AgentAction.Scroll)
        assertEquals("down", (decision?.action as AgentAction.Scroll).direction)
    }

    @Test
    fun `recovery follow through rewrites repeated failed action to suggested recovery action`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开应用找目标内容",
                profileId = "generic_app",
                observation =
                    observation(
                        packageName = "com.example.app",
                        topTexts = listOf("列表页"),
                        defaultScrollableId = "list_1",
                        elements = listOf(element(id = "e1", text = "无关项")),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("e1"),
                        reason = "继续点击原候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("recovery_follow_through"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "LIST",
                            action = "click(e1)",
                            result = "点击后无变化。",
                            recoveryCategory = RecoveryCategory.NO_PROGRESS.name,
                            recoverySummary = "页面无明显变化",
                            suggestedRecoveryAction = "scroll(list_1,down)",
                        ),
                    ),
            )

        assertTrue(refined.action is AgentAction.Scroll)
        assertEquals("down", (refined.action as AgentAction.Scroll).direction)
    }

    private fun observation(
        packageName: String = "com.example.app",
        topTexts: List<String>,
        defaultScrollableId: String? = null,
        elements: List<UiElementObservation> = emptyList(),
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = "UNKNOWN",
            signature = "sig",
            screenSummary = topTexts.joinToString(" "),
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = defaultScrollableId,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = elements,
        )

    private fun element(
        id: String,
        text: String,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "",
            className = "android.widget.TextView",
            bounds = "[0,0][100,100]",
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )
}
