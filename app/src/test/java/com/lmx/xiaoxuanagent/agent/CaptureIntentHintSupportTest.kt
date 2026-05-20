package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntentHintSupportTest {
    @Test
    fun `document capture hint prefers align then shoot`() {
        val hint = CaptureIntentHintSupport.forNote("拍一下发票留证")

        assertEquals("evidence", hint.mode)
        assertEquals("rear", hint.lensFacing)
        assertEquals("capture_evidence_then_review", hint.completionHint)
        assertTrue(hint.detailLines.any { it.contains("capture_completion=") })
    }

    @Test
    fun `qr capture hint marks quick capture`() {
        val hint = CaptureIntentHintSupport.forNote("打开相机扫码")

        assertEquals("qr", hint.mode)
        assertEquals("aim_code_then_shoot", hint.completionHint)
    }
}
