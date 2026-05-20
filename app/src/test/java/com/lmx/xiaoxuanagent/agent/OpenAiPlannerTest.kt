package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPlannerTest {
    @Test
    fun `invalid click id can fall back by target text in reason`() {
        val planner = OpenAiPlanner()
        val decision =
            planner.debugResolveFallbackClick(
                reason = "当前处于 find_conversation 阶段，需要在会话列表中定位目标联系人“韩威”，点击它进入目标会话。",
                observation = fallbackObservation(),
            )

        assertEquals(AgentAction.Click("e03"), decision)
    }

    @Test
    fun `invalid click id can fall back by target text field`() {
        val planner = OpenAiPlanner()
        val decision =
            planner.debugResolveFallbackClick(
                targetText = "评论",
                reason = "进入评论区读取更多证据。",
                observation =
                    fallbackObservation().copy(
                        elements =
                            listOf(
                                fallbackElement(id = "e01", text = "详情"),
                                fallbackElement(id = "e02", text = "评论"),
                                fallbackElement(id = "e03", text = "参数"),
                            ),
                    ),
            )

        assertEquals(AgentAction.Click("e02"), decision)
    }

    @Test
    fun `planner memory payload carries result artifacts as dedicated field`() {
        val planner = OpenAiPlanner()

        val payload =
            planner.debugMemoryContextPayload(
                PlanningMemoryContext(
                    shortTermNotes = listOf("当前任务关键词: 勇哥说餐饮/评论"),
                    resultArtifacts = listOf("content | 勇哥说餐饮 最新一期 餐饮观察 | 高赞评论提到外卖利润"),
                ),
            )

        assertEquals(
            listOf("content | 勇哥说餐饮 最新一期 餐饮观察 | 高赞评论提到外卖利润"),
            payload["result_artifacts"],
        )
        assertTrue(payload.containsKey("contacts"))
        assertEquals(listOf("当前任务关键词: 勇哥说餐饮/评论"), payload["short_term_notes"])
    }

    @Test
    fun `planner memory payload carries correction templates as dedicated field`() {
        val planner = OpenAiPlanner()

        val payload =
            planner.debugMemoryContextPayload(
                PlanningMemoryContext(
                    correctionTemplates =
                        listOf(
                            "avoid_target:公众号 | 遇到相似任务时避开公众号，优先重新定位更贴近目标的候选。",
                            "backtrack | 如果进入错误页面或误点目标，先返回上一层再重新定位当前子目标。",
                        ),
                ),
            )

        assertEquals(
            listOf(
                "avoid_target:公众号 | 遇到相似任务时避开公众号，优先重新定位更贴近目标的候选。",
                "backtrack | 如果进入错误页面或误点目标，先返回上一层再重新定位当前子目标。",
            ),
            payload["correction_templates"],
        )
    }

    @Test
    fun `planner resume payload carries user correction`() {
        val planner = OpenAiPlanner()

        val payload =
            planner.debugResumeContextPayload(
                ResumeContext(
                    active = true,
                    source = "manual_correction_resume",
                    resumeEvent = "manual_correction",
                    resumeHint = "回到联系人定位步骤继续执行。",
                    resumedSubgoalId = "find",
                    userCorrection = "刚才点错会话了，优先找韩威本人，不要点公众号。",
                ),
            )

        assertEquals("刚才点错会话了，优先找韩威本人，不要点公众号。", payload["user_correction"])
        assertEquals("manual_correction_resume", payload["source"])
    }

    @Test
    fun `planner uses fast primary on weak visual pages`() {
        val planner = OpenAiPlanner()

        val strategy =
            planner.debugResolvePlannerRequestStrategy(
                observation =
                    ScreenObservation(
                        packageName = "com.tencent.mm",
                        pageState = "UNKNOWN",
                        signature = "weak12345678",
                        screenSummary = "page=UNKNOWN, rawNodes=1, visibleNodes=0",
                        topTexts = listOf("微信", "韩威"),
                        primaryEditableId = null,
                        focusedElementId = null,
                        defaultScrollableId = null,
                        primaryInterruptActionId = null,
                        interruptiveHints = emptyList(),
                        elements = emptyList(),
                    ),
                visualContext =
                    VisualPerceptionContext(
                        captureAvailable = true,
                        ocrTexts = listOf(VisualTextObservation("韩威", "[10,10][100,40]", 0.98f)),
                        visualHints = listOf("微信", "韩威"),
                    ),
                screenshot = ScreenshotPayload("image/jpeg", "abc", 320, 640),
            )

        if (BuildConfig.AGENT_ROUTE_MODEL != BuildConfig.AGENT_MODEL) {
            assertEquals(BuildConfig.AGENT_ROUTE_MODEL, strategy.primaryModel)
            assertEquals("weak_visual_fast_primary", strategy.policyTag)
            assertFalse(strategy.attachScreenshotOnPrimary)
            assertTrue(strategy.primaryReadTimeoutMs <= 18_000)
            assertTrue(strategy.fallbackReadTimeoutMs <= 45_000)
        } else {
            assertEquals(BuildConfig.AGENT_MODEL, strategy.primaryModel)
            assertEquals("default_primary", strategy.policyTag)
        }
    }

    @Test
    fun `planner keeps primary model on structured pages`() {
        val planner = OpenAiPlanner()

        val strategy =
            planner.debugResolvePlannerRequestStrategy(
                observation =
                    ScreenObservation(
                        packageName = "com.tencent.mm",
                        pageState = "UNKNOWN",
                        signature = "strong123456",
                        screenSummary = "page=UNKNOWN, editable=1, clickable=12",
                        topTexts = listOf("微信", "搜索", "通讯录"),
                        primaryEditableId = "e01",
                        focusedElementId = "e01",
                        defaultScrollableId = "e02",
                        primaryInterruptActionId = null,
                        interruptiveHints = emptyList(),
                        elements =
                            List(12) { index ->
                                UiElementObservation(
                                    id = "e${index + 1}",
                                    text = "元素$index",
                                    viewId = "id/$index",
                                    className = "android.widget.TextView",
                                    bounds = "[0,0][100,100]",
                                    clickable = true,
                                    editable = index == 0,
                                    scrollable = index == 1,
                                    enabled = true,
                                    focused = index == 0,
                                    selected = false,
                                )
                            },
                    ),
                visualContext = VisualPerceptionContext(captureAvailable = true),
                screenshot = ScreenshotPayload("image/jpeg", "abc", 320, 640),
            )

        assertEquals(BuildConfig.AGENT_MODEL, strategy.primaryModel)
        assertEquals("default_primary", strategy.policyTag)
    }

    @Test
    fun `visual augmentation updates signature when visual hints change`() {
        val observation =
            ScreenObservation(
                packageName = "com.tencent.mm",
                pageState = "UNKNOWN",
                signature = "baseabcdef12",
                screenSummary = "page=UNKNOWN, rawNodes=1, visibleNodes=0",
                topTexts = emptyList(),
                primaryEditableId = null,
                focusedElementId = null,
                defaultScrollableId = null,
                primaryInterruptActionId = null,
                interruptiveHints = emptyList(),
                elements = emptyList(),
            )

        val augmented =
            ObservationAugmenter.augmentObservation(
                observation = observation,
                visualContext =
                    VisualPerceptionContext(
                        captureAvailable = true,
                        visualHints = listOf("微信", "韩威"),
                    ),
            )

        assertTrue(augmented.signature.isNotBlank())
        assertTrue(augmented.signature != observation.signature)
        assertTrue(augmented.topTexts.isNotEmpty())
    }

    private fun fallbackObservation(): ScreenObservation =
        ScreenObservation(
            packageName = "com.tencent.mm",
            pageState = "UNKNOWN",
            signature = "fallback1234",
            screenSummary = "page=UNKNOWN, visualTargets=3",
            topTexts = listOf("微信", "韩威"),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements =
                listOf(
                    fallbackElement(id = "e01", text = "公众号"),
                    fallbackElement(id = "e02", text = "宫咸尧"),
                    fallbackElement(id = "e03", text = "韩威"),
                ),
        )

    private fun fallbackElement(
        id: String,
        text: String,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "visual:$id",
            className = "VisualTextTarget",
            bounds = "[0,0][100,100]",
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )
}
