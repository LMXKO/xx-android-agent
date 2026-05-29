package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchAvailabilityTest {
    @Test
    fun `web search returns the unavailable sentinel when base url is not configured`() {
        // 单测环境下 BuildConfig.AGENT_WEB_SEARCH_BASE_URL 为空：search 必须给出 missing 受体，
        // 由 executeWebSearch 据此如实报告"不可用"而非谎报"已执行"。
        val lines = PlatformWebResearchService.search("今天天气预报")
        assertTrue("expected unavailable sentinel, got=$lines", lines.any { it == "web_search_base_url_missing" })
    }
}
