package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingGuardrailTest {
    @Test
    fun `message body is blocked until recipient is verified`() {
        val observation =
            observation(
                topTexts = listOf("发送", "输入消息"),
                primaryEditableId = "e1",
                elements =
                    listOf(
                        element(id = "e1", text = "", editable = true),
                    ),
            )
        val decision =
            AgentDecision(
                action = AgentAction.SetText("e1", "晚安"),
                reason = "输入消息",
                rawResponse = "{}",
            )

        val refined =
            GenericTaskDecisionPolicy.refineDecision(
                task = "去微信给韩威发一条晚安的消息",
                observation = observation,
                decision = decision,
            )

        assertEquals("韩威", (refined.action as AgentAction.PopulatePrimaryInput).text)
    }

    @Test
    fun `messaging skill rewrites click to the best recipient candidate`() {
        val observation =
            observation(
                topTexts = listOf("搜索", "聊天"),
                elements =
                    listOf(
                        element(id = "e1", text = "公众号"),
                        element(id = "e2", text = "韩威"),
                        element(id = "e3", text = "【京东外卖】官方群聊页面"),
                    ),
            )
        val decision =
            AgentDecision(
                action = AgentAction.Click("e1"),
                reason = "点击候选",
                rawResponse = "{}",
            )

        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation = observation,
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision = decision,
                activeSkillIds = listOf("messaging"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `messaging skill backs out from mismatched conversation context`() {
        val observation =
            observation(
                topTexts = listOf("公众号", "输入消息", "发送"),
                primaryEditableId = "e9",
                elements =
                    listOf(
                        element(id = "e9", text = "", editable = true),
                        element(id = "e10", text = "发送"),
                    ),
            )

        val decision =
            SkillExecutionEngine.prePlanDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation = observation,
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "confirm_send"),
                activeSkillIds = listOf("messaging"),
            )

        assertTrue(decision?.action is AgentAction.NavigateBack)
    }

    @Test
    fun `messaging skill does not click recently rejected candidate again`() {
        val observation =
            observation(
                topTexts = listOf("韩威", "聊天"),
                elements = listOf(element(id = "e2", text = "韩威")),
            )
        val history =
            listOf(
                AgentTurnRecord(
                    observationSignature = "sig1",
                    pageState = "UNKNOWN",
                    action = "click(e2)",
                    result = "已通过视觉候选点击 e02(韩威)。 点击后页面已变化。",
                    decisionReason = "进入聊天窗口",
                ),
                AgentTurnRecord(
                    observationSignature = "sig2",
                    pageState = "UNKNOWN",
                    action = "back()",
                    result = "已执行返回。 返回后页面已变化。",
                    decisionReason = "当前处于【京东外卖】官方群聊页面，并非目标联系人“韩威”的会话，需返回上一层查找目标联系人。",
                ),
            )
        val decision =
            AgentDecision(
                action = AgentAction.Click("e2"),
                reason = "再次点击韩威",
                rawResponse = "{}",
            )

        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation = observation,
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision = decision,
                activeSkillIds = listOf("messaging"),
                history = history,
            )

        assertTrue(refined.action !is AgentAction.Click)
    }

    @Test
    fun `messaging skill avoids group like candidate even if it shares recipient characters`() {
        val observation =
            observation(
                topTexts = listOf("聊天"),
                elements =
                    listOf(
                        element(id = "e1", text = "grmn售后群2"),
                        element(id = "e2", text = "韩威"),
                    ),
            )
        val decision =
            AgentDecision(
                action = AgentAction.Click("e1"),
                reason = "点击候选",
                rawResponse = "{}",
            )

        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发送晚安",
                profileId = "wechat_assistant",
                observation = observation,
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision = decision,
                activeSkillIds = listOf("messaging"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `messaging skill prefers search entry when no confident recipient candidate exists`() {
        val observation =
            observation(
                topTexts = listOf("微信"),
                elements =
                    listOf(
                        element(id = "e1", text = "腾讯新闻"),
                        element(id = "e2", text = "搜索"),
                    ),
            )
        val decision =
            AgentDecision(
                action = AgentAction.Click("e1"),
                reason = "点击会话",
                rawResponse = "{}",
            )

        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发晚安",
                profileId = "wechat_assistant",
                observation = observation,
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision = decision,
                activeSkillIds = listOf("messaging"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("搜索", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `app state guard rewrites duplicate launch to wait`() {
        val decision =
            SkillExecutionEngine.refineDecision(
                task = "打开微信给韩威发晚安",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("微信"),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                decision =
                    AgentDecision(
                        action = AgentAction.LaunchApp("com.tencent.mm"),
                        reason = "启动目标应用",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("app_state_guard"),
            )

        assertTrue(decision.action is AgentAction.Wait)
    }

    @Test
    fun `context recovery skill avoids clicking the same reverted target again`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开应用找目标内容",
                profileId = "generic_app",
                observation =
                    observation(
                        packageName = "com.example.app",
                        topTexts = listOf("列表"),
                        defaultScrollableId = "list_1",
                        elements = listOf(element(id = "e1", text = "无关项")),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("e1"),
                        reason = "再次点击候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("context_recovery"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "LIST",
                            action = "click(e1)",
                            result = "点击后进入了错误上下文。",
                        ),
                        AgentTurnRecord(
                            observationSignature = "sig2",
                            pageState = "DETAIL",
                            action = "back()",
                            result = "已返回上一层。",
                        ),
                    ),
            )

        assertTrue(refined.action !is AgentAction.Click)
    }

    private fun observation(
        packageName: String = "com.tencent.mm",
        topTexts: List<String>,
        primaryEditableId: String? = null,
        defaultScrollableId: String? = null,
        elements: List<UiElementObservation> = emptyList(),
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = "UNKNOWN",
            signature = "sig",
            screenSummary = "",
            topTexts = topTexts,
            primaryEditableId = primaryEditableId,
            focusedElementId = null,
            defaultScrollableId = defaultScrollableId,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = elements,
        )

    private fun element(
        id: String,
        text: String,
        clickable: Boolean = true,
        editable: Boolean = false,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "",
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            bounds = "[0,0][100,100]",
            clickable = clickable,
            editable = editable,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )
}
