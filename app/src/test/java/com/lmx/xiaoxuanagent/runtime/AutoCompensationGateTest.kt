package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompensationGateTest {
    @Test
    fun `side-effecting actions are detected`() {
        assertTrue(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.SetText("e1", "hello")))
        assertTrue(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.Click("e1")))
        assertTrue(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.SubmitPrimaryAction()))
        assertTrue(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.PopulatePrimaryInput("x")))
    }

    @Test
    fun `non side-effecting actions are excluded`() {
        assertFalse(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.Wait))
        assertFalse(SessionRuntimeTurnOrchestrator.debugIsSideEffectingAction(AgentAction.Finish("done")))
    }
}
