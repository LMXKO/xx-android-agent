package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.correctionConfirmsWaitingEventCompleted
import com.lmx.xiaoxuanagent.agent.HumanCorrectionRecoveryIntent
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.interpretHumanCorrection
import com.lmx.xiaoxuanagent.agent.normalizeCorrectionText
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.resolveResumeCorrectionDirective
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.safety.SafetyHandoffFormatter
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore

internal data class ResumeWaitingEventPolicy(
    val shouldReenterWait: Boolean,
    val policy: String,
    val adjustedReason: String,
)

internal fun resolveResumeWaitingEventPolicy(
    waitingEvent: String,
    userCorrection: String,
    fallbackReason: String,
): ResumeWaitingEventPolicy {
    val normalizedCorrection = normalizeCorrectionText(userCorrection)
    val correctionInterpretation = interpretHumanCorrection(normalizedCorrection)
    val defaultReason =
        when (waitingEvent) {
            "manual_verification" -> "恢复后仍检测到验证/登录页面，继续等待你手动完成后再恢复。"
            "permission_grant" -> "恢复后仍检测到权限/授权页面，继续等待你完成授权后再恢复。"
            else -> fallbackReason
        }
    if (waitingEvent !in setOf("manual_verification", "permission_grant")) {
        return ResumeWaitingEventPolicy(
            shouldReenterWait = false,
            policy = "guard_non_blocking_wait",
            adjustedReason = fallbackReason,
        )
    }
    if (normalizedCorrection.isBlank()) {
        return ResumeWaitingEventPolicy(
            shouldReenterWait = true,
            policy = "await_external_completion",
            adjustedReason = defaultReason,
        )
    }
    if (correctionConfirmsWaitingEventCompleted(waitingEvent, normalizedCorrection)) {
        return ResumeWaitingEventPolicy(
            shouldReenterWait = false,
            policy = "user_confirmed_completion",
            adjustedReason = fallbackReason,
        )
    }
    if (correctionInterpretation.mentionsExecutableResumeDirective) {
        val policy =
            when (correctionInterpretation.primaryRecoveryIntent) {
                HumanCorrectionRecoveryIntent.BACKTRACK -> "user_requested_backtrack"
                HumanCorrectionRecoveryIntent.OVERSCROLL_UP -> "user_requested_overscroll_up"
                HumanCorrectionRecoveryIntent.SCROLL_DOWN -> "user_requested_scroll_down"
                HumanCorrectionRecoveryIntent.REFOCUS_ENTRY -> "user_requested_refocus_entry"
                HumanCorrectionRecoveryIntent.NONE -> "user_requested_correction"
            }
        return ResumeWaitingEventPolicy(
            shouldReenterWait = false,
            policy = policy,
            adjustedReason = fallbackReason,
        )
    }
    return ResumeWaitingEventPolicy(
        shouldReenterWait = true,
        policy = "correction_not_actionable_for_wait",
        adjustedReason =
            buildString {
                append(defaultReason)
                append(" 当前纠错“").append(normalizedCorrection).append("”会在恢复后继续保留，等页面真正脱离阻塞后再消费。")
            },
    )
}

internal fun enrichPendingSafetyConfirmation(
    confirmation: PendingSafetyConfirmation,
    planStage: String,
    subgoalId: String,
    nextObjective: String,
    targetPackageName: String,
    profileId: String = "",
): PendingSafetyConfirmation =
    SafetyHandoffFormatter.enrich(
        confirmation =
            confirmation.copy(
                planStage = confirmation.planStage.ifBlank { planStage },
                subgoalId = confirmation.subgoalId.ifBlank { subgoalId },
                nextObjective = confirmation.nextObjective.ifBlank { nextObjective },
                targetPackageName = confirmation.targetPackageName.ifBlank { targetPackageName },
            ),
        policy =
            AutomationSupportPolicyStore.resolve(
                profileId = profileId,
                packageName = targetPackageName,
                taskIntent = TaskIntentParser.parse(SessionRuntime.State.runtimeState().session.task).intentType.name.lowercase(),
                planStage = confirmation.planStage.ifBlank { planStage },
            ),
    )

