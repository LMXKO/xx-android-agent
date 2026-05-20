package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubgoalAlignmentSkillTest {
    @Test
    fun `subgoal alignment rewrites control click to better result candidate`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开闲鱼看二手内存条价格",
                profileId = "xianyu_assistant",
                observation =
                    observation(
                        topTexts = listOf("二手内存条", "综合", "筛选"),
                        defaultScrollableId = "list_1",
                        elements =
                            listOf(
                                element(id = "ctrl_1", text = "综合"),
                                element(id = "item_1", text = "二手内存条 DDR5 16G 价格实拍"),
                                element(id = "item_2", text = "猜你喜欢"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "shopping_research", currentStage = "browse_candidates"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("ctrl_1"),
                        reason = "点击顶部入口",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("subgoal_alignment"),
                history = emptyList(),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("二手内存条 DDR5 16G 价格实拍", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `subgoal alignment avoids recently rejected target label`() {
        val refined =
            SkillExecutionEngine.refineDecision(
                task = "打开 b 站看勇哥说餐饮最新一期点赞最高的评论",
                profileId = "bilibili_assistant",
                observation =
                    observation(
                        packageName = "tv.danmaku.bili",
                        topTexts = listOf("勇哥说餐饮", "视频"),
                        defaultScrollableId = "list_1",
                        elements =
                            listOf(
                                element(id = "item_bad", text = "勇哥说餐饮 旧视频"),
                                element(id = "item_good", text = "勇哥说餐饮 最新一期 高赞评论"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "browse_candidates"),
                decision =
                    AgentDecision(
                        action = AgentAction.Click("item_bad"),
                        reason = "点击第一个候选",
                        rawResponse = "{}",
                    ),
                activeSkillIds = listOf("subgoal_alignment"),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "DETAIL",
                            action = "click(item_bad)",
                            result = "已点击 item_bad(勇哥说餐饮 旧视频)。",
                            recoveryCategory = RecoveryCategory.NO_PROGRESS.name,
                            recoverySummary = "进入了不相关内容页",
                        ),
                    ),
            )

        assertTrue(refined.action is AgentAction.OpenBestCandidate)
        assertEquals("勇哥说餐饮 最新一期 高赞评论", (refined.action as AgentAction.OpenBestCandidate).targetText)
    }

    private fun observation(
        packageName: String = "com.example.app",
        topTexts: List<String>,
        defaultScrollableId: String? = null,
        elements: List<UiElementObservation>,
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
