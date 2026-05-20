package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentRiskClassifierTest {
    @Test
    fun `meituan deceptive payment copy is flagged with profile context`() {
        val signal =
            ScreenContentRiskClassifier.analyze(
                observation =
                    ScreenObservation(
                        packageName = "com.sankuai.meituan",
                        pageState = "checkout",
                        signature = "sig",
                        screenSummary = "确认下单并开启免密支付",
                        topTexts = listOf("极速支付"),
                        primaryEditableId = null,
                        focusedElementId = null,
                        defaultScrollableId = null,
                        primaryInterruptActionId = null,
                        interruptiveHints = emptyList(),
                        elements = listOf(element("立即支付")),
                    ),
                semanticText = "继续支付",
            )

        assertEquals("ask", signal.behavior)
        assertEquals("screen_deceptive_copy", signal.summary)
        assertTrue(signal.detailLines.any { it.contains("profile=meituan_assistant") })
    }

    private fun element(
        text: String,
    ) = UiElementObservation(
        id = "e1",
        text = text,
        viewId = "",
        className = "android.widget.Button",
        bounds = "[0,0][10,10]",
        clickable = true,
        editable = false,
        scrollable = false,
        enabled = true,
        focused = false,
        selected = false,
    )
}
