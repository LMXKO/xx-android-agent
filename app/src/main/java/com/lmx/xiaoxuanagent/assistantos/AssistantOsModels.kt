package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusModel
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusSnapshot
import com.lmx.xiaoxuanagent.runtime.resolveAgentUiStatusModel
import com.lmx.xiaoxuanagent.runtime.toSnapshot

enum class AssistantEntrySurface {
    MAIN_APP,
    NOTIFICATION,
    SHARE_SHEET,
    OVERLAY,
    SHORTCUT,
    WIDGET,
    TILE,
    VOICE,
    SYSTEM,
}

enum class AssistantPendingActionType {
    OPEN_CONTROL_CENTER,
    RESUME_SESSION,
    RETURN_TO_TARGET_APP,
    APPROVE_SAFETY,
    REJECT_SAFETY,
    STOP_SESSION,
    ENABLE_OVERLAY_PERMISSION,
}

enum class AssistantPermissionMode {
    PROMPT_EACH_TIME,
    ASSISTED,
    HANDS_FREE,
}

enum class AssistantSafetyMode {
    STRICT,
    BALANCED,
    FOCUSED,
}

enum class AssistantFeatureFlagKey(
    val defaultEnabled: Boolean,
    val title: String,
    val description: String,
) {
    NOTIFICATION_CONTROL_CENTER(
        defaultEnabled = true,
        title = "通知控制台",
        description = "在通知里展示暂停、继续、返回目标 App 和风险审批入口。",
    ),
    SHARE_SHEET_ENTRY(
        defaultEnabled = true,
        title = "分享入口",
        description = "接收系统分享文本并直接作为新任务草稿。",
    ),
    USER_ACTION_RETURN(
        defaultEnabled = true,
        title = "显式返回目标 App",
        description = "在用户点击开始/继续后允许直接回到目标 App。",
    ),
    BOOTSTRAP_AUTO_RETURN(
        defaultEnabled = true,
        title = "恢复后自动回现场",
        description = "cold-start 恢复后自动回到目标 App 继续现场。",
    ),
    ASSISTANT_INBOX(
        defaultEnabled = true,
        title = "助手收件箱",
        description = "记录最近的入口动作、运行态切换和待处理摘要。",
    ),
    OVERLAY_SURFACE_STUB(
        defaultEnabled = false,
        title = "悬浮入口预留",
        description = "启用后在控制面中展示 overlay surface 预留位与策略摘要。",
    ),
}

enum class AssistantExperimentKey(
    val defaultEnabled: Boolean,
    val title: String,
) {
    PERSISTENT_ASSISTANT_OS_V1(true, "Persistent Assistant OS v1"),
    SAFETY_MODE_V1(true, "Safety Mode v1"),
    ENTRY_CONTROL_CENTER_V1(true, "Entry Control Center v1"),
}

data class AssistantFeatureFlagState(
    val key: AssistantFeatureFlagKey,
    val enabled: Boolean,
)

data class AssistantExperimentState(
    val key: AssistantExperimentKey,
    val enabled: Boolean,
)

data class AssistantEntryRecord(
    val surface: AssistantEntrySurface,
    val action: String,
    val summary: String,
    val createdAtMs: Long,
)

data class AssistantSurfaceState(
    val surface: AssistantEntrySurface,
    val supported: Boolean = true,
    val enabled: Boolean = true,
    val available: Boolean = true,
    val summary: String = "",
)

data class AssistantPendingAction(
    val type: AssistantPendingActionType,
    val title: String,
    val summary: String,
    val surface: AssistantEntrySurface,
    val sessionId: String = "",
    val createdAtMs: Long = 0L,
)

enum class AssistantWorkQueueItemType {
    PENDING_ACTION,
    TRIGGER,
    RESUMABLE_SESSION,
    FAILED_SESSION,
    APPROVAL_SESSION,
    WORKER_SESSION,
    PROACTIVE_TASK,
    REMOTE_REQUEST,
    HEALTH_ALERT,
}

