package com.lmx.xiaoxuanagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMemoryHintTest {
    @Test
    fun `build route hints prefers historically successful matching profile`() {
        val hints =
            MemoryRetriever.buildRouteHintsFromData(
                task = "去微信给韩威发一条晚安的消息",
                candidateProfileIds = setOf("wechat_assistant", "amap_assistant"),
                profileStats =
                    mapOf(
                        "wechat_assistant" to MemoryRetriever.RouteProfileStats(runCount = 4, successCount = 4),
                        "amap_assistant" to MemoryRetriever.RouteProfileStats(runCount = 3, successCount = 1),
                    ),
                profileSummaries =
                    mapOf(
                        "wechat_assistant" to "最近任务偏向于 给韩威发消息",
                        "amap_assistant" to "最近任务偏向于 去机场怎么走",
                    ),
                preferenceNotes =
                    mapOf(
                        "wechat_assistant" to
                            listOf(
                                "近期成功使用该应用完成过类似任务 | 去微信给韩威发消息",
                            ),
                    ),
                resultArtifacts = emptyMap(),
            )

        assertEquals("wechat_assistant", hints.first().profileId)
        assertTrue(hints.first().score > 55)
        assertTrue(hints.first().summary.contains("历史成功"))
    }

    @Test
    fun `build route hints can leverage matched result artifacts for routing`() {
        val hints =
            MemoryRetriever.buildRouteHintsFromData(
                task = "看下勇哥说餐饮最新一期点赞最高的评论",
                candidateProfileIds = setOf("dynamic_app_tv.danmaku.bili", "amap_assistant"),
                profileStats =
                    mapOf(
                        "dynamic_app_tv.danmaku.bili" to MemoryRetriever.RouteProfileStats(runCount = 1, successCount = 1),
                        "amap_assistant" to MemoryRetriever.RouteProfileStats(runCount = 2, successCount = 1),
                    ),
                profileSummaries =
                    mapOf(
                        "dynamic_app_tv.danmaku.bili" to "最近常看餐饮内容",
                        "amap_assistant" to "最近导航去机场",
                    ),
                preferenceNotes = emptyMap(),
                resultArtifacts =
                    mapOf(
                        "dynamic_app_tv.danmaku.bili" to
                            listOf(
                                ResultArtifactMemory(
                                    intentType = "content",
                                    title = "勇哥说餐饮 最新一期 餐饮观察",
                                    summary = "高赞评论提到外卖利润和门店模型",
                                    sourceProfileId = "dynamic_app_tv.danmaku.bili",
                                    updatedAt = 1L,
                                ),
                            ),
                    ),
            )

        assertEquals("dynamic_app_tv.danmaku.bili", hints.first().profileId)
        assertTrue(hints.first().summary.contains("历史结果"))
        assertTrue(hints.first().summary.contains("勇哥说餐饮"))
    }
}
