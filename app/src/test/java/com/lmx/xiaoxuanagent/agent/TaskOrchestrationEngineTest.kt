package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOrchestrationEngineTest {
    @Test
    fun `messaging plan exposes active subgoal and suspendable state`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("聊天", "发送"),
                        primaryEditableId = "e1",
                    ),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig1",
                            pageState = "CHAT",
                            action = "set_text(e1)",
                            result = "已输入消息。",
                        ),
                    ),
            )

        assertEquals("confirm_send", plan.currentStage)
        assertTrue(plan.currentSubgoalId.isNotBlank())
        assertTrue(plan.suspendable)
        assertTrue(plan.taskStack.isNotEmpty())
        assertTrue(plan.taskGraph.isNotEmpty())
        assertTrue(plan.waitConditions.any { it.type == "checkpoint" })
        assertTrue(plan.orchestrationSummary.contains("focus="))
    }

    @Test
    fun `manual verification screen becomes waiting state`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "打开应用完成任务",
                profileId = "generic_app",
                observation =
                    observation(
                        packageName = "com.example.app",
                        topTexts = listOf("请输入验证码", "登录"),
                    ),
                history = emptyList(),
            )

        assertTrue(plan.waitingForExternal)
        assertEquals("manual_verification", plan.waitingForEvent)
        assertTrue(plan.suspendReason.contains("验证码"))
        assertTrue(plan.waitConditions.any { it.resumeEvent == "manual_verification" })
    }

    @Test
    fun `generic plan still stays active when no waiting signal exists`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "打开应用找目标内容",
                profileId = "generic_app",
                observation =
                    observation(
                        packageName = "com.example.app",
                        topTexts = listOf("首页", "搜索"),
                    ),
                history = emptyList(),
            )

        assertFalse(plan.waitingForExternal)
        assertTrue(plan.taskStack.isNotEmpty())
        assertTrue(plan.taskGraph.first().children.any { it.kind == "lane" })
    }

    @Test
    fun `login word alone does not trigger external wait`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("微信", "通讯录", "搜索", "登录"),
                    ),
                history = emptyList(),
            )

        assertFalse(plan.waitingForExternal)
    }

    @Test
    fun `login plus credential cues still triggers external wait`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "打开应用完成任务",
                profileId = "generic_app",
                observation =
                    observation(
                        packageName = "com.example.app",
                        topTexts = listOf("登录", "请输入手机号", "获取验证码"),
                    ),
                history = emptyList(),
            )

        assertTrue(plan.waitingForExternal)
        assertEquals("manual_verification", plan.waitingForEvent)
    }

    @Test
    fun `resume context is injected into plan state`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "去微信给韩威发一条晚安的消息",
                profileId = "wechat_assistant",
                observation =
                    observation(
                        packageName = "com.tencent.mm",
                        topTexts = listOf("通讯录", "搜索"),
                    ),
                history = emptyList(),
                resumeContext =
                    ResumeContext(
                        active = true,
                        source = "external_wait_resolved",
                        resumeEvent = "manual_verification",
                        resumeHint = "外部处理已完成，回到联系人定位步骤。",
                        resumedSubgoalId = "find",
                    ),
            )

        assertTrue(plan.resumeContext.active)
        assertEquals("external_wait_resolved", plan.resumeContext.source)
        assertEquals("find", plan.resumeContext.resumedSubgoalId)
        assertTrue(plan.resumeContext.resumeHint.contains("联系人"))
    }

    @Test
    fun `content task builds content research skeleton from intent`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "打开b站，看下勇哥说餐饮最新一期点赞最高的评论",
                profileId = "dynamic_app_tv.danmaku.bili",
                observation =
                    observation(
                        packageName = "tv.danmaku.bili",
                        pageState = "VIDEO_DETAIL",
                        topTexts = listOf("勇哥说餐饮", "评论区", "高赞"),
                    ),
                history =
                    listOf(
                        AgentTurnRecord(
                            observationSignature = "sig0",
                            pageState = "SEARCH_RESULT",
                            action = "click(item_1)",
                            result = "已点击候选内容。",
                        ),
                    ),
            )

        assertEquals("content_research", plan.planType)
        assertEquals("inspect_information", plan.currentStage)
        assertTrue(plan.nextObjective.contains("评论区") || plan.nextObjective.contains("证据"))
    }

    @Test
    fun `intent family takes priority over generic profile id`() {
        val plan =
            MultiStepTaskEngine.buildPlanState(
                task = "去浦东机场怎么走",
                profileId = "dynamic_app_com.example.toolbox",
                observation =
                    observation(
                        packageName = "com.autonavi.minimap",
                        topTexts = listOf("搜索地点", "路线"),
                        primaryEditableId = "search_box",
                    ),
                history = emptyList(),
            )

        assertEquals("navigation", plan.planType)
        assertTrue(plan.currentStage in setOf("enter_destination", "confirm_route", "submit_query"))
    }

    private fun observation(
        packageName: String,
        topTexts: List<String>,
        pageState: String = "UNKNOWN",
        primaryEditableId: String? = null,
    ): ScreenObservation =
        ScreenObservation(
            packageName = packageName,
            pageState = pageState,
            signature = "sig",
            screenSummary = "",
            topTexts = topTexts,
            primaryEditableId = primaryEditableId,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )
}
