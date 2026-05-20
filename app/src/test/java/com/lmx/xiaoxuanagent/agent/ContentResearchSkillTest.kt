package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentResearchSkillTest {
    @Test
    fun `content skill rewrites browse click from meta tab to actual content candidate`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                profileId = "dynamic_app_tv.danmaku.bili",
                observation =
                    observation(
                        topTexts = listOf("勇哥说餐饮", "综合", "视频"),
                        elements =
                            listOf(
                                element(id = "tab_1", text = "综合"),
                                element(id = "video_1", text = "勇哥说餐饮 最新一期 餐饮观察"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "content_research", currentStage = "browse_candidates"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("tab_1"),
                        reason = "点击综合页签",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("content_research"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("勇哥说餐饮 最新一期 餐饮观察", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `content skill pre plan opens comments when inspection stage is active`() {
        val decision =
            SkillExecutionEngine.prePlanDecision(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                profileId = "dynamic_app_tv.danmaku.bili",
                observation =
                    observation(
                        pageState = "VIDEO_DETAIL",
                        topTexts = listOf("勇哥说餐饮", "评论区", "高赞"),
                        elements =
                            listOf(
                                element(id = "comment_1", text = "评论区"),
                                element(id = "more_1", text = "更多"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "content_research", currentStage = "inspect_information"),
                activeSkillIds = listOf("content_research"),
            )

        assertTrue(decision?.action is AgentAction.OpenBestCandidate)
        assertEquals("评论区", (decision?.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `content skill prefers candidate aligned with remembered result artifact`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                profileId = "dynamic_app_tv.danmaku.bili",
                observation =
                    observation(
                        topTexts = listOf("勇哥说餐饮", "综合", "餐饮观察"),
                        elements =
                            listOf(
                                element(id = "video_old", text = "勇哥说餐饮 往期回顾"),
                                element(id = "video_new", text = "勇哥说餐饮 最新一期 餐饮观察"),
                            ),
                    ),
                memoryContext =
                    PlanningMemoryContext(
                        resultArtifacts = listOf("content | 勇哥说餐饮 最新一期 餐饮观察 | 高赞评论提到外卖利润"),
                    ),
                taskPlanState = TaskPlanState(planType = "content_research", currentStage = "browse_candidates"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("video_old"),
                        reason = "点击第一个候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("content_research"),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("勇哥说餐饮 最新一期 餐饮观察", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    private fun observation(
        pageState: String = "RESULT",
        topTexts: List<String>,
        elements: List<UiElementObservation>,
    ): ScreenObservation =
        ScreenObservation(
            packageName = "tv.danmaku.bili",
            pageState = pageState,
            signature = "sig",
            screenSummary = topTexts.joinToString(" "),
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = "list_1",
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
