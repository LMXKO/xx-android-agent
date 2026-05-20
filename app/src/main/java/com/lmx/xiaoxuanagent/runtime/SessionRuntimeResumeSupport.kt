package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.skills.isResumeEntryAlignmentSource
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentPrePlanDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentRecoveryDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeAlignmentTarget
import com.lmx.xiaoxuanagent.skills.resolveResumeContinuationPrePlanDirective
import com.lmx.xiaoxuanagent.skills.resolveResumeContinuationRecoveryDirective
import com.lmx.xiaoxuanagent.skills.resumeAlignmentStages
import com.lmx.xiaoxuanagent.skills.resumeContinuationStages
import com.lmx.xiaoxuanagent.skills.resumeEntryStageLabel
import org.json.JSONObject

internal object SessionRuntimeResumeSupport {
    fun resolveRuntimeResumeIntervention(
        planningContext: AgentPlanningContext,
    ): RuntimeResumeDecisionDirective? {
        val taskPlanState = planningContext.taskPlanState
        val resumeContext = taskPlanState.resumeContext
        val stage = taskPlanState.currentStage
        if (!isResumeEntryAlignmentSource(resumeContext) || resumeContext.userCorrection.isNotBlank()) {
            return null
        }
        return when {
            stage in resumeAlignmentStages -> {
                val directive =
                    resolveResumeAlignmentPrePlanDirective(
                        task = planningContext.task,
                        stage = stage,
                        observation = planningContext.observation,
                    ) ?: return null
                val target = resolveResumeAlignmentTarget(planningContext.task, stage)
                buildRuntimeResumeDecisionDirective(
                    channel = RuntimeResumeDecisionChannel.ENTRY_ALIGNMENT,
                    phase = RuntimeResumeDecisionPhase.PRE_PLAN,
                    stage = stage,
                    action = directive.action,
                    policy = directive.policy,
                    reason =
                        when (directive.policy) {
                            RuntimeResumePolicy.SET_ALIGNMENT_TARGET ->
                                "运行时恢复接管：恢复后仍停在 ${resumeEntryStageLabel(stage)}，先把目标 ${target.ifBlank { "-" }} 重新对齐到主要输入框。"
                            RuntimeResumePolicy.FOCUS_PRIMARY_EDITABLE ->
                                "运行时恢复接管：恢复后已看到目标 ${target.ifBlank { "-" }}，先重新聚焦主要输入框对齐入口。"
                            else ->
                                "运行时恢复接管：恢复后仍停在 ${resumeEntryStageLabel(stage)}，先执行 ${directive.action.label} 回到继续入口。"
                        },
                    extraJsonFields = mapOf("target" to target.take(80)),
                )
            }

            stage in resumeContinuationStages -> {
                val directive =
                    resolveResumeContinuationPrePlanDirective(
                        stage = stage,
                        task = planningContext.task,
                        observation = planningContext.observation,
                        history = planningContext.history,
                    ) ?: return null
                buildRuntimeResumeDecisionDirective(
                    channel = RuntimeResumeDecisionChannel.CONTINUATION,
                    phase = RuntimeResumeDecisionPhase.PRE_PLAN,
                    stage = stage,
                    action = directive.action,
                    policy = directive.policy,
                    reason =
                        "运行时恢复接管：恢复后已回到 ${resumeEntryStageLabel(stage)}(stage=$stage)，" +
                            "先执行 ${directive.action.label} 推进当前阶段。",
                )
            }

            else -> null
        }
    }

