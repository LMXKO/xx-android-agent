package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.CrossAppMissionEngine
import com.lmx.xiaoxuanagent.agent.InstalledPackageChecker
import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactServiceMissionTest {
    @Test
    fun `buildMissionGovernanceHints exposes mission id progress and blackboard payloads`() {
        val mission =
            CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 16 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
                .recordAndAdvance(
                    TaskResultPayload(
                        intentType = "shopping",
                        title = "iPhone 16 Pro 256GB 黑色",
                        summary = "京东在售",
                        highlights = listOf("自营/含税"),
                        fields = listOf(TaskResultField(key = "price", label = "价格", value = "¥8999")),
                    ),
                )

        val hints = CompactService.buildMissionGovernanceHints(mission)

        assertTrue("应含 mission 进度", hints.any { it.startsWith("mission_progress=") })
        assertTrue(
            "应含上一腿实体与价格证据",
            hints.any { it.contains("iPhone 16 Pro 256GB 黑色") && it.contains("¥8999") },
        )
    }

    @Test
    fun `buildMissionGovernanceHints is empty when no mission active`() {
        assertTrue(CompactService.buildMissionGovernanceHints(mission = null).isEmpty())
    }
}
