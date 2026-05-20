package com.lmx.xiaoxuanagent

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicy
import com.lmx.xiaoxuanagent.agent.PlannerProviderFeedbackStore
import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicyStore
import com.lmx.xiaoxuanagent.agent.PlannerProviderRegistryStore
import com.lmx.xiaoxuanagent.assistantos.AssistantFeatureFlagKey
import com.lmx.xiaoxuanagent.assistantos.AssistantMobileEntryController
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantOsSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantOsStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellStore
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderState
import com.lmx.xiaoxuanagent.assistantos.AssistantTriggerRoutePolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantTriggerRoutePolicyStore
import com.lmx.xiaoxuanagent.harness.RegressionRunner
import com.lmx.xiaoxuanagent.harness.HarnessStore
import com.lmx.xiaoxuanagent.memory.PersonalMemoryEntryType
import com.lmx.xiaoxuanagent.memory.PersonalMemoryGovernanceSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformCommandRegistry
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.ConnectedAppGovernanceStore
import com.lmx.xiaoxuanagent.runtime.UtilityGovernanceStore
import com.lmx.xiaoxuanagent.skills.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MainAssistantShellUiState(
    val assistantSnapshot: AssistantOsSnapshot = AssistantOsSnapshot(),
    val controlCenterSummary: String = "",
    val inboxSummary: String = "",
    val productShell: AssistantProductShellSnapshot = AssistantProductShellSnapshot(),
    val memoryGovernance: PersonalMemoryGovernanceSnapshot = PersonalMemoryGovernanceSnapshot(),
    val providerPolicy: PlannerProviderPolicy = PlannerProviderPolicy(),
    val providerFailures: Map<String, Int> = emptyMap(),
    val providerFeedbackLines: List<String> = emptyList(),
    val providerRegistryLines: List<String> = emptyList(),
    val connectedAppGovernanceLines: List<String> = emptyList(),
    val utilityGovernanceLines: List<String> = emptyList(),
    val signalProviders: List<AssistantSignalProviderState> = emptyList(),
    val triggerPolicy: AssistantTriggerRoutePolicySnapshot = AssistantTriggerRoutePolicySnapshot(),
    val commandCatalogLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val entrySurfaceLines: List<String> = emptyList(),
    val skillCatalogLines: List<String> = emptyList(),
    val regressionLines: List<String> = emptyList(),
)

internal enum class MainAssistantShellPage(
    val label: String,
    val intentValue: String,
    val defaultCommand: String,
) {
    TODAY("今日", "today", "/today"),
    INBOX("收件箱", "inbox", "/inbox"),
    APPROVALS("审批", "approvals", "/approval-center"),
    COMMAND("提问", "command", "/help"),
    WORKBENCH("工作台", "workbench", "/viewer"),
    VIEWER("Viewer", "viewer", "/viewer"),
    SESSIONS("会话", "sessions", "/sessions"),
    ROUTINE("节律", "routine", "/routine-center"),
    GOVERNANCE("治理", "governance", "/governance"),
    ENTRY("入口", "entry", "/entry-surfaces"),
    MEMORY("记忆", "memory", "/memory-governance"),
    ;

    companion object {
        fun fromIntentValue(value: String): MainAssistantShellPage? =
            entries.firstOrNull { it.intentValue == value }
    }
}

private val workbenchDetailPages =
    setOf(
        MainAssistantShellPage.VIEWER,
        MainAssistantShellPage.SESSIONS,
        MainAssistantShellPage.ROUTINE,
        MainAssistantShellPage.GOVERNANCE,
        MainAssistantShellPage.ENTRY,
        MainAssistantShellPage.MEMORY,
    )

internal fun MainAssistantShellPage.isWorkbenchDetail(): Boolean = this in workbenchDetailPages

internal fun MainAssistantShellPage.isWorkbenchFamily(): Boolean =
    this == MainAssistantShellPage.WORKBENCH || isWorkbenchDetail()

internal fun MainAssistantShellPage.topLevelPage(): MainAssistantShellPage =
    if (isWorkbenchFamily()) {
        MainAssistantShellPage.WORKBENCH
    } else {
        this
    }

internal data class MainAssistantShellPageBody(
    val title: String,
    val summary: String,
    val cards: List<MainAssistantShellCard>,
    val actions: List<MainAssistantShellAction>,
)

internal data class MainAssistantShellCard(
    val title: String,
    val body: String,
)

