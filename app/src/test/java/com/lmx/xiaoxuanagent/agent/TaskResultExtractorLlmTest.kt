package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResultExtractorLlmTest {
    @Test
    fun `parseLlmExtraction builds payload with title price and highlights`() {
        val content =
            """
            {
              "title":"iPhone 16 Pro 256GB 黑色",
              "summary":"京东自营在售，价格 ¥8999",
              "highlights":["自营","含税"],
              "fields":[
                {"key":"price","label":"价格","value":"¥8999"},
                {"key":"shipping","label":"运费","value":"包邮"}
              ]
            }
            """.trimIndent()
        val payload = TaskResultExtractor.parseLlmExtraction(content, intentType = "shopping")
        assertNotNull(payload)
        payload!!
        assertEquals("iPhone 16 Pro 256GB 黑色", payload.title)
        assertEquals("shopping", payload.intentType)
        assertEquals("¥8999", payload.fields.first { it.key == "price" }.value)
        // 价格被提到 highlights 首位（供下一腿 rewriteSubTaskWithHandoff 直接看到）
        assertTrue(payload.highlights.contains("¥8999"))
    }

    @Test
    fun `parseLlmExtraction tolerates markdown fences and merges with fallback`() {
        val content =
            """
            ```json
            {"title":"AirPods Pro 2","fields":[{"key":"price","value":"¥1599"}]}
            ```
            """.trimIndent()
        val fallback =
            TaskResultPayload(
                intentType = "shopping",
                title = "fallback title",
                summary = "fallback summary",
                highlights = listOf("fallback evidence"),
            )
        val payload = TaskResultExtractor.parseLlmExtraction(content, intentType = "shopping", fallback = fallback)
        assertNotNull(payload)
        payload!!
        assertEquals("AirPods Pro 2", payload.title)
        // summary 缺失 → 回落到 fallback
        assertEquals("fallback summary", payload.summary)
    }

    @Test
    fun `parseLlmExtraction returns null for unparseable or empty content`() {
        assertNull(TaskResultExtractor.parseLlmExtraction("not json", intentType = "shopping"))
        assertNull(TaskResultExtractor.parseLlmExtraction("""{"unrelated":1}""", intentType = "shopping"))
    }
}
