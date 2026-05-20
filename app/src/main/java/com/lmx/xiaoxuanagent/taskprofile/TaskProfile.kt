package com.lmx.xiaoxuanagent.taskprofile

import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.runtime.AppPageState

interface TaskProfile {
    val id: String
    val displayName: String
    val packageName: String
    val routeKeywords: List<String>
    val capabilitySummary: String

    fun detectPageState(
        foregroundPackage: String,
        root: AccessibilityNodeInfo,
    ): AppPageState

    fun routeScore(task: String): Int {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank()) return 0
        return routeKeywords.fold(0) { acc, keyword ->
            when {
                normalizedTask.equals(keyword, ignoreCase = true) -> acc + 8
                normalizedTask.contains(keyword, ignoreCase = true) -> acc + 4
                else -> acc
            }
        }
    }

    fun refineDecision(
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
    ): AgentDecision = decision

    fun summarizeTaskConstraints(task: String): String = ""
}