internal enum class MainAssistantShellActionType {
    RUN_COMMAND,
    FILL_COMMAND,
    SWITCH_PAGE,
    REFRESH,
}

internal data class MainAssistantShellAction(
    val label: String,
    val type: MainAssistantShellActionType,
    val command: String = "",
    val targetPage: MainAssistantShellPage? = null,
)

internal class MainAssistantShellController(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(MainAssistantShellUiState())
    private var pendingRefreshReason: String = "initial"
    private var refreshJob: Job? = null
    @Volatile
    private var lastProductShellProjection: AssistantProductShellSnapshot? = null
    @Volatile
    private var lastAssistantOsProjectionFingerprint: String = ""
    @Volatile
    private var lastMemoryGovernanceFingerprint: String = ""
    val uiState: StateFlow<MainAssistantShellUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            _uiState.value = buildUiState("initial").also(::captureRefreshFingerprints)
        }
        scope.launch {
            AssistantProductShellStore.observe()
                .drop(1)
                .collect { snapshot ->
                    val previous = lastProductShellProjection
                    if (previous == null || !AssistantProductShellStore.isEquivalent(previous, snapshot)) {
                        lastProductShellProjection = snapshot
                        requestRefresh("product_shell_store")
                    }
                }
        }
        scope.launch {
            AssistantOsStore.observe()
                .drop(1)
                .collect { snapshot ->
                    val fingerprint = assistantOsProjectionFingerprint(snapshot)
                    if (fingerprint != lastAssistantOsProjectionFingerprint) {
                        lastAssistantOsProjectionFingerprint = fingerprint
                        requestRefresh("assistant_os_store")
                    }
                }
        }
        scope.launch {
            PersonalMemoryStore.observeGovernanceSnapshot()
                .drop(1)
                .collect { snapshot ->
                    val fingerprint = memoryGovernanceFingerprint(snapshot)
                    if (fingerprint != lastMemoryGovernanceFingerprint) {
                        lastMemoryGovernanceFingerprint = fingerprint
                        requestRefresh("memory_governance_store")
                    }
                }
        }
    }

    fun requestRefresh(reason: String) {
        pendingRefreshReason = reason
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                delay(140L)
                _uiState.value = buildUiState(pendingRefreshReason).also(::captureRefreshFingerprints)
            }
    }

    suspend fun runMutation(
        reason: String,
        block: suspend () -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            block()
        }
        requestRefresh(reason)
    }

    private suspend fun buildUiState(
        reason: String,
    ): MainAssistantShellUiState =
        withContext(Dispatchers.IO) {
            val assistantSnapshot = AssistantOsController.snapshot()
            val productShell = AssistantProductShellStore.observe().value
            val memoryGovernance = PersonalMemoryStore.observeGovernanceSnapshot().value
            MainAssistantShellUiState(
                assistantSnapshot = assistantSnapshot,
                controlCenterSummary = AssistantOsController.buildControlCenterSummary(),
                inboxSummary = AssistantOsController.buildEntryInboxSummary(),
                productShell = productShell.takeIf { it.updatedAtMs > 0L || reason != "initial" }
                    ?: SessionPlatformFacade.readProductShellSnapshot(),
                memoryGovernance =
                    memoryGovernance.takeIf { it.totalEntries > 0 || it.auditTrail.isNotEmpty() || reason != "initial" }
                        ?: PersonalMemoryStore.readGovernanceSnapshot(limit = 12, auditLimit = 8),
                providerPolicy = SessionPlatformFacade.readPlannerProviderPolicy(),
                providerFailures = PlannerProviderPolicyStore.recentFailureCountsByProvider(limit = 24),
                providerFeedbackLines = PlannerProviderFeedbackStore.summaryLines(limit = 6),
                providerRegistryLines = PlannerProviderRegistryStore.summaryLines(limit = 6),
                connectedAppGovernanceLines = ConnectedAppGovernanceStore.lines(limit = 4),
                utilityGovernanceLines = UtilityGovernanceStore.lines(limit = 5),
                signalProviders = SessionPlatformFacade.readSignalProviders(limit = 8),
                triggerPolicy = AssistantTriggerRoutePolicyStore.read(),
                commandCatalogLines = SessionPlatformCommandRegistry.catalogLines(limit = 18),
                recommendedCommands =
                    (
                        productShell.operatorShell.recommendedCommands +
                            productShell.userModel.recommendedCommands +
                            productShell.autonomyPlan.recommendedCommands +
                            productShell.swarmStrategy.recommendedCommands +
                            productShell.routineShell.recommendedCommands +
                            productShell.interruptBudget.recommendedCommands +
                            productShell.diagnostics.appPreflightRecommendedCommands +
                            SessionPlatformCommandRegistry.recommendedUsages(limit = 10)
                    ).distinct().take(12),
                entrySurfaceLines = AssistantMobileEntryController.buildEntrySurfaceLines(assistantSnapshot, productShell),
                skillCatalogLines =
                    SkillRegistry.packSummaryLines(limit = 4) +
                        SkillRegistry.catalogLines(limit = 6) +
                        SkillRegistry.providerSummaryLines(limit = 4),
                regressionLines =
                    RegressionRunner.dashboardLines(
                        aggregate = HarnessStore.readAggregateSnapshot() ?: HarnessStore.AggregateSnapshot(),
                        limit = 4,
                    ),
            )
        }

    private fun captureRefreshFingerprints(
        state: MainAssistantShellUiState,
    ) {
        lastProductShellProjection = state.productShell
        lastAssistantOsProjectionFingerprint = assistantOsProjectionFingerprint(state.assistantSnapshot)
        lastMemoryGovernanceFingerprint = memoryGovernanceFingerprint(state.memoryGovernance)
    }

    private fun assistantOsProjectionFingerprint(
        snapshot: AssistantOsSnapshot,
    ): String =
        buildString {
            append(snapshot.activeSession.sessionId).append('|')
            append(snapshot.activeSession.statusCode).append('|')
            append(snapshot.activeSession.awaitingConfirmation).append('|')
            append(snapshot.activeSession.waitingForExternal).append('|')
            append(snapshot.activeSession.turn).append('|')
            append(snapshot.pendingActions.joinToString(",") { "${it.type}:${it.title}:${it.sessionId}" }).append('|')
            append(snapshot.taskInbox.joinToString(",") { "${it.id}:${it.type}:${it.statusCode}" }).append('|')
            append(snapshot.sessionBacklog.joinToString(",") { "${it.sessionId}:${it.statusCode}:${it.turn}" }).append('|')
            append(snapshot.failedSessions.joinToString(",") { "${it.sessionId}:${it.statusCode}" }).append('|')
            append(snapshot.approvalSessions.joinToString(",") { "${it.sessionId}:${it.statusCode}" }).append('|')
            append(snapshot.recentEntries.take(6).joinToString(",") { "${it.surface}:${it.action}:${it.summary}" }).append('|')
            append(snapshot.surfaces.joinToString(",") { "${it.surface}:${it.available}:${it.enabled}:${it.summary}" })
        }

    private fun memoryGovernanceFingerprint(
        snapshot: PersonalMemoryGovernanceSnapshot,
    ): String =
        buildString {
            append(snapshot.totalEntries).append('|')
            append(snapshot.entries.joinToString(",") { "${it.entryId}:${it.type.wireName}:${it.title}:${it.summary}" }).append('|')
            append(snapshot.auditTrail.joinToString(",") { "${it.action}:${it.type.wireName}:${it.entryId}:${it.summary}" })
        }
}

