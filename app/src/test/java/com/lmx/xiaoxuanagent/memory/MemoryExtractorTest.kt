package com.lmx.xiaoxuanagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {
    @Test
    fun `extract correction templates keeps generic and avoid target memories`() {
        val templates =
            MemoryExtractor.extractCorrectionTemplates(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "dynamic_app_com.tencent.mm",
                correction = "刚才点错会话了，不要点公众号，回上一页重新找韩威本人。",
            )

        assertTrue(templates.any { it.templateType == "backtrack" })
        assertTrue(templates.any { it.templateType == "refocus_entry" })
        assertTrue(templates.any { it.templateType == "avoid_target" && it.argument == "公众号" })
        assertEquals(3, templates.size)
    }
}