data class AssistantWorkQueueItem(
    val id: String,
    val type: AssistantWorkQueueItemType,
    val title: String,
    val summary: String,
    val sessionId: String = "",
    val statusCode: String = "",
    val source: String = "",
    val createdAtMs: Long = 0L,
    val priority: Int = 0,
    val deadlineAtMs: Long = 0L,
    val deferCount: Int = 0,
    val recommendedCommand: String = "",
)

data class AssistantActiveSession(
    val sessionId: String = "",
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val task: String = "",
    val entrySource: String = "",
    val targetPackageName: String = "",
    val routeReason: String = "",
    val summary: String = "",
    val resumable: Boolean = false,
    val awaitingConfirmation: Boolean = false,
    val targetAppReturnEligible: Boolean = false,
    val waitingForExternal: Boolean = false,
    val turn: Int = 0,
    val updatedAtMs: Long = 0L,
) {
    val statusCode: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = resolveAgentUiStatusModel(statusSnapshot.code, statusSnapshot)
}

data class AssistantSessionCard(
    val sessionId: String,
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val task: String,
    val summary: String,
    val routeReason: String = "",
    val entrySource: String = "",
    val targetPackageName: String = "",
    val turn: Int = 0,
    val resumable: Boolean = false,
    val awaitingConfirmation: Boolean = false,
    val waitingForExternal: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val statusCode: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = resolveAgentUiStatusModel(statusSnapshot.code, statusSnapshot)
}

data class AssistantHealthSnapshot(
    val status: String = "idle",
    val summary: String = "",
    val activeSessionId: String = "",
    val staleRuntime: Boolean = false,
    val backlogCount: Int = 0,
    val resumableSessionCount: Int = 0,
    val failedSessionCount: Int = 0,
    val approvalSessionCount: Int = 0,
    val dueTriggerCount: Int = 0,
    val lastProjectionAtMs: Long = 0L,
)

data class AssistantOsSnapshot(
    val permissionMode: AssistantPermissionMode = AssistantPermissionMode.ASSISTED,
    val safetyMode: AssistantSafetyMode = AssistantSafetyMode.BALANCED,
    val featureFlags: List<AssistantFeatureFlagState> =
        AssistantFeatureFlagKey.entries.map { AssistantFeatureFlagState(it, it.defaultEnabled) },
    val experiments: List<AssistantExperimentState> =
        AssistantExperimentKey.entries.map { AssistantExperimentState(it, it.defaultEnabled) },
    val recentEntries: List<AssistantEntryRecord> = emptyList(),
    val surfaces: List<AssistantSurfaceState> =
        AssistantEntrySurface.entries.map { AssistantSurfaceState(surface = it) },
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val taskInbox: List<AssistantWorkQueueItem> = emptyList(),
    val activeSession: AssistantActiveSession = AssistantActiveSession(),
    val sessionBacklog: List<AssistantSessionCard> = emptyList(),
    val failedSessions: List<AssistantSessionCard> = emptyList(),
    val approvalSessions: List<AssistantSessionCard> = emptyList(),
    val health: AssistantHealthSnapshot = AssistantHealthSnapshot(),
    val updatedAtMs: Long = 0L,
) {
    fun isFeatureEnabled(key: AssistantFeatureFlagKey): Boolean =
        featureFlags.firstOrNull { it.key == key }?.enabled ?: key.defaultEnabled

    fun isExperimentEnabled(key: AssistantExperimentKey): Boolean =
        experiments.firstOrNull { it.key == key }?.enabled ?: key.defaultEnabled

    fun supportsSurface(surface: AssistantEntrySurface): Boolean =
        when (surface) {
            AssistantEntrySurface.OVERLAY -> isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
            AssistantEntrySurface.WIDGET -> isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)
            else -> true
        }
}
