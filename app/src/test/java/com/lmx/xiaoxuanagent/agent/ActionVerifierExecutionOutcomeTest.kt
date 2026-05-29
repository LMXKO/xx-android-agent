package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionVerifierExecutionOutcomeTest {
    private fun obs(
        pkg: String,
        sig: String,
    ): ScreenObservation =
        ScreenObservation(
            packageName = pkg,
            pageState = "UNKNOWN",
            signature = sig,
            screenSummary = "",
            topTexts = emptyList(),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )

    private fun indexed(o: ScreenObservation): IndexedScreenObservation =
        IndexedScreenObservation(observation = o, nodesById = emptyMap())

    private fun verifyCopyText(executionResult: AgentExecutionResult?) =
        ActionVerifier.verify(
            action = AgentAction.CopyText("e1"),
            before = obs("com.x", "s0"),
            afterIndexedObservation = indexed(obs("com.x", "s1")),
            executionResult = executionResult,
        )

    @Test
    fun `off-screen action is unverified on error receipt`() {
        val res = verifyCopyText(AgentExecutionResult(message = "失败", keepRunning = true, toolRuntimeState = "error"))
        assertFalse(res.verified)
    }

    @Test
    fun `off-screen action is unverified on permission receipt`() {
        val res =
            verifyCopyText(
                AgentExecutionResult(
                    message = "sms_permission_required",
                    keepRunning = false,
                    toolRuntimeSummary = "receipt=sms_permission_required",
                ),
            )
        assertFalse(res.verified)
    }

    @Test
    fun `off-screen action is verified on running receipt`() {
        val res = verifyCopyText(AgentExecutionResult(message = "ok", keepRunning = true, toolRuntimeState = "running"))
        assertTrue(res.verified)
    }

    @Test
    fun `off-screen action stays verified when execution result is absent`() {
        // backward compatibility: no receipt -> still verified
        assertTrue(verifyCopyText(null).verified)
    }

    @Test
    fun `global action verified only when screen actually changed`() {
        val changed =
            ActionVerifier.verify(
                action = AgentAction.OpenSettings(),
                before = obs("com.app", "home"),
                afterIndexedObservation = indexed(obs("com.android.settings", "settings")),
            )
        assertTrue(changed.verified)

        val unchanged =
            ActionVerifier.verify(
                action = AgentAction.OpenSettings(),
                before = obs("com.app", "same"),
                afterIndexedObservation = indexed(obs("com.app", "same")),
            )
        assertFalse(unchanged.verified)
        assertTrue(unchanged.shouldImmediateReplan)
    }
}
