package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.InterruptiveHint
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyEngineTest {
    @Test
    fun `click send requires confirmation`() {
        val review =
            SafetyPolicyEngine.review(
                task = "去微信给韩威发一条晚安的消息",
                indexedObservation =
                    observation(
                        topTexts = listOf("聊天", "发送"),
                        primaryEditableId = "e1",
                        elements =
                            listOf(
                                element(id = "e1", text = "", editable = true),
                                element(id = "e2", text = "发送"),
                            ),
                    ),
                action = AgentAction.Click("e2"),
            )

        assertEquals(RiskLevel.CONFIRM, review.level)
        assertTrue(review.summary.contains("发送"))
    }

    @Test
    fun `click pay is blocked`() {
        val review =
            SafetyPolicyEngine.review(
                task = "打开购物应用完成支付",
                indexedObservation =
                    observation(
                        topTexts = listOf("订单确认"),
                        elements = listOf(element(id = "e9", text = "立即支付")),
                    ),
                action = AgentAction.Click("e9"),
            )

        assertEquals(RiskLevel.BLOCK, review.level)
    }

    @Test
    fun `normal search click stays low risk`() {
        val review =
            SafetyPolicyEngine.review(
                task = "打开搜索应用看内容",
                indexedObservation =
                    observation(
                        topTexts = listOf("搜索", "结果"),
                        elements = listOf(element(id = "e2", text = "勇哥说餐饮")),
                    ),
                action = AgentAction.Click("e2"),
            )

        assertEquals(RiskLevel.LOW, review.level)
    }

    @Test
    fun `press enter in message context requires confirmation`() {
        val review =
            SafetyPolicyEngine.review(
                task = "去微信给韩威发一条晚安的消息",
                indexedObservation =
                    observation(
                        topTexts = listOf("聊天", "消息", "发送"),
                        primaryEditableId = "e1",
                        elements =
                            listOf(
                                element(id = "e1", text = "", editable = true),
                                element(id = "e2", text = "发送"),
                            ),
                    ),
                action = AgentAction.PressEnter,
            )

        assertEquals(RiskLevel.CONFIRM, review.level)
        assertTrue(review.approvalKey.isNotBlank())
    }

    @Test
    fun `reply notification treats system ui as neutral surface`() {
        val resolved =
            SafetyPolicyEngine.resolveCrossAppPackages(
                observationPackageName = "com.android.systemui",
                action = AgentAction.ReplyNotification(notificationKey = "key_1", replyText = "稍后回你"),
                fallbackTargetPackageName = "com.tencent.mm",
                notificationPackageLookup = { "com.tencent.mm" },
            )

        assertEquals("com.tencent.mm", resolved.first)
        assertEquals("com.tencent.mm", resolved.second)
    }

    @Test
    fun `reply notification still flags real cross app source`() {
        val resolved =
            SafetyPolicyEngine.resolveCrossAppPackages(
                observationPackageName = "com.eg.android.AlipayGphone",
                action = AgentAction.ReplyNotification(notificationKey = "key_2", replyText = "晚点说"),
                fallbackTargetPackageName = "com.tencent.mm",
                notificationPackageLookup = { "com.tencent.mm" },
            )

        assertEquals("com.eg.android.AlipayGphone", resolved.first)
        assertEquals("com.tencent.mm", resolved.second)
    }

    private fun observation(
        packageName: String = "com.example.app",
        topTexts: List<String>,
        primaryEditableId: String? = null,
        elements: List<UiElementObservation> = emptyList(),
    ): IndexedScreenObservation =
        IndexedScreenObservation(
            observation =
                ScreenObservation(
                    packageName = packageName,
                    pageState = "UNKNOWN",
                    signature = "sig",
                    screenSummary = "",
                    topTexts = topTexts,
                    primaryEditableId = primaryEditableId,
                    focusedElementId = null,
                    defaultScrollableId = null,
                    primaryInterruptActionId = null,
                    interruptiveHints = emptyList<InterruptiveHint>(),
                    elements = elements,
                ),
            nodesById = emptyMap(),
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
