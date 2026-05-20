package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionVerifierTest {
    @Test
    fun `messaging finish is allowed when recipient and compose cues are present`() {
        val result =
            TaskCompletionVerifier.verifyFinish(
                task = "去微信给韩威发一条晚安的消息",
                taskPlanState = TaskPlanState(planType = "messaging", currentStage = "confirm_send"),
                observation =
                    observation(
                        pageState = "UNKNOWN",
                        topTexts = listOf("韩威", "发送", "输入消息"),
                    ),
            )

        assertTrue(result.verified)
    }

    @Test
    fun `navigation finish is allowed when route cues are present`() {
        val result =
            TaskCompletionVerifier.verifyFinish(
                task = "去浦东机场怎么走",
                taskPlanState = TaskPlanState(planType = "navigation", currentStage = "confirm_route"),
                observation =
                    observation(
                        packageName = "com.autonavi.minimap",
                        pageState = "UNKNOWN",
                        topTexts = listOf("路线", "预计 42 分钟", "开始导航"),
                    ),
            )

        assertTrue(result.verified)
    }

    @Test
    fun `shopping finish is blocked on result list without evidence page`() {
        val result =
            TaskCompletionVerifier.verifyFinish(
                task = "打开购物 App 搜零食，看评价和参数后给我推荐",
                taskPlanState = TaskPlanState(planType = "shopping_research", currentStage = "browse_candidates"),
                observation =
                    observation(
                        packageName = "com.taobao.taobao",
                        pageState = "SHOPPING_SEARCH_RESULT_WEAK",
                        topTexts = listOf("零食", "综合", "销量"),
                    ),
            )

        assertFalse(result.verified)
        assertTrue(result.shouldImmediateReplan)
    }

    @Test
    fun `generic progress recognizes changed execution context`() {
        val result =
            TaskCompletionVerifier.verifyProgress(
                task = "打开应用看一下最新内容",
                taskPlanState = TaskPlanState(planType = "generic", currentStage = "continue_execution"),
                action = AgentAction.Click("e1"),
                before =
                    observation(
                        packageName = "com.example.app",
                        signature = "sig_before",
                        topTexts = listOf("首页"),
                    ),
                after =
                    observation(
                        packageName = "com.example.app",
                        signature = "sig_after",
                        topTexts = listOf("详情页"),
                    ),
            )

        assertTrue(result.verified)
    }

    private fun observation(
        packageName: String = "com.tencent.mm",
        pageState: String = "UNKNOWN",
        signature: String = "sig",
        topTexts: List<String>,
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = pageState,
            signature = signature,
            screenSummary = topTexts.joinToString(" "),
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )
}
