package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedAppExecutionReceiptStoreTest {
    @Test
    fun `receipt lines include latest connected state`() {
        ConnectedAppExecutionReceiptStore.record(
            ConnectedAppExecutionReceipt(
                appId = "wechat_assistant",
                operation = "prepare_message_handoff",
                state = "message_draft_ready",
                summary = "已打开微信，并准备把消息推进到发送前最后一步。",
                handoffRequired = true,
            ),
        )

        val lines = ConnectedAppExecutionReceiptStore.lines(limit = 2)

        assertTrue(lines.any { it.contains("wechat_assistant") && it.contains("message_draft_ready") })
    }
}
