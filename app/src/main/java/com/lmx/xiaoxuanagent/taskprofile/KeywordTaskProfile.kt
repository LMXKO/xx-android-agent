package com.lmx.xiaoxuanagent.taskprofile

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.GenericTaskDecisionPolicy
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.runtime.AppPageState

class KeywordTaskProfile(
    override val id: String,
    override val displayName: String,
    override val packageName: String,
    override val routeKeywords: List<String>,
    override val capabilitySummary: String,
) : TaskProfile {
    override fun detectPageState(
        foregroundPackage: String,
        root: AccessibilityNodeInfo,
    ): AppPageState = AppPageState.Unknown

    override fun refineDecision(
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
    ): AgentDecision = GenericTaskDecisionPolicy.refineDecision(task, observation, decision)

    override fun summarizeTaskConstraints(task: String): String =
        GenericTaskDecisionPolicy.summarizeConstraints(task)
}
