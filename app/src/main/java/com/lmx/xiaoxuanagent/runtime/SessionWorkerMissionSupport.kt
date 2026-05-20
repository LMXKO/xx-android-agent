package com.lmx.xiaoxuanagent.runtime

enum class SessionWorkerMissionType {
    EXECUTION,
    APPROVAL,
    RECOVERY,
    FOLLOW_UP,
    MEMORY,
    OBSERVATION,
    ORCHESTRATION,
}

enum class SessionWorkerEscalationPolicy {
    NONE,
    MANUAL_APPROVAL,
    SAFETY_CONFIRMATION,
    PARENT_ACK,
    OPERATOR_REVIEW,
}

enum class SessionWorkerJoinExpectation {
    NONE,
    FIRST_CHILD,
    ALL_CHILDREN,
    ANY_CHILD,
    PARENT_RESUME,
    SIGNAL_ACK,
}

internal data class SessionWorkerMissionProfile(
    val missionType: SessionWorkerMissionType,
    val escalationPolicy: SessionWorkerEscalationPolicy,
    val joinExpectation: SessionWorkerJoinExpectation,
    val missionLabel: String,
    val missionSummary: String,
)

internal fun deriveSessionWorkerMissionProfile(
    task: String,
    entrySource: String,
    joinPolicy: SessionWorkerJoinPolicy,
    metadata: Map<String, String>,
    hasParent: Boolean,
): SessionWorkerMissionProfile {
    val missionType =
        metadata["mission_type"].toWorkerMissionType()
            ?: inferWorkerMissionType(task = task, entrySource = entrySource, metadata = metadata)
    val escalationPolicy =
        metadata["escalation_policy"].toWorkerEscalationPolicy()
            ?: inferWorkerEscalationPolicy(
                missionType = missionType,
                entrySource = entrySource,
                metadata = metadata,
                joinPolicy = joinPolicy,
                hasParent = hasParent,
            )
    val joinExpectation =
        metadata["join_expectation"].toWorkerJoinExpectation()
            ?: inferWorkerJoinExpectation(
                missionType = missionType,
                joinPolicy = joinPolicy,
                metadata = metadata,
            )
    val missionLabel =
        metadata["mission_label"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: defaultWorkerMissionLabel(missionType)
    val missionSummary =
        metadata["mission_summary"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(missionLabel)
                append(" | ")
                append(defaultWorkerJoinSummary(joinExpectation))
                val escalationSummary = defaultWorkerEscalationSummary(escalationPolicy)
                if (escalationSummary.isNotBlank()) {
                    append(" | ").append(escalationSummary)
                }
                task.trim().takeIf { it.isNotBlank() }?.let {
                    append(" | ").append(it.take(48))
                }
            }
    return SessionWorkerMissionProfile(
        missionType = missionType,
        escalationPolicy = escalationPolicy,
        joinExpectation = joinExpectation,
        missionLabel = missionLabel,
        missionSummary = missionSummary,
    )
}

internal fun SessionWorkerRecord.missionLabelResolved(): String =
    missionLabel.ifBlank { defaultWorkerMissionLabel(missionType) }

internal fun SessionWorkerRecord.missionSummaryResolved(): String =
    missionSummary.ifBlank {
        buildString {
            append(missionLabelResolved())
            append(" | ")
            append(defaultWorkerJoinSummary(joinExpectation))
            defaultWorkerEscalationSummary(escalationPolicy)
                .takeIf { it.isNotBlank() }
                ?.let { append(" | ").append(it) }
        }
    }

internal fun SessionWorkerRecord.requiresEscalationAttention(): Boolean =
    escalationPolicy != SessionWorkerEscalationPolicy.NONE &&
        status != SessionWorkerStatus.COMPLETED &&
        status != SessionWorkerStatus.FAILED &&
        status != SessionWorkerStatus.STOPPED &&
        (status == SessionWorkerStatus.WAITING_APPROVAL ||
            status == SessionWorkerStatus.WAITING_CHILDREN ||
            blockedReason.isNotBlank())

internal fun List<SessionWorkerRecord>.missionMixSummary(): String {
    if (isEmpty()) return "missions=-"
    return groupBy { it.missionType }
        .entries
        .sortedByDescending { it.value.size }
        .joinToString(" | ") { (type, workers) ->
            "${type.name.lowercase()}:${workers.size}"
        }
}

internal fun List<SessionWorkerRecord>.escalationPressureSummary(): String {
    if (isEmpty()) return "escalations=0"
    val tracked = filter { it.escalationPolicy != SessionWorkerEscalationPolicy.NONE }
    if (tracked.isEmpty()) return "escalations=0"
    val awaiting = tracked.count { it.requiresEscalationAttention() }
    val manual = tracked.count { it.escalationPolicy == SessionWorkerEscalationPolicy.MANUAL_APPROVAL }
    val safety = tracked.count { it.escalationPolicy == SessionWorkerEscalationPolicy.SAFETY_CONFIRMATION }
    val parent = tracked.count { it.escalationPolicy == SessionWorkerEscalationPolicy.PARENT_ACK }
    return "escalations=${tracked.size} awaiting=$awaiting manual=$manual safety=$safety parent_ack=$parent"
}

internal fun List<SessionWorkerRecord>.joinPressureSummary(): String {
    if (isEmpty()) return "joins=0"
    val waiting = count { it.status == SessionWorkerStatus.WAITING_CHILDREN }
    val allChildren = count { it.joinExpectation == SessionWorkerJoinExpectation.ALL_CHILDREN }
    val anyChild = count { it.joinExpectation == SessionWorkerJoinExpectation.ANY_CHILD || it.joinExpectation == SessionWorkerJoinExpectation.FIRST_CHILD }
    val parentResume = count { it.joinExpectation == SessionWorkerJoinExpectation.PARENT_RESUME }
    return "joins=$waiting wait_all=$allChildren wait_any=$anyChild parent_resume=$parentResume"
}

private fun inferWorkerMissionType(
    task: String,
    entrySource: String,
    metadata: Map<String, String>,
): SessionWorkerMissionType {
    val lowerTask = task.lowercase()
    val lowerSource = entrySource.lowercase()
    return when {
        metadata["follow_up_session_id"].orEmpty().isNotBlank() ||
            lowerTask.contains("follow up") ||
            task.contains("跟进") ||
            task.contains("回访") ->
            SessionWorkerMissionType.FOLLOW_UP

        metadata["memory_scope"].orEmpty().isNotBlank() ||
            lowerSource.contains("memory") ||
            task.contains("记忆") ->
            SessionWorkerMissionType.MEMORY

        lowerSource.contains("approval") ||
            lowerSource.contains("safety") ||
            lowerTask.contains("approve") ||
            lowerTask.contains("confirm") ||
            task.contains("审批") ||
            task.contains("确认") ->
            SessionWorkerMissionType.APPROVAL

        lowerTask.contains("recover") ||
            lowerTask.contains("resume") ||
            lowerTask.contains("retry") ||
            lowerTask.contains("refocus") ||
            task.contains("恢复") ||
            task.contains("重试") ||
            task.contains("回退") ->
            SessionWorkerMissionType.RECOVERY

        lowerSource.contains("provider") ||
            lowerSource.contains("signal") ||
            lowerTask.contains("observe") ||
            lowerTask.contains("watch") ||
            task.contains("观测") ||
            task.contains("检查") ->
            SessionWorkerMissionType.OBSERVATION

        lowerSource.contains("swarm") ||
            lowerSource.contains("coordinator") ||
            metadata["coordinator"].orEmpty().equals("true", ignoreCase = true) ->
            SessionWorkerMissionType.ORCHESTRATION

        else -> SessionWorkerMissionType.EXECUTION
    }
}

private fun inferWorkerEscalationPolicy(
    missionType: SessionWorkerMissionType,
    entrySource: String,
    metadata: Map<String, String>,
    joinPolicy: SessionWorkerJoinPolicy,
    hasParent: Boolean,
): SessionWorkerEscalationPolicy =
    when {
        metadata["requires_safety"].orEmpty().equals("true", ignoreCase = true) ||
            entrySource.contains("safety", ignoreCase = true) ->
            SessionWorkerEscalationPolicy.SAFETY_CONFIRMATION

        missionType == SessionWorkerMissionType.APPROVAL ->
            SessionWorkerEscalationPolicy.MANUAL_APPROVAL

        metadata["operator_review"].orEmpty().equals("true", ignoreCase = true) ||
            (missionType == SessionWorkerMissionType.ORCHESTRATION &&
                metadata["operator_review"].orEmpty() != "false") ->
            SessionWorkerEscalationPolicy.OPERATOR_REVIEW

        hasParent && joinPolicy != SessionWorkerJoinPolicy.DETACHED ->
            SessionWorkerEscalationPolicy.PARENT_ACK

        else -> SessionWorkerEscalationPolicy.NONE
    }

private fun inferWorkerJoinExpectation(
    missionType: SessionWorkerMissionType,
    joinPolicy: SessionWorkerJoinPolicy,
    metadata: Map<String, String>,
): SessionWorkerJoinExpectation {
    if (metadata["signal_ack"].orEmpty().equals("true", ignoreCase = true)) {
        return SessionWorkerJoinExpectation.SIGNAL_ACK
    }
    return when (joinPolicy) {
        SessionWorkerJoinPolicy.DETACHED -> SessionWorkerJoinExpectation.NONE
        SessionWorkerJoinPolicy.WAIT_ANY_CHILD ->
            when (missionType) {
                SessionWorkerMissionType.OBSERVATION -> SessionWorkerJoinExpectation.ANY_CHILD
                else -> SessionWorkerJoinExpectation.FIRST_CHILD
            }

        SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN ->
            when (missionType) {
                SessionWorkerMissionType.APPROVAL,
                SessionWorkerMissionType.RECOVERY,
                -> SessionWorkerJoinExpectation.PARENT_RESUME

                else -> SessionWorkerJoinExpectation.ALL_CHILDREN
            }
    }
}

private fun defaultWorkerMissionLabel(
    missionType: SessionWorkerMissionType,
): String =
    when (missionType) {
        SessionWorkerMissionType.EXECUTION -> "主执行"
        SessionWorkerMissionType.APPROVAL -> "审批协作"
        SessionWorkerMissionType.RECOVERY -> "恢复收口"
        SessionWorkerMissionType.FOLLOW_UP -> "跟进任务"
        SessionWorkerMissionType.MEMORY -> "记忆整理"
        SessionWorkerMissionType.OBSERVATION -> "观察采样"
        SessionWorkerMissionType.ORCHESTRATION -> "协同调度"
    }

private fun defaultWorkerEscalationSummary(
    policy: SessionWorkerEscalationPolicy,
): String =
    when (policy) {
        SessionWorkerEscalationPolicy.NONE -> ""
        SessionWorkerEscalationPolicy.MANUAL_APPROVAL -> "需要人工审批"
        SessionWorkerEscalationPolicy.SAFETY_CONFIRMATION -> "需要安全确认"
        SessionWorkerEscalationPolicy.PARENT_ACK -> "等待父任务确认"
        SessionWorkerEscalationPolicy.OPERATOR_REVIEW -> "需要 operator review"
    }

private fun defaultWorkerJoinSummary(
    expectation: SessionWorkerJoinExpectation,
): String =
    when (expectation) {
        SessionWorkerJoinExpectation.NONE -> "detached"
        SessionWorkerJoinExpectation.FIRST_CHILD -> "first_child"
        SessionWorkerJoinExpectation.ALL_CHILDREN -> "all_children"
        SessionWorkerJoinExpectation.ANY_CHILD -> "any_child"
        SessionWorkerJoinExpectation.PARENT_RESUME -> "parent_resume"
        SessionWorkerJoinExpectation.SIGNAL_ACK -> "signal_ack"
    }

private fun String?.toWorkerMissionType(): SessionWorkerMissionType? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { SessionWorkerMissionType.valueOf(it.uppercase()) }.getOrNull() }

private fun String?.toWorkerEscalationPolicy(): SessionWorkerEscalationPolicy? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { SessionWorkerEscalationPolicy.valueOf(it.uppercase()) }.getOrNull() }

private fun String?.toWorkerJoinExpectation(): SessionWorkerJoinExpectation? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { SessionWorkerJoinExpectation.valueOf(it.uppercase()) }.getOrNull() }
