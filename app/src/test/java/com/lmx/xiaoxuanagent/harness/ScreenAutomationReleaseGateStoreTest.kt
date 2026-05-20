package com.lmx.xiaoxuanagent.harness

import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationReleaseGateStoreTest {
    @Test
    fun `meituan gate uses search submit stages instead of route stages`() {
        val stages = ScreenAutomationReleaseGateStore.criticalStagesForProfile("meituan_assistant")

        assertTrue(stages.contains("prepare_takeout_search"))
        assertTrue(stages.contains("submit_query"))
        assertTrue(stages.contains("prepare_checkout_handoff"))
    }

    @Test
    fun `wechat gate keeps conversation and send stages`() {
        val stages = ScreenAutomationReleaseGateStore.criticalStagesForProfile("wechat_assistant")

        assertTrue(stages.contains("find_conversation"))
        assertTrue(stages.contains("prepare_message_reply_handoff"))
        assertTrue(stages.contains("confirm_send"))
    }

    @Test
    fun `amap gate keeps route stages`() {
        val stages = ScreenAutomationReleaseGateStore.criticalStagesForProfile("amap_assistant")

        assertTrue(stages.contains("enter_destination"))
        assertTrue(stages.contains("confirm_route"))
        assertTrue(stages.contains("start_navigation"))
    }
}
