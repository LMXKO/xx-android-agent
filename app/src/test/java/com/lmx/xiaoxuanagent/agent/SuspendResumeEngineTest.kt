package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendResumeEngineTest {
    private fun obs(topTexts: List<String>): ScreenObservation =
        ScreenObservation(
            packageName = "com.x",
            pageState = "UNKNOWN",
            signature = "sig",
            screenSummary = "",
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )

    @Test
    fun `detects a login wall as a blocking external wait`() {
        val plan =
            SuspendResumeEngine.enrich(
                task = "在京东搜耳机",
                observation = obs(listOf("请先登录", "请输入密码")),
                history = emptyList(),
                base = TaskPlanState(),
            )
        assertTrue(plan.waitConditions.any { it.type == "external_wait" && it.status == "blocking" })
        assertTrue(plan.waitingForExternal)
    }

    @Test
    fun `does not flag an ordinary page as waiting`() {
        val plan =
            SuspendResumeEngine.enrich(
                task = "搜耳机",
                observation = obs(listOf("搜索结果", "蓝牙耳机")),
                history = emptyList(),
                base = TaskPlanState(),
            )
        assertFalse(plan.waitingForExternal)
    }
}