internal data class MainAssistantShellViews(
    val shellPageTodayButton: Button,
    val shellPageInboxButton: Button,
    val shellPageViewerButton: Button,
    val shellPageApprovalsButton: Button,
    val shellPageSessionsButton: Button,
    val shellPageCommandButton: Button,
    val shellPageRoutineButton: Button,
    val shellPageGovernanceButton: Button,
    val shellPageEntryButton: Button,
    val shellPageMemoryButton: Button,
    val shellWorkbenchNavRow: View,
    val shellWorkbenchMemoryRow: View,
    val shellPageBadgeText: TextView,
    val shellPageTitleText: TextView,
    val shellPageSummaryText: TextView,
    val shellPagePrimaryCard: View,
    val shellPagePrimaryTitleText: TextView,
    val shellPagePrimaryText: TextView,
    val shellPageSecondaryCard: View,
    val shellPageSecondaryTitleText: TextView,
    val shellPageSecondaryText: TextView,
    val shellPageTertiaryCard: View,
    val shellPageTertiaryTitleText: TextView,
    val shellPageTertiaryText: TextView,
    val shellActionPrimaryButton: Button,
    val shellActionSecondaryButton: Button,
    val shellActionTertiaryButton: Button,
    val shellCommandComposer: View,
    val shellCommandInput: EditText,
    val shellRunCommandButton: Button,
    val shellFillCommandButton: Button,
    val shellCommandResultText: TextView,
    val assistantOsSummaryText: TextView,
    val assistantOsInboxText: TextView,
    val permissionModeButton: Button,
    val safetyModeButton: Button,
    val notificationFlagButton: Button,
    val autoReturnFlagButton: Button,
    val shareFlagButton: Button,
    val inboxFlagButton: Button,
    val overlaySurfaceButton: Button,
    val experimentButton: Button,
    val productShellControlText: TextView,
    val productShellAgendaText: TextView,
    val refreshProductShellButton: Button,
    val tipPrimaryButton: Button,
    val tipDismissButton: Button,
    val onboardingPrimaryButton: Button,
    val onboardingSkipButton: Button,
    val providerRouterText: TextView,
    val memoryEntryTypeButton: Button,
    val memoryPrimaryInput: EditText,
    val memorySecondaryInput: EditText,
    val memoryNoteInput: EditText,
    val saveMemoryButton: Button,
    val deleteMemoryButton: Button,
    val memoryGovernanceText: TextView,
    val memoryAuditText: TextView,
)

