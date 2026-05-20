package com.lmx.xiaoxuanagent

import android.view.View
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot

internal fun renderProductShellControl(
    views: MainAssistantShellViews,
    productShell: AssistantProductShellSnapshot,
) {
    val pendingStep = productShell.onboarding.steps.firstOrNull { it.status == "pending" }
    val tip = productShell.tips.firstOrNull()
    views.productShellControlText.updateTextIfChanged(
        buildString {
            append("shell=").append(productShell.operatorShell.mode).append(" | ")
                .append(productShell.operatorShell.summary.ifBlank { "-" }).append('\n')
            append("focus=").append(productShell.personalFocus.mode).append(" | ")
                .append(productShell.personalFocus.summary.ifBlank { "-" }).append('\n')
            append("autonomy=").append(productShell.autonomyPlan.mode).append(" | ")
                .append(productShell.autonomyPlan.summary.ifBlank { "-" }).append('\n')
            append("user_model=").append(productShell.userModel.summary.ifBlank { "-" }).append('\n')
            append("onboarding=").append(productShell.onboarding.status).append(" pending=")
                .append(productShell.onboarding.pendingSteps.size).append('\n')
            append("tips=").append(productShell.tips.size).append(" next=")
                .append(tip?.title ?: "-").append('\n')
            append("swarm=").append(productShell.swarmStrategy.mode).append(" | ")
                .append(productShell.swarmStrategy.dispatchSummary.ifBlank { "-" }).append('\n')
            append("commands=").append(
                (
                    productShell.operatorShell.recommendedCommands +
                        productShell.personalFocus.nextBestActions +
                        productShell.swarmStrategy.recommendedCommands +
                        productShell.agendaShell.recommendedCommands
                ).distinct().joinToString(", ").ifBlank { "-" },
            )
        }.trim(),
    )
    views.productShellAgendaText.updateTextIfChanged(
        buildString {
            append("agenda=").append(productShell.agendaShell.mode).append(" | ")
                .append(productShell.agendaShell.summary.ifBlank { "-" }).append('\n')
            productShell.agendaShell.agendaLines.take(3).forEach { line ->
                append("agenda | ").append(line).append('\n')
            }
            append("viewer_lane=").append(productShell.viewerShell.actionLaneSummary.ifBlank { "-" }).append('\n')
            productShell.viewerShell.actionLines.take(2).forEach { line ->
                append("viewer | ").append(line).append('\n')
            }
            append("rhythm=").append(productShell.dailyRhythm.mode).append(" | ")
                .append(productShell.dailyRhythm.summary.ifBlank { "-" }).append('\n')
            productShell.dailyRhythm.rhythmLines.take(2).forEach { line ->
                append("rhythm | ").append(line).append('\n')
            }
            append("diagnostics=").append(productShell.diagnostics.status).append(" | ")
                .append(productShell.diagnostics.summary.ifBlank { "-" }).append('\n')
            productShell.operatorShell.urgentLines.take(4).forEach { line ->
                append("operator | ").append(line).append('\n')
            }
        }.trim(),
    )
    views.refreshProductShellButton.isEnabled = true
    views.tipPrimaryButton.isEnabled = tip != null
    views.tipDismissButton.isEnabled = tip != null
    views.onboardingPrimaryButton.isEnabled = pendingStep != null
    views.onboardingSkipButton.isEnabled = pendingStep != null
    views.tipPrimaryButton.updateTextIfChanged(tip?.actionLabel ?: "完成 Tip")
    views.tipDismissButton.updateTextIfChanged(if (tip == null) "关闭 Tip" else "关闭 ${tip.title.take(8)}")
    views.onboardingPrimaryButton.updateTextIfChanged(pendingStep?.actionLabel ?: "完成引导")
    views.onboardingSkipButton.updateTextIfChanged(
        if (pendingStep == null) "跳过当前引导" else "跳过 ${pendingStep.title.take(8)}",
    )
}

