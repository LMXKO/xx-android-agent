package com.lmx.xiaoxuanagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationFocusStageSupportTest {
    @Test
    fun `meituan submit stage is treated as submit`() {
        assertTrue(ScreenAutomationFocusStageSupport.isSubmitStage("meituan_assistant", "submit_query"))
    }

    @Test
    fun `wechat conversation stage is treated as fill`() {
        assertTrue(ScreenAutomationFocusStageSupport.isFillStage("wechat_assistant", "find_conversation"))
    }

    @Test
    fun `stage labels are profile specific`() {
        assertEquals("定位微信会话", ScreenAutomationFocusStageSupport.stageLabel("wechat_assistant", "find_conversation"))
        assertEquals("输入路线目的地", ScreenAutomationFocusStageSupport.stageLabel("amap_assistant", "enter_destination"))
        assertEquals("准备微信回复待发送状态", ScreenAutomationFocusStageSupport.stageLabel("wechat_assistant", "prepare_message_reply_handoff"))
        assertEquals("进入外卖搜索与筛选阶段", ScreenAutomationFocusStageSupport.stageLabel("meituan_assistant", "prepare_takeout_search"))
    }
}
