package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSemanticActionSupportTest {
    @Test
    fun `semantic resolver prefers target candidate over meta control`() {
        val observation =
            ScreenObservation(
                packageName = "com.tencent.mm",
                pageState = "LIST",
                signature = "sig-1",
                screenSummary = "conversation list",
                topTexts = listOf("微信"),
                primaryEditableId = null,
                focusedElementId = null,
                defaultScrollableId = "list_1",
                primaryInterruptActionId = null,
                interruptiveHints = emptyList(),
                elements =
                    listOf(
                        element(id = "meta", text = "更多", clickable = true),
                        element(id = "target", text = "韩威", clickable = true),
                        element(id = "group", text = "韩威粉丝群", clickable = true),
                    ),
            )

        val resolved =
            AgentSemanticActionResolver.resolveOpenBestCandidate(
                indexedObservation = indexedObservation(observation),
                action = AgentAction.OpenBestCandidate(targetText = "韩威", roleHint = "conversation"),
            )

        assertEquals(AgentAction.Click("target"), resolved)
    }

    @Test
    fun `semantic resolver uses primary interrupt id first`() {
        val observation =
            ScreenObservation(
                packageName = "com.test",
                pageState = "POPUP",
                signature = "sig-2",
                screenSummary = "popup",
                topTexts = listOf("权限提示"),
                primaryEditableId = null,
                focusedElementId = null,
                defaultScrollableId = null,
                primaryInterruptActionId = "dismiss_1",
                interruptiveHints = emptyList(),
                elements = listOf(element(id = "dismiss_1", text = "稍后", clickable = true)),
            )

        val resolved =
            AgentSemanticActionResolver.resolveDismissInterrupt(
                indexedObservation = indexedObservation(observation),
                action = AgentAction.DismissInterrupt(),
            )

        assertEquals(AgentAction.Click("dismiss_1"), resolved)
    }

    @Test
    fun `skill parser understands semantic labels`() {
        val action =
            SkillActionParser.parseActionLabel(
                "open_best_candidate(target=韩威;role=conversation)",
            )

        assertTrue(action is AgentAction.OpenBestCandidate)
        assertEquals("韩威", (action as AgentAction.OpenBestCandidate).targetText)
        assertEquals("conversation", action.roleHint)
    }

    private fun element(
        id: String,
        text: String,
        clickable: Boolean,
    ): UiElementObservation =
        UiElementObservation(
            id = id,
            text = text,
            viewId = "id/$id",
            className = "android.widget.TextView",
            bounds = "[0,0][100,100]",
            clickable = clickable,
            editable = false,
            scrollable = false,
            enabled = true,
            focused = false,
            selected = false,
        )

    private fun indexedObservation(
        observation: ScreenObservation,
    ): IndexedScreenObservation =
        IndexedScreenObservation(
            observation = observation,
            nodesById = emptyMap(),
        )
}
