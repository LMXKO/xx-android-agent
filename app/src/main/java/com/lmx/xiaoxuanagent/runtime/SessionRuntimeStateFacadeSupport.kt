package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.taskprofile.TaskRouteResolution

internal object SessionRuntimeStateFacadeSupport {
    fun runtimeState(): SessionRuntimeState = SessionRuntimeStore.read()

    fun consumeGrantedRiskApproval(approvalKey: String): Boolean =
        SessionRuntimeStateSupport.consumeGrantedRiskApproval(approvalKey)

    fun publishRuntimeState(
        sessionId: String,
    ) =
        SessionRuntimeStateSupport.publishRuntimeState(sessionId)

    fun buildRuntimeResultSnapshot(
        lastAction: String,
        finalResult: String,
        taskResult: TaskResultPayload?,
        lastError: String,
        hint: String,
    ): RuntimeResultSnapshot =
        SessionRuntimeStateSupport.buildRuntimeResultSnapshot(
            lastAction = lastAction,
            finalResult = finalResult,
            taskResult = taskResult,
            lastError = lastError,
            hint = hint,
        )

    fun buildRuntimeRouteSnapshot(
        routeResolution: TaskRouteResolution,
    ): RuntimeRouteSnapshot =
        SessionRuntimeStateSupport.buildRuntimeRouteSnapshot(routeResolution)

    fun persistMemoryOutcome(
        sessionId: String,
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload?,
    ) =
        SessionRuntimeStateSupport.persistMemoryOutcome(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            status = status,
            finalMessage = finalMessage,
            taskResult = taskResult,
        )
}
