package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.CrossAppMission
import com.lmx.xiaoxuanagent.agent.ResumeContext

sealed interface SessionCommand {
    data class BlockPreflight(
        val session: RuntimeSession,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class EnterRouting(
        val session: RuntimeSession,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class RoutingFailed(
        val routeSnapshot: RuntimeRouteSnapshot,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class MarkAppUnavailable(
        val routeSnapshot: RuntimeRouteSnapshot,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class RouteResolved(
        val routeSnapshot: RuntimeRouteSnapshot,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class StartExecution(
        val session: RuntimeSession,
        val resultSnapshot: RuntimeResultSnapshot,
        val reason: String,
    ) : SessionCommand

    data class RecordPlannerDecisionState(
        val actionLabel: String,
        val rawResponse: String,
        val reason: String,
    ) : SessionCommand

    data class RecordResumeDecisionState(
        val resumeDecision: RuntimeResumeDecisionSnapshot,
        val reason: String,
    ) : SessionCommand

    data class ResetSafety(
        val reason: String,
    ) : SessionCommand

    data class RejectSafetyAction(
        val reason: String,
    ) : SessionCommand

    data class ConsumeGrantedRiskApproval(
        val reason: String,
    ) : SessionCommand

    data class FinishTurn(
        val turnRecord: AgentTurnRecord,
        val disposition: SessionTurnDisposition,
        val nextPlanEligibleAtMs: Long,
        val clearLastObservationSignature: Boolean,
        val clearResumeContext: Boolean,
        val keepRunning: Boolean,
        val clearSafety: Boolean,
        val resultSnapshot: RuntimeResultSnapshot,
        val takeoverSnapshot: RuntimeTakeoverSnapshot,
        val reason: String,
    ) : SessionCommand

    data class AcquirePlanning(
        val observationSignature: String,
        val reason: String,
    ) : SessionCommand

    data class PlanningAcquired(
        val sessionId: String,
        val profileId: String,
        val targetPackageName: String,
        val task: String,
        val observationSignature: String,
        val planningSnapshot: RuntimePlanningSnapshot,
        val reason: String,
    ) : SessionCommand

    data class RecordRecoverableError(
        val clearSafety: Boolean,
        val resultSnapshot: RuntimeResultSnapshot,
        val takeoverSnapshot: RuntimeTakeoverSnapshot,
        val reason: String,
    ) : SessionCommand

    data class TerminateSession(
        val terminalReason: AgentUiTerminalReason,
        val clearSafety: Boolean,
        val resultSnapshot: RuntimeResultSnapshot,
        val takeoverSnapshot: RuntimeTakeoverSnapshot,
        val reason: String,
    ) : SessionCommand

    data class EnterTakeover(
        val takeoverReason: AgentUiTakeoverReason,
        val resultSnapshot: RuntimeResultSnapshot,
        val takeoverSnapshot: RuntimeTakeoverSnapshot,
        val externalWaitState: RuntimeExternalWaitState? = null,
        val planningSnapshot: RuntimePlanningSnapshot? = null,
        val safetyState: RuntimeSafetyState = RuntimeSafetyState(),
        val reason: String,
    ) : SessionCommand

    data class ResumeExecution(
        val resumeContext: ResumeContext,
        val resultSnapshot: RuntimeResultSnapshot,
        val takeoverSnapshot: RuntimeTakeoverSnapshot,
        val nextPlanEligibleAtMs: Long = 0L,
        val planningSnapshot: RuntimePlanningSnapshot? = null,
        val safetyState: RuntimeSafetyState = RuntimeSafetyState(),
        val reason: String,
    ) : SessionCommand

    data class AdoptResumeContext(
        val resumeContext: ResumeContext,
        val reason: String,
    ) : SessionCommand

    /** 跨 App mission 推进到下一腿：把活跃腿的 profileId/targetPackageName/task 镜像进 session。 */
    data class AdvanceMissionLeg(
        val profileId: String,
        val targetPackageName: String,
        val task: String,
        val mission: CrossAppMission,
        val reason: String,
    ) : SessionCommand
}