internal fun buildSafetyApprovalResumeContext(
    confirmation: PendingSafetyConfirmation,
    userCorrection: String = "",
): ResumeContext {
    val resumedSubgoalId = confirmation.subgoalId.ifBlank { "current" }
    val correctionDirective = resolveResumeCorrectionDirective(userCorrection)
    val resumeHint =
        buildString {
            append("高风险动作已确认，回到子目标 ")
            append(resumedSubgoalId)
            append(" 继续执行。")
            if (confirmation.actionLabel.isNotBlank()) {
                append(" 已放行动作：").append(confirmation.actionLabel)
            }
            if (confirmation.nextObjective.isNotBlank()) {
                append(" 下一步：").append(confirmation.nextObjective)
            } else if (confirmation.planStage.isNotBlank()) {
                append(" 当前阶段：").append(confirmation.planStage)
            }
            if (userCorrection.isNotBlank()) {
                append(" 用户纠错：").append(userCorrection)
                correctionDirective.guidance.takeIf { it.isNotBlank() }?.let { guidance ->
                    append(" ").append(guidance)
                }
            }
        }
    return ResumeContext(
        active = true,
        source = "safety_confirmation_approved",
        resumeEvent = "safety_confirmation",
        resumeHint = resumeHint,
        resumedSubgoalId = confirmation.subgoalId,
        resumeAttempt = 0,
        userCorrection = userCorrection,
    )
}

internal fun buildManualResumeContext(
    subgoalId: String,
    nextObjective: String,
    userCorrection: String = "",
): ResumeContext {
    val correctionDirective = resolveResumeCorrectionDirective(userCorrection)
    val resumeHint =
        buildString {
            append("任务由用户手动恢复，优先续跑当前子目标，不要从头重复。")
            if (subgoalId.isNotBlank()) {
                append(" 当前子目标：").append(subgoalId)
            }
            if (nextObjective.isNotBlank()) {
                append(" 下一步：").append(nextObjective)
            }
            if (userCorrection.isNotBlank()) {
                append(" 用户纠错：").append(userCorrection)
                correctionDirective.guidance.takeIf { it.isNotBlank() }?.let { guidance ->
                    append(" ").append(guidance)
                }
            }
        }
    return ResumeContext(
        active = true,
        source = if (userCorrection.isBlank()) "manual_resume" else "manual_correction_resume",
        resumeEvent = if (userCorrection.isBlank()) "manual_resume" else "manual_correction",
        resumeHint = resumeHint,
        resumedSubgoalId = subgoalId,
        resumeAttempt = 0,
        userCorrection = userCorrection,
    )
}

internal fun buildBootstrapResumeContext(
    previousResumeContext: ResumeContext,
    subgoalId: String,
    nextObjective: String,
    userCorrection: String = "",
): ResumeContext {
    val effectiveCorrection = userCorrection.ifBlank { previousResumeContext.userCorrection }
    val resumedSubgoalId = previousResumeContext.resumedSubgoalId.ifBlank { subgoalId }
    val correctionDirective = resolveResumeCorrectionDirective(effectiveCorrection)
    val resumeHint =
        buildString {
            append("任务从本地恢复快照继续执行，优先续跑当前子目标，不要从头重复。")
            if (resumedSubgoalId.isNotBlank()) {
                append(" 当前子目标：").append(resumedSubgoalId)
            }
            if (nextObjective.isNotBlank()) {
                append(" 下一步：").append(nextObjective)
            }
            previousResumeContext.source.takeIf { it.isNotBlank() }?.let { source ->
                append(" 恢复来源：").append(source)
            }
            if (effectiveCorrection.isNotBlank()) {
                append(" 用户纠错：").append(effectiveCorrection)
                correctionDirective.guidance.takeIf { it.isNotBlank() }?.let { guidance ->
                    append(" ").append(guidance)
                }
            }
        }
    return ResumeContext(
        active = true,
        source = if (effectiveCorrection.isBlank()) "resume_snapshot_continue" else "resume_snapshot_correction_continue",
        resumeEvent =
            previousResumeContext.resumeEvent.ifBlank {
                if (effectiveCorrection.isBlank()) {
                    "resume_snapshot_continue"
                } else {
                    "resume_snapshot_correction"
                }
            },
        resumeHint = resumeHint,
        resumedSubgoalId = resumedSubgoalId,
        resumeAttempt = previousResumeContext.resumeAttempt,
        userCorrection = effectiveCorrection,
    )
}

internal fun normalizeHumanCorrection(raw: String): String = normalizeCorrectionText(raw)

internal fun buildTakeoverDisplayReason(
    type: String,
    reason: String,
    actionLabel: String = "",
): String =
    buildString {
        append(type)
        val detail = reason.ifBlank { actionLabel }
        if (detail.isNotBlank()) {
            append(" | ").append(detail)
        }
    }
