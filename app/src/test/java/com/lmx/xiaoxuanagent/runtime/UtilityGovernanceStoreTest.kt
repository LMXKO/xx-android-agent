package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.UtilityExecutionReceiptStore
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityGovernanceStoreTest {
    @Test
    fun `utility governance surfaces latest receipt summary`() {
        UtilityExecutionReceiptStore.record(
            toolName = "system.open_device_panel",
            summary = "已直接切换手电：开启。",
            detailLines = listOf("flashlight_enabled=true"),
            handoffRequired = false,
        )

        val lines = UtilityGovernanceStore.lines(limit = 12)
        val content = lines.joinToString("\n")

        assertTrue(content.contains("打开设备控制面板"))
        assertTrue(content.contains("status="))
        assertTrue(content.contains("已直接切换手电"))
    }

    @Test
    fun `utility governance includes advanced device tools`() {
        val lines = UtilityGovernanceStore.lines(limit = 12)
        val content = lines.joinToString("\n")

        assertTrue(content.contains("设置亮度"))
        assertTrue(content.contains("设置免打扰"))
        assertTrue(content.contains("设置省电模式"))
        assertTrue(content.contains("读取设备状态"))
    }
}
