package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

data class RuntimeExternalWaitState(
    val event: String = "",
    val reason: String = "",
    val subgoalId: String = "",
    val signature: String = "",
    val enteredAtMs: Long = 0L,
)

enum class RuntimeResumeDecisionChannel {
    NONE,
    ENTRY_ALIGNMENT,
    CONTINUATION,
}

enum class RuntimeResumeDecisionPhase {
    NONE,
    PRE_PLAN,
    RECOVER,
}

enum class RuntimeResumePolicy(
    val wireName: String,
) {
    NONE(""),
    SET_ALIGNMENT_TARGET("set_alignment_target"),
    FOCUS_PRIMARY_EDITABLE("focus_primary_editable"),
    CLICK_ALIGNMENT_ENTRY("click_alignment_entry"),
    RECOVER_SET_ALIGNMENT_TARGET("recover_set_alignment_target"),
    RECOVER_FOCUS_PRIMARY_EDITABLE("recover_focus_primary_editable"),
    RECOVER_CLICK_ALIGNMENT_ENTRY("recover_click_alignment_entry"),
    RECOVER_ALIGNMENT_ACTION("recover_alignment_action"),
    SUBMIT_QUERY_PRESS_ENTER("submit_query_press_enter"),
    SUBMIT_QUERY_FOCUS_INPUT("submit_query_focus_input"),
    SUBMIT_QUERY_CLICK_ACTION("submit_query_click_action"),
    SUBMIT_QUERY_ACTION("submit_query_action"),
    RECOVER_SUBMIT_QUERY_PRESS_ENTER("recover_submit_query_press_enter"),
    RECOVER_SUBMIT_QUERY_FOCUS_INPUT("recover_submit_query_focus_input"),
    RECOVER_SUBMIT_QUERY_CLICK_ACTION("recover_submit_query_click_action"),
    RECOVER_SUBMIT_QUERY_ACTION("recover_submit_query_action"),
    CONFIRM_ROUTE_CLICK_ACTION("confirm_route_click_action"),
    CONFIRM_ROUTE_ACTION("confirm_route_action"),
    RECOVER_CONFIRM_ROUTE_CLICK_ACTION("recover_confirm_route_click_action"),
    RECOVER_CONFIRM_ROUTE_ACTION("recover_confirm_route_action"),
    CONFIRM_SEND_RESTORE_BODY("confirm_send_restore_body"),
    CONFIRM_SEND_PRESS_ENTER("confirm_send_press_enter"),
    CONFIRM_SEND_FOCUS_INPUT("confirm_send_focus_input"),
    CONFIRM_SEND_CLICK_ACTION("confirm_send_click_action"),
    CONFIRM_SEND_ACTION("confirm_send_action"),
    RECOVER_CONFIRM_SEND_RESTORE_BODY("recover_confirm_send_restore_body"),
    RECOVER_CONFIRM_SEND_PRESS_ENTER("recover_confirm_send_press_enter"),
    RECOVER_CONFIRM_SEND_FOCUS_INPUT("recover_confirm_send_focus_input"),
    RECOVER_CONFIRM_SEND_CLICK_ACTION("recover_confirm_send_click_action"),
    RECOVER_CONFIRM_SEND_ACTION("recover_confirm_send_action"),
    ;

    companion object {
        fun fromWireName(
            wireName: String,
        ): RuntimeResumePolicy =
            entries.firstOrNull { it.wireName == wireName } ?: NONE
    }
}

data class RuntimeResumeDecisionSnapshot(
    val channel: RuntimeResumeDecisionChannel = RuntimeResumeDecisionChannel.NONE,
    val phase: RuntimeResumeDecisionPhase = RuntimeResumeDecisionPhase.NONE,
    val stage: String = "",
    val actionLabel: String = "",
    val policy: RuntimeResumePolicy = RuntimeResumePolicy.NONE,
    val recoveryCategory: String = "",
) {
    val active: Boolean
        get() =
            channel != RuntimeResumeDecisionChannel.NONE &&
                phase != RuntimeResumeDecisionPhase.NONE &&
                stage.isNotBlank() &&
                policy != RuntimeResumePolicy.NONE
}

data class RuntimePlanningSnapshot(
    val planType: String = "",
    val currentPlanStage: String = "",
    val currentSubgoalId: String = "",
    val nextObjective: String = "",
    val activeSkillTitles: List<String> = emptyList(),
    val lastPlannerAction: String = "",
    val lastPlannerRaw: String = "",
    val lastResumeDecision: RuntimeResumeDecisionSnapshot = RuntimeResumeDecisionSnapshot(),
)

data class RuntimeRouteSnapshot(
    val activeProfileId: String = "",
    val activeTargetPackage: String = "",
    val reason: String = "",
    val policyTag: String = "",
    val modelChoiceProfileId: String = "",
    val selectedProfileId: String = "",
    val fallbackReason: String = "",
    val memoryHints: List<String> = emptyList(),
    val modelRaw: String = "",
)

data class RuntimeResultSnapshot(
    val lastAction: String = "",
    val lastResult: String = "",
    val intentType: String = "",
    val title: String = "",
    val summary: String = "",
    val highlights: List<String> = emptyList(),
    val lastError: String = "",
    val hint: String = "",
)

