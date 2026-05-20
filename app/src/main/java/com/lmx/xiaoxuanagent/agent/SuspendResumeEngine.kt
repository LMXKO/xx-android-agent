package com.lmx.xiaoxuanagent.agent

object SuspendResumeEngine {
    private val strongManualInterventionKeywords =
        listOf(
            "验证码",
            "短信验证",
            "手动完成",
            "手动验证",
            "人脸识别",
            "身份验证",
            "刷脸验证",
            "安全验证",
            "开启权限",
            "系统设置",
        )

    private val loginKeywords =
        listOf(
            "登录",
            "请先登录",
            "立即登录",
            "登录后继续",
            "手机号登录",
            "验证码登录",
            "账号登录",
            "密码登录",
        )

    private val credentialKeywords =
        listOf(
            "手机号",
            "手机号码",
            "请输入手机号",
            "请输入手机号码",
            "密码",
            "请输入密码",
            "短信验证码",
            "获取验证码",
            "发送验证码",
            "注册",
            "忘记密码",
            "同意并继续",
            "继续登录",
        )

    private val permissionKeywords =
        listOf(
            "授权",
            "权限",
            "去设置",
            "前往设置",
            "允许",
            "始终允许",
            "开启",
        )

    fun enrich(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        base: TaskPlanState,
        resumeContext: ResumeContext = ResumeContext(),
    ): TaskPlanState {
        val currentSubgoalId = resolveCurrentSubgoalId(base)
        val waitConditions =
            detectWaitConditions(
                observation = observation,
                history = history,
                base = base,
                currentSubgoalId = currentSubgoalId,
            )
        val blockingWait = waitConditions.firstOrNull { it.type == "external_wait" && it.status == "blocking" }
        val suspendable = waitConditions.isNotEmpty()
        val suspendReason = blockingWait?.reason ?: waitConditions.firstOrNull()?.reason.orEmpty()
        val waitingEvent = blockingWait?.resumeEvent.orEmpty()
        val effectiveResumeContext =
            if (!resumeContext.active) {
                ResumeContext()
            } else {
                resumeContext.copy(
                    resumedSubgoalId = resumeContext.resumedSubgoalId.ifBlank { currentSubgoalId },
                    resumeHint =
                        resumeContext.resumeHint.ifBlank {
                            buildResumeHint(
                                currentSubgoalId = currentSubgoalId,
                                stageSummary = base.stageSummary,
                                nextObjective = base.nextObjective,
                                userCorrection = resumeContext.userCorrection,
                            )
                        },
                )
            }
        return base.copy(
            currentSubgoalId = currentSubgoalId,
            taskGraph =
                TaskGraphBuilder.build(
                    planType = base.planType,
                    title = task.take(24).ifBlank { base.planType },
                    currentStage = base.currentStage,
                    stageSummary = base.stageSummary,
                    nextObjective = base.nextObjective,
                    steps = base.steps,
                    waitConditions = waitConditions,
                ),
            taskStack =
                TaskStackBuilder.from(
                    planType = base.planType,
                    currentStage = base.currentStage,
                    stageSummary = base.stageSummary,
                    nextObjective = base.nextObjective,
                    steps = base.steps,
                    waitConditions = waitConditions,
                ),
            waitConditions = waitConditions,
            waitingForExternal = blockingWait != null,
            waitingForEvent = waitingEvent,
            suspendable = suspendable,
            suspendReason = suspendReason,
            orchestrationSummary =
                buildOrchestrationSummary(
                    currentSubgoalId = currentSubgoalId,
                    nextObjective = base.nextObjective,
                    waitConditions = waitConditions,
                ),
            resumeContext = effectiveResumeContext,
        )
    }

    private fun resolveCurrentSubgoalId(
        base: TaskPlanState,
    ): String =
        base.steps.firstOrNull { it.status == "in_progress" || it.status == "ready" }?.id
            ?: base.steps.firstOrNull { it.status == "pending" }?.id
            ?: base.currentStage