internal object MainAssistantShellBinder {
    fun render(
        views: MainAssistantShellViews,
        uiState: MainAssistantShellUiState,
        selectedMemoryEntryType: PersonalMemoryEntryType,
        selectedPage: MainAssistantShellPage,
        commandResult: String,
    ): MainAssistantShellPageBody {
        val snapshot = uiState.assistantSnapshot
        val productShell = uiState.productShell
        val memoryGovernance = uiState.memoryGovernance
        val pageBody = renderShellPage(views, uiState, selectedPage, commandResult)
        views.assistantOsSummaryText.updateTextIfChanged(uiState.controlCenterSummary)
        views.assistantOsInboxText.updateTextIfChanged(uiState.inboxSummary)
        views.permissionModeButton.updateTextIfChanged("权限: ${snapshot.permissionMode.name.lowercase()}")
        views.safetyModeButton.updateTextIfChanged("安全: ${snapshot.safetyMode.name.lowercase()}")
        views.notificationFlagButton.updateTextIfChanged(
            "通知台: ${if (snapshot.isFeatureEnabled(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER)) "on" else "off"}",
        )
        val autoReturnEnabled =
            snapshot.isFeatureEnabled(AssistantFeatureFlagKey.USER_ACTION_RETURN) ||
                snapshot.isFeatureEnabled(AssistantFeatureFlagKey.BOOTSTRAP_AUTO_RETURN)
        views.autoReturnFlagButton.updateTextIfChanged("回现场: ${if (autoReturnEnabled) "on" else "off"}")
        views.shareFlagButton.updateTextIfChanged(
            "分享入口: ${if (snapshot.isFeatureEnabled(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY)) "on" else "off"}",
        )
        views.inboxFlagButton.updateTextIfChanged(
            "收件箱: ${if (snapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) "on" else "off"}",
        )
        views.overlaySurfaceButton.updateTextIfChanged(
            when {
                !AssistantOsController.canDrawOverlays() -> "悬浮入口: 授权"
                snapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB) -> "悬浮入口: on"
                else -> "悬浮入口: off"
            },
        )
        views.experimentButton.updateTextIfChanged(
            "实验总开关: ${if (snapshot.experiments.all { it.enabled }) "on" else "off"}",
        )
        renderProductShellControl(views, productShell)
        renderProviderRouterPanel(views, uiState)
        renderMemoryGovernancePanel(views, memoryGovernance, selectedMemoryEntryType)
        return pageBody
    }

    fun updateMemoryInputHints(
        views: MainAssistantShellViews,
        selectedMemoryEntryType: PersonalMemoryEntryType,
    ) {
        views.memoryEntryTypeButton.updateTextIfChanged("类型: ${selectedMemoryEntryType.wireName}")
        when (selectedMemoryEntryType) {
            PersonalMemoryEntryType.FACT -> {
                views.memoryPrimaryInput.hint = "事实内容"
                views.memorySecondaryInput.hint = "无需填写"
                views.memoryNoteInput.hint = "备注"
            }

            PersonalMemoryEntryType.CONTACT -> {
                views.memoryPrimaryInput.hint = "联系人名"
                views.memorySecondaryInput.hint = "别名，多个用逗号分隔"
                views.memoryNoteInput.hint = "备注"
            }

            PersonalMemoryEntryType.LOCATION -> {
                views.memoryPrimaryInput.hint = "地点名"
                views.memorySecondaryInput.hint = "地点分类"
                views.memoryNoteInput.hint = "备注"
            }

            PersonalMemoryEntryType.APP_PREFERENCE -> {
                views.memoryPrimaryInput.hint = "偏好内容"
                views.memorySecondaryInput.hint = "profileId"
                views.memoryNoteInput.hint = "备注"
            }

            PersonalMemoryEntryType.SAFETY_RULE -> {
                views.memoryPrimaryInput.hint = "安全规则"
                views.memorySecondaryInput.hint = "level，例如 confirm / block"
                views.memoryNoteInput.hint = "备注"
            }

            PersonalMemoryEntryType.RESULT_ARTIFACT -> {
                views.memoryPrimaryInput.hint = "结果标题"
                views.memorySecondaryInput.hint = "intentType"
                views.memoryNoteInput.hint = "结果摘要"
            }

            PersonalMemoryEntryType.CORRECTION_TEMPLATE -> {
                views.memoryPrimaryInput.hint = "模板类型"
                views.memorySecondaryInput.hint = "参数"
                views.memoryNoteInput.hint = "纠错指令"
            }
        }
    }

    fun recommendedCommandsForPage(
        uiState: MainAssistantShellUiState,
        page: MainAssistantShellPage,
    ): List<String> {
        val productShell = uiState.productShell
        return when (page) {
            MainAssistantShellPage.TODAY ->
                listOf("/today", productShell.digestShell.actionCommand) +
                    productShell.autonomyPlan.recommendedCommands +
                    productShell.routineShell.recommendedCommands +
                    productShell.interruptBudget.recommendedCommands

            MainAssistantShellPage.INBOX ->
                listOf("/inbox", "/approval-center") +
                    productShell.tips.firstOrNull()?.let { listOf("/tip --tip-id ${it.id} --action complete") }.orEmpty()

            MainAssistantShellPage.WORKBENCH ->
                listOf(
                    "/viewer",
                    "/sessions",
                    "/governance",
                    "/entry-surfaces",
                    "/memory-governance",
                    "/routine-center",
                ) +
                    productShell.viewerShell.recommendedCommands +
                    productShell.governanceShell.recommendedCommands +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.VIEWER ->
                listOf("/viewer", "/timeline", "/graph") +
                    productShell.viewerShell.recommendedCommands +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.APPROVALS ->
                listOf("/approval-center", "/approvals", "/approve-cap approval_id", "/reject-cap approval_id") +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.COMMAND ->
                uiState.recommendedCommands + listOf("/regression-plan", "/run-regression --limit 4") + SessionPlatformCommandRegistry.recommendedUsages(limit = 8)

            MainAssistantShellPage.SESSIONS ->
                listOf("/sessions", "/viewer", "/compare left_session_id right_session_id") +
                    productShell.swarmStrategy.recommendedCommands +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.ROUTINE ->
                listOf(
                    "/routine-center",
                    "/routine-policy",
                    "/set-routine-policy --focus-theme \"消息收口\"",
                    "/digest-policy",
                    "/set-digest-policy --enabled true",
                    "/quiet-hours",
                    "/set-quiet-hours --enabled true --start-local-time 22:30 --end-local-time 08:00",
                    "/interrupt-policy",
                    "/set-interrupt-policy --base-budget 4 --focus-budget 2",
                )

            MainAssistantShellPage.GOVERNANCE ->
                listOf("/governance", "/permission-product", "/permission-center", "/provider-policy", "/memory-governance", "/approvals") +
                    productShell.governanceShell.recommendedCommands +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.ENTRY ->
                listOf("/entry-surfaces", "/interrupt-budget", "/digest") +
                    productShell.diagnostics.appPreflightRecommendedCommands +
                    productShell.autonomyPlan.recommendedCommands

            MainAssistantShellPage.MEMORY ->
                listOf("/memory", "/memory-governance", "/memory-workspace", "/upsert-memory contact \"张三\" \"同事\"") +
                    productShell.userModel.recommendedCommands
        }.filter { it.isNotBlank() }.distinct()
    }

}
