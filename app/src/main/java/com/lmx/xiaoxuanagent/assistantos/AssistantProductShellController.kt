package com.lmx.xiaoxuanagent.assistantos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lmx.xiaoxuanagent.harness.SuperAssistantEntrySurfaceSignal
import com.lmx.xiaoxuanagent.harness.SuperAssistantMaturityGateStore
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.SessionCompensationPlan
import com.lmx.xiaoxuanagent.runtime.SessionCommandCenterStore
import com.lmx.xiaoxuanagent.runtime.SessionCompensationStore
import com.lmx.xiaoxuanagent.runtime.SessionExplanationStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryNotebookStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionHistoryService
import com.lmx.xiaoxuanagent.runtime.SessionPermissionProductStore
import com.lmx.xiaoxuanagent.runtime.SessionSwarmCoordinationPolicy
import com.lmx.xiaoxuanagent.runtime.SessionWorkingMemoryStore
import com.lmx.xiaoxuanagent.safety.PermissionModeOrchestrator
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore
object AssistantProductShellController {
    internal const val MAX_TIPS = 5

    fun sync(
        assistantSnapshot: AssistantOsSnapshot,
        reason: String,
    ): AssistantProductShellSnapshot {
        val current = AssistantProductShellStore.read()
        val next =
            derive(
                currentSnapshot = current,
                assistantSnapshot = assistantSnapshot,
                reason = reason,
            )
        if (AssistantProductShellStore.isEquivalent(current, next)) {
            return current
        }
        AssistantOsStrategyPolicy.recordProductShellSync(next, reason)
        return AssistantProductShellStore.replace(next)
    }