    private fun detectWaitConditions(
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        base: TaskPlanState,
        currentSubgoalId: String,
    ): List<TaskWaitCondition> {
        val screenTexts =
            buildList {
                addAll(observation.topTexts)
                addAll(observation.elements.take(12).map { it.text })
            }.filter { it.isNotBlank() }
        val joinedText = screenTexts.joinToString(" ")
        val conditions = mutableListOf<TaskWaitCondition>()
        val strongKeyword = strongManualInterventionKeywords.firstOrNull { joinedText.contains(it) }
        if (strongKeyword != null) {
            conditions +=
                TaskWaitCondition(
                    id = "wait_manual_intervention",
                    title = "人工介入",
                    type = "external_wait",
                    status = "blocking",
                    reason = "检测到“$strongKeyword”相关界面，当前更适合等待外部处理。",
                    resumeEvent = "manual_verification",
                    resumeHint = "外部验证完成后，从 $currentSubgoalId 继续。",
                    blockingNodeId = currentSubgoalId,
                    sourceHints = screenTexts.take(4),
                )
        }
        val loginKeyword = loginKeywords.firstOrNull { joinedText.contains(it) }
        if (loginKeyword != null) {
            val credentialKeyword = credentialKeywords.firstOrNull { joinedText.contains(it) }
            if (credentialKeyword != null) {
                conditions +=
                    TaskWaitCondition(
                        id = "wait_login_credentials",
                        title = "登录校验",
                        type = "external_wait",
                        status = "blocking",
                        reason = "检测到“$loginKeyword/$credentialKeyword”相关界面，当前更适合等待外部处理。",
                        resumeEvent = "manual_verification",
                        resumeHint = "登录完成后，回到 $currentSubgoalId 继续。",
                        blockingNodeId = currentSubgoalId,
                        sourceHints = listOf(loginKeyword, credentialKeyword),
                    )
            }
        }
        val permissionSignal =
            joinedText.contains("权限") ||
                joinedText.contains("授权") ||
                joinedText.contains("系统设置") ||
                joinedText.contains("去设置") ||
                joinedText.contains("前往设置")
        if (permissionSignal) {
            val permissionKeyword = permissionKeywords.firstOrNull { joinedText.contains(it) }
            if (permissionKeyword != null) {
                conditions +=
                    TaskWaitCondition(
                        id = "wait_permission_grant",
                        title = "权限授予",
                        type = "external_wait",
                        status = "blocking",
                        reason = "检测到“$permissionKeyword”相关界面，当前更适合等待外部处理。",
                        resumeEvent = "permission_grant",
                        resumeHint = "权限处理完成后，优先恢复 $currentSubgoalId。",
                        blockingNodeId = currentSubgoalId,
                        sourceHints = listOf(permissionKeyword),
                    )
            }
        }
        val recentWaits = history.takeLast(2)
        if (recentWaits.size == 2 && recentWaits.all { it.action == "wait()" }) {
            conditions +=
                TaskWaitCondition(
                    id = "wait_page_settling",
                    title = "页面稳定",
                    type = "external_wait",
                    status = "blocking",
                    reason = "连续等待后页面仍未明显推进，建议挂起等待页面稳定或外部事件。",
                    resumeEvent = "page_settling",
                    resumeHint = "页面恢复变化后，回到 $currentSubgoalId 继续。",
                    blockingNodeId = currentSubgoalId,
                    sourceHints = listOf("wait()", "wait()"),
                )
        }
        checkpointWaitForStage(base.currentStage, currentSubgoalId, base.nextObjective)?.let(conditions::add)
        return conditions
            .distinctBy { it.id }
            .sortedByDescending { if (it.status == "blocking") 1 else 0 }
    }

    private fun checkpointWaitForStage(
        currentStage: String,
        currentSubgoalId: String,
        nextObjective: String,
    ): TaskWaitCondition? =
        when (currentStage) {
            "confirm_send" ->
                TaskWaitCondition(
                    id = "checkpoint_confirm_send",
                    title = "发送前确认",
                    type = "checkpoint",
                    status = "ready",
                    reason = "已经进入发送前关键阶段，可挂起等待人工确认。",
                    resumeEvent = "confirm_send",
                    resumeHint = nextObjective.ifBlank { "确认发送内容后继续。" },
                    blockingNodeId = currentSubgoalId,
                )

            "confirm_route" ->
                TaskWaitCondition(
                    id = "checkpoint_confirm_route",
                    title = "路线确认",
                    type = "checkpoint",
                    status = "ready",
                    reason = "已经进入路线确认阶段，可挂起等待人工选择或确认。",
                    resumeEvent = "confirm_route",
                    resumeHint = nextObjective.ifBlank { "确认路线后继续。" },
                    blockingNodeId = currentSubgoalId,
                )

            "summarize" ->
                TaskWaitCondition(
                    id = "checkpoint_summarize",
                    title = "结果收口",
                    type = "checkpoint",
                    status = "ready",
                    reason = "已经接近收口阶段，可挂起等待用户查看结果。",
                    resumeEvent = "summarize",
                    resumeHint = nextObjective.ifBlank { "整理结果并结束任务。" },
                    blockingNodeId = currentSubgoalId,
                )

            else -> null
        }

    private fun buildOrchestrationSummary(
        currentSubgoalId: String,
        nextObjective: String,
        waitConditions: List<TaskWaitCondition>,
    ): String =
        buildString {
            append("focus=").append(currentSubgoalId.ifBlank { "-" })
            if (nextObjective.isNotBlank()) {
                append(" | next=").append(nextObjective)
            }
            val blocking = waitConditions.filter { it.status == "blocking" }
            if (blocking.isNotEmpty()) {
                append(" | waits=").append(blocking.joinToString("/") { it.resumeEvent.ifBlank { it.type } })
            }
            val checkpoints = waitConditions.filter { it.type == "checkpoint" }
            if (checkpoints.isNotEmpty()) {
                append(" | checkpoints=").append(checkpoints.joinToString("/") { it.title })
            }
        }

    private fun buildResumeHint(
        currentSubgoalId: String,
        stageSummary: String,
        nextObjective: String,
        userCorrection: String,
    ): String =
        buildString {
            append("恢复后优先续跑子目标 ")
            append(currentSubgoalId.ifBlank { "current" })
            append("。")
            if (nextObjective.isNotBlank()) {
                append(" 下一步：").append(nextObjective)
            } else if (stageSummary.isNotBlank()) {
                append(" 当前阶段：").append(stageSummary)
            }
            if (userCorrection.isNotBlank()) {
                append(" 用户纠错：").append(userCorrection)
            }
        }
}