    fun resolveRuntimeResumeRecoveryIntervention(
        planningContext: AgentPlanningContext,
        diagnosis: RecoveryDiagnosis,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionDirective? {
        if (diagnosis.category !in setOf(RecoveryCategory.NO_PROGRESS, RecoveryCategory.EMPTY_OBSERVATION)) {
            return null
        }
        val taskPlanState = planningContext.taskPlanState
        val resumeContext = taskPlanState.resumeContext
        val stage = taskPlanState.currentStage
        if (!isResumeEntryAlignmentSource(resumeContext) || resumeContext.userCorrection.isNotBlank()) {
            return null
        }
        return when {
            stage in resumeAlignmentStages -> {
                val directive =
                    resolveResumeAlignmentRecoveryDirective(
                        task = planningContext.task,
                        stage = stage,
                        observation = observation,
                        history = planningContext.history,
                    ) ?: return null
                buildRuntimeResumeDecisionDirective(
                    channel = RuntimeResumeDecisionChannel.ENTRY_ALIGNMENT,
                    phase = RuntimeResumeDecisionPhase.RECOVER,
                    stage = stage,
                    action = directive.action,
                    policy = directive.policy,
                    recoveryCategory = diagnosis.category.name.lowercase(),
                    reason =
                        "运行时恢复接管：恢复入口对齐后仍未稳定回到 ${resumeEntryStageLabel(stage)}，" +
                            "先执行 ${directive.action.label} 做一次更保守的入口纠偏。",
                )
            }

            stage in resumeContinuationStages -> {
                val directive =
                    resolveResumeContinuationRecoveryDirective(
                        stage = stage,
                        task = planningContext.task,
                        observation = observation,
                        history = planningContext.history,
                    ) ?: return null
                buildRuntimeResumeDecisionDirective(
                    channel = RuntimeResumeDecisionChannel.CONTINUATION,
                    phase = RuntimeResumeDecisionPhase.RECOVER,
                    stage = stage,
                    action = directive.action,
                    policy = directive.policy,
                    recoveryCategory = diagnosis.category.name.lowercase(),
                    reason =
                        "运行时恢复接管：恢复继续阶段 $stage 首次推进未稳定生效(stage=$stage)，" +
                            "先改用 ${directive.action.label} 做一次更保守的继续动作。",
                )
            }

            else -> null
        }
    }

    fun buildRuntimeResumeDecisionDirective(
        channel: RuntimeResumeDecisionChannel,
        phase: RuntimeResumeDecisionPhase,
        stage: String,
        action: AgentAction,
        policy: RuntimeResumePolicy,
        reason: String,
        recoveryCategory: String = "",
        extraJsonFields: Map<String, String> = emptyMap(),
    ): RuntimeResumeDecisionDirective {
        val snapshot =
            RuntimeResumeDecisionSnapshot(
                channel = channel,
                phase = phase,
                stage = stage,
                actionLabel = action.label,
                policy = policy,
                recoveryCategory = recoveryCategory,
            )
        val runtimeId =
            when (channel) {
                RuntimeResumeDecisionChannel.ENTRY_ALIGNMENT -> "resume_entry_alignment"
                RuntimeResumeDecisionChannel.CONTINUATION -> "resume_continuation"
                RuntimeResumeDecisionChannel.NONE -> "resume"
            }
        val rawResponse =
            JSONObject().apply {
                put("runtime", runtimeId)
                put("phase", phase.name.lowercase())
                put("stage", stage)
                if (recoveryCategory.isNotBlank()) {
                    put("category", recoveryCategory)
                }
                put("action", action.label)
                put("policy", policy.wireName)
                extraJsonFields.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        put(key, value)
                    }
                }
            }.toString()
        return RuntimeResumeDecisionDirective(
            decision =
                AgentDecision(
                    action = action,
                    reason = reason,
                    rawResponse = rawResponse,
                ),
            snapshot = snapshot,
        )
    }

    fun publishResumeContextApplied(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ) {
        val resumeContext = taskPlanState.resumeContext
        if (!resumeContext.active || resumeContext.resumeAttempt > 0) {
            return
        }
        val directivePreview = previewRuntimeResumeDirective(session, taskPlanState, observation)
        if (session.sessionId.isNotBlank()) {
            ReplayStore.appendEvent(
                session.sessionId,
                "resume_context_applied",
                "source=${resumeContext.source.ifBlank { "-" }} event=${resumeContext.resumeEvent.ifBlank { "-" }} subgoal=${resumeContext.resumedSubgoalId.ifBlank { taskPlanState.currentSubgoalId.ifBlank { "-" } }}",
                attributes =
                    mapOf(
                        "resume_source" to resumeContext.source,
                        "resume_event" to resumeContext.resumeEvent,
                        "resume_subgoal_id" to resumeContext.resumedSubgoalId.ifBlank { taskPlanState.currentSubgoalId },
                        "waiting_for_external" to taskPlanState.waitingForExternal,
                        "waiting_event" to taskPlanState.waitingForEvent,
                        "user_correction" to resumeContext.userCorrection,
                        "observation_signature" to observation.signature,
                        "resume_stage" to directivePreview?.stage.orEmpty(),
                        "resume_channel" to directivePreview?.channel?.name?.lowercase().orEmpty(),
                        "resume_policy" to directivePreview?.policy?.wireName.orEmpty(),
                        "resume_action" to directivePreview?.actionLabel.orEmpty(),
                    ),
            )
        }
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.LogMessage(
                "恢复上下文已接入 planning: source=${resumeContext.source.ifBlank { "-" }}, " +
                    "event=${resumeContext.resumeEvent.ifBlank { "-" }}, " +
                    "subgoal=${resumeContext.resumedSubgoalId.ifBlank { taskPlanState.currentSubgoalId.ifBlank { "-" } }}" +
                    resumeContext.userCorrection.takeIf { it.isNotBlank() }?.let { ", correction=$it" }.orEmpty() +
                    directivePreview?.let { ", next=${it.channel.name.lowercase()}:${it.policy.wireName}:${it.actionLabel}" }.orEmpty(),
            ),
        )
    }

    fun previewRuntimeResumeDirective(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionSnapshot? {
        val resumeContext = taskPlanState.resumeContext
        if (!isResumeEntryAlignmentSource(resumeContext) || resumeContext.userCorrection.isNotBlank()) {
            return null
        }
        val stage = taskPlanState.currentStage
        return when {
            stage in resumeAlignmentStages ->
                resolveResumeAlignmentPrePlanDirective(
                    task = session.task,
                    stage = stage,
                    observation = observation,
                )?.let { directive ->
                    RuntimeResumeDecisionSnapshot(
                        channel = RuntimeResumeDecisionChannel.ENTRY_ALIGNMENT,
                        phase = RuntimeResumeDecisionPhase.PRE_PLAN,
                        stage = stage,
                        policy = directive.policy,
                        actionLabel = directive.action.label,
                    )
                }

            stage in resumeContinuationStages ->
                resolveResumeContinuationPrePlanDirective(
                    stage = stage,
                    task = session.task,
                    observation = observation,
                    history = session.history,
                )?.let { directive ->
                    RuntimeResumeDecisionSnapshot(
                        channel = RuntimeResumeDecisionChannel.CONTINUATION,
                        phase = RuntimeResumeDecisionPhase.PRE_PLAN,
                        stage = stage,
                        policy = directive.policy,
                        actionLabel = directive.action.label,
                    )
                }

            else -> null
        }
    }

    fun shouldApplyResumeGuard(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): Boolean {
        val resumeContext = session.resumeContext
        if (!resumeContext.active || !taskPlanState.waitingForExternal) {
            return false
        }
        if (resumeContext.resumeAttempt >= 1) {
            return false
        }
        val resumeEvent = resumeContext.resumeEvent.ifBlank { session.externalWaitState?.event.orEmpty() }
        if (resumeEvent.isBlank()) {
            return false
        }
        return taskPlanState.waitingForEvent == resumeEvent
    }

    fun resolveResumeWaitPolicy(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): ResumeWaitPolicyDecision {
        if (!taskPlanState.resumeContext.active || !taskPlanState.waitingForExternal) {
            return ResumeWaitPolicyDecision.Proceed
        }
        val resumeContext = taskPlanState.resumeContext
        val waitingEvent =
            taskPlanState.waitingForEvent.ifBlank {
                resumeContext.resumeEvent.ifBlank { session.externalWaitState?.event.orEmpty() }
            }
        val subgoalId = taskPlanState.currentSubgoalId.ifBlank { resumeContext.resumedSubgoalId.ifBlank { "-" } }
        val baseAttributes =
            mapOf(
                "resume_source" to resumeContext.source,
                "resume_event" to resumeContext.resumeEvent,
                "waiting_event" to waitingEvent,
                "subgoal_id" to subgoalId,
                "resume_attempt" to resumeContext.resumeAttempt,
                "user_correction" to resumeContext.userCorrection,
            )
        if (!shouldApplyResumeGuard(session, taskPlanState)) {
            return ResumeWaitPolicyDecision.Proceed
        }
        val waitingEventPolicy =
            resolveResumeWaitingEventPolicy(
                waitingEvent = waitingEvent,
                userCorrection = resumeContext.userCorrection,
                fallbackReason = taskPlanState.suspendReason,
            )
        return when {
            waitingEvent == "page_settling" -> {
                val guardedResumeContext =
                    resumeContext.copy(
                        resumeAttempt = resumeContext.resumeAttempt + 1,
                        resumeHint =
                            resumeContext.resumeHint.ifBlank {
                                "恢复后页面仍在稳定中，先尝试一次最小纠偏，再决定是否重新挂起。"
                            },
                    )
                ResumeWaitPolicyDecision.ApplyGuard(
                    guardedTaskPlanState =
                        taskPlanState.copy(
                            waitingForExternal = false,
                            waitingForEvent = "",
                            suspendReason = "",
                            resumeContext = guardedResumeContext,
                        ),
                    logMessage =
                        "恢复保护触发: source=${guardedResumeContext.source.ifBlank { "-" }}, " +
                            "event=page_settling, subgoal=$subgoalId, attempt=${guardedResumeContext.resumeAttempt}" +
                            guardedResumeContext.userCorrection.takeIf { it.isNotBlank() }?.let { ", correction=$it" }.orEmpty(),
                    replayMessage =
                        "source=${guardedResumeContext.source.ifBlank { "-" }} event=page_settling " +
                            "subgoal=$subgoalId attempt=${guardedResumeContext.resumeAttempt}",
                    replayAttributes = baseAttributes + ("resume_attempt" to guardedResumeContext.resumeAttempt),
                )
            }

            waitingEventPolicy.shouldReenterWait -> {
                ResumeWaitPolicyDecision.ReenterWait(
                    adjustedTaskPlanState = taskPlanState.copy(suspendReason = waitingEventPolicy.adjustedReason),
                    logMessage =
                        "恢复后仍命中等待事件，直接回到等待态: source=${resumeContext.source.ifBlank { "-" }}, " +
                            "event=$waitingEvent, subgoal=$subgoalId, policy=${waitingEventPolicy.policy}" +
                            resumeContext.userCorrection.takeIf { it.isNotBlank() }?.let { ", correction=$it" }.orEmpty(),
                    replayMessage =
                        "source=${resumeContext.source.ifBlank { "-" }} event=$waitingEvent " +
                            "subgoal=$subgoalId policy=reenter_wait/${waitingEventPolicy.policy}",
                    replayAttributes =
                        baseAttributes +
                            mapOf(
                                "policy" to "reenter_wait",
                                "wait_policy" to waitingEventPolicy.policy,
                            ),
                )
            }

            else -> {
                val guardedResumeContext =
                    resumeContext.copy(
                        resumeAttempt = resumeContext.resumeAttempt + 1,
                        resumeHint =
                            resumeContext.resumeHint.ifBlank {
                                "恢复后仍检测到阻塞，先尝试做一次最小纠偏，再决定是否重新挂起。"
                            },
                    )
                ResumeWaitPolicyDecision.ApplyGuard(
                    guardedTaskPlanState =
                        taskPlanState.copy(
                            waitingForExternal = false,
                            waitingForEvent = "",
                            suspendReason = "",
                            resumeContext = guardedResumeContext,
                        ),
                    logMessage =
                        "恢复保护触发: source=${guardedResumeContext.source.ifBlank { "-" }}, " +
                            "event=${waitingEvent.ifBlank { "-" }}, subgoal=$subgoalId, attempt=${guardedResumeContext.resumeAttempt}, " +
                            "policy=${waitingEventPolicy.policy}" +
                            guardedResumeContext.userCorrection.takeIf { it.isNotBlank() }?.let { ", correction=$it" }.orEmpty(),
                    replayMessage =
                        "source=${guardedResumeContext.source.ifBlank { "-" }} event=${waitingEvent.ifBlank { "-" }} " +
                            "subgoal=$subgoalId attempt=${guardedResumeContext.resumeAttempt} policy=${waitingEventPolicy.policy}",
                    replayAttributes =
                        baseAttributes +
                            mapOf(
                                "resume_attempt" to guardedResumeContext.resumeAttempt,
                                "wait_policy" to waitingEventPolicy.policy,
                            ),
                )
            }
        }
    }

    fun buildResolvedResumeContext(
        previousWaitState: RuntimeExternalWaitState?,
        taskPlanState: TaskPlanState,
        previousEvent: String,
    ): ResumeContext {
        val resumedSubgoalId =
            previousWaitState?.subgoalId
                ?.takeIf { it.isNotBlank() }
                ?: taskPlanState.currentSubgoalId
        val resumeHint =
            buildString {
                append("外部处理已完成，回到子目标 ")
                append(resumedSubgoalId.ifBlank { "current" })
                append(" 继续执行。")
                if (taskPlanState.nextObjective.isNotBlank()) {
                    append(" 下一步：").append(taskPlanState.nextObjective)
                }
            }
        return ResumeContext(
            active = true,
            source = "external_wait_resolved",
            resumeEvent = previousEvent.ifBlank { previousWaitState?.event.orEmpty() },
            resumeHint = resumeHint,
            resumedSubgoalId = resumedSubgoalId,
            resumeAttempt = 0,
        )
    }
}
