package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.taskprofile.GenericInstalledAppProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEngineTest {
    private val genericProfile =
        GenericInstalledAppProfile(
            id = "generic_app",
            displayName = "通用应用",
            packageName = "com.example.app",
            capabilitySummary = "测试用通用应用",
        )

    @Test
    fun `diagnose empty observation returns empty tree recovery`() {
        val diagnosis =
            RecoveryEngine.diagnoseExecutionFailure(
                task = "打开应用看一下最新内容",
                targetPackageName = "com.example.app",
                action = AgentAction.Click("e1"),
                beforeObservation = observation(signature = "before", topTexts = listOf("首页")),
                afterObservation = null,
                history = emptyList(),
            )

        assertEquals(RecoveryCategory.EMPTY_OBSERVATION, diagnosis?.category)
        assertTrue(diagnosis?.suggestedAction is AgentAction.Wait)
    }

    @Test
    fun `diagnose wrong context suggests relaunching target app`() {
        val diagnosis =
            RecoveryEngine.diagnoseExecutionFailure(
                task = "打开 b 站看最新视频",
                targetPackageName = "tv.danmaku.bili",
                action = AgentAction.Click("e2"),
                beforeObservation =
                    observation(
                        packageName = "tv.danmaku.bili",
                        signature = "before",
                        topTexts = listOf("搜索", "推荐"),
                    ),
                afterObservation =
                    observation(
                        packageName = "com.tencent.mm",
                        signature = "after",
                        topTexts = listOf("微信"),
                        elements = listOf(element(id = "wx_1", text = "聊天")),
                    ),
                history = emptyList(),
            )

        assertEquals(RecoveryCategory.WRONG_CONTEXT, diagnosis?.category)
        assertEquals(
            "tv.danmaku.bili",
            (diagnosis?.suggestedAction as? AgentAction.LaunchApp)?.packageName,
        )
    }

    @Test
    fun `diagnose overscroll when task cues disappear after scroll`() {
        val diagnosis =
            RecoveryEngine.diagnoseExecutionFailure(
                task = "看二手内存条",
                targetPackageName = "com.taobao.idlefish",
                action = AgentAction.Scroll("list_1", "down"),
                beforeObservation =
                    observation(
                        packageName = "com.taobao.idlefish",
                        signature = "before",
                        defaultScrollableId = "list_1",
                        topTexts = listOf("二手内存条", "内存条", "排序"),
                        elements = listOf(element(id = "item_1", text = "二手内存条 128G 内存条")),
                    ),
                afterObservation =
                    observation(
                        packageName = "com.taobao.idlefish",
                        signature = "after",
                        defaultScrollableId = "list_1",
                        topTexts = listOf("猜你喜欢", "广告", "推荐"),
                        elements = listOf(element(id = "item_2", text = "猜你喜欢 商品推荐")),
                    ),
                history = emptyList(),
            )

        assertEquals(RecoveryCategory.OVERSCROLLED, diagnosis?.category)
        assertEquals("up", (diagnosis?.suggestedAction as? AgentAction.ScrollForMore)?.direction)
    }

    @Test
    fun `plan recovery uses context recovery skill for no progress`() {
        val diagnosis =
            RecoveryDiagnosis(
                category = RecoveryCategory.NO_PROGRESS,
                summary = "页面无明显变化",
            )

        val hint =
            RecoveryEngine.planRecovery(
                task = "打开应用找目标内容",
                profile = genericProfile,
                profileId = genericProfile.id,
                observation =
                    observation(
                        packageName = "com.example.app",
                        defaultScrollableId = "list_1",
                        topTexts = listOf("列表"),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                activeSkillIds = listOf("context_recovery"),
                history = emptyList(),
                diagnosis = diagnosis,
            )

        assertNotNull(hint.recoveryDecision)
        assertTrue(hint.recoveryDecision?.action is AgentAction.ScrollForMore)
    }

    @Test
    fun `diagnose semantic detour suggests backing out from wrong deep page`() {
        val diagnosis =
            RecoveryEngine.diagnoseSemanticDetour(
                task = "打开微信给韩威发一条晚安的消息",
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                action = AgentAction.Click("e1"),
                beforeObservation =
                    observation(
                        packageName = "com.tencent.mm",
                        signature = "before",
                        topTexts = listOf("搜索", "韩威", "聊天"),
                    ),
                afterObservation =
                    observation(
                        packageName = "com.tencent.mm",
                        signature = "after",
                        topTexts = listOf("公众号", "输入消息", "发送"),
                    ),
                history = emptyList(),
            )

        assertEquals(RecoveryCategory.TARGET_MISMATCH, diagnosis?.category)
        assertTrue(diagnosis?.suggestedAction is AgentAction.NavigateBack)
    }

    @Test
    fun `plan recovery uses exit recovery skill for semantic detour`() {
        val diagnosis =
            RecoveryDiagnosis(
                category = RecoveryCategory.SUBGOAL_MISMATCH,
                summary = "进入了错误页面",
            )

        val hint =
            RecoveryEngine.planRecovery(
                task = "打开微信给韩威发一条晚安的消息",
                profile = genericProfile,
                profileId = genericProfile.id,
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("公众号", "输入消息", "发送"),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "find_conversation"),
                activeSkillIds = listOf("exit_recovery"),
                history = emptyList(),
                diagnosis = diagnosis,
            )

        assertTrue(hint.recoveryDecision?.action is AgentAction.NavigateBack)
    }

    @Test
    fun `plan recovery uses stage aware inspect action for content research`() {
        val diagnosis =
            RecoveryDiagnosis(
                category = RecoveryCategory.NO_PROGRESS,
                summary = "还没有进入评论证据页",
            )

        val hint =
            RecoveryEngine.planRecovery(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                profile = genericProfile,
                profileId = genericProfile.id,
                observation =
                    observation(
                        packageName = "tv.danmaku.bili",
                        topTexts = listOf("勇哥说餐饮", "评论区", "高赞"),
                        elements =
                            listOf(
                                element(id = "comment_1", text = "评论区"),
                                element(id = "video_1", text = "勇哥说餐饮 最新一期"),
                            ),
                    ),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "content_research", currentStage = "inspect_information"),
                activeSkillIds = emptyList(),
                history = emptyList(),
                diagnosis = diagnosis,
            )

        assertTrue(hint.recoveryDecision?.action is AgentAction.OpenBestCandidate)
        assertEquals("评论区", (hint.recoveryDecision?.action as AgentAction.OpenBestCandidate).targetText)
    }

    @Test
    fun `plan recovery uses stage aware entry action for navigation`() {
        val diagnosis =
            RecoveryDiagnosis(
                category = RecoveryCategory.NO_PROGRESS,
                summary = "还没有输入目的地",
            )

        val hint =
            RecoveryEngine.planRecovery(
                task = "去浦东机场怎么走",
                profile = genericProfile,
                profileId = genericProfile.id,
                observation =
                    observation(
                        packageName = "com.autonavi.minimap",
                        topTexts = listOf("搜索地点", "路线"),
                        elements = listOf(element(id = "search_1", text = "搜索地点、公交站、地铁站")),
                    ).copy(primaryEditableId = "input_1"),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "navigation", currentStage = "enter_destination"),
                activeSkillIds = emptyList(),
                history = emptyList(),
                diagnosis = diagnosis,
            )

        assertTrue(hint.recoveryDecision?.action is AgentAction.PopulatePrimaryInput)
        assertEquals("浦东机场", (hint.recoveryDecision?.action as AgentAction.PopulatePrimaryInput).text)
    }

    private fun observation(
        packageName: String = "com.example.app",
        signature: String = "sig",
        defaultScrollableId: String? = null,
        topTexts: List<String>,
        elements: List<UiElementObservation> = listOf(element(id = "e1", text = "默认节点")),
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = "UNKNOWN",
            signature = signature,
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
