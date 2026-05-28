package com.lmx.xiaoxuanagent.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSafetyPolicyStoreTest {
    @Test
    fun `contact and data-type dimensions resolve specific persistent rules`() {
        val family = "unit_test_family_${System.nanoTime()}"
        RuntimeSafetyPolicyStore.upsertRule(
            behavior = RuntimeSafetyPolicyBehavior.ALLOW,
            actionFamily = family,
            contactContains = "老婆",
            sourceTag = "unit_test",
        )
        RuntimeSafetyPolicyStore.upsertRule(
            behavior = RuntimeSafetyPolicyBehavior.DENY,
            actionFamily = family,
            dataType = "verification_code",
            sourceTag = "unit_test",
        )

        val byContact =
            RuntimeSafetyPolicyStore.resolveRule(
                actionFamily = family,
                targetPackageName = "com.tencent.mm",
                contact = "给老婆发消息",
            )
        assertEquals(RuntimeSafetyPolicyBehavior.ALLOW, byContact?.behavior)

        val byDataType =
            RuntimeSafetyPolicyStore.resolveRule(
                actionFamily = family,
                targetPackageName = "com.tencent.mm",
                dataType = "verification_code",
            )
        assertEquals(RuntimeSafetyPolicyBehavior.DENY, byDataType?.behavior)

        val noMatch =
            RuntimeSafetyPolicyStore.resolveRule(
                actionFamily = family,
                targetPackageName = "com.tencent.mm",
                contact = "张三",
                dataType = "amount",
            )
        assertNull(noMatch)
    }

    @Test
    fun `baseline rules only gate payment and destructive after autonomy alignment`() {
        val baselines = RuntimeSafetyPolicyStore.ensureSuperAssistantBaselineRules()
        val families = baselines.map { it.actionFamily }.toSet()
        assertTrue(families.contains("transaction"))
        assertTrue(families.contains("destructive"))
        // 自治对齐后，发送/提交/发布/导航不再有 baseline 确认规则
        assertFalse(families.contains("message_send"))
        assertFalse(families.contains("submit_in_context"))
        assertFalse(families.contains("content_publish"))
        assertFalse(families.contains("route_confirm"))
        // 破坏性动作是确认（ASK）而非阻断（DENY）
        assertEquals(
            RuntimeSafetyPolicyBehavior.ASK,
            baselines.first { it.actionFamily == "destructive" }.behavior,
        )
    }
}