internal fun renderShellPage(
    views: MainAssistantShellViews,
    uiState: MainAssistantShellUiState,
    selectedPage: MainAssistantShellPage,
    commandResult: String,
): MainAssistantShellPageBody {
    val productShell = uiState.productShell
    val snapshot = uiState.assistantSnapshot
    val pageBody =
        when (selectedPage) {
            MainAssistantShellPage.TODAY ->
                MainAssistantShellPageBody(
                    title = "Today",
                    summary = productShell.digestShell.summary.ifBlank { productShell.routineShell.summary.ifBlank { "查看今天的节奏、摘要和打断预算。" } },
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Digest",
                                body =
                                    buildString {
                                        append("digest=").append(productShell.digestShell.mode).append('\n')
                                        append("title=").append(productShell.digestShell.title.ifBlank { "-" }).append('\n')
                                        append("autonomy=").append(productShell.autonomyPlan.mode).append(" | ")
                                            .append(productShell.autonomyPlan.summary.ifBlank { "-" }).append('\n')
                                        productShell.digestShell.highlightLines.forEach { append("- ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Routine",
                                body =
                                    buildString {
                                        append("routine=").append(productShell.routineShell.mode).append(" | ")
                                            .append(productShell.routineShell.nextWindowSummary.ifBlank { "-" }).append('\n')
                                        productShell.routineShell.checklistLines.forEach { append("todo | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Interrupt Budget",
                                body =
                                    buildString {
                                        append("budget=").append(productShell.interruptBudget.remainingBudget)
                                            .append("/").append(productShell.interruptBudget.totalBudget).append('\n')
                                        append("allowed=").append(productShell.interruptBudget.allowedSources.joinToString(",").ifBlank { "-" }).append('\n')
                                        append("blocked=").append(productShell.interruptBudget.blockedSources.joinToString(",").ifBlank { "-" }).append('\n')
                                        append("user_model=").append(productShell.userModel.summary.ifBlank { "-" }).append('\n')
                                        productShell.routineShell.followUpLines.forEach { append("follow | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /today", MainAssistantShellActionType.RUN_COMMAND, command = "/today"),
                            MainAssistantShellAction("打开收件箱", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.INBOX),
                            MainAssistantShellAction("刷新", MainAssistantShellActionType.REFRESH),
                        ),
                )

            MainAssistantShellPage.INBOX ->
                MainAssistantShellPageBody(
                    title = "Inbox",
                    summary = "把 tip、onboarding、待跟进事项收成一个手机用户能读的收件箱面。",
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Tips",
                                body =
                                    buildString {
                                        append("tips=").append(productShell.tips.size).append('\n')
                                        productShell.tips.forEach { tip ->
                                            append("- ").append(tip.title).append(" | ").append(tip.summary).append('\n')
                                        }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Onboarding",
                                body =
                                    buildString {
                                        append("onboarding=").append(productShell.onboarding.status).append('\n')
                                        productShell.onboarding.steps.forEach { step ->
                                            append("- ").append(step.status).append(" | ").append(step.title).append('\n')
                                        }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Inbox Summary",
                                body = uiState.inboxSummary,
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /inbox", MainAssistantShellActionType.RUN_COMMAND, command = "/inbox"),
                            MainAssistantShellAction("打开审批", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.APPROVALS),
                            MainAssistantShellAction("刷新", MainAssistantShellActionType.REFRESH),
                        ),
                )

            MainAssistantShellPage.WORKBENCH ->
                MainAssistantShellPageBody(
                    title = "Workbench",
                    summary = "把 Viewer、会话、治理、入口和记忆收进一个二级工作台，首页不再同时摊开所有调试区块。",
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "观察与回放",
                                body =
                                    buildString {
                                        append("focus_session=").append(productShell.viewerShell.focusSessionId.ifBlank { "-" }).append('\n')
                                        append("focus_task=").append(productShell.viewerShell.focusTask.ifBlank { "-" }).append('\n')
                                        append("timeline=").append(productShell.viewerShell.timelineSummary.ifBlank { "-" }).append('\n')
                                        append("active=").append(snapshot.activeSession.statusCode).append(" | ")
                                            .append(snapshot.activeSession.task.ifBlank { "-" }).append('\n')
                                        productShell.viewerShell.actionLines.take(3).forEach { append("viewer | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "治理与记忆",
                                body =
                                    buildString {
                                        append("governance=").append(productShell.governanceShell.summary.ifBlank { "-" }).append('\n')
                                        append("provider=").append(productShell.governanceShell.providerPolicySummary.ifBlank { "-" }).append('\n')
                                        append("memory=").append(uiState.memoryGovernance.summary.ifBlank { "-" }).append('\n')
                                        append("workspace=").append(uiState.memoryGovernance.workspaceSummary.ifBlank { "-" }).append('\n')
                                        uiState.providerRegistryLines.take(3).forEach { append("registry | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "入口与节律",
                                body =
                                    buildString {
                                        append("entry_surfaces=").append(uiState.entrySurfaceLines.size).append('\n')
                                        uiState.entrySurfaceLines.take(4).forEach { append("entry | ").append(it).append('\n') }
                                        append("routine=").append(productShell.routinePolicy.summary.ifBlank { "-" }).append('\n')
                                        append("digest=").append(productShell.digestPolicy.summary.ifBlank { "-" }).append('\n')
                                        append("quiet_hours=").append(productShell.quietHours.summary.ifBlank { "-" }).append('\n')
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("打开 Viewer", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.VIEWER),
                            MainAssistantShellAction("打开治理", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.GOVERNANCE),
                            MainAssistantShellAction("打开记忆", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.MEMORY),
                        ),
                )

            MainAssistantShellPage.VIEWER ->
                MainAssistantShellPageBody(
                    title = "Viewer",
                    summary = productShell.viewerShell.detailSummary.ifBlank { productShell.viewerShell.timelineSummary.ifBlank { "把当前 session 的 detail、timeline、graph 收口成一个移动端 viewer。" } },
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Focus Session",
                                body =
                                    buildString {
                                        append("focus_session=").append(productShell.viewerShell.focusSessionId.ifBlank { "-" }).append('\n')
                                        append("focus_task=").append(productShell.viewerShell.focusTask.ifBlank { "-" }).append('\n')
                                        append("result=").append(productShell.viewerShell.resultSummary.ifBlank { "-" }).append('\n')
                                        productShell.viewerShell.detailLines.forEach { append("detail | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Timeline + Graph",
                                body =
                                    buildString {
                                        append("timeline=").append(productShell.viewerShell.timelineSummary.ifBlank { "-" }).append('\n')
                                        append("graph=").append(productShell.viewerShell.graphSummary.ifBlank { "-" }).append('\n')
                                        append("action_lane=").append(productShell.viewerShell.actionLaneSummary.ifBlank { "-" }).append('\n')
                                        productShell.viewerShell.timelineLines.take(5).forEach { append("timeline | ").append(it).append('\n') }
                                        productShell.viewerShell.graphLines.take(4).forEach { append("graph | ").append(it).append('\n') }
                                        productShell.viewerShell.actionLines.take(4).forEach { append("action | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Regression",
                                body = uiState.regressionLines.take(4).joinToString("\n").ifBlank { "暂无回归结果。" },
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /viewer", MainAssistantShellActionType.RUN_COMMAND, command = "/viewer"),
                            MainAssistantShellAction("执行回归", MainAssistantShellActionType.RUN_COMMAND, command = "/run-regression --limit 4"),
                            MainAssistantShellAction("打开会话", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.SESSIONS),
                        ),
                )

            MainAssistantShellPage.APPROVALS ->
                MainAssistantShellPageBody(
                    title = "Approval Center",
                    summary = productShell.viewerShell.approvalSummary.ifBlank { productShell.governanceShell.approvalSummary.ifBlank { "查看待审批事项、打断与治理控制面。" } },
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Approval Queue",
                                body =
                                    buildString {
                                        append("approval_summary=").append(productShell.viewerShell.approvalSummary.ifBlank { "-" }).append('\n')
                                        productShell.viewerShell.approvalLines.forEach { append("approval | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Interrupt",
                                body = "interrupt=${productShell.interruptBudget.summary.ifBlank { "-" }}",
                            ),
                            MainAssistantShellCard(
                                title = "Governance Controls",
                                body =
                                    buildString {
                                        append("governance=").append(productShell.governanceShell.summary.ifBlank { "-" }).append('\n')
                                        append("autonomy=").append(productShell.governanceShell.autonomySummary.ifBlank { "-" }).append('\n')
                                        productShell.governanceShell.controlLines.take(8).forEach { append("control | ").append(it).append('\n') }
                                        productShell.governanceShell.actionLines.take(4).forEach { append("action | ").append(it).append('\n') }
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /approvals", MainAssistantShellActionType.RUN_COMMAND, command = "/approvals"),
                            MainAssistantShellAction("去治理面", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.GOVERNANCE),
                            MainAssistantShellAction("刷新", MainAssistantShellActionType.REFRESH),
                        ),
                )

            MainAssistantShellPage.SESSIONS ->
                MainAssistantShellPageBody(
                    title = "Sessions",
                    summary = snapshot.activeSession.task.ifBlank { "查看当前 active session、backlog 和 swarm 协同摘要。" },
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Active Session",
                                body =
                                    buildString {
                                        append("active=").append(snapshot.activeSession.statusCode).append('\n')
                                        append("task=").append(snapshot.activeSession.task.ifBlank { "-" }).append('\n')
                                        append("summary=").append(snapshot.activeSession.summary.ifBlank { "-" }).append('\n')
                                        append("swarm=").append(productShell.swarmStrategy.mode).append(" | ")
                                            .append(productShell.swarmStrategy.dispatchSummary.ifBlank { "-" }).append('\n')
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Swarm + Regression",
                                body =
                                    buildString {
                                        productShell.operatorShell.urgentLines.forEach { append("operator | ").append(it).append('\n') }
                                        uiState.regressionLines.take(3).forEach { append(it).append('\n') }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Focus + Skills",
                                body =
                                    buildString {
                                        append("focus=").append(productShell.personalFocus.focusTask.ifBlank { "-" }).append('\n')
                                        append("focus_reason=").append(productShell.personalFocus.focusReason.ifBlank { "-" }).append('\n')
                                        append("skills=").append(uiState.skillCatalogLines.take(4).joinToString(" || ").ifBlank { "-" })
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /sessions", MainAssistantShellActionType.RUN_COMMAND, command = "/sessions"),
                            MainAssistantShellAction("打开 Viewer", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.VIEWER),
                            MainAssistantShellAction("填入命令", MainAssistantShellActionType.FILL_COMMAND, command = "/run-regression --limit 4"),
                        ),
                )

            MainAssistantShellPage.COMMAND ->
                MainAssistantShellPageBody(
                    title = "Ask",
                    summary = "把 slash command 和高级问答收进单独入口，首页只保留任务输入，不再同时铺满命令信息。",
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Command Catalog",
                                body = (listOf("commands") + uiState.commandCatalogLines).joinToString("\n").ifBlank { "暂无命令目录。" },
                            ),
                            MainAssistantShellCard(
                                title = "Recommended",
                                body = recommendedCommandsForPageFromSupport(uiState, selectedPage).take(8).joinToString("\n").ifBlank { "暂无推荐命令。" },
                            ),
                            MainAssistantShellCard(
                                title = "Result + Regression",
                                body =
                                    commandResult.ifBlank {
                                        uiState.regressionLines.take(4).joinToString("\n").ifBlank { "在这里运行 slash command，结果会显示在这里。" }
                                    },
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("填入推荐", MainAssistantShellActionType.FILL_COMMAND, command = recommendedCommandsForPageFromSupport(uiState, selectedPage).firstOrNull().orEmpty()),
                            MainAssistantShellAction("回归计划", MainAssistantShellActionType.RUN_COMMAND, command = "/regression-plan --limit 4"),
                            MainAssistantShellAction("执行回归", MainAssistantShellActionType.RUN_COMMAND, command = "/run-regression --limit 4"),
                        ),
                )

            MainAssistantShellPage.ROUTINE ->
                MainAssistantShellPageBody(
                    title = "Routine Center",
                    summary = productShell.routinePolicy.summary.ifBlank { productShell.digestPolicy.summary.ifBlank { "把 routine、digest、quiet hours、interrupt 策略收进一个可读可改的产品面。" } },
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Routine Policy",
                                body =
                                    buildString {
                                        append("routine_policy=").append(productShell.routinePolicy.summary.ifBlank { "-" }).append('\n')
                                        append("focus_theme=").append(productShell.routinePolicy.focusTheme).append('\n')
                                        append("review_window=").append(productShell.routinePolicy.reviewWindow).append('\n')
                                        append("follow_up_window=").append(productShell.routinePolicy.followUpWindow).append('\n')
                                        append("surfaces=").append(productShell.routinePolicy.preferredSurfaces.joinToString(",").ifBlank { "-" }).append('\n')
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Routine Checklist",
                                body = productShell.routineShell.checklistLines.joinToString("\n") { "routine | $it" }.ifBlank { "-" },
                            ),
                            MainAssistantShellCard(
                                title = "Digest + Quiet Hours",
                                body =
                                    buildString {
                                        append("digest_policy=").append(productShell.digestPolicy.summary.ifBlank { "-" }).append('\n')
                                        append("quiet_hours=").append(productShell.quietHours.summary.ifBlank { "-" }).append('\n')
                                        append("interrupt_policy=").append(productShell.interruptPolicy.summary.ifBlank { "-" }).append('\n')
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /routine-center", MainAssistantShellActionType.RUN_COMMAND, command = "/routine-center"),
                            MainAssistantShellAction("填入策略", MainAssistantShellActionType.FILL_COMMAND, command = "/set-routine-policy --focus-theme \"消息收口\""),
                            MainAssistantShellAction("打开 Today", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.TODAY),
                        ),
                )

            MainAssistantShellPage.GOVERNANCE ->
                run {
                    val focusSessionId = productShell.viewerShell.focusSessionId.ifBlank { snapshot.activeSession.sessionId }
                    val permissionTabs = productShell.governanceShell.permissionProductTabs
                    val permissionCards = productShell.governanceShell.permissionProductCards
                    val primaryPermissionCard = permissionCards.firstOrNull()
                    val secondaryPermissionCard = permissionCards.getOrNull(1)
                    val alternateTab = permissionTabs.firstOrNull { !it.active && it.id.isNotBlank() }
                    MainAssistantShellPageBody(
                        title = "Governance",
                        summary =
                            productShell.governanceShell.permissionProductSummary.ifBlank {
                                productShell.governanceShell.summary.ifBlank { "展示 consent、privacy、connected apps、utilities 和 provider/router 治理摘要。" }
                            },
                        cards =
                            listOf(
                                MainAssistantShellCard(
                                    title = "Permission Tabs",
                                    body =
                                        renderPermissionProductTabsCard(
                                            productShell = productShell,
                                            focusSessionId = focusSessionId,
                                        ),
                                ),
                                MainAssistantShellCard(
                                    title = "Connected Apps",
                                    body =
                                        buildString {
                                            uiState.connectedAppGovernanceLines.forEach { append(it).append('\n') }
                                            primaryPermissionCard?.takeIf { it.cardType == "app_permission" }?.let {
                                                append('\n').append(renderPermissionProductCard(it))
                                            }
                                        }.trim(),
                                ),
                                MainAssistantShellCard(
                                    title = "Utilities",
                                    body =
                                        buildString {
                                            uiState.utilityGovernanceLines.forEach { append(it).append('\n') }
                                            secondaryPermissionCard?.let {
                                                append('\n').append(renderPermissionProductCard(it)).append('\n')
                                            }
                                            append(renderGovernanceOverviewCard(productShell = productShell, uiState = uiState))
                                        }.trim(),
                                ),
                            ),
                        actions =
                            listOf(
                                MainAssistantShellAction(
                                    label = primaryPermissionCard?.primaryCommand?.takeIf { it.isNotBlank() }?.let { "执行 ${it.take(18)}" } ?: "打开授权面",
                                    type = MainAssistantShellActionType.RUN_COMMAND,
                                    command =
                                        primaryPermissionCard?.primaryCommand?.takeIf { it.isNotBlank() }
                                            ?: buildPermissionProductCommand(sessionId = focusSessionId),
                                ),
                                MainAssistantShellAction(
                                    label = "Connected Apps",
                                    type = MainAssistantShellActionType.RUN_COMMAND,
                                    command = "/connected-apps",
                                ),
                                MainAssistantShellAction(
                                    label = "Utilities",
                                    type = MainAssistantShellActionType.RUN_COMMAND,
                                    command = "/utilities",
                                ),
                            ),
                    )
                }

            MainAssistantShellPage.ENTRY ->
                MainAssistantShellPageBody(
                    title = "Entry",
                    summary = "通知、悬浮、快捷方式、桌面组件、快捷设置、语音入口统一归到 entry surface。",
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Surface Status",
                                body =
                                    (
                                        uiState.entrySurfaceLines +
                                            productShell.diagnostics.appPreflightLines.take(6).map { "preflight | $it" }
                                    ).joinToString(separator = "\n").ifBlank { "暂无入口摘要。" },
                            ),
                            MainAssistantShellCard(
                                title = "Permissions",
                                body =
                                    buildString {
                                        append("permission=").append(snapshot.permissionMode.name.lowercase()).append('\n')
                                        append("safety=").append(snapshot.safetyMode.name.lowercase()).append('\n')
                                        append("summary=").append(productShell.interruptBudget.summary.ifBlank { "-" }).append('\n')
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Overlay + Quick Entry",
                                body =
                                    buildString {
                                        append("digest_action=").append(productShell.digestShell.actionCommand.ifBlank { "-" }).append('\n')
                                        append("entry_mode=").append(productShell.autonomyPlan.entrySurfaceMode).append('\n')
                                        append("overlay_actions=").append(recommendedCommandsForPageFromSupport(uiState, MainAssistantShellPage.ENTRY).take(4).joinToString(" | "))
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /entry-surfaces", MainAssistantShellActionType.RUN_COMMAND, command = "/entry-surfaces"),
                            MainAssistantShellAction("打开 Today", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.TODAY),
                            MainAssistantShellAction("刷新", MainAssistantShellActionType.REFRESH),
                        ),
                )

            MainAssistantShellPage.MEMORY ->
                MainAssistantShellPageBody(
                    title = "Memory",
                    summary = "记忆页保留一层主视图：上面给你快速判断，下面直接进入治理和编辑。",
                    cards =
                        listOf(
                            MainAssistantShellCard(
                                title = "Memory Snapshot",
                                body =
                                    buildString {
                                        append(uiState.memoryGovernance.summary.ifBlank { "entries=0" }).append('\n')
                                        append("workspace=").append(uiState.memoryGovernance.workspaceSummary.ifBlank { "-" }).append('\n')
                                        append("user_model=").append(productShell.userModel.summary.ifBlank { "-" }).append('\n')
                                        append("top_types=").append(
                                            uiState.memoryGovernance.entries
                                                .take(5)
                                                .joinToString(", ") { it.type.wireName }
                                                .ifBlank { "-" },
                                        ).append('\n')
                                        append("next=使用下方治理区直接查看、写入或删除记忆")
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Recent Changes",
                                body =
                                    buildString {
                                        if (uiState.memoryGovernance.auditTrail.isEmpty()) {
                                            append("最近没有治理变更。")
                                        } else {
                                            uiState.memoryGovernance.auditTrail.take(4).forEach { audit ->
                                                append("- ").append(audit.action).append(" | ").append(audit.summary).append('\n')
                                            }
                                        }
                                    }.trim(),
                            ),
                            MainAssistantShellCard(
                                title = "Recall Guidance",
                                body =
                                    buildString {
                                        append("recommended=").append(
                                            recommendedCommandsForPageFromSupport(uiState, MainAssistantShellPage.MEMORY)
                                                .take(3)
                                                .joinToString(" | ")
                                                .ifBlank { "-" },
                                        ).append('\n')
                                        if (productShell.userModel.lines.isNotEmpty()) {
                                            productShell.userModel.lines.take(3).forEach { append(it).append('\n') }
                                        } else {
                                            append("暂无更细的用户模型摘要。")
                                        }
                                    }.trim(),
                            ),
                        ),
                    actions =
                        listOf(
                            MainAssistantShellAction("执行 /memory-governance", MainAssistantShellActionType.RUN_COMMAND, command = "/memory-governance"),
                            MainAssistantShellAction("填入写入", MainAssistantShellActionType.FILL_COMMAND, command = "/upsert-memory contact \"张三\" \"同事\""),
                            MainAssistantShellAction("打开治理", MainAssistantShellActionType.SWITCH_PAGE, targetPage = MainAssistantShellPage.GOVERNANCE),
                        ),
                )
        }
    views.shellPageBadgeText.updateTextIfChanged(selectedPage.label)
    views.shellPageTitleText.updateTextIfChanged(pageBody.title)
    views.shellPageSummaryText.updateTextIfChanged(pageBody.summary)
    renderCard(views.shellPagePrimaryCard, views.shellPagePrimaryTitleText, views.shellPagePrimaryText, pageBody.cards.getOrNull(0))
    renderCard(views.shellPageSecondaryCard, views.shellPageSecondaryTitleText, views.shellPageSecondaryText, pageBody.cards.getOrNull(1))
    renderCard(views.shellPageTertiaryCard, views.shellPageTertiaryTitleText, views.shellPageTertiaryText, pageBody.cards.getOrNull(2))
    renderActionButton(views.shellActionPrimaryButton, pageBody.actions.getOrNull(0), primary = true)
    renderActionButton(views.shellActionSecondaryButton, pageBody.actions.getOrNull(1), primary = false)
    renderActionButton(views.shellActionTertiaryButton, pageBody.actions.getOrNull(2), primary = false)
    views.shellCommandComposer.visibility = if (selectedPage == MainAssistantShellPage.COMMAND) View.VISIBLE else View.GONE
    views.shellCommandResultText.updateTextIfChanged(commandResult.ifBlank { "在这里运行 slash command，结果会显示在这里。" })
    val topLevelPage = selectedPage.topLevelPage()
    updatePageButton(views.shellPageTodayButton, topLevelPage == MainAssistantShellPage.TODAY)
    updatePageButton(views.shellPageInboxButton, topLevelPage == MainAssistantShellPage.INBOX)
    updatePageButton(views.shellPageViewerButton, topLevelPage == MainAssistantShellPage.APPROVALS)
    updatePageButton(views.shellPageApprovalsButton, topLevelPage == MainAssistantShellPage.COMMAND)
    updatePageButton(views.shellPageSessionsButton, topLevelPage == MainAssistantShellPage.WORKBENCH)
    updatePageButton(views.shellPageCommandButton, selectedPage == MainAssistantShellPage.VIEWER)
    updatePageButton(views.shellPageRoutineButton, selectedPage == MainAssistantShellPage.SESSIONS)
    updatePageButton(views.shellPageGovernanceButton, selectedPage == MainAssistantShellPage.GOVERNANCE)
    updatePageButton(views.shellPageEntryButton, selectedPage == MainAssistantShellPage.MEMORY)
    updatePageButton(views.shellPageMemoryButton, false)
    return pageBody
}

private fun renderPermissionProductTabsCard(
    productShell: AssistantProductShellSnapshot,
    focusSessionId: String,
): String =
    buildString {
        append("summary=").append(productShell.governanceShell.permissionProductSummary.ifBlank { "-" }).append('\n')
        append("active_tab=").append(productShell.governanceShell.permissionProductActiveTab.ifBlank { "-" }).append('\n')
        productShell.governanceShell.permissionProductTabs.forEach { tab ->
            append(if (tab.active) "* " else "- ")
                .append(tab.title.ifBlank { tab.id })
                .append(" | count=").append(tab.count)
                .append(" | ").append(tab.summary.ifBlank { "-" })
                .append('\n')
        }
        append("query=").append(buildPermissionProductCommand(sessionId = focusSessionId, tab = productShell.governanceShell.permissionProductActiveTab))
    }.trim()

private fun renderPermissionProductCard(
    card: com.lmx.xiaoxuanagent.assistantos.AssistantPermissionProductCardSnapshot?,
): String =
    if (card == null) {
        "当前没有更细的授权卡片。"
    } else {
        buildString {
            append("type=").append(card.cardType.ifBlank { "-" }).append('\n')
            append("behavior=").append(card.behavior.ifBlank { "-" }).append('\n')
            append("scope=").append(card.scope.ifBlank { "-" }).append('\n')
            card.subtitle.takeIf { it.isNotBlank() }?.let { append("subtitle=").append(it).append('\n') }
            card.sourceTag.takeIf { it.isNotBlank() }?.let { append("source=").append(it).append('\n') }
            card.surfaceHint.takeIf { it.isNotBlank() }?.let { append("surface=").append(it).append('\n') }
            append("explanation=").append(card.explanation.ifBlank { "-" }).append('\n')
            append("action=").append(card.primaryCommand.ifBlank { "-" })
        }.trim()
    }

private fun renderGovernanceOverviewCard(
    productShell: AssistantProductShellSnapshot,
    uiState: MainAssistantShellUiState,
): String =
    buildString {
        append("consent=").append(productShell.governanceShell.consentSummary.ifBlank { "-" }).append('\n')
        append("privacy=").append(productShell.governanceShell.privacySummary.ifBlank { "-" }).append('\n')
        append("history=").append(productShell.governanceShell.historySummary.ifBlank { "-" }).append('\n')
        append("retention=").append(productShell.governanceShell.retentionSummary.ifBlank { "-" }).append('\n')
        append("provider_policy=").append(productShell.governanceShell.providerPolicySummary.ifBlank { "-" }).append('\n')
        uiState.providerRegistryLines.take(3).forEach { append("registry | ").append(it).append('\n') }
    }.trim()

private fun buildPermissionProductCommand(
    sessionId: String,
    tab: String = "",
): String =
    buildString {
        append("/permission-product")
        sessionId.takeIf { it.isNotBlank() }?.let { append(" --session-id ").append(it) }
        tab.takeIf { it.isNotBlank() }?.let { append(" --tab ").append(it) }
    }

private fun buildPermissionCenterCommand(
    sessionId: String,
): String =
    buildString {
        append("/permission-center")
        sessionId.takeIf { it.isNotBlank() }?.let { append(" --session-id ").append(it) }
    }

private fun buildSafetyCenterCommand(
    sessionId: String,
): String =
    buildString {
        append("/safety-center")
        sessionId.takeIf { it.isNotBlank() }?.let { append(" --session-id ").append(it) }
    }

private fun recommendedCommandsForPageFromSupport(
    uiState: MainAssistantShellUiState,
    page: MainAssistantShellPage,
): List<String> = MainAssistantShellBinder.recommendedCommandsForPage(uiState, page)
