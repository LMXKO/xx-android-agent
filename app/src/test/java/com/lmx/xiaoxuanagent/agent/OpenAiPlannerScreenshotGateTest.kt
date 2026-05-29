package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class OpenAiPlannerScreenshotGateTest {
    private val planner = OpenAiPlanner()
    private val shot = ScreenshotPayload("image/jpeg", "abc", 320, 640)

    private fun observation(elementCount: Int): ScreenObservation =
        ScreenObservation(
            packageName = "com.taobao.taobao",
            pageState = "UNKNOWN",
            signature = "sig12345678",
            screenSummary = "page=UNKNOWN",
            topTexts = emptyList(),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements =
                List(elementCount) { index ->
                    UiElementObservation(
                        id = "e$index",
                        text = "元素$index",
                        viewId = "id/$index",
                        className = "android.widget.TextView",
                        bounds = "[0,0][10,10]",
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                        focused = false,
                        selected = false,
                    )
                },
        )

    @Test
    fun `dense page with visual hints now attaches screenshot`() {
        Assume.assumeFalse(BuildConfig.AGENT_FORCE_SCREENSHOT)
        // 20 elements > old cap(14) but <= new cap(32): was dropped before, attached now.
        val visualContext = VisualPerceptionContext(captureAvailable = true, visualHints = listOf("商品", "价格"))
        assertTrue(planner.debugShouldAttachScreenshot(observation(20), visualContext, shot))
    }

    @Test
    fun `very dense page without grounding stays text-only`() {
        Assume.assumeFalse(BuildConfig.AGENT_FORCE_SCREENSHOT)
        val visualContext = VisualPerceptionContext(captureAvailable = true, visualHints = listOf("商品"))
        assertFalse(planner.debugShouldAttachScreenshot(observation(40), visualContext, shot))
    }

    @Test
    fun `weak page always attaches`() {
        Assume.assumeFalse(BuildConfig.AGENT_FORCE_SCREENSHOT)
        val visualContext = VisualPerceptionContext(captureAvailable = true)
        assertTrue(planner.debugShouldAttachScreenshot(observation(3), visualContext, shot))
    }

    @Test
    fun `no screenshot never attaches`() {
        val visualContext = VisualPerceptionContext(captureAvailable = true, visualHints = listOf("x"))
        assertFalse(planner.debugShouldAttachScreenshot(observation(20), visualContext, null))
    }
}
