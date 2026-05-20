package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicy
import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicyStore
import com.lmx.xiaoxuanagent.assistantos.AssistantDigestPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantInterruptPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellController
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellStore
import com.lmx.xiaoxuanagent.assistantos.AssistantQuietHoursSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantRoutinePolicySnapshot

object SessionPlatformOperatorOps {
    fun readBackgroundMemoryQueue(
        includeCompleted: Boolean = false,
        limit: Int = 24,
    ): List<BackgroundMemoryQueueTask> =
        BackgroundMemoryExtractor.readQueue(
            includeCompleted = includeCompleted,
            limit = limit,
        )

    fun retryBackgroundMemoryTask(
        taskId: String,
    ): BackgroundMemoryQueueTask? =
        BackgroundMemoryExtractor.retry(taskId)

    fun readProductShellSnapshot(): AssistantProductShellSnapshot =
        AssistantProductShellStore.read()

    fun refreshProductShellSnapshot(
        reason: String = "platform_refresh_product_shell",
    ): AssistantProductShellSnapshot {
        val projection = AssistantOsController.refreshProjection(reason = reason)
        return AssistantProductShellController.sync(
            assistantSnapshot = projection,
            reason = reason,
        )
    }

    fun acknowledgeProductShellTip(
        tipId: String,
        action: String,
        note: String = "",
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.acknowledgeTip(
            tipId = tipId,
            action = action,
            note = note,
        )

    fun updateProductOnboardingStep(
        stepId: String,
        action: String,
        note: String = "",
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.updateOnboardingStep(
            stepId = stepId,
            action = action,
            note = note,
        )

    fun updateRoutinePolicy(
        reducer: (AssistantRoutinePolicySnapshot) -> AssistantRoutinePolicySnapshot,
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.updateRoutinePolicy(reducer)

    fun updateDigestPolicy(
        reducer: (AssistantDigestPolicySnapshot) -> AssistantDigestPolicySnapshot,
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.updateDigestPolicy(reducer)

    fun updateQuietHours(
        reducer: (AssistantQuietHoursSnapshot) -> AssistantQuietHoursSnapshot,
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.updateQuietHours(reducer)

    fun updateInterruptPolicy(
        reducer: (AssistantInterruptPolicySnapshot) -> AssistantInterruptPolicySnapshot,
    ): AssistantProductShellSnapshot =
        AssistantProductShellStore.updateInterruptPolicy(reducer)

    fun readArtifactRetentionPolicy(): ArtifactRetentionPolicy =
        ArtifactRetentionPolicyStore.read()

    fun updateArtifactRetentionPolicy(
        policy: ArtifactRetentionPolicy,
    ): ArtifactRetentionPolicy =
        ArtifactRetentionPolicyStore.update(policy)

    fun readPlannerProviderPolicy(): PlannerProviderPolicy =
        PlannerProviderPolicyStore.read()

    fun updatePlannerProviderPolicy(
        policy: PlannerProviderPolicy,
    ): PlannerProviderPolicy =
        PlannerProviderPolicyStore.update(policy)

    fun previewArtifactRetention(
        policy: ArtifactRetentionPolicy = ArtifactRetentionPolicyStore.read(),
    ): ArtifactRetentionReport =
        ArtifactStore.previewRetention(
            policy = policy,
            keepSessionIds = collectRetainedSessionIds(),
        )

    fun runArtifactRetentionSweep(
        policy: ArtifactRetentionPolicy = ArtifactRetentionPolicyStore.read(),
    ): ArtifactRetentionReport =
        ArtifactStore.applyRetentionPolicy(
            policy = policy,
            keepSessionIds = collectRetainedSessionIds(),
        ).also { report ->
            if (report.deletedArtifacts > 0 || report.deletedSessions > 0) {
                RuntimeMetricsStore.recordPlatformEvent("artifact_retention_sweep")
            }
        }

    private fun collectRetainedSessionIds(): Set<String> =
        (ReplayStore.listSessionIds(limit = 200) + SessionResumeStore.readResumableSnapshots(200).map { it.sessionId })
            .filter { it.isNotBlank() }
            .toSet()
}