data class RuntimeTakeoverSnapshot(
    val latestTakeoverType: String = "",
    val latestTakeoverReason: String = "",
    val latestTakeoverResumeHint: String = "",
    val latestTakeoverCorrection: String = "",
)

data class RuntimeSession(
    val running: Boolean = false,
    val planning: Boolean = false,
    val paused: Boolean = false,
    val statusSnapshot: AgentUiStatusSnapshot = AgentUiStatus.resolve(AgentUiStatus.IDLE).toSnapshot(),
    val sessionId: String = "",
    val entrySource: String = "app",
    val profileId: String = TaskRegistry.defaultProfile.id,
    val targetPackageName: String = TaskRegistry.defaultProfile.packageName,
    val task: String = "",
    val turns: Int = 0,
    val lastObservationSignature: String = "",
    val nextPlanEligibleAtMs: Long = 0L,
    val history: List<AgentTurnRecord> = emptyList(),
    val recentFingerprints: List<String> = emptyList(),
    val externalWaitState: RuntimeExternalWaitState? = null,
    val resumeContext: ResumeContext = ResumeContext(),
    val planningSnapshot: RuntimePlanningSnapshot = RuntimePlanningSnapshot(),
) {
    val status: String
        get() = statusSnapshot.code

    val statusModel: AgentUiStatusModel
        get() = statusSnapshot.resolveModel()
}

enum class RuntimeSafetyMode {
    IDLE,
    AWAITING_CONFIRMATION,
    APPROVED_ONCE,
}

data class RuntimeSafetyState(
    val mode: RuntimeSafetyMode = RuntimeSafetyMode.IDLE,
    val pendingConfirmation: PendingSafetyConfirmation? = null,
    val grantedApprovalKey: String? = null,
) {
    val awaitingConfirmation: Boolean
        get() = mode == RuntimeSafetyMode.AWAITING_CONFIRMATION && pendingConfirmation != null

    fun grantedApprovalMatches(approvalKey: String): Boolean =
        mode == RuntimeSafetyMode.APPROVED_ONCE &&
            approvalKey.isNotBlank() &&
            grantedApprovalKey == approvalKey
}

data class RuntimeSessionSemantics(
    val statusModel: AgentUiStatusModel,
    val mode: AgentUiMode,
    val resumable: Boolean,
    val targetAppReturnEligible: Boolean,
    val takeoverLike: Boolean,
    val terminal: Boolean,
)

data class SessionRuntimeState(
    val session: RuntimeSession = RuntimeSession(),
    val safety: RuntimeSafetyState = RuntimeSafetyState(),
    val routeSnapshot: RuntimeRouteSnapshot? = null,
    val resultSnapshot: RuntimeResultSnapshot? = null,
    val takeoverSnapshot: RuntimeTakeoverSnapshot? = null,
    val lastTransition: String = "",
    val updatedAtMs: Long = 0L,
)

sealed interface SessionTurnDisposition {
    data class ContinueExecution(
        val phase: AgentUiExecutionPhase = AgentUiExecutionPhase.RUNNING,
    ) : SessionTurnDisposition

    data class Terminate(
        val reason: AgentUiTerminalReason,
    ) : SessionTurnDisposition
}

fun RuntimeSession.resolveSemantics(
    safety: RuntimeSafetyState = RuntimeSafetyState(),
): RuntimeSessionSemantics {
    val effectiveStatusModel =
        if (safety.awaitingConfirmation) {
            AgentUiStatus.modelForTakeoverReason(AgentUiTakeoverReason.AWAITING_CONFIRMATION)
        } else {
            statusModel
        }
    return RuntimeSessionSemantics(
        statusModel = effectiveStatusModel,
        mode = effectiveStatusModel.toMode(),
        resumable =
            sessionId.isNotBlank() &&
                (
                    effectiveStatusModel.category == AgentUiStatusCategory.ACTIVE_EXECUTION ||
                        effectiveStatusModel.category == AgentUiStatusCategory.TAKEOVER ||
                        effectiveStatusModel.blockedReason == AgentUiBlockedReason.ROUTING
                ),
        targetAppReturnEligible =
            targetPackageName.isNotBlank() &&
                (
                    running ||
                        effectiveStatusModel.category == AgentUiStatusCategory.ACTIVE_EXECUTION ||
                        effectiveStatusModel.category == AgentUiStatusCategory.TAKEOVER
                ),
        takeoverLike = effectiveStatusModel.category == AgentUiStatusCategory.TAKEOVER,
        terminal = effectiveStatusModel.category == AgentUiStatusCategory.TERMINAL,
    )
}

fun SessionRuntimeState.resolveSessionSemantics(): RuntimeSessionSemantics =
    session.resolveSemantics(safety)

fun RuntimeSession.withStatusModel(
    statusModel: AgentUiStatusModel,
): RuntimeSession =
    copy(
        statusSnapshot = statusModel.toSnapshot(),
    )

fun SessionTurnDisposition.toStatusModel(): AgentUiStatusModel =
    when (this) {
        is SessionTurnDisposition.ContinueExecution -> AgentUiStatus.modelForExecutionPhase(phase)
        is SessionTurnDisposition.Terminate -> AgentUiStatus.modelForTerminalReason(reason)
    }