    fun derive(
        currentSnapshot: AssistantProductShellSnapshot,
        assistantSnapshot: AssistantOsSnapshot,
        reason: String,
    ): AssistantProductShellSnapshot {
        val now = System.currentTimeMillis()
        val analyticsSnapshot = AssistantAnalyticsStore.read()
        val userModel = PersonalMemoryStore.readUserModelSnapshot(limit = 4)
        val memoryInsight = PersonalMemoryStore.readInsightSnapshot(limit = 4)
        val voiceInteraction = AssistantVoiceInteractionStore.read()
        val routinePolicy = normalizeRoutinePolicySnapshot(currentSnapshot.routinePolicy, now)
        val digestPolicy = normalizeDigestPolicySnapshot(currentSnapshot.digestPolicy, now)
        val quietHours = deriveQuietHoursSnapshot(currentSnapshot.quietHours, now)
        val interruptPolicy = normalizeInterruptPolicySnapshot(currentSnapshot.interruptPolicy, now)
        val providers = SessionPlatformFacade.readSignalProviders(limit = 12)
        val providerPolicy = SessionPlatformFacade.readPlannerProviderPolicy()
        val pendingCapabilityApprovals = SessionPlatformFacade.readPendingCapabilityApprovals(limit = 12)
        val memoryQueue = SessionPlatformFacade.readBackgroundMemoryQueue(includeCompleted = false, limit = 24)
        val memoryGovernance = PersonalMemoryStore.readGovernanceSnapshot(limit = 8, auditLimit = 6)
        val proactiveTasks = SessionPlatformFacade.readProactiveTasks(limit = 24)
        val externalSignals = SessionPlatformFacade.readExternalSignals(limit = 24)
        val workers = SessionPlatformFacade.readWorkerQueue(limit = 24)
        val workerGraph = SessionPlatformFacade.readWorkerGraphSnapshot(limit = 12)
        val mailboxSnapshot =
            SessionPlatformFacade.readWorkerMailboxSnapshot(
                includeConsumed = false,
                limit = 24,
                priorityMode = workerGraph.scheduler.mailboxPriorityMode,
            )
        val sessionGraph = SessionPlatformFacade.readSessionGraphNodes(limit = 24)
        val schedulerSnapshot = SessionPlatformFacade.readExecutionSchedulerSnapshot()
        val proactiveQueue =
            AssistantProactiveTaskStore.readDispatchSnapshot(
                activeSessionId = assistantSnapshot.activeSession.sessionId,
                dispatchableWorkerIds = workerGraph.scheduler.readyWorkerIds.toSet(),
                nowMs = now,
                limit = 8,
            )
        val retentionPreview = SessionPlatformFacade.previewArtifactRetention()
        val focusSessionId =
            assistantSnapshot.activeSession.sessionId
                .ifBlank {
                    sessionGraph.maxByOrNull { it.pendingApprovalCount * 10 + it.mailboxPendingCount }?.sessionId.orEmpty()
                }
        val lifecycleSnapshot =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionPlatformFacade.readArtifactLifecycleSnapshot(sessionId)
            }
        val focusPlatformSnapshot =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionPlatformFacade.readSessionSnapshot(sessionId = sessionId, eventLimit = 12)
            }
        val focusCompensationPlans =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionCompensationStore.readSessionPlans(sessionId = sessionId, limit = 4)
            }.orEmpty()
        val focusWorkingMemory =
            focusSessionId.takeIf { it.isNotBlank() }?.let(SessionWorkingMemoryStore::readSnapshot)
        val focusExplanationEntries =
            focusSessionId.takeIf { it.isNotBlank() }?.let { SessionExplanationStore.readRecent(it, limit = 6) }.orEmpty()
        val focusNotebook =
            focusSessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryNotebookStore::readSnapshot)
        val commandCenter = SessionCommandCenterStore.readSnapshot(limit = 6)
        val focusCommandReceipts =
            when {
                focusSessionId.isNotBlank() -> SessionCommandCenterStore.readRecentForSession(focusSessionId, limit = 4)
                else -> SessionCommandCenterStore.readRecent(limit = 4)
            }
        val pendingCompensationPlans = SessionCompensationStore.readPendingPlans(limit = 8)
        RuntimeSafetyPolicyStore.ensureSuperAssistantBaselineRules()
        val safetyPolicies = RuntimeSafetyPolicyStore.readRules(limit = 8)
        val traceSnapshot =
            SessionPlatformFacade.readTraceSnapshot(
                sessionId = focusSessionId,
                limit = 12,
            )
        val focusPermissionProduct =
            SessionPermissionProductStore.refresh(sessionId = focusSessionId, limit = 8)
        val focusGroundingHealth =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionPlatformFacade.readGroundingHealth(sessionId = sessionId, limit = 12)
            } ?: com.lmx.xiaoxuanagent.runtime.SessionGroundingHealthSnapshot()
        val superAssistantMaturity =
            SuperAssistantMaturityGateStore.evaluate(
                profileId =
                    focusPlatformSnapshot
                        ?.state
                        ?.session
                        ?.profileId
                        .orEmpty()
                        .ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.profileId.orEmpty() },
                packageName =
                    assistantSnapshot.activeSession.targetPackageName
                        .ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.targetPackageName.orEmpty() },
                focusSessionId = focusSessionId,
                focusPlatformSnapshot = focusPlatformSnapshot,
                traceSnapshot = traceSnapshot,
                groundingHealth = focusGroundingHealth,
                permissionProduct = focusPermissionProduct,
                entrySurfaceSignal = buildSuperAssistantEntrySurfaceSignal(assistantSnapshot, voiceInteraction),
            )
        val swarmCoordination =
            SessionSwarmCoordinationPolicy.buildSnapshot(
                schedulerSnapshot = schedulerSnapshot,
                workerGraph = workerGraph,
                mailboxSnapshot = mailboxSnapshot,
                proactiveQueue = proactiveQueue,
                traceSnapshot = traceSnapshot,
                activeSessionId = assistantSnapshot.activeSession.sessionId,
            )
        val replayTimeline =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                SessionPlatformFacade.readReplayTimeline(sessionId = sessionId, limit = 8)
            }.orEmpty()
        val historySummary = SessionHistoryService.buildHistorySummary(limit = 4)
        val conversationCompact =
            focusSessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                com.lmx.xiaoxuanagent.runtime.SessionConversationCompactStore.readSnapshot(sessionId)
            }
        val notificationProviderReady = providers.any { it.providerId == "notification_listener" }
        val calendarProviderReady = providers.any { it.providerId == "calendar_agenda" }
        val locationProviderReady = providers.any { it.providerId == "passive_location" }
        val voiceReady = voiceInteraction.availabilitySummary == "voice_ready"
        val appPreflight =
            AssistantAppCapabilityPreflight.evaluate(
                buildAppCapabilityPreflightInput(
                    assistantSnapshot = assistantSnapshot,
                    voiceInteraction = voiceInteraction,
                    providers = providers,
                    focusPlatformSnapshot = focusPlatformSnapshot,
                ),
            )
        val onboarding =
            mergeProductOnboardingState(
                previous = currentSnapshot.onboarding,
                candidates =
                    AssistantOnboardingRegistry.buildCandidates(
                        assistantSnapshot = assistantSnapshot,
                        providerCount = providers.size,
                        notificationProviderReady = notificationProviderReady,
                        calendarProviderReady = calendarProviderReady,
                        locationProviderReady = locationProviderReady,
                        voiceReady = voiceReady,
                ),
                now = now,
            )
        val initialAdaptivePolicy =
            AssistantAdaptivePolicyStore.derive(
                productShell =
                    currentSnapshot.copy(
                        onboarding = onboarding,
                        routinePolicy = routinePolicy,
                        digestPolicy = digestPolicy,
                        quietHours = quietHours,
                        interruptPolicy = interruptPolicy,
                        userModel = userModel,
                        memoryInsight = memoryInsight,
                        voiceInteraction = voiceInteraction,
                    ),
                analytics = analyticsSnapshot,
                userModel = userModel,
            )
        val pendingPermissionRequests = swarmCoordination.pendingPermissionRequests
        val swarmStrategy =
            AssistantProductShellPolicy.deriveSwarmStrategy(
                now = now,
                activeSessionId = assistantSnapshot.activeSession.sessionId,
                swarmCoordination = swarmCoordination,
                sessionGraph = sessionGraph,
                retentionPreview = retentionPreview,
            )
        val unhealthyProviders = providers.filter { it.healthScore < 50 || it.failureCount >= 3 }
        val blockedGraphNodes =
            sessionGraph.filter {
                it.pendingApprovalCount > 0 || it.pendingChildSessionIds.isNotEmpty() || it.blockedReason.isNotBlank()
            }
        val operatorShell =
            AssistantProductShellPolicy.deriveOperatorShell(
                now = now,
                providers = providers,
                workers = workers,
                schedulerSnapshot = schedulerSnapshot,
                retentionPreview = retentionPreview,
                sessionGraph = sessionGraph,
                blockedGraphNodes = blockedGraphNodes,
                pendingPermissionRequests = pendingPermissionRequests,
                unhealthyProviders = unhealthyProviders,
                focusSessionId = focusSessionId,
                lifecycleSnapshot = lifecycleSnapshot,
                focusPlatformSnapshot = focusPlatformSnapshot,
                replayTimeline = replayTimeline,
                swarmCoordination = swarmCoordination,
                traceSnapshot = traceSnapshot,
            )
        val agendaShell =
            AssistantPersonalShellPolicy.deriveAgendaShell(
                now = now,
                assistantSnapshot = assistantSnapshot,
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
            )
        val dailyRhythm =
            AssistantPersonalShellPolicy.deriveDailyRhythm(
                now = now,
                assistantSnapshot = assistantSnapshot,
                providers = providers,
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
            )
        val interruptBudget =
            deriveInterruptBudgetSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                mailboxPendingApprovals = pendingPermissionRequests,
                proactiveTasks = proactiveTasks,
                externalSignals = externalSignals,
                interruptPolicy = interruptPolicy,
                adaptivePolicy = initialAdaptivePolicy,
                voiceInteraction = voiceInteraction,
                quietHours = quietHours,
            )
        val diagnostics =
            AssistantProductDiagnosticsService.derive(
                providers = providers,
                memoryQueue = memoryQueue,
                retentionPreview = retentionPreview,
                sessionGraph = sessionGraph,
                focusPlatformSnapshot = focusPlatformSnapshot,
                traceSnapshot = traceSnapshot,
                commandCenter = commandCenter,
                proactiveTasks = proactiveTasks,
                followUpHealth = AssistantFollowUpHealthStore.derive(proactiveTasks, nowMs = now),
                memoryMaintenance = com.lmx.xiaoxuanagent.runtime.SessionMemoryMaintenanceStore.read(),
                historySummary = historySummary,
                appPreflight = appPreflight,
                superAssistantMaturity = superAssistantMaturity,
            )
        val mergedLedger =
            AssistantTipScheduler.mergeLedger(
                previous = AssistantTipScheduler.ledgerForProductShell(currentSnapshot),
                candidates =
                    AssistantTipRegistry.buildCandidates(
                        assistantSnapshot = assistantSnapshot,
                        mailboxPendingApprovals = pendingPermissionRequests,
                        providerStates = providers,
                        blockedSessionCount = blockedGraphNodes.size,
                        retentionPreview = retentionPreview,
                        memoryQueue = memoryQueue,
                        diagnostics = diagnostics,
                        memoryInsight = memoryInsight,
                        conversationCompact = conversationCompact,
                        voiceInteraction = voiceInteraction,
                        adaptivePolicy = initialAdaptivePolicy,
                        reason = reason,
                    ),
                now = now,
            )
        val presentedTips =
            AssistantTipScheduler.present(
                ledger = AssistantTipScheduler.compactLedger(mergedLedger, AssistantProductShellStore.MAX_LEDGER_SIZE),
                now = now,
                maxVisible = MAX_TIPS,
            )
        val provisionalAutonomyPlan =
            AssistantAutonomyPlanPolicy.derive(
                now = now,
                assistantSnapshot = assistantSnapshot,
                swarmCoordination = swarmCoordination,
                adaptivePolicy = initialAdaptivePolicy,
                userModel = userModel,
                memoryInsight = memoryInsight,
                voiceInteraction = voiceInteraction,
                providers = providers,
                externalSignals = externalSignals,
                proactiveQueue = proactiveQueue,
                onboarding = onboarding,
                tips = presentedTips.visible,
            )
        val adaptivePolicy =
            AssistantAdaptivePolicyStore.derive(
                productShell =
                    currentSnapshot.copy(
                        onboarding = onboarding,
                        tips = presentedTips.visible,
                        tipLedger = AssistantTipScheduler.compactLedger(presentedTips.ledger, AssistantProductShellStore.MAX_LEDGER_SIZE),
                        routinePolicy = routinePolicy,
                        digestPolicy = digestPolicy,
                        quietHours = quietHours,
                        interruptPolicy = interruptPolicy,
                        userModel = userModel,
                        memoryInsight = memoryInsight,
                        voiceInteraction = voiceInteraction,
                        autonomyPlan = provisionalAutonomyPlan,
                        analytics = analyticsSnapshot.toSummary(),
                    ),
                analytics = analyticsSnapshot,
                userModel = userModel,
            ).also(AssistantAdaptivePolicyStore::replace)
        val autonomyPlan =
            AssistantAutonomyPlanPolicy.derive(
                now = now,
                assistantSnapshot = assistantSnapshot,
                swarmCoordination = swarmCoordination,
                adaptivePolicy = adaptivePolicy,
                userModel = userModel,
                memoryInsight = memoryInsight,
                voiceInteraction = voiceInteraction,
                providers = providers,
                externalSignals = externalSignals,
                proactiveQueue = proactiveQueue,
                onboarding = onboarding,
                tips = presentedTips.visible,
            )
        val personalFocus =
            AssistantPersonalShellPolicy.derivePersonalFocus(
                now = now,
                assistantSnapshot = assistantSnapshot,
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
                userModel = userModel,
                autonomyPlan = autonomyPlan,
            )
        val routineShell =
            deriveRoutineShellSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                agendaShell = agendaShell,
                dailyRhythm = dailyRhythm,
                personalFocus = personalFocus,
                proactiveTasks = proactiveTasks,
                externalSignals = externalSignals,
                routinePolicy = routinePolicy,
                adaptivePolicy = adaptivePolicy,
                quietHours = quietHours,
            )
        val companionShell =
            deriveCompanionShellSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                providers = providers,
                externalSignals = externalSignals,
                agendaShell = agendaShell,
                dailyRhythm = dailyRhythm,
                personalFocus = personalFocus,
                swarmStrategy = swarmStrategy,
                voiceInteraction = voiceInteraction,
                userModel = userModel,
                memoryInsight = memoryInsight,
                autonomyPlan = autonomyPlan,
            )
        val digestShell =
            deriveDigestShellSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                onboarding = onboarding,
                tips = presentedTips.visible,
                routineShell = routineShell,
                operatorShell = operatorShell,
                diagnostics = diagnostics,
                digestPolicy = digestPolicy,
                adaptivePolicy = adaptivePolicy,
                userModel = userModel,
                memoryInsight = memoryInsight,
                voiceInteraction = voiceInteraction,
                autonomyPlan = autonomyPlan,
                quietHours = quietHours,
            )
        val viewerShell =
            deriveViewerShellSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                focusSessionId = focusSessionId,
                focusPlatformSnapshot = focusPlatformSnapshot,
                replayTimeline = replayTimeline,
                sessionGraph = sessionGraph,
                pendingCapabilityApprovals = pendingCapabilityApprovals,
                compensationPlans = focusCompensationPlans,
                traceSnapshot = traceSnapshot,
                userModel = userModel,
                conversationCompact = conversationCompact,
                autonomyPlan = autonomyPlan,
                workingMemory = focusWorkingMemory,
                recentCommands = focusCommandReceipts,
                explanationEntries = focusExplanationEntries,
                notebookSnapshot = focusNotebook,
            )
        val governanceShell =
            deriveGovernanceShellSnapshot(
                now = now,
                assistantSnapshot = assistantSnapshot,
                providerPolicy = providerPolicy,
                retentionPreviewSummary =
                    retentionPreview.lines.firstOrNull()
                        ?: "deleted_artifacts=${retentionPreview.deletedArtifacts} kept=${retentionPreview.keptArtifacts}",
                pendingCapabilityApprovals = pendingCapabilityApprovals,
                memoryGovernance = memoryGovernance,
                quietHours = quietHours,
                digestPolicy = digestPolicy,
                interruptPolicy = interruptPolicy,
                historySummary = historySummary,
                pendingCompensationPlans = pendingCompensationPlans,
                userModel = userModel,
                memoryInsight = memoryInsight,
                voiceInteraction = voiceInteraction,
                conversationCompact = conversationCompact,
                autonomyPlan = autonomyPlan,
                safetyPolicies = safetyPolicies,
            )
        val snapshot =
            AssistantProductShellSnapshot(
                onboarding = onboarding,
                tips = presentedTips.visible,
                tipLedger = presentedTips.ledger,
                swarmStrategy = swarmStrategy,
                companionShell = companionShell,
                operatorShell = operatorShell,
                agendaShell = agendaShell,
                dailyRhythm = dailyRhythm,
                routineShell = routineShell,
                routinePolicy = routinePolicy,
                digestShell = digestShell,
                digestPolicy = digestPolicy,
                quietHours = quietHours,
                interruptBudget = interruptBudget,
                interruptPolicy = interruptPolicy,
                personalFocus = personalFocus,
                viewerShell = viewerShell,
                governanceShell = governanceShell,
                userModel = userModel,
                memoryInsight = memoryInsight,
                voiceInteraction = voiceInteraction,
                autonomyPlan = autonomyPlan,
                diagnostics = diagnostics,
                analytics = analyticsSnapshot.toSummary(),
                lastSyncReason = reason,
                updatedAtMs = now,
            )
        return snapshot
    }

    private fun buildSuperAssistantEntrySurfaceSignal(
        assistantSnapshot: AssistantOsSnapshot,
        voiceInteraction: AssistantVoiceInteractionSnapshot,
    ): SuperAssistantEntrySurfaceSignal {
        val enabledSurfaces = assistantSnapshot.surfaces.filter { it.supported && it.enabled }
        val readyCount = enabledSurfaces.count { it.available }
        val blockedCount = enabledSurfaces.count { !it.available }
        val voiceReady = voiceInteraction.availabilitySummary == "voice_ready"
        return SuperAssistantEntrySurfaceSignal(
            enabledSurfaceCount = enabledSurfaces.size,
            readySurfaceCount = readyCount,
            blockedSurfaceCount = blockedCount,
            voiceReady = voiceReady,
            recentEntryCount = assistantSnapshot.recentEntries.size,
            pendingActionCount = assistantSnapshot.pendingActions.size + assistantSnapshot.approvalSessions.size,
            summary =
                buildString {
                    append("ready=").append(readyCount).append('/').append(enabledSurfaces.size)
                    append(" blocked=").append(blockedCount)
                    append(" voice=").append(voiceReady)
                    append(" recent=").append(assistantSnapshot.recentEntries.size)
                    append(" pending=").append(assistantSnapshot.pendingActions.size + assistantSnapshot.approvalSessions.size)
                },
        )
    }

    private fun buildAppCapabilityPreflightInput(
        assistantSnapshot: AssistantOsSnapshot,
        voiceInteraction: AssistantVoiceInteractionSnapshot,
        providers: List<AssistantSignalProviderState>,
        focusPlatformSnapshot: com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot?,
    ): AssistantAppCapabilityPreflightInput {
        val context = AppRuntimeContext.get()
        val targetPackageName =
            assistantSnapshot.activeSession.targetPackageName
                .ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.targetPackageName.orEmpty() }
        val voiceAvailability = voiceInteraction.availabilitySummary
        return AssistantAppCapabilityPreflightInput(
            accessibilityConnected = DebugAgentStore.uiState.value.accessibilityConnected,
            postNotificationPermissionGranted = PermissionModeOrchestrator.hasNotificationPermission(),
            notificationControlEnabled =
                assistantSnapshot.isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
                    assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER),
            notificationProviderReady = providers.any { it.providerId == "notification_listener" },
            notificationListenerEnabled = isNotificationListenerEnabled(context),
            overlayFeatureEnabled = assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB),
            overlayPermissionGranted = PermissionModeOrchestrator.canDrawOverlays(),
            shareSheetEnabled =
                assistantSnapshot.isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
                    assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY),
            shortcutSupported = true,
            widgetEnabled = assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX),
            tileSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            speechRecognizerAvailable = voiceAvailability != "speech_recognizer_unavailable",
            recordAudioPermissionGranted =
                context?.let {
                    ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                } ?: (voiceAvailability == "voice_ready"),
            voiceBlockedByApproval = assistantSnapshot.activeSession.awaitingConfirmation,
            foregroundServiceDeclared = isComponentDeclared(context, AssistantRuntimeService::class.java.name),
            voiceForegroundServiceDeclared = isComponentDeclared(context, AssistantVoiceRuntimeService::class.java.name),
            runtimeResidencyLikely =
                assistantSnapshot.activeSession.sessionId.isNotBlank() ||
                    focusPlatformSnapshot?.healthSummary?.staleRuntime != true,
            bootReceiverDeclared = isComponentDeclared(context, AssistantBootReceiver::class.java.name),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
            screenshotStatus = DebugAgentStore.uiState.value.screenshotStatus,
            targetPackageName = targetPackageName,
            targetAppLaunchable = targetPackageName.isBlank() || isPackageLaunchable(context, targetPackageName),
            providerStates = providers,
        )
    }

    private fun isPackageLaunchable(
        context: Context?,
        packageName: String,
    ): Boolean =
        context?.packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.resolveActivity(context.packageManager) != null

    private fun isNotificationListenerEnabled(context: Context?): Boolean {
        context ?: return false
        val enabledListeners =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabledListeners
            .split(':')
            .any { component ->
                component.substringBefore('/').equals(context.packageName, ignoreCase = true)
            }
    }

    private fun isIgnoringBatteryOptimizations(context: Context?): Boolean {
        context ?: return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isComponentDeclared(
        context: Context?,
        className: String,
    ): Boolean {
        context ?: return true
        val packageManager = context.packageManager
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong() or PackageManager.GET_RECEIVERS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            }
        return packageInfo.services.orEmpty().any { it.name == className } ||
            packageInfo.receivers.orEmpty().any { it.name == className }
    }
}
