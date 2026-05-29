package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item1：跨 App 编排去"比价化"——通用收口、结构化交接、失败降级、kind 推导。 */
class CrossAppMissionGeneralTest {
    private fun payload(
        title: String,
        summary: String,
        fields: List<TaskResultField> = emptyList(),
        highlights: List<String> = emptyList(),
    ): TaskResultPayload =
        TaskResultPayload(intentType = "x", title = title, summary = summary, highlights = highlights, fields = fields)

    @Test
    fun `general mission composes a task summary, not a price comparison`() {
        val mission =
            CrossAppMission(
                missionId = "m",
                goal = "查快递到哪了再用微信通知我",
                kind = "general",
                legs =
                    listOf(
                        MissionLeg("l0", "cainiao_assistant", "p", "查快递"),
                        MissionLeg("l1", "wechat_assistant", "p", "通知"),
                    ),
            ).record(payload("顺丰已到站", "快递已到本市集散中心"))
        val result = CrossAppMissionEngine.composeFinalResult(mission, payload("已发送", "已在微信告知你快递到站"))
        assertEquals("cross_app_task", result.intentType)
        assertTrue(result.summary.contains("跨 App 任务"))
        assertFalse(result.summary.contains("更便宜"))
    }

    @Test
    fun `compare mission still composes a price comparison`() {
        val mission = CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        val after = mission.recordAndAdvance(payload("iPhone", "", fields = listOf(TaskResultField("price", "价格", "¥5999"))))
        val result = CrossAppMissionEngine.composeFinalResult(after, payload("iPhone", "", fields = listOf(TaskResultField("price", "价格", "¥5799"))))
        assertEquals("cross_app_compare", result.intentType)
    }

    @Test
    fun `LLM decomposition derives kind from the goal`() {
        val json =
            """{"multi_app":true,"legs":[
              {"app":"cainiao_assistant","sub_task":"查快递"},
              {"app":"wechat_assistant","sub_task":"通知"}
            ]}"""
        val general = CrossAppMissionEngine.parseLlmDecomposition(json, "查快递然后微信通知我", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        assertEquals("general", general.kind)

        val compareJson =
            """{"multi_app":true,"legs":[
              {"app":"jd_assistant","sub_task":"搜"},
              {"app":"shopping_search","sub_task":"搜"}
            ]}"""
        val compare = CrossAppMissionEngine.parseLlmDecomposition(compareJson, "比较京东和淘宝的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
        assertEquals("compare", compare.kind)
    }

    @Test
    fun `resolveHandoffFields extracts entity and whitelisted fields only`() {
        val mission =
            CrossAppMission(
                missionId = "m",
                goal = "g",
                legs = listOf(MissionLeg("l", "p", "p", "t")),
                declaredHandoffFields = setOf("query", "price"),
            )
        val prev =
            payload(
                "AirPods Pro 2",
                "已找到",
                fields = listOf(TaskResultField("price", "价格", "¥1799"), TaskResultField("secret", "x", "y")),
            )
        val slots = CrossAppMissionEngine.resolveHandoffFields(mission, prev)
        assertEquals("AirPods Pro 2", slots["entity"])
        assertEquals("¥1799", slots["price"])
        assertNull(slots["secret"])
    }

    @Test
    fun `markActiveLegFailed advances past the failed leg`() {
        val mission =
            CrossAppMission(
                missionId = "m",
                goal = "g",
                legs = listOf(MissionLeg("l0", "a", "p", "t0"), MissionLeg("l1", "b", "p", "t1")),
            )
        val failed = mission.markActiveLegFailed()
        assertEquals(1, failed.activeLegIndex)
        assertEquals("failed", failed.legs[0].status)
        assertEquals(1, failed.failedLegCount())
    }

    @Test
    fun `general compose tolerates a degraded mission with a failed leg`() {
        val mission =
            CrossAppMission(
                missionId = "m",
                goal = "目标 X",
                kind = "general",
                legs =
                    listOf(
                        MissionLeg("l0", "a", "p", "t0", status = "done"),
                        MissionLeg("l1", "b", "p", "t1", status = "failed"),
                    ),
                blackboard = listOf(payload("步骤1", "已完成 A")),
            )
        val result = CrossAppMissionEngine.composeFinalResult(mission, null)
        assertEquals("cross_app_task", result.intentType)
        assertTrue(result.summary.contains("未完成"))
    }
}
