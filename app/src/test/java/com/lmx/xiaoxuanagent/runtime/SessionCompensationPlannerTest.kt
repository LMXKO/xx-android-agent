package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCompensationPlannerTest {
    @Test
    fun `derive plan restores previous text for set_text`() {
        val observation =
            ScreenObservation(
                packageName = "com.example.chat",
                pageState = "CHAT",
                signature = "sig_chat",
                screenSummary = "chat",
                topTexts = listOf("原始内容"),
                primaryEditableId = "input_1",
                focusedElementId = "input_1",
                defaultScrollableId = "list_1",
                primaryInterruptActionId = null,
                interruptiveHints = emptyList(),
                elements =
                    listOf(
                        UiElementObservation(
                            id = "input_1",
                            text = "原始内容",
                            viewId = "input",
                            className = "EditText",
                            bounds = "",
                            clickable = true,
                            editable = true,
                            scrollable = false,
                            enabled = true,
                            focused = true,
                            selected = false,
                        ),
                    ),
            )

        val plan =
            SessionCompensationPlanner.derivePlan(
                sessionId = "session_1",
                turn = 3,
                task = "发送消息",
                beforeObservation = observation,
                action = AgentAction.SetText("input_1", "新的消息"),
            ) ?: error("plan should exist")

        val step = plan.steps.first { it.stepType == "restore_input" }
        assertEquals("restore_text_input_1", step.stepId)
        assertTrue(step.action is AgentAction.SetText)
        assertEquals("原始内容", (step.action as AgentAction.SetText).text)
    }

    @Test
    fun `derive plan reverses scroll direction`() {
        val observation =
            ScreenObservation(
                packageName = "com.example.feed",
                pageState = "FEED",
                signature = "sig_feed",
                screenSummary = "feed",
                topTexts = emptyList(),
                primaryEditableId = null,
                focusedElementId = null,
                defaultScrollableId = "list_1",
                primaryInterruptActionId = null,
                interruptiveHints = emptyList(),
                elements = emptyList(),
            )

        val plan =
            SessionCompensationPlanner.derivePlan(
                sessionId = "session_2",
                turn = 5,
                task = "继续浏览",
                beforeObservation = observation,
                action = AgentAction.ScrollForMore("down"),
            ) ?: error("plan should exist")

        val action = plan.steps.first { it.stepType == "reverse_scroll" }.action as AgentAction.Scroll
        assertEquals("list_1", action.elementId)
        assertEquals("up", action.direction)
    }
}
