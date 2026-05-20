package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResultExtractorTest {
    @Test
    fun `content result extractor keeps objective and evidence`() {
        val result =
            TaskResultExtractor.extract(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                observation =
                    observation(
                        pageState = "VIDEO_DETAIL",
                        topTexts = listOf("勇哥说餐饮", "评论区", "高赞", "最新一期"),
                    ),
                taskPlanState = TaskPlanState(planType = "content_research", currentStage = "summarize"),
            )

        requireNotNull(result)
        assertEquals("content", result.intentType)
        assertTrue(result.summary.contains("勇哥说餐饮"))
        assertTrue(result.highlights.any { it.contains("评论") || it.contains("高赞") })
    }

    @Test
    fun `navigation result extractor keeps destination and route cue`() {
        val result =
            TaskResultExtractor.extract(
                task = "去浦东机场怎么走",
                observation =
                    observation(
                        topTexts = listOf("浦东机场", "路线", "预计 42 分钟", "到这去"),
                    ),
                taskPlanState = TaskPlanState(planType = "navigation", currentStage = "confirm_route"),
            )

        requireNotNull(result)
        assertEquals("navigation", result.intentType)
        assertTrue(result.summary.contains("浦东机场"))
        assertTrue(result.highlights.any { it.contains("路线") || it.contains("分钟") })
    }

    @Test
    fun `shopping result extractor keeps product objective`() {
        val result =
            TaskResultExtractor.extract(
                task = "打开购物 App搜零食看评价",
                observation =
                    observation(
                        pageState = "DETAIL",
                        topTexts = listOf("零食大礼包", "评价", "用户评论"),
                    ),
                taskPlanState = TaskPlanState(planType = "shopping_research", currentStage = "summarize"),
            )

        requireNotNull(result)
        assertEquals("shopping", result.intentType)
        assertTrue(result.fields.any { it.key == "query" && it.value.contains("零食") })
    }

    private fun observation(
        pageState: String = "UNKNOWN",
        topTexts: List<String>,
    ): ScreenObservation =
        ScreenObservation(
            packageName = "com.example.app",
            pageState = pageState,
            signature = "sig",
            screenSummary = topTexts.joinToString(" "),
            topTexts = topTexts,
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = topTexts.mapIndexed { index, text ->
                UiElementObservation(
                    id = "e$index",
                    text = text,
                    viewId = "",
                    className = "android.widget.TextView",
                    bounds = "[0,0][100,100]",
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    focused = false,
                    selected = false,
                )
            },
        )
}
