package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrant
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionPresentationStoreTest {
    @Test
    fun `build renders consumer-friendly labels and capability summaries`() {
        val snapshot =
            AppPermissionPresentationStore.build(
                rules =
                    listOf(
                        RuntimeSafetyPolicyRule(
                            ruleId = "rule_wechat_ask",
                            behavior = RuntimeSafetyPolicyBehavior.ASK,
                            actionFamily = "message_send",
                            targetPackageName = "com.tencent.mm",
                        ),
                    ),
                grants =
                    listOf(
                        RuntimeSafetyGrant(
                            grantId = "grant_wechat",
                            scope = "session",
                            targetPackageName = "com.tencent.mm",
                            actionFamily = "message_send",
                        ),
                    ),
                sessionId = "session_1",
            )

        assertEquals("微信", snapshot.cards.first().title)
        assertEquals("每次询问", snapshot.cards.first().subtitle)
        assertTrue(snapshot.cards.first().matchSummary.contains("发送与回复"))
        assertTrue(snapshot.lines.first().contains("微信"))
    }
}
