package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInterventionEngineTest {
    private fun button(
        id: String,
        text: String,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "",
            className = "android.widget.Button",
            bounds = "[0,0][100,50]",
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )

    private fun obs(
        pkg: String,
        topTexts: List<String>,
        elements: List<UiElementObservation>,
        hints: List<InterruptiveHint> = emptyList(),
    ): ScreenObservation =
        ScreenObservation(
            packageName = pkg,
            pageState = "DIALOG",
            signature = "sig",
            screenSummary = "",
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = hints,
            elements = elements,
        )

    @Test
    fun `allows a task-relevant permission dialog`() {
        val decision =
            SystemInterventionEngine.decide(
                task = "帮我导航去公司",
                observation =
                    obs(
                        pkg = "com.android.permissioncontroller",
                        topTexts = listOf("允许访问位置信息？"),
                        elements = listOf(button("b", "允许"), button("d", "拒绝")),
                    ),
                visualContext = VisualPerceptionContext(),
            )
        assertNotNull(decision)
        assertTrue(decision!!.action is AgentAction.OpenBestCandidate)
    }

    @Test
    fun `dismisses a soft popup that blocks the main flow`() {
        val decision =
            SystemInterventionEngine.decide(
                task = "在淘宝搜耳机",
                observation =
                    obs(
                        pkg = "com.taobao.taobao",
                        topTexts = emptyList(),
                        elements = listOf(button("x", "跳过")),
                        hints = listOf(InterruptiveHint("x", "跳过广告", "ad")),
                    ),
                visualContext = VisualPerceptionContext(),
            )
        assertNotNull(decision)
    }

    @Test
    fun `returns null when there is no interruption`() {
        val decision =
            SystemInterventionEngine.decide(
                task = "搜耳机",
                observation =
                    obs(
                        pkg = "com.taobao.taobao",
                        topTexts = listOf("搜索结果"),
                        elements = listOf(button("a", "蓝牙耳机A")),
                    ),
                visualContext = VisualPerceptionContext(),
            )
        assertNull(decision)
    }
}
