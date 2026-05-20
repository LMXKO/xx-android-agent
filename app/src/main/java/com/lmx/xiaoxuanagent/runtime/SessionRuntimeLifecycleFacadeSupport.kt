package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation

internal object SessionRuntimeLifecycleFacadeSupport {
    fun installBridge(sessionBridge: SessionBridge) {
        SessionRuntimeBridgeSupport.install(sessionBridge)
    }

    fun handleMaxTurnsReached() =
        SessionRuntimeLifecycleSupport.handleMaxTurnsReached()

    suspend fun startAgent(
        sessionId: String,
        task: String,
        entrySource: String,
    ): Boolean =
        SessionRuntimeLifecycleSupport.startAgent(
            sessionId = sessionId,
            task = task,
            entrySource = entrySource,
        )

    fun pauseAgent() =
        SessionRuntimeLifecycleSupport.pauseAgent()

    fun continueAgent(
        userCorrection: String,
    ) =
        SessionRuntimeLifecycleSupport.continueAgent(userCorrection)

    fun resumeAgent(
        userCorrection: String,
    ) =
        SessionRuntimeLifecycleSupport.resumeAgent(userCorrection)

    fun requestSafetyConfirmation(
        confirmation: PendingSafetyConfirmation,
    ) =
        SessionRuntimeLifecycleSupport.requestSafetyConfirmation(confirmation)

    fun approvePendingSafetyConfirmation(userCorrection: String): PendingSafetyConfirmation? =
        SessionRuntimeLifecycleSupport.approvePendingSafetyConfirmation(userCorrection)

    fun rejectPendingSafetyConfirmation() =
        SessionRuntimeLifecycleSupport.rejectPendingSafetyConfirmation()

    fun stopAgent(reason: String) =
        SessionRuntimeLifecycleSupport.stopAgent(reason)
}
