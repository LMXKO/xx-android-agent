package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.memory.RouteMemoryHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResolutionPolicyTest {
    @Test
    fun `memory bias only overrides low confidence candidate within same category`() {
        val selectedCandidate =
            candidate(
                profileId = "shopping_assistant",
                installed = true,
                category = "shopping",
            )
        val preferredMemoryCandidate =
            MemoryPreferredCandidate(
                candidate =
                    candidate(
                        profileId = "jd_assistant",
                        installed = true,
                        category = "shopping",
                    ),
                hint = RouteMemoryHint(profileId = "jd_assistant", summary = "历史上常用京东看数码", score = 82),
            )

        val preferred =
            RouteResolutionPolicy.maybeApplyMemoryBias(
                choice = LlmRouteChoice(profileId = "shopping_assistant", reason = "购物场景", confidence = 0.34, rawResponse = "{}"),
                selectedCandidate = selectedCandidate,
                preferredMemoryCandidate = preferredMemoryCandidate,
            )

        assertSame(preferredMemoryCandidate, preferred)
    }

    @Test
    fun `memory bias does not override high confidence cross category route`() {
        val preferred =
            RouteResolutionPolicy.maybeApplyMemoryBias(
                choice = LlmRouteChoice(profileId = "amap_assistant", reason = "导航场景", confidence = 0.91, rawResponse = "{}"),
                selectedCandidate =
                    candidate(
                        profileId = "amap_assistant",
                        installed = true,
                        category = "navigation",
                    ),
                preferredMemoryCandidate =
                    MemoryPreferredCandidate(
                        candidate =
                            candidate(
                                profileId = "wechat_assistant",
                                installed = true,
                                category = "messaging",
                            ),
                        hint = RouteMemoryHint(profileId = "wechat_assistant", summary = "常用微信", score = 96),
                    ),
            )

        assertNull(preferred)
    }

    @Test
    fun `installed fallback prefers memory matched app in same category`() {
        val selectedCandidate =
            candidate(
                profileId = "dynamic_app_com.example.uninstalled.shop",
                installed = false,
                category = "shopping",
            )
        val memoryCandidate =
            candidate(
                profileId = "jd_assistant",
                installed = true,
                category = "shopping",
            )
        val fallback =
            RouteResolutionPolicy.findInstalledFallbackCandidate(
                selectedCandidate = selectedCandidate,
                candidates =
                    listOf(
                        selectedCandidate,
                        candidate(
                            profileId = "shopping_assistant",
                            installed = true,
                            category = "shopping",
                        ),
                        memoryCandidate,
                    ),
                preferredMemoryCandidate =
                    MemoryPreferredCandidate(
                        candidate = memoryCandidate,
                        hint = RouteMemoryHint(profileId = "jd_assistant", summary = "历史常用京东", score = 78),
                    ),
            )

        assertEquals("jd_assistant", fallback?.profileId)
    }

    @Test
    fun `route debug lines include policy selected app and memory evidence`() {
        val lines =
            TaskRegistry.renderRouteDebugLines(
                TaskRegistry.RouteDebugInfo(
                    candidateCount = 8,
                    candidatePreview = emptyList(),
                    modelRawResponse = """{"profile_id":"shopping_assistant","reason":"购物","confidence":0.41}""",
                    memoryHintPreview = listOf("jd_assistant:86:历史结果: 显卡价格"),
                    fallbackReason = "memory_bias:jd_assistant",
                    modelChoiceProfileId = "shopping_assistant",
                    selectedProfileId = "jd_assistant",
                    policyTag = "memory_bias_override",
                ),
            )

        assertTrue(lines.any { it.contains("policy=memory_bias_override") })
        assertTrue(lines.any { it.contains("modelChoice=shopping_assistant") })
        assertTrue(lines.any { it.contains("selected=jd_assistant") })
        assertTrue(lines.any { it.contains("memory=") && it.contains("显卡价格") })
    }

    @Test
    fun `local semantic fallback prefers explicit messaging app on llm error`() {
        val fallback =
            TaskRegistry.chooseLocalSemanticFallback(
                task = "去微信给韩威发一条晚安的消息",
                candidates =
                    listOf(
                        candidate(
                            profileId = "shopping_assistant",
                            installed = true,
                            category = "shopping",
                            displayName = "购物搜索助手",
                            packageName = "com.taobao.taobao",
                            aliases = listOf("淘宝", "商品", "购物"),
                            capabilitySummary = "适合商品搜索",
                        ),
                        candidate(
                            profileId = "wechat_assistant",
                            installed = true,
                            category = "messaging",
                            displayName = "微信助手",
                            packageName = "com.tencent.mm",
                            aliases = listOf("微信", "发消息", "聊天", "联系人"),
                            capabilitySummary = "适合聊天联系人任务",
                        ),
                    ),
            )

        assertEquals("wechat_assistant", fallback?.profileId)
        assertTrue((fallback?.score ?: 0) >= 110)
    }

    @Test
    fun `local semantic fallback prefers navigation app for travel intent`() {
        val fallback =
            TaskRegistry.chooseLocalSemanticFallback(
                task = "去浦东机场怎么走",
                candidates =
                    listOf(
                        candidate(
                            profileId = "amap_assistant",
                            installed = true,
                            category = "navigation",
                            displayName = "高德地图助手",
                            packageName = "com.autonavi.minimap",
                            aliases = listOf("地图", "导航", "路线"),
                            capabilitySummary = "适合导航路线规划",
                        ),
                        candidate(
                            profileId = "wechat_assistant",
                            installed = true,
                            category = "messaging",
                            displayName = "微信助手",
                            packageName = "com.tencent.mm",
                            aliases = listOf("微信", "聊天"),
                            capabilitySummary = "适合聊天联系人任务",
                        ),
                    ),
            )

        assertEquals("amap_assistant", fallback?.profileId)
    }

    private fun candidate(
        profileId: String,
        installed: Boolean,
        category: String?,
        displayName: String = profileId,
        packageName: String = "pkg.$profileId",
        aliases: List<String> = emptyList(),
        capabilitySummary: String = "summary",
    ): LlmRouteCandidate =
        LlmRouteCandidate(
            profileId = profileId,
            displayName = displayName,
            packageName = packageName,
            capabilitySummary = capabilitySummary,
            installed = installed,
            aliases = aliases,
            category = category,
        )
}
