package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanCorrectionSkillTest {
    @Test
    fun `human correction pre plan backs out from wrong page`() {
        val decision =
            SkillExecutionEngine.prePlanDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "dynamic_app_com.tencent.mm",
                observation =
                    observation(
                        topTexts = listOf("公众号", "服务通知", "聊天信息"),
                        elements = listOf(element(id = "pub_1", text = "某公众号")),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState =
                    TaskPlanState(
                        planType = "messaging",
                        currentStage = "find_conversation",
                        currentSubgoalId = "find",
                        nextObjective = "重新找正确联系人",
                        resumeContext =
                            ResumeContext(
                                active = true,
                                source = "manual_correction_resume",
                                resumeEvent = "manual_correction",
                                resumeHint = "回到上一页重新找联系人。",
                                resumedSubgoalId = "find",
                                userCorrection = "刚才点错会话了，回上一页重新找韩威本人。",
                            ),
                    ),
                activeSkillIds = listOf("human_correction"),
            )

        assertTrue(decision?.action is AgentAction.NavigateBack)
    }

    @Test
    fun `human correction rewrites click away from blocked target`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "dynamic_app_com.tencent.mm",
                observation =
                    observation(
                        topTexts = listOf("韩威", "公众号", "聊天"),
                        elements =
                            listOf(
                                element(id = "public_account", text = "韩威公众号"),
                                element(id = "real_contact", text = "韩威"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState =
                    TaskPlanState(
                        planType = "messaging",
                        currentStage = "find_conversation",
                        currentSubgoalId = "find",
                        nextObjective = "重新找正确联系人",
                        resumeContext =
                            ResumeContext(
                                active = true,
                                source = "manual_correction_resume",
                                resumeEvent = "manual_correction",
                                resumeHint = "不要点公众号，重新找联系人。",
                                resumedSubgoalId = "find",
                                userCorrection = "不要点公众号，优先找韩威本人。",
                            ),
                    ),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("public_account"),
                        reason = "点击第一个韩威相关项",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("human_correction"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `human correction pre plan pulls up after overscroll feedback`() {
        val decision =
            SkillExecutionEngine.prePlanDecision(
                task = "打开购物 App看零食评价",
                profileId = "shopping_assistant",
                observation =
                    observation(
                        topTexts = listOf("推荐", "更多商品"),
                        elements = emptyList(),
                        defaultScrollableId = "list_1",
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState =
                    TaskPlanState(
                        planType = "shopping_research",
                        currentStage = "browse_candidates",
                        currentSubgoalId = "browse",
                        nextObjective = "回到更相关的候选位置",
                        resumeContext =
                            ResumeContext(
                                active = true,
                                source = "manual_correction_resume",
                                resumeEvent = "manual_correction",
                                resumeHint = "往上回拉一点。",
                                resumedSubgoalId = "browse",
                                userCorrection = "刚才滚过头了，往上滑一点。",
                            ),
                    ),
                activeSkillIds = listOf("human_correction"),
            )

        assertTrue(decision?.action is AgentAction.ScrollForMore)
        assertEquals("up", (decision?.action as AgentAction.ScrollForMore).direction)
    }

    @Test
    fun `human correction rewrites click from remembered template memory`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "dynamic_app_com.tencent.mm",
                observation =
                    observation(
                        topTexts = listOf("韩威", "公众号", "聊天"),
                        elements =
                            listOf(
                                element(id = "public_account", text = "韩威公众号"),
                                element(id = "real_contact", text = "韩威"),
                            ),
                    ),
                memoryContext =
                    PlanningMemoryContext(
                        correctionTemplates =
                            listOf(
                                "avoid_target:公众号 | 遇到相似任务时避开公众号，优先重新定位更贴近目标的候选。",
                            ),
                    ),
                taskPlanState =
                    TaskPlanState(
                        planType = "messaging",
                        currentStage = "find_conversation",
                        currentSubgoalId = "find",
                        nextObjective = "定位正确联系人",
                    ),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("public_account"),
                        reason = "点击第一个韩威相关项",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("human_correction"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    private fun observation(
        topTexts: List<String>,
        elements: List<UiElementObservation>,
        defaultScrollableId: String? = "list_1",
    ): ScreenObservation =
        ScreenObservation(
            packageName = "com.tencent.mm",
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
