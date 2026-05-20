package com.lmx.xiaoxuanagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.lmx.xiaoxuanagent.accessibility.ForegroundAutomationSession
import com.lmx.xiaoxuanagent.assistantos.AssistantEntrySurface
import com.lmx.xiaoxuanagent.assistantos.AssistantExperimentKey
import com.lmx.xiaoxuanagent.assistantos.AssistantFeatureFlagKey
import com.lmx.xiaoxuanagent.assistantos.AssistantInvocationCoordinator
import com.lmx.xiaoxuanagent.assistantos.AssistantMobileEntryController
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantRuntimeServiceController
import com.lmx.xiaoxuanagent.assistantos.AssistantShellIntentRouter
import com.lmx.xiaoxuanagent.assistantos.AssistantShareSheetSignalProvider
import com.lmx.xiaoxuanagent.assistantos.AssistantVoiceInteractionStore
import com.lmx.xiaoxuanagent.assistantos.AssistantVoiceRuntimeController
import com.lmx.xiaoxuanagent.entry.AssistantEntryDirective
import com.lmx.xiaoxuanagent.entry.EntryRouter
import com.lmx.xiaoxuanagent.memory.PersonalMemoryEntryType
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusCategory
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.DebugUiState
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionPlatformCommandRegistry
import com.lmx.xiaoxuanagent.runtime.SessionPlatformService
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.TargetAppReturnTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var queryInput: EditText
    private lateinit var startAgentButton: Button
    private lateinit var stopAgentButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var assistantShellSection: View
    private lateinit var homeStatusTitleText: TextView
    private lateinit var homeStatusSummaryText: TextView
    private lateinit var homeStatusDetailText: TextView
    private lateinit var approveActionButton: Button
    private lateinit var rejectActionButton: Button
    private lateinit var takeoverCorrectionCard: View
    private lateinit var takeoverCorrectionInput: EditText
    private lateinit var applyCorrectionButton: Button
    private lateinit var shellPageTodayButton: Button
    private lateinit var shellPageInboxButton: Button
    private lateinit var shellPageViewerButton: Button
    private lateinit var shellPageApprovalsButton: Button
    private lateinit var shellPageSessionsButton: Button
    private lateinit var shellPageCommandButton: Button
    private lateinit var shellPageRoutineButton: Button
    private lateinit var shellPageGovernanceButton: Button
    private lateinit var shellPageEntryButton: Button
    private lateinit var shellPageMemoryButton: Button
    private lateinit var shellWorkbenchNavRow: View
    private lateinit var shellWorkbenchMemoryRow: View
    private lateinit var shellPageBadgeText: TextView
    private lateinit var shellPageTitleText: TextView
    private lateinit var shellPageSummaryText: TextView
    private lateinit var shellPagePrimaryCard: View
    private lateinit var shellPagePrimaryTitleText: TextView
    private lateinit var shellPagePrimaryText: TextView
    private lateinit var shellPageSecondaryCard: View
    private lateinit var shellPageSecondaryTitleText: TextView
    private lateinit var shellPageSecondaryText: TextView
    private lateinit var shellPageTertiaryCard: View
    private lateinit var shellPageTertiaryTitleText: TextView
    private lateinit var shellPageTertiaryText: TextView
    private lateinit var shellActionPrimaryButton: Button
    private lateinit var shellActionSecondaryButton: Button
    private lateinit var shellActionTertiaryButton: Button
    private lateinit var shellCommandComposer: View
    private lateinit var shellCommandInput: EditText
    private lateinit var shellRunCommandButton: Button
    private lateinit var shellFillCommandButton: Button
    private lateinit var shellCommandResultText: TextView
    private lateinit var permissionModeButton: Button
    private lateinit var safetyModeButton: Button
    private lateinit var notificationFlagButton: Button
    private lateinit var autoReturnFlagButton: Button
    private lateinit var shareFlagButton: Button
    private lateinit var inboxFlagButton: Button
    private lateinit var overlaySurfaceButton: Button
    private lateinit var experimentButton: Button
    private lateinit var refreshProductShellButton: Button
    private lateinit var tipPrimaryButton: Button
    private lateinit var tipDismissButton: Button
    private lateinit var onboardingPrimaryButton: Button
    private lateinit var onboardingSkipButton: Button
    private lateinit var memoryEntryTypeButton: Button
    private lateinit var saveMemoryButton: Button
    private lateinit var deleteMemoryButton: Button
    private lateinit var overviewText: TextView
    private lateinit var contextText: TextView
    private lateinit var resultText: TextView
    private lateinit var logsText: TextView
    private lateinit var assistantOsSummaryText: TextView
    private lateinit var assistantOsInboxText: TextView
    private lateinit var productShellControlText: TextView
    private lateinit var productShellAgendaText: TextView
    private lateinit var providerRouterText: TextView
    private lateinit var memoryGovernanceText: TextView
    private lateinit var memoryAuditText: TextView
    private lateinit var memoryPrimaryInput: EditText
    private lateinit var memorySecondaryInput: EditText
    private lateinit var memoryNoteInput: EditText
    private lateinit var memoryText: TextView
    private lateinit var historyText: TextView
    private lateinit var routingText: TextView
    private lateinit var takeoverText: TextView
    private lateinit var skillText: TextView
    private lateinit var assistantWorkbenchSection: View
    private lateinit var assistantControlCenterSection: View
    private lateinit var providerRouterSection: View
    private lateinit var memoryGovernanceSection: View
    private lateinit var legacyStatusSection: View
    private lateinit var legacyMemorySection: View
    private lateinit var legacyTakeoverSection: View
    private lateinit var legacyHistorySection: View
    private lateinit var legacyRoutingSection: View
    private lateinit var legacySkillSection: View
    private lateinit var returnSummaryText: TextView
    private lateinit var confirmationCard: View
    private lateinit var confirmationTitleText: TextView
    private lateinit var confirmationSummaryText: TextView
    private lateinit var minimalHomeViews: MainMinimalHomeViews
    private lateinit var assistantShellViews: MainAssistantShellViews
    private lateinit var assistantShellController: MainAssistantShellController
    private lateinit var assistantShellInteractionController: MainAssistantShellInteractionController
    private var nextStartSource: String = "app"
    private var skipDashboardHydrateUntilMs: Long = 0L
    private var lastStatusSections: StatusSections? = null
    private var selectedMemoryEntryType: PersonalMemoryEntryType = PersonalMemoryEntryType.CONTACT
    private var assistantShellUiState: MainAssistantShellUiState = MainAssistantShellUiState()
    private var lastAssistantShellRenderFingerprint: String = ""
    private var lastAssistantEntrySyncFingerprint: String = ""
    private var startupWarmupComplete: Boolean = false
    private var startupWarmupJob: Job? = null
    private var resumeRefreshJob: Job? = null
    private val voiceEntryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (text.isBlank()) return@registerForActivityResult
            applyVoiceTranscript(text, source = "voice_entry")
        }
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                syncVoiceRuntimeState()
                startStreamingVoiceRecognition()
            } else {
                AssistantVoiceInteractionStore.syncEnvironment(
                    available = SpeechRecognizer.isRecognitionAvailable(this),
                    permissionGranted = false,
                )
                AssistantVoiceInteractionStore.markBlocked(
                    reason = "record_audio_permission_required",
                    summary = "没有录音权限，持续语音入口暂时不可用。",
                )
                assistantShellInteractionController.setCommandResult("没有录音权限，持续语音入口暂时不可用。")
                assistantShellInteractionController.setPage(MainAssistantShellPage.ENTRY)
                renderAssistantOsPanels()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRuntimeContext.init(applicationContext)
        setContentView(R.layout.activity_main)

        queryInput = findViewById(R.id.queryInput)
        startAgentButton = findViewById(R.id.startAgentButton)
        stopAgentButton = findViewById(R.id.stopAgentButton)
        pauseResumeButton = findViewById(R.id.pauseResumeButton)
        assistantShellSection = findViewById(R.id.assistantShellSection)
        homeStatusTitleText = findViewById(R.id.homeStatusTitleText)
        homeStatusSummaryText = findViewById(R.id.homeStatusSummaryText)
        homeStatusDetailText = findViewById(R.id.homeStatusDetailText)
        approveActionButton = findViewById(R.id.approveActionButton)
        rejectActionButton = findViewById(R.id.rejectActionButton)
        takeoverCorrectionCard = findViewById(R.id.takeoverCorrectionCard)
        takeoverCorrectionInput = findViewById(R.id.takeoverCorrectionInput)
        applyCorrectionButton = findViewById(R.id.applyCorrectionButton)
        shellPageTodayButton = findViewById(R.id.shellPageTodayButton)
        shellPageInboxButton = findViewById(R.id.shellPageInboxButton)
        shellPageViewerButton = findViewById(R.id.shellPageViewerButton)
        shellPageApprovalsButton = findViewById(R.id.shellPageApprovalsButton)
        shellPageSessionsButton = findViewById(R.id.shellPageSessionsButton)
        shellPageCommandButton = findViewById(R.id.shellPageCommandButton)
        shellPageRoutineButton = findViewById(R.id.shellPageRoutineButton)
        shellPageGovernanceButton = findViewById(R.id.shellPageGovernanceButton)
        shellPageEntryButton = findViewById(R.id.shellPageEntryButton)
        shellPageMemoryButton = findViewById(R.id.shellPageMemoryButton)
        shellWorkbenchNavRow = findViewById(R.id.shellWorkbenchNavRow)
        shellWorkbenchMemoryRow = findViewById(R.id.shellWorkbenchMemoryRow)
        shellPageBadgeText = findViewById(R.id.shellPageBadgeText)
        shellPageTitleText = findViewById(R.id.shellPageTitleText)
        shellPageSummaryText = findViewById(R.id.shellPageSummaryText)
        shellPagePrimaryCard = findViewById(R.id.shellPagePrimaryCard)
        shellPagePrimaryTitleText = findViewById(R.id.shellPagePrimaryTitleText)
        shellPagePrimaryText = findViewById(R.id.shellPagePrimaryText)
        shellPageSecondaryCard = findViewById(R.id.shellPageSecondaryCard)
        shellPageSecondaryTitleText = findViewById(R.id.shellPageSecondaryTitleText)
        shellPageSecondaryText = findViewById(R.id.shellPageSecondaryText)
        shellPageTertiaryCard = findViewById(R.id.shellPageTertiaryCard)
        shellPageTertiaryTitleText = findViewById(R.id.shellPageTertiaryTitleText)
        shellPageTertiaryText = findViewById(R.id.shellPageTertiaryText)
        shellActionPrimaryButton = findViewById(R.id.shellActionPrimaryButton)
        shellActionSecondaryButton = findViewById(R.id.shellActionSecondaryButton)
        shellActionTertiaryButton = findViewById(R.id.shellActionTertiaryButton)
        shellCommandComposer = findViewById(R.id.shellCommandComposer)
        shellCommandInput = findViewById(R.id.shellCommandInput)
        shellRunCommandButton = findViewById(R.id.shellRunCommandButton)
        shellFillCommandButton = findViewById(R.id.shellFillCommandButton)
        shellCommandResultText = findViewById(R.id.shellCommandResultText)
        permissionModeButton = findViewById(R.id.permissionModeButton)
        safetyModeButton = findViewById(R.id.safetyModeButton)
        notificationFlagButton = findViewById(R.id.notificationFlagButton)
        autoReturnFlagButton = findViewById(R.id.autoReturnFlagButton)
        shareFlagButton = findViewById(R.id.shareFlagButton)
        inboxFlagButton = findViewById(R.id.inboxFlagButton)
        overlaySurfaceButton = findViewById(R.id.overlaySurfaceButton)
        experimentButton = findViewById(R.id.experimentButton)
        refreshProductShellButton = findViewById(R.id.refreshProductShellButton)
        tipPrimaryButton = findViewById(R.id.tipPrimaryButton)
        tipDismissButton = findViewById(R.id.tipDismissButton)
        onboardingPrimaryButton = findViewById(R.id.onboardingPrimaryButton)
        onboardingSkipButton = findViewById(R.id.onboardingSkipButton)
        memoryEntryTypeButton = findViewById(R.id.memoryEntryTypeButton)
        saveMemoryButton = findViewById(R.id.saveMemoryButton)
        deleteMemoryButton = findViewById(R.id.deleteMemoryButton)
        overviewText = findViewById(R.id.overviewText)
        contextText = findViewById(R.id.contextText)
        resultText = findViewById(R.id.resultText)
        logsText = findViewById(R.id.logsText)
        assistantOsSummaryText = findViewById(R.id.assistantOsSummaryText)
        assistantOsInboxText = findViewById(R.id.assistantOsInboxText)
        productShellControlText = findViewById(R.id.productShellControlText)
        productShellAgendaText = findViewById(R.id.productShellAgendaText)
        providerRouterText = findViewById(R.id.providerRouterText)
        memoryGovernanceText = findViewById(R.id.memoryGovernanceText)
        memoryAuditText = findViewById(R.id.memoryAuditText)
        memoryPrimaryInput = findViewById(R.id.memoryPrimaryInput)
        memorySecondaryInput = findViewById(R.id.memorySecondaryInput)
        memoryNoteInput = findViewById(R.id.memoryNoteInput)
        memoryText = findViewById(R.id.memoryText)
        historyText = findViewById(R.id.historyText)
        routingText = findViewById(R.id.routingText)
        takeoverText = findViewById(R.id.takeoverText)
        skillText = findViewById(R.id.skillText)
        assistantWorkbenchSection = findViewById(R.id.assistantWorkbenchSection)
        assistantControlCenterSection = findViewById(R.id.assistantControlCenterSection)
        providerRouterSection = findViewById(R.id.providerRouterSection)
        memoryGovernanceSection = findViewById(R.id.memoryGovernanceSection)
        legacyStatusSection = findViewById(R.id.legacyStatusSection)
        legacyMemorySection = findViewById(R.id.legacyMemorySection)
        legacyTakeoverSection = findViewById(R.id.legacyTakeoverSection)
        legacyHistorySection = findViewById(R.id.legacyHistorySection)
        legacyRoutingSection = findViewById(R.id.legacyRoutingSection)
        legacySkillSection = findViewById(R.id.legacySkillSection)
        returnSummaryText = findViewById(R.id.returnSummaryText)
        confirmationCard = findViewById(R.id.confirmationCard)
        confirmationTitleText = findViewById(R.id.confirmationTitleText)
        confirmationSummaryText = findViewById(R.id.confirmationSummaryText)
        minimalHomeViews =
            MainMinimalHomeViews(
                assistantShellSection = assistantShellSection,
                assistantWorkbenchSection = assistantWorkbenchSection,
                assistantControlCenterSection = assistantControlCenterSection,
                providerRouterSection = providerRouterSection,
                memoryGovernanceSection = memoryGovernanceSection,
                legacyStatusSection = legacyStatusSection,
                legacyMemorySection = legacyMemorySection,
                legacyTakeoverSection = legacyTakeoverSection,
                legacyHistorySection = legacyHistorySection,
                legacyRoutingSection = legacyRoutingSection,
                legacySkillSection = legacySkillSection,
                homeStatusTitleText = homeStatusTitleText,
                homeStatusSummaryText = homeStatusSummaryText,
                homeStatusDetailText = homeStatusDetailText,
            )
        assistantShellViews =
            MainAssistantShellViews(
                shellPageTodayButton = shellPageTodayButton,
                shellPageInboxButton = shellPageInboxButton,
                shellPageViewerButton = shellPageViewerButton,
                shellPageApprovalsButton = shellPageApprovalsButton,
                shellPageSessionsButton = shellPageSessionsButton,
                shellPageCommandButton = shellPageCommandButton,
                shellPageRoutineButton = shellPageRoutineButton,
                shellPageGovernanceButton = shellPageGovernanceButton,
                shellPageEntryButton = shellPageEntryButton,
                shellPageMemoryButton = shellPageMemoryButton,
                shellWorkbenchNavRow = shellWorkbenchNavRow,
                shellWorkbenchMemoryRow = shellWorkbenchMemoryRow,
                shellPageBadgeText = shellPageBadgeText,
                shellPageTitleText = shellPageTitleText,
                shellPageSummaryText = shellPageSummaryText,
                shellPagePrimaryCard = shellPagePrimaryCard,
                shellPagePrimaryTitleText = shellPagePrimaryTitleText,
                shellPagePrimaryText = shellPagePrimaryText,
                shellPageSecondaryCard = shellPageSecondaryCard,
                shellPageSecondaryTitleText = shellPageSecondaryTitleText,
                shellPageSecondaryText = shellPageSecondaryText,
                shellPageTertiaryCard = shellPageTertiaryCard,
                shellPageTertiaryTitleText = shellPageTertiaryTitleText,
                shellPageTertiaryText = shellPageTertiaryText,
                shellActionPrimaryButton = shellActionPrimaryButton,
                shellActionSecondaryButton = shellActionSecondaryButton,
                shellActionTertiaryButton = shellActionTertiaryButton,
                shellCommandComposer = shellCommandComposer,
                shellCommandInput = shellCommandInput,
                shellRunCommandButton = shellRunCommandButton,
                shellFillCommandButton = shellFillCommandButton,
                shellCommandResultText = shellCommandResultText,
                assistantOsSummaryText = assistantOsSummaryText,
                assistantOsInboxText = assistantOsInboxText,
                permissionModeButton = permissionModeButton,
                safetyModeButton = safetyModeButton,
                notificationFlagButton = notificationFlagButton,
                autoReturnFlagButton = autoReturnFlagButton,
                shareFlagButton = shareFlagButton,
                inboxFlagButton = inboxFlagButton,
                overlaySurfaceButton = overlaySurfaceButton,
                experimentButton = experimentButton,
                productShellControlText = productShellControlText,
                productShellAgendaText = productShellAgendaText,
                refreshProductShellButton = refreshProductShellButton,
                tipPrimaryButton = tipPrimaryButton,
                tipDismissButton = tipDismissButton,
                onboardingPrimaryButton = onboardingPrimaryButton,
                onboardingSkipButton = onboardingSkipButton,
                providerRouterText = providerRouterText,
                memoryEntryTypeButton = memoryEntryTypeButton,
                memoryPrimaryInput = memoryPrimaryInput,
                memorySecondaryInput = memorySecondaryInput,
                memoryNoteInput = memoryNoteInput,
                saveMemoryButton = saveMemoryButton,
                deleteMemoryButton = deleteMemoryButton,
                memoryGovernanceText = memoryGovernanceText,
                memoryAuditText = memoryAuditText,
            )
        assistantShellController = MainAssistantShellController(lifecycleScope)
        assistantShellInteractionController =
            MainAssistantShellInteractionController(
                scope = lifecycleScope,
                views = assistantShellViews,
                renderPanels = ::renderAssistantOsPanels,
                requestRefresh = assistantShellController::requestRefresh,
                executeCommand = { raw ->
                    runCatching {
                        SessionPlatformService.executeCommandInput(raw, entrySource = "main_shell_command")
                    }.fold(
                        onSuccess = { result ->
                            MainAssistantShellCommandExecutionResult(
                                success = result.success,
                                summary = result.summary,
                                lines = result.lines,
                            )
                        },
                        onFailure = { error ->
                            MainAssistantShellCommandExecutionResult(
                                success = false,
                                summary = "命令入口异常：${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}",
                                lines = listOf(error.javaClass.simpleName),
                            )
                        },
                    )
                },
                handoffAfterCommand = handoff@{ raw ->
                    val directive = SessionRuntime.resolveTargetAppLaunchDirective() ?: return@handoff
                    if (directive.targetPackageName.isBlank() || directive.targetPackageName == packageName) {
                        return@handoff
                    }
                    val launchIntent = AppLaunchResolver.resolve(packageManager, directive.targetPackageName) ?: return@handoff
                    runCatching {
                        startActivity(launchIntent)
                        if (directive.moveAssistantToBack) {
                            moveTaskToBack(true)
                        }
                        DebugAgentStore.appendLog(
                            "shell command handoff: cmd=${raw.take(80)}, reason=${directive.reason}, pkg=${directive.targetPackageName}",
                        )
                    }.onFailure { error ->
                        DebugAgentStore.appendLog(
                            "shell command handoff failed: cmd=${raw.take(80)}, pkg=${directive.targetPackageName}, error=${error.message.orEmpty()}",
                        )
                    }
                },
            )
        assistantShellInteractionController.bind { assistantShellUiState }

        overviewText.text = getString(R.string.permission_tip)
        assistantShellInteractionController.updateMemoryInputHints(selectedMemoryEntryType)
        renderAssistantOsPanels()
        scheduleDeferredStartupInitialization(intent)

        lifecycleScope.launch {
            assistantShellController.uiState.collect { state ->
                assistantShellUiState = state
                val renderFingerprint = assistantShellRenderFingerprint(state)
                if (renderFingerprint != lastAssistantShellRenderFingerprint) {
                    lastAssistantShellRenderFingerprint = renderFingerprint
                    renderAssistantOsPanels()
                }
                val entryFingerprint = assistantEntrySyncFingerprint(state)
                if (entryFingerprint != lastAssistantEntrySyncFingerprint) {
                    lastAssistantEntrySyncFingerprint = entryFingerprint
                    AssistantMobileEntryController.sync(this@MainActivity)
                }
            }
        }

        startAgentButton.setOnClickListener {
            val task = queryInput.text?.toString()?.trim().orEmpty()
            val source = nextStartSource
            startAgentFromDraft(task = task, source = source, surface = AssistantEntrySurface.MAIN_APP)
        }

        stopAgentButton.setOnClickListener {
            AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "stop_agent", "主界面停止任务")
            SessionRuntime.Lifecycle.stopAgent("已停止自动任务。")
        }

        pauseResumeButton.setOnClickListener {
            if (pauseResumeButton.text == getString(R.string.resume_agent)) {
                AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "resume_agent", "主界面继续任务")
                SessionRuntime.continueAgent()
                returnToTargetAppIfNeeded(TargetAppReturnTrigger.USER_ACTION)
            } else {
                AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "pause_agent", "主界面暂停任务")
                SessionRuntime.Lifecycle.pauseAgent()
            }
        }

        approveActionButton.setOnClickListener {
            AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "approve_risky_action", "主界面确认高风险动作")
            val confirmation = SessionRuntime.Lifecycle.approvePendingSafetyConfirmation() ?: return@setOnClickListener
            clearReturnSummary()
            confirmationSummaryText.updateTextIfChanged(confirmation.summary)
            returnToTargetAppIfNeeded(TargetAppReturnTrigger.USER_ACTION)
        }

        rejectActionButton.setOnClickListener {
            AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "reject_risky_action", "主界面取消高风险动作")
            SessionRuntime.rejectPendingSafetyConfirmation()
        }

        applyCorrectionButton.setOnClickListener {
            val correction = takeoverCorrectionInput.text?.toString()?.trim().orEmpty()
            clearReturnSummary()
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "apply_correction",
                correction.ifBlank { "带空纠错继续" },
            )
            if (DebugAgentStore.uiState.value.safety.awaitingConfirmation) {
                val confirmation = SessionRuntime.Lifecycle.approvePendingSafetyConfirmation(correction) ?: return@setOnClickListener
                takeoverCorrectionInput.setText("")
                confirmationSummaryText.updateTextIfChanged(confirmation.summary)
            } else {
                SessionRuntime.continueAgent(correction)
                takeoverCorrectionInput.setText("")
            }
            returnToTargetAppIfNeeded(TargetAppReturnTrigger.USER_ACTION)
        }

        permissionModeButton.setOnClickListener {
            val snapshot = AssistantOsController.cyclePermissionMode()
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "cycle_permission_mode",
                "切换到 ${snapshot.permissionMode.name.lowercase()}",
            )
            assistantShellController.requestRefresh("cycle_permission_mode")
            DebugAgentStore.refreshEntrySurfaces()
        }

        safetyModeButton.setOnClickListener {
            val snapshot = AssistantOsController.cycleSafetyMode()
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "cycle_safety_mode",
                "切换到 ${snapshot.safetyMode.name.lowercase()}",
            )
            assistantShellController.requestRefresh("cycle_safety_mode")
            DebugAgentStore.refreshEntrySurfaces()
        }

        notificationFlagButton.setOnClickListener {
            val snapshot = AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER)
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "toggle_notification_control_center",
                "通知控制台=${snapshot.isFeatureEnabled(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER)}",
            )
            assistantShellController.requestRefresh("toggle_notification_control_center")
            DebugAgentStore.refreshEntrySurfaces()
        }

        autoReturnFlagButton.setOnClickListener {
            val userReturnEnabledBefore = AssistantOsController.isFeatureEnabled(AssistantFeatureFlagKey.USER_ACTION_RETURN)
            AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.USER_ACTION_RETURN)
            val snapshot = AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.BOOTSTRAP_AUTO_RETURN)
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "toggle_auto_return",
                "userReturn=${!userReturnEnabledBefore}, bootstrapReturn=${snapshot.isFeatureEnabled(AssistantFeatureFlagKey.BOOTSTRAP_AUTO_RETURN)}",
            )
            assistantShellController.requestRefresh("toggle_auto_return")
            DebugAgentStore.refreshEntrySurfaces()
        }

        shareFlagButton.setOnClickListener {
            val snapshot = AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY)
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "toggle_share_entry",
                "分享入口=${snapshot.isFeatureEnabled(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY)}",
            )
            assistantShellController.requestRefresh("toggle_share_entry")
        }

        inboxFlagButton.setOnClickListener {
            val enabledBefore = AssistantOsController.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)
            val snapshot = AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.ASSISTANT_INBOX)
            if (!enabledBefore && snapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) {
                AssistantOsController.recordEntry(
                    AssistantEntrySurface.MAIN_APP,
                    "toggle_assistant_inbox",
                    "助手收件箱=true",
                )
            }
            assistantShellController.requestRefresh("toggle_assistant_inbox")
        }

        overlaySurfaceButton.setOnClickListener {
            val overlayReady = AssistantOsController.canDrawOverlays()
            val overlayEnabled = AssistantOsController.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
            if (!overlayReady) {
                if (!overlayEnabled) {
                    AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
                }
                AssistantOsController.recordEntry(
                    AssistantEntrySurface.MAIN_APP,
                    "request_overlay_permission",
                    "请求开启悬浮入口权限",
                )
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } else {
                val snapshot = AssistantOsController.toggleFeatureFlag(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
                AssistantOsController.recordEntry(
                    AssistantEntrySurface.MAIN_APP,
                    "toggle_overlay_surface",
                    "悬浮入口=${snapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)}",
                )
            }
            assistantShellController.requestRefresh("toggle_overlay_surface")
        }

        experimentButton.setOnClickListener {
            val enableAll = !AssistantOsController.areAllExperimentsEnabled()
            val snapshot = AssistantOsController.setAllExperimentsEnabled(enableAll)
            AssistantOsController.recordEntry(
                AssistantEntrySurface.MAIN_APP,
                "toggle_experiment_group",
                "实验总开关=${snapshot.experiments.all { it.enabled }}",
            )
            assistantShellController.requestRefresh("toggle_experiment_group")
            DebugAgentStore.refreshEntrySurfaces()
        }

        refreshProductShellButton.setOnClickListener {
            lifecycleScope.launch {
                assistantShellController.runMutation("refresh_product_shell") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "refresh_product_shell",
                        "主界面刷新 product shell",
                    )
                    SessionPlatformFacade.refreshProductShellSnapshot(reason = "main_activity_control_center")
                }
            }
        }

        tipPrimaryButton.setOnClickListener {
            val tip = assistantShellUiState.productShell.tips.firstOrNull() ?: return@setOnClickListener
            lifecycleScope.launch {
                assistantShellController.runMutation("complete_product_tip") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "complete_product_tip",
                        tip.title,
                    )
                    SessionPlatformFacade.acknowledgeProductShellTip(
                        tipId = tip.id,
                        action = "complete",
                        note = "main_activity_primary_action",
                    )
                }
            }
        }

        tipDismissButton.setOnClickListener {
            val tip = assistantShellUiState.productShell.tips.firstOrNull() ?: return@setOnClickListener
            lifecycleScope.launch {
                assistantShellController.runMutation("dismiss_product_tip") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "dismiss_product_tip",
                        tip.title,
                    )
                    SessionPlatformFacade.acknowledgeProductShellTip(
                        tipId = tip.id,
                        action = "dismiss",
                        note = "main_activity_dismiss",
                    )
                }
            }
        }

        onboardingPrimaryButton.setOnClickListener {
            val step = assistantShellUiState.productShell.onboarding.steps.firstOrNull { it.status == "pending" }
                ?: return@setOnClickListener
            lifecycleScope.launch {
                assistantShellController.runMutation("complete_onboarding_step") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "complete_onboarding_step",
                        step.title,
                    )
                    SessionPlatformFacade.updateProductOnboardingStep(
                        stepId = step.id,
                        action = "complete",
                        note = "main_activity_complete",
                    )
                }
            }
        }

        onboardingSkipButton.setOnClickListener {
            val step = assistantShellUiState.productShell.onboarding.steps.firstOrNull { it.status == "pending" }
                ?: return@setOnClickListener
            lifecycleScope.launch {
                assistantShellController.runMutation("skip_onboarding_step") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "skip_onboarding_step",
                        step.title,
                    )
                    SessionPlatformFacade.updateProductOnboardingStep(
                        stepId = step.id,
                        action = "skip",
                        note = "main_activity_skip",
                    )
                }
            }
        }

        memoryEntryTypeButton.setOnClickListener {
            selectedMemoryEntryType =
                PersonalMemoryEntryType.entries[
                    (PersonalMemoryEntryType.entries.indexOf(selectedMemoryEntryType) + 1) % PersonalMemoryEntryType.entries.size
                ]
            assistantShellInteractionController.updateMemoryInputHints(selectedMemoryEntryType)
            renderAssistantOsPanels()
        }

        saveMemoryButton.setOnClickListener {
            val primary = memoryPrimaryInput.text?.toString()?.trim().orEmpty()
            if (primary.isBlank()) return@setOnClickListener
            val secondary = memorySecondaryInput.text?.toString()?.trim().orEmpty()
            val note = memoryNoteInput.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                assistantShellController.runMutation("upsert_memory_entry") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "upsert_memory_entry",
                        "${selectedMemoryEntryType.wireName}:$primary",
                    )
                    SessionPlatformFacade.upsertMemoryGovernanceEntry(
                        typeWire = selectedMemoryEntryType.wireName,
                        primary = primary,
                        secondary = secondary,
                        note = note,
                        profileId = SessionRuntime.Planning.currentTaskProfile().id,
                    )
                }
                memoryPrimaryInput.setText("")
                memorySecondaryInput.setText("")
                memoryNoteInput.setText("")
            }
        }

        deleteMemoryButton.setOnClickListener {
            val entryRef = memoryPrimaryInput.text?.toString()?.trim().orEmpty()
            if (entryRef.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                assistantShellController.runMutation("delete_memory_entry") {
                    AssistantOsController.recordEntry(
                        AssistantEntrySurface.MAIN_APP,
                        "delete_memory_entry",
                        "${selectedMemoryEntryType.wireName}:$entryRef",
                    )
                    SessionPlatformFacade.deleteMemoryGovernanceEntry(
                        typeWire = selectedMemoryEntryType.wireName,
                        entryRef = entryRef,
                    )
                }
                memoryPrimaryInput.setText("")
            }
        }

        lifecycleScope.launch {
            DebugAgentStore.uiState.collect { state ->
                val sections = buildStatusSections(state, getString(R.string.permission_tip))
                if (sections != lastStatusSections) {
                    lastStatusSections = sections
                    overviewText.updateTextIfChanged(sections.overview)
                    contextText.updateTextIfChanged(sections.context)
                    resultText.updateTextIfChanged(sections.result)
                    logsText.updateTextIfChanged(sections.logs)
                }
                memoryText.updateTextIfChanged(state.memorySummary.ifBlank { "暂无个人记忆。" })
                historyText.updateTextIfChanged(
                    buildString {
                        append("最近任务\n")
                        append(state.recentSessionSummaries.joinToString(separator = "\n").ifBlank { "暂无最近任务。" })
                        append("\n\n回归摘要\n")
                        append(state.harnessSummary.ifBlank { "暂无回归统计。" })
                    },
                )
                routingText.updateTextIfChanged(state.routingSummary.ifBlank { "暂无路由回归统计。" })
                takeoverText.updateTextIfChanged(state.takeoverSummary.ifBlank { "暂无人工接管回归统计。" })
                skillText.updateTextIfChanged(
                    state.recentSkillInvocations.joinToString(separator = "\n").ifBlank { "暂无技能调用。" },
                )
                val statusPanel = state.status
                pauseResumeButton.updateTextIfChanged(
                    if (statusPanel.isPaused) {
                        getString(R.string.resume_agent)
                    } else {
                        getString(R.string.pause_agent)
                    },
                )
                confirmationCard.visibility = if (state.safety.awaitingConfirmation) View.VISIBLE else View.GONE
                confirmationTitleText.updateTextIfChanged(
                    state.safety.pendingConfirmationTitle.ifBlank { getString(R.string.confirmation_title_default) },
                )
                confirmationSummaryText.updateTextIfChanged(
                    state.safety.pendingConfirmationSummary.ifBlank { getString(R.string.confirmation_summary_default) },
                )
                approveActionButton.isEnabled = state.safety.awaitingConfirmation
                rejectActionButton.isEnabled = state.safety.awaitingConfirmation
                pauseResumeButton.isEnabled =
                    !state.safety.awaitingConfirmation && !statusPanel.isWaitingExternal
                val canCorrect = state.safety.awaitingConfirmation || statusPanel.isTakeoverLike
                takeoverCorrectionCard.visibility = if (canCorrect) View.VISIBLE else View.GONE
                if (!canCorrect && takeoverCorrectionInput.text?.isNotEmpty() == true) {
                    takeoverCorrectionInput.setText("")
                }
                applyCorrectionButton.isEnabled = canCorrect
                applyCorrectionButton.updateTextIfChanged(
                    if (state.safety.awaitingConfirmation) {
                        getString(R.string.apply_correction_approve)
                    } else {
                        getString(R.string.apply_correction_resume)
                    },
                )
                MainMinimalHomeSupport.render(
                    context = this@MainActivity,
                    views = minimalHomeViews,
                    state = state,
                    automation = ForegroundAutomationSession.snapshot(),
                )
            }
        }

        lifecycleScope.launch {
            DebugAgentStore.openAccessibilitySettings.collect { shouldOpen ->
                if (shouldOpen) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    DebugAgentStore.consumeAccessibilitySettingsRequest()
                }
            }
        }
    }

    private fun renderAssistantOsPanels() {
        assistantShellInteractionController.onRenderedPage(
            MainAssistantShellBinder.render(
                views = assistantShellViews,
                uiState = assistantShellUiState,
                selectedMemoryEntryType = selectedMemoryEntryType,
                selectedPage = assistantShellInteractionController.selectedPage,
                commandResult = assistantShellInteractionController.commandResult,
            ).actions,
        )
        MainMinimalHomeSupport.apply(minimalHomeViews)
        MainMinimalHomeSupport.render(
            context = this,
            views = minimalHomeViews,
            state = DebugAgentStore.uiState.value,
            automation = ForegroundAutomationSession.snapshot(),
        )
    }

    override fun onResume() {
        super.onResume()
        consumePendingVoiceTranscriptIfNeeded()
        if (startupWarmupComplete) {
            scheduleDeferredResumeRefresh(reason = "main_activity_resume")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "new_intent", intent.action.orEmpty().ifBlank { "singleTop 回调" })
        consumeIncomingIntent(intent)
    }

    private fun scheduleDeferredStartupInitialization(
        launchIntent: Intent?,
    ) {
        startupWarmupJob?.cancel()
        window.decorView.post {
            startupWarmupJob =
                lifecycleScope.launch {
                    // Let the first frame land before starting warm runtime / dashboard work.
                    delay(250)
                syncVoiceRuntimeState()
                    if (SystemClock.elapsedRealtime() >= skipDashboardHydrateUntilMs) {
                        DebugAgentStore.hydrateDashboard()
                    }
                    AssistantRuntimeServiceController.ensureStarted(this@MainActivity, reason = "main_activity_startup")
                    AssistantMobileEntryController.sync(this@MainActivity, reason = "main_activity_startup")
                    withContext(Dispatchers.IO) {
                        AssistantOsController.syncOverlaySurface()
                        AssistantOsController.recordEntry(AssistantEntrySurface.MAIN_APP, "open", "主界面进入前台")
                    }
                    val bootstrappedFromResume =
                        if (shouldBootstrapResumableSession(launchIntent)) {
                            withContext(Dispatchers.IO) {
                                SessionRuntime.Planning.bootstrapFromLatestResumeSnapshot()
                            }
                        } else {
                            false
                        }
                    consumeIncomingIntent(launchIntent)
                    if (bootstrappedFromResume) {
                        autoReturnToTargetAppIfNeeded(launchIntent)
                    }
                    withContext(Dispatchers.IO) {
                        DebugAgentStore.refreshEntrySurfaces()
                    }
                    startupWarmupComplete = true
                }
        }
    }

    private fun scheduleDeferredResumeRefresh(
        reason: String,
    ) {
        resumeRefreshJob?.cancel()
        resumeRefreshJob =
            lifecycleScope.launch {
                delay(120)
                syncVoiceRuntimeState()
                if (SystemClock.elapsedRealtime() >= skipDashboardHydrateUntilMs) {
                    DebugAgentStore.hydrateDashboard()
                }
                AssistantRuntimeServiceController.ensureStarted(this@MainActivity, reason = reason)
                AssistantMobileEntryController.sync(this@MainActivity, reason = reason)
                assistantShellController.requestRefresh(reason)
            }
    }

    private fun buildSection(
        title: String,
        lines: List<String>,
    ): String =
        buildString {
            append("== ").append(title).append(" ==\n")
            lines.forEach { line ->
                append(line).append('\n')
            }
        }

    private fun buildLogsSection(recentLogs: List<String>): String {
        val logPreview =
            recentLogs
                .take(24)
                .toMutableList()
                .apply {
                    val omitted = recentLogs.size - size
                    if (omitted > 0) {
                        add("... 省略 $omitted 条更早日志")
                    }
                }
        return buildString {
            append("== Logs ==\n")
            if (logPreview.isEmpty()) {
                append("-")
            } else {
                append(logPreview.joinToString(separator = "\n"))
            }
        }
    }

    private fun TextView.updateTextIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) {
            text = value
        }
    }

    private fun Button.updateTextIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) {
            text = value
        }
    }

    private fun assistantShellRenderFingerprint(
        state: MainAssistantShellUiState,
    ): String =
        buildString {
            append(assistantShellInteractionController.selectedPage.name).append('|')
            append(assistantShellInteractionController.lastContentPage.name).append('|')
            append(state.assistantSnapshot.activeSession.sessionId).append('|')
            append(state.assistantSnapshot.activeSession.statusCode).append('|')
            append(state.assistantSnapshot.activeSession.awaitingConfirmation).append('|')
            append(state.controlCenterSummary).append('|')
            append(state.inboxSummary).append('|')
            append(state.productShell.lastSyncReason).append('|')
            append(state.productShell.updatedAtMs).append('|')
            append(state.productShell.tips.joinToString(",") { "${it.id}:${it.status}:${it.title}" }).append('|')
            append(state.productShell.onboarding.status).append('|')
            append(state.productShell.operatorShell.summary).append('|')
            append(state.productShell.viewerShell.timelineSummary).append('|')
            append(state.productShell.routineShell.summary).append('|')
            append(state.productShell.governanceShell.summary).append('|')
            append(state.memoryGovernance.totalEntries).append('|')
            append(assistantShellInteractionController.commandResult)
        }

    private fun assistantEntrySyncFingerprint(
        state: MainAssistantShellUiState,
    ): String =
        buildString {
            append(state.assistantSnapshot.activeSession.sessionId).append('|')
            append(state.assistantSnapshot.activeSession.statusCode).append('|')
            append(state.assistantSnapshot.activeSession.awaitingConfirmation).append('|')
            append(state.assistantSnapshot.pendingActions.joinToString(",") { "${it.type}:${it.sessionId}:${it.title}" }).append('|')
            append(state.productShell.interruptBudget.summary).append('|')
            append(state.productShell.swarmStrategy.mode).append('|')
            append(state.productShell.onboarding.status).append('|')
            append(state.productShell.voiceInteraction.state).append('|')
            append(state.productShell.digestShell.summary)
        }

    private fun consumeReturnIntent(intent: Intent?) {
        val summary = AssistantShellIntentRouter.consumeReturnSummary(intent)
        if (summary.isBlank()) {
            return
        }
        returnSummaryText.updateTextIfChanged(summary)
        returnSummaryText.visibility = View.VISIBLE
        skipDashboardHydrateUntilMs = SystemClock.elapsedRealtime() + 1_200L
    }

    private fun clearReturnSummary() {
        returnSummaryText.updateTextIfChanged("")
        returnSummaryText.visibility = View.GONE
    }

    private fun consumeIncomingIntent(intent: Intent?) {
        if (intent == null) return
        consumeShellPageIntent(intent)
        consumeVoiceEntryIntent(intent)
        val directives =
            EntryRouter.resolve(
                intent = intent,
                actionExtraKey = AssistantShellIntentRouter.EXTRA_ASSISTANT_ACTION,
                returnSummaryKey = AssistantShellIntentRouter.EXTRA_RETURN_SUMMARY,
            )
        directives.forEach { directive ->
            when (directive) {
                is AssistantEntryDirective.AssistantAction -> consumeActionIntent(intent, directive.action)
                is AssistantEntryDirective.SharedText -> consumeShareTextIntent(intent, directive.text)
                is AssistantEntryDirective.ReturnSummary -> consumeReturnSummaryIntent(intent, directive.summary)
            }
        }
    }

    private fun consumeShellPageIntent(intent: Intent) {
        val page = AssistantShellIntentRouter.consumePage(intent)
        if (page.isBlank()) return
        assistantShellInteractionController.setPage(MainAssistantShellPage.fromIntentValue(page) ?: MainAssistantShellPage.TODAY)
    }

    private fun consumeVoiceEntryIntent(intent: Intent) {
        val invocation = AssistantInvocationCoordinator.resolve(intent)
        if (invocation.active) {
            val surface =
                when (invocation.entryMode) {
                    AssistantInvocationCoordinator.ENTRY_MODE_ASSIST -> AssistantEntrySurface.SYSTEM
                    AssistantInvocationCoordinator.ENTRY_MODE_SCREEN_AUTOMATION -> AssistantEntrySurface.SYSTEM
                    else -> AssistantEntrySurface.VOICE
                }
            AssistantOsController.recordEntry(
                surface = surface,
                action = "assistant_invoke",
                summary = invocation.taskSeed.ifBlank { invocation.entryMode },
            )
            if (invocation.taskSeed.isNotBlank()) {
                queryInput.setText(invocation.taskSeed)
                queryInput.setSelection(invocation.taskSeed.length)
                nextStartSource = AssistantInvocationCoordinator.entrySource(invocation)
                assistantShellInteractionController.setPage(MainAssistantShellPage.TODAY)
                returnSummaryText.updateTextIfChanged("已接收屏幕自动化任务草稿，可直接开始执行。")
                returnSummaryText.visibility = View.VISIBLE
                assistantShellInteractionController.setCommandResult("已进入屏幕自动化入口。")
                renderAssistantOsPanels()
                if (invocation.autoRun) {
                    startAgentFromDraft(
                        task = invocation.taskSeed,
                        source = AssistantInvocationCoordinator.entrySource(invocation),
                        surface = surface,
                    )
                }
            } else if (invocation.shouldLaunchVoiceEntry) {
                launchVoiceEntry()
            } else {
                assistantShellInteractionController.setPage(MainAssistantShellPage.TODAY)
                renderAssistantOsPanels()
            }
            AssistantInvocationCoordinator.consume(intent)
            return
        }
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    queryInput.setText(text)
                    queryInput.setSelection(text.length)
                    nextStartSource = "process_text"
                    AssistantOsController.recordEntry(AssistantEntrySurface.VOICE, "process_text_draft", text.take(60))
                    assistantShellInteractionController.setPage(MainAssistantShellPage.TODAY)
                    intent.action = Intent.ACTION_VIEW
                }
            }

            Intent.ACTION_VOICE_COMMAND -> launchVoiceEntry()
        }
    }

    private fun consumeActionIntent(
        intent: Intent,
        action: String,
    ) {
        val source = AssistantShellIntentRouter.readPayload(intent).actionSource
        val surface = AssistantShellIntentRouter.resolveSurface(source)
        when (action) {
            AssistantShellIntentRouter.ACTION_OPEN -> {
                AssistantOsController.recordEntry(surface, "open_control_center", "从${source.ifBlank { "notification" }}打开小轩")
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_STOP -> {
                AssistantOsController.recordEntry(surface, "stop_agent", "${source.ifBlank { "notification" }}停止任务")
                SessionRuntime.Lifecycle.stopAgent("已从通知快捷操作停止任务。")
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_PAUSE -> {
                AssistantOsController.recordEntry(surface, "pause_agent", "${source.ifBlank { "notification" }}暂停任务")
                SessionRuntime.Lifecycle.pauseAgent()
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_RESUME -> {
                AssistantOsController.recordEntry(surface, "resume_agent", "${source.ifBlank { "notification" }}继续任务")
                SessionRuntime.continueAgent()
                returnToTargetAppIfNeeded(
                    trigger = TargetAppReturnTrigger.USER_ACTION,
                    explicitReturnRequest = true,
                )
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_APPROVE -> {
                AssistantOsController.recordEntry(surface, "approve_risky_action", "${source.ifBlank { "notification" }}确认高风险动作")
                SessionRuntime.Lifecycle.approvePendingSafetyConfirmation()
                returnToTargetAppIfNeeded(
                    trigger = TargetAppReturnTrigger.USER_ACTION,
                    explicitReturnRequest = true,
                )
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_REJECT -> {
                AssistantOsController.recordEntry(surface, "reject_risky_action", "${source.ifBlank { "notification" }}拒绝高风险动作")
                SessionRuntime.rejectPendingSafetyConfirmation()
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_RETURN_TARGET -> {
                AssistantOsController.recordEntry(surface, "return_target_app", "${source.ifBlank { "notification" }}回到目标 App")
                returnToTargetAppIfNeeded(
                    trigger = TargetAppReturnTrigger.USER_ACTION,
                    explicitReturnRequest = true,
                )
                AssistantShellIntentRouter.consumeAction(intent)
            }

            AssistantShellIntentRouter.ACTION_VOICE_TOGGLE -> {
                AssistantOsController.recordEntry(surface, "voice_toggle", "${source.ifBlank { "notification" }}切换持续语音")
                startStreamingVoiceRecognition()
                AssistantShellIntentRouter.consumeAction(intent)
            }
        }
    }

    private fun autoReturnToTargetAppIfNeeded(intent: Intent?) {
        if (!shouldAutoReturnToTargetApp(intent)) {
            return
        }
        returnToTargetAppIfNeeded(TargetAppReturnTrigger.BOOTSTRAP_AUTO)
    }

    private fun returnToTargetAppIfNeeded(
        trigger: TargetAppReturnTrigger,
        explicitReturnRequest: Boolean = false,
    ) {
        if (!AssistantOsController.shouldAutoReturnToTargetApp(trigger, explicitReturnRequest)) {
            return
        }
        val directive = SessionRuntime.resolveTargetAppReturnDirective(trigger) ?: return
        if (directive.continueBeforeLaunch) {
            SessionRuntime.continueAgent()
            DebugAgentStore.appendLog("检测到 cold-start 恢复中的执行中 session，已自动继续任务。")
        }
        AppLaunchResolver.resolve(packageManager, directive.targetPackageName)?.let(::startActivity)
        if (trigger == TargetAppReturnTrigger.BOOTSTRAP_AUTO) {
            DebugAgentStore.appendLog(
                "检测到恢复 session，自动回到目标 App: reason=${directive.reason}, pkg=${directive.targetPackageName}",
            )
        }
        if (directive.moveAssistantToBack) {
            moveTaskToBack(true)
        }
    }

    private fun shouldAutoReturnToTargetApp(intent: Intent?): Boolean {
        return EntryRouter.shouldAutoReturn(
            intent = intent,
            actionExtraKey = AssistantShellIntentRouter.EXTRA_ASSISTANT_ACTION,
            returnSummaryKey = AssistantShellIntentRouter.EXTRA_RETURN_SUMMARY,
        )
    }

    private fun shouldBootstrapResumableSession(intent: Intent?): Boolean {
        val payload = AssistantShellIntentRouter.readPayload(intent)
        if (payload.returnSummary.isNotBlank()) {
            return true
        }
        return when (payload.action) {
            AssistantShellIntentRouter.ACTION_RESUME,
            AssistantShellIntentRouter.ACTION_APPROVE,
            AssistantShellIntentRouter.ACTION_RETURN_TARGET,
            -> true
            else -> false
        }
    }

    private fun consumeShareTextIntent(
        intent: Intent,
        sharedText: String,
    ) {
        if (!AssistantOsController.shouldAcceptShareEntry()) {
            return
        }
        AssistantShareSheetSignalProvider.recordSharedText(sharedText)
        queryInput.setText(sharedText)
        queryInput.setSelection(sharedText.length)
        nextStartSource = "share_text"
        AssistantOsController.recordEntry(AssistantEntrySurface.SHARE_SHEET, "share_text", sharedText.take(60))
        returnSummaryText.updateTextIfChanged("已接收分享内容，可直接开始执行。")
        returnSummaryText.visibility = View.VISIBLE
        intent.action = Intent.ACTION_VIEW
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun launchVoiceEntry() {
        if (SessionRuntime.State.runtimeState().safety.awaitingConfirmation) {
            AssistantVoiceInteractionStore.markBlocked(
                reason = "voice_gated_by_approval",
                summary = "当前还有审批待处理，持续语音入口先保持阻断。",
            )
            assistantShellInteractionController.setCommandResult("当前还有审批待处理，持续语音入口先保持阻断。")
            assistantShellInteractionController.setPage(MainAssistantShellPage.APPROVALS)
            renderAssistantOsPanels()
            return
        }
        val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        AssistantVoiceInteractionStore.syncEnvironment(
            available = recognitionAvailable,
            permissionGranted = permissionGranted,
        )
        if (recognitionAvailable) {
            if (!permissionGranted) {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            AssistantVoiceRuntimeController.toggle(
                context = this,
                source = "main_activity_voice_entry",
                note = "从主界面切换持续语音入口。",
            )
            return
        }
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你想让小轩执行的任务")
            }
        runCatching {
            voiceEntryLauncher.launch(intent)
        }.onFailure {
            AssistantVoiceInteractionStore.markError("当前设备没有可用的语音识别入口。")
            assistantShellInteractionController.setCommandResult("当前设备没有可用的语音识别入口。")
            assistantShellInteractionController.setPage(MainAssistantShellPage.COMMAND)
            renderAssistantOsPanels()
        }
    }

    private fun startStreamingVoiceRecognition() {
        AssistantVoiceRuntimeController.toggle(
            context = this,
            source = "main_activity_streaming_voice",
            note = "从控制台切换持续语音监听。",
        )
        assistantShellInteractionController.setCommandResult("持续语音入口已切到后台运行时。")
        assistantShellInteractionController.setPage(MainAssistantShellPage.ENTRY)
        renderAssistantOsPanels()
    }

    private fun stopStreamingVoiceRecognition() {
        AssistantVoiceRuntimeController.stop(
            context = this,
            source = "main_activity_streaming_voice",
            note = "从主界面停止持续语音入口。",
        )
    }

    private fun applyVoiceTranscript(
        text: String,
        source: String,
        persistVoiceDraft: Boolean = true,
    ) {
        queryInput.setText(text)
        queryInput.setSelection(text.length)
        nextStartSource = source
        assistantShellInteractionController.setPage(MainAssistantShellPage.TODAY)
        AssistantOsController.recordEntry(AssistantEntrySurface.VOICE, "voice_draft", text.take(60))
        if (persistVoiceDraft) {
            AssistantVoiceInteractionStore.markFinal(text)
        }
        returnSummaryText.updateTextIfChanged("已接收持续语音草稿，可直接开始执行。")
        returnSummaryText.visibility = View.VISIBLE
        assistantShellInteractionController.setCommandResult("持续语音草稿已生成。")
        renderAssistantOsPanels()
    }

    private fun startAgentFromDraft(
        task: String,
        source: String,
        surface: AssistantEntrySurface,
    ) {
        clearReturnSummary()
        nextStartSource = "app"
        AssistantOsController.recordEntry(
            surface = surface,
            action = "start_agent",
            summary = task.ifBlank { "启动空任务草稿" },
        )
        lifecycleScope.launch {
            val started = DebugAgentStore.startAgent(task, entrySource = source)
            if (started) {
                returnToTargetAppIfNeeded(TargetAppReturnTrigger.USER_ACTION)
            }
        }
    }

    private fun readVoiceResult(
        bundle: Bundle?,
    ): String =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun syncVoiceRuntimeState() {
        AssistantVoiceInteractionStore.syncEnvironment(
            available = SpeechRecognizer.isRecognitionAvailable(this),
            permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        AssistantVoiceRuntimeController.sync(this, source = "main_activity_sync")
    }

    private fun consumePendingVoiceTranscriptIfNeeded() {
        val snapshot = AssistantVoiceInteractionStore.read()
        val transcript = snapshot.pendingTranscript.trim()
        if (transcript.isBlank()) return
        AssistantVoiceInteractionStore.consumePendingTranscript()
        val source = snapshot.pendingSource.ifBlank { "voice_runtime_service" }
        when (snapshot.pendingAutoAction.ifBlank { if (snapshot.autoExecuteEligible) "voice_execute" else "voice_draft" }) {
            "voice_execute" -> {
                applyVoiceTranscript(
                    text = transcript,
                    source = source,
                    persistVoiceDraft = false,
                )
                AssistantVoiceInteractionStore.markActionHandled(
                    autoAction = "voice_execute",
                    summary = "语音任务已转入自动执行。",
                )
                startAgentFromDraft(
                    task = transcript,
                    source = source,
                    surface = AssistantEntrySurface.VOICE,
                )
            }

            "voice_resume" -> {
                SessionRuntime.continueAgent()
                AssistantVoiceInteractionStore.markActionHandled(
                    autoAction = "voice_resume",
                    summary = "已按语音指令继续当前任务。",
                )
                assistantShellInteractionController.setCommandResult("已按语音指令继续当前任务。")
                renderAssistantOsPanels()
            }

            "voice_pause" -> {
                SessionRuntime.Lifecycle.pauseAgent()
                AssistantVoiceInteractionStore.markActionHandled(
                    autoAction = "voice_pause",
                    summary = "已按语音指令暂停当前任务。",
                )
                assistantShellInteractionController.setCommandResult("已按语音指令暂停当前任务。")
                renderAssistantOsPanels()
            }

            "voice_stop" -> {
                if (SessionRuntime.State.runtimeState().session.sessionId.isNotBlank()) {
                    SessionRuntime.Lifecycle.stopAgent("已按语音指令停止自动任务。")
                    AssistantVoiceInteractionStore.markActionHandled(
                        autoAction = "voice_stop",
                        summary = "已按语音指令停止当前任务。",
                    )
                    assistantShellInteractionController.setCommandResult("已按语音指令停止当前任务。")
                } else {
                    stopStreamingVoiceRecognition()
                    AssistantVoiceInteractionStore.markActionHandled(
                        autoAction = "voice_disable",
                        summary = "没有活跃任务，已停止持续语音监听。",
                    )
                    assistantShellInteractionController.setCommandResult("没有活跃任务，已停止持续语音监听。")
                }
                renderAssistantOsPanels()
            }

            "voice_confirm" -> {
                if (DebugAgentStore.uiState.value.safety.awaitingConfirmation) {
                    SessionRuntime.Lifecycle.approvePendingSafetyConfirmation()
                    AssistantVoiceInteractionStore.markActionHandled(
                        autoAction = "voice_confirm",
                        summary = "已按语音指令确认当前待处理动作。",
                    )
                    assistantShellInteractionController.setCommandResult("已按语音指令确认当前待处理动作。")
                } else {
                    AssistantVoiceInteractionStore.markActionHandled(
                        autoAction = "voice_confirm",
                        summary = "当前没有待确认动作，语音确认已忽略。",
                    )
                    assistantShellInteractionController.setCommandResult("当前没有待确认动作。")
                }
                renderAssistantOsPanels()
            }

            "voice_disable" -> {
                stopStreamingVoiceRecognition()
                AssistantVoiceInteractionStore.markActionHandled(
                    autoAction = "voice_disable",
                    summary = "已按语音指令关闭持续语音入口。",
                )
                assistantShellInteractionController.setCommandResult("已按语音指令关闭持续语音入口。")
                renderAssistantOsPanels()
            }

            else -> {
                applyVoiceTranscript(
                    text = transcript,
                    source = source,
                    persistVoiceDraft = false,
                )
            }
        }
    }

    private fun consumeReturnSummaryIntent(
        intent: Intent,
        summary: String,
    ) {
        val resolvedSummary = summary.ifBlank { AssistantShellIntentRouter.consumeReturnSummary(intent) }
        if (resolvedSummary.isBlank()) {
            return
        }
        returnSummaryText.updateTextIfChanged(resolvedSummary)
        returnSummaryText.visibility = View.VISIBLE
        skipDashboardHydrateUntilMs = SystemClock.elapsedRealtime() + 1_200L
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun requestContextSignalPermissionsIfNeeded() {
        val missingPermissions =
            buildList {
                if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.READ_CALENDAR)
                }
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.READ_CONTACTS)
                }
                if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.READ_SMS)
                }
                if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.READ_CALL_LOG)
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        if (missingPermissions.isEmpty()) {
            return
        }
        requestPermissions(missingPermissions.toTypedArray(), REQUEST_CONTEXT_SIGNALS)
    }

    private fun buildStatusSections(
        state: DebugUiState,
        permissionTip: String,
    ): StatusSections {
        val route = state.route
        val planning = state.planning
        val result = state.result
        val takeover = state.takeover
        val safety = state.safety
        val statusPanel = state.status
        val plannerRawPreview =
            planning.lastPlannerRaw
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(4)
                .joinToString(separator = "\n")
                .ifBlank { "-" }
        val routeRawPreview =
            route.modelRaw
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(4)
                .joinToString(separator = "\n")
                .ifBlank { "-" }
        val routeMemoryPreview =
            route.memoryHints
                .take(3)
                .joinToString(separator = "\n")
                .ifBlank { "-" }
        val runtimeArtifactLines =
            state.recentRuntimeArtifacts
                .take(6)
                .ifEmpty { listOf("-") }
        val runtimeEventLines =
            state.recentRuntimeEvents
                .take(6)
                .ifEmpty { listOf("-") }
        val bridgeEventLines =
            state.recentBridgeEvents
                .take(6)
                .ifEmpty { listOf("-") }
        val platformSummaryLines =
            state.platformSummaryLines
                .take(16)
                .ifEmpty { listOf("-") }
        val platformApprovalLines =
            state.platformApprovalLines
                .take(6)
                .ifEmpty { listOf("-") }
        val capabilityLines =
            state.capabilityLines
                .take(6)
                .ifEmpty { listOf("-") }
        val remoteBridgeLines =
            state.remoteBridgeLines
                .take(6)
                .ifEmpty { listOf("-") }
        val workerLines =
            state.workerLines
                .take(6)
                .ifEmpty { listOf("-") }
        val proactiveTaskLines =
            state.proactiveTaskLines
                .take(6)
                .ifEmpty { listOf("-") }
        val externalSignalLines =
            state.externalSignalLines
                .take(6)
                .ifEmpty { listOf("-") }
        val memoryRecallLines =
            state.memoryRecallLines
                .take(6)
                .ifEmpty { listOf("-") }
        val traceLines =
            state.traceLines
                .take(6)
                .ifEmpty { listOf("-") }
        val remoteTransportLines =
            state.remoteTransportLines
                .take(6)
                .ifEmpty { listOf("-") }
        val compareLines =
            state.compareLines
                .take(6)
                .ifEmpty { listOf("-") }
        val batchReplayLines =
            state.batchReplayLines
                .take(6)
                .ifEmpty { listOf("-") }
        val stepReplayLines =
            state.stepReplayLines
                .take(8)
                .ifEmpty { listOf("-") }
        val taskResultLines =
            buildList {
                add("hint=${result.hint.ifBlank { permissionTip }}")
                add("lastResult=${result.lastResult.ifBlank { "-" }}")
                if (result.intentType.isNotBlank()) {
                    add("resultIntent=${result.intentType}")
                }
                if (result.title.isNotBlank()) {
                    add("resultTitle=${result.title}")
                }
                if (result.summary.isNotBlank()) {
                    add("resultSummary=${result.summary}")
                }
                if (result.highlights.isNotEmpty()) {
                    add("resultHighlights=${result.highlights.take(3).joinToString(" / ")}")
                }
                add("lastError=${result.lastError.ifBlank { "-" }}")
            }
        return StatusSections(
            overview =
                buildSection(
                    title = "Overview",
                    lines = listOf(
                        "query=${state.query.ifBlank { "-" }}",
                        "profile=${route.activeProfileId.ifBlank { "-" }}",
                        "entry=${state.entrySource}",
                        "skills=${planning.activeSkillTitles.joinToString(" / ").ifBlank { "-" }}",
                        "route=${route.reason.ifBlank { "-" }}",
                        "routePolicy=${route.policyTag.ifBlank { "-" }}",
                        "routeModelChoice=${route.modelChoiceProfileId.ifBlank { "-" }}",
                        "routeSelected=${route.selectedProfileId.ifBlank { "-" }}",
                        "planType=${planning.planType.ifBlank { "-" }}",
                        "planStage=${planning.currentPlanStage.ifBlank { "-" }}",
                        "subgoal=${planning.currentSubgoalId.ifBlank { "-" }}",
                        "nextObjective=${planning.nextObjective.ifBlank { "-" }}",
                        "sessionId=${state.sessionId.ifBlank { "-" }}",
                        "replay=${state.replayFileName.ifBlank { "-" }}",
                        "targetPkg=${route.activeTargetPackage.ifBlank { "-" }}",
                        "awaitingConfirmation=${safety.awaitingConfirmation}",
                        "takeoverType=${takeover.latestTakeoverType.ifBlank { "-" }}",
                    ),
                ),
            context =
                buildSection(
                    title = "Context",
                    lines = listOf(
                        "accessibility=${state.accessibilityConnected}",
                        "foreground=${state.foregroundPackage.ifBlank { "-" }}",
                        "pageState=${state.pageState.label}",
                        "lastEvent=${state.lastEventType}",
                        "observation=${state.observationSignature.ifBlank { "-" }}",
                        "elements=${state.visibleElementCount}",
                        "waitingForExternal=${takeover.waitingForExternal}",
                        "waitingEvent=${takeover.waitingForEvent.ifBlank { "-" }}",
                        "screenshot=${state.screenshotStatus}",
                    ),
                ) +
                    "\n" +
                    buildSection(
                        title = "Route",
                        lines = buildList {
                            add("routeReason=${route.reason.ifBlank { "-" }}")
                            add("routeFallback=${route.fallbackReason.ifBlank { "-" }}")
                            add("routeMemory=")
                            add(routeMemoryPreview)
                            add("routeModelRaw=")
                            add(routeRawPreview)
                        },
                    ) +
                    "\n" +
                    buildSection(
                        title = "Takeover",
                        lines = listOf(
                            "type=${takeover.latestTakeoverType.ifBlank { "-" }}",
                            "reason=${takeover.latestTakeoverReason.ifBlank { "-" }}",
                            "resumeHint=${takeover.latestTakeoverResumeHint.ifBlank { "-" }}",
                            "correction=${takeover.latestTakeoverCorrection.ifBlank { "-" }}",
                        ),
                    ) +
                    "\n" +
                    buildSection(
                        title = "Agent",
                        lines = listOf(
                            "agentRunning=${state.agentRunning}",
                            "agentStatus=${statusPanel.code}",
                            "statusCategory=${statusPanel.category}",
                            "blockedReason=${statusPanel.blockedReason}",
                            "executionPhase=${statusPanel.executionPhase}",
                            "takeoverReason=${statusPanel.takeoverReason}",
                            "terminalReason=${statusPanel.terminalReason}",
                            "agentTurn=${state.agentTurn}",
                            "lastAction=${result.lastAction.ifBlank { "-" }}",
                            "lastPlannerAction=${planning.lastPlannerAction.ifBlank { "-" }}",
                            "lastResumeDecision=${planning.lastResumeDecision.ifBlank { "-" }}",
                            "runtimeTransition=${state.runtimeTransition.ifBlank { "-" }}",
                            "runtimeUpdatedAt=${state.runtimeUpdatedAtLabel}",
                            "suspendReason=${takeover.suspendReason.ifBlank { "-" }}",
                            "pendingAction=${safety.pendingConfirmationActionLabel.ifBlank { "-" }}",
                        ),
                    ) +
                    "\n" +
                    buildSection(
                        title = "Platform",
                        lines = platformSummaryLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Approvals",
                        lines = platformApprovalLines + listOf("replayCheck=${state.replayVerificationSummary.ifBlank { "-" }}"),
                    ) +
                    "\n" +
                    buildSection(
                        title = "Capabilities",
                        lines = capabilityLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Workers",
                        lines = workerLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Proactive",
                        lines = proactiveTaskLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Signals",
                        lines = externalSignalLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Compare",
                        lines = compareLines,
                    ),
            result =
                buildSection(
                    title = "Result",
                    lines = taskResultLines,
                ) +
                    "\n" +
                    buildSection(
                        title = "Planner",
                        lines = listOf(
                            "plannerMode=${state.plannerMode}",
                            "plannerRaw=",
                            plannerRawPreview,
                        ),
                    ) +
                    "\n" +
                    buildSection(
                        title = "Artifacts",
                        lines = runtimeArtifactLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "RuntimeLedger",
                        lines = runtimeEventLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "BridgeFeed",
                        lines = bridgeEventLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "RemoteBridge",
                        lines = remoteBridgeLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "RemoteTransport",
                        lines = remoteTransportLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "MemoryRecall",
                        lines = memoryRecallLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "Trace",
                        lines = traceLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "BatchReplay",
                        lines = batchReplayLines,
                    ) +
                    "\n" +
                    buildSection(
                        title = "StepReplay",
                        lines = stepReplayLines,
                    ),
            logs = buildLogsSection(state.recentLogs),
        )
    }

    private data class StatusSections(
        val overview: String,
        val context: String,
        val result: String,
        val logs: String,
    )

    companion object {
        private const val REQUEST_NOTIFICATIONS = 3001
        private const val REQUEST_CONTEXT_SIGNALS = 3003
        const val REQUEST_OPEN = 3002
        const val ACTION_OPEN = AssistantShellIntentRouter.ACTION_OPEN
        const val ACTION_STOP = AssistantShellIntentRouter.ACTION_STOP
        const val ACTION_PAUSE = AssistantShellIntentRouter.ACTION_PAUSE
        const val ACTION_RESUME = AssistantShellIntentRouter.ACTION_RESUME
        const val ACTION_APPROVE = AssistantShellIntentRouter.ACTION_APPROVE
        const val ACTION_REJECT = AssistantShellIntentRouter.ACTION_REJECT
        const val ACTION_RETURN_TARGET = AssistantShellIntentRouter.ACTION_RETURN_TARGET
        const val ACTION_VOICE_TOGGLE = AssistantShellIntentRouter.ACTION_VOICE_TOGGLE

        fun createReturnIntent(
            context: Context,
            summary: String,
        ): Intent = AssistantShellIntentRouter.createReturnIntent(context, summary)

        fun createActionIntent(
            context: Context,
            action: String,
            source: String = "notification",
        ): Intent = AssistantShellIntentRouter.createActionIntent(context, action, source)

        fun createPageIntent(
            context: Context,
            page: String,
            source: String = "main_app",
        ): Intent = AssistantShellIntentRouter.createPageIntent(context, page, source)

        fun createVoiceEntryIntent(
            context: Context,
            source: String = "voice",
        ): Intent = AssistantShellIntentRouter.createVoiceEntryIntent(context, source)
    }
}
