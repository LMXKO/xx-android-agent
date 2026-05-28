package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionResumeStore

internal object AssistantOsStrategyPolicy {
    fun recordProductShellSync(
        snapshot: AssistantProductShellSnapshot,
        reason: String,
    ) {
        AssistantAnalyticsStore.logEvent(
            name = "product_shell_sync",
            source = "product_shell",
            metadata =
                mapOf(
                    "reason" to reason,
                    "tips_visible" to snapshot.tips.size.toString(),
                    "onboarding_status" to snapshot.onboarding.status,
                    "diagnostics" to snapshot.diagnostics.status,
                ),
        )
    }

    fun recordAssistantEntry(
        surface: AssistantEntrySurface,
        action: String,
        summary: String,
    ) {
        AssistantAnalyticsStore.logEvent(
            name = "assistant_entry",
            source = "assistant_os",
            metadata =
                mapOf(
                    "surface" to surface.name.lowercase(),
                    "action" to action,
                    "summary" to summary.take(96),
                ),
        )
    }

    fun selectPreferredRestoreSessionIds(): Set<String> {
        val historyPreferred =
            SessionPlatformFacade
                .readSessionHistory(limit = 8)
                .entries
                .filter { it.resumable || it.pendingSafety }
                .sortedByDescending { it.updatedAtMs }
                .take(3)
                .map { it.sessionId }
        // 跨 App mission 还有未跑完的腿 → 优先恢复，确保"长时间"承诺真正成立。
        val missionInflight =
            SessionResumeStore
                .readResumableSnapshots(limit = 8)
                .asSequence()
                .filter { snapshot ->
                    snapshot.mission?.let { mission ->
                        mission.activeLegIndex in 0 until mission.legs.size
                    } == true
                }
                .sortedByDescending { it.updatedAtMs }
                .map { it.sessionId }
                .toList()
        return (missionInflight + historyPreferred).toSet()
    }
}
