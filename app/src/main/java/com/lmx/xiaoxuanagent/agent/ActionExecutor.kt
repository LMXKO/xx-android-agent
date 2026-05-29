package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.AlarmClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.lmx.xiaoxuanagent.AppLaunchResolver
import com.lmx.xiaoxuanagent.accessibility.boundsRect
import com.lmx.xiaoxuanagent.accessibility.firstDescendantOrSelf
import com.lmx.xiaoxuanagent.accessibility.readableText
import com.lmx.xiaoxuanagent.accessibility.setTextValue
import com.lmx.xiaoxuanagent.accessibility.supportsAction
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryExtractor
import com.lmx.xiaoxuanagent.runtime.PlatformWebResearchTraceStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryNotebookStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionRuntime

internal data class AgentToolExecutionContext(
    val service: AccessibilityService,
    val indexedObservation: IndexedScreenObservation,
)

internal interface AgentToolExecutor {
    val toolType: AgentActionToolType

    fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult
}

private data class GroundingTargetMetadata(
    val text: String = "",
    val bounds: String = "",
    val containerId: String = "",
    val parserRegionId: String = "",
    val searchScopeId: String = "",
    val stableId: String = "",
    val accessibilityLabel: String = "",
    val collectionPosition: String = "",
    val descriptorTokens: List<String> = emptyList(),
    val visualDescriptorTokens: List<String> = emptyList(),
    val interactionRegions: List<InteractionRegion> = emptyList(),
    val visualSignature: String = "",
    val spatialSignature: String = "",
    val roleHint: String = "",
    val objectType: String = "",
)

object ActionExecutor {
    internal fun runtimeHandlerFor(
        toolName: String,
    ): (AgentToolExecutionContext, AgentAction) -> AgentExecutionResult =
        when (toolName) {
            AgentAction.Click("").toolName,
            AgentAction.SetText("", "").toolName,
            AgentAction.Scroll(null, "down").toolName,
            AgentAction.LongClick("").toolName,
            AgentAction.TapPoint(0, 0).toolName,
            AgentAction.Swipe(0, 0, 0, 0).toolName,
            AgentAction.PressEnter.toolName,
            ->
                { context, action -> GuiActionExecutor.executeRaw(context, action) }

            AgentAction.LaunchApp("").toolName,
            AgentAction.Back.toolName,
            AgentAction.Home.toolName,
            AgentAction.Notifications.toolName,
            AgentAction.QuickSettings.toolName,
            AgentAction.Recents.toolName,
            AgentAction.OpenSettings("").toolName,
            AgentAction.ShareText("").toolName,
            AgentAction.CreateAlarm("", "").toolName,
            AgentAction.CreateTimer("").toolName,
            AgentAction.OpenStopwatch.toolName,
            AgentAction.InsertCalendarEvent("", "", "").toolName,
            AgentAction.DialNumber("").toolName,
            AgentAction.DraftSms("").toolName,
            AgentAction.LookupContact("").toolName,
            AgentAction.ReadSms().toolName,
            AgentAction.ReadCallLog().toolName,
            AgentAction.ReadNotifications().toolName,
            AgentAction.ReplyNotification("", "").toolName,
            AgentAction.MediaControl("").toolName,
            AgentAction.AdjustVolume().toolName,
            AgentAction.OpenDevicePanel("").toolName,
            AgentAction.ReadDeviceStatus().toolName,
            AgentAction.ReadCurrentLocation().toolName,
            AgentAction.SetBrightness().toolName,
            AgentAction.SetDoNotDisturb().toolName,
            AgentAction.SetBatterySaver().toolName,
            AgentAction.OpenPowerDialog.toolName,
            AgentAction.CaptureScreenshot().toolName,
            AgentAction.CapturePhoto().toolName,
            ->
                { context, action -> SystemToolExecutor.executeRaw(context, action) }

            AgentAction.CopyText("").toolName,
            AgentAction.PasteClipboard("").toolName,
            AgentAction.ReadSessionHistory("").toolName,
            AgentAction.SearchArtifacts("").toolName,
            AgentAction.RecallMemory("").toolName,
            AgentAction.ReadSessionNotebook.toolName,
            AgentAction.WriteSessionNote("").toolName,
            AgentAction.SearchTools("").toolName,
            AgentAction.WebSearch("").toolName,
            AgentAction.WebFetch("").toolName,
            AgentAction.ReadConnectedAppCapabilities().toolName,
            AgentAction.ExecuteConnectedAppAction("", "").toolName,
            AgentAction.ReadTodoBoard.toolName,
            AgentAction.WriteTodoBoard("").toolName,
            AgentAction.DelegateLocalAgent("").toolName,
            AgentAction.ReadWorkerRoles.toolName,
            AgentAction.ReadWorkerMailbox().toolName,
            AgentAction.ReplyWorkerMessage("").toolName,
            AgentAction.AcknowledgeWorkerMessage("").toolName,
            AgentAction.ReadSessionMemoryFile.toolName,
            AgentAction.ReadWorkerStatus().toolName,
            AgentAction.MergeWorkerResult("").toolName,
            ->
                { context, action -> OnlineToolExecutor.executeRaw(context, action) }

            AgentAction.FocusPrimaryInput.toolName,
            AgentAction.PopulatePrimaryInput("").toolName,
            AgentAction.SubmitPrimaryAction("").toolName,
            AgentAction.DismissInterrupt("").toolName,
            AgentAction.OpenBestCandidate("", "").toolName,
            AgentAction.ScrollForMore("down").toolName,
            AgentAction.ReturnToTargetApp("").toolName,
            AgentAction.NavigateBack(false, "").toolName,
            ->
                { context, action -> SemanticToolExecutor.executeRaw(context, action) }

            AgentAction.Wait.toolName,
            AgentAction.Finish("").toolName,
            AgentAction.Fail("").toolName,
            ->
                { context, action -> MetaToolExecutor.executeRaw(context, action) }

            else ->
                { _, action -> unsupported(action) }
        }

    fun execute(
        service: AccessibilityService,
        indexedObservation: IndexedScreenObservation,
        action: AgentAction,
    ): AgentExecutionResult {
        val context = AgentToolExecutionContext(service = service, indexedObservation = indexedObservation)
        val runtimeObject = AgentToolRuntimeObjectRegistry.resolve(action = action)
        val validation = runtimeObject.validateInput(action = action, observation = indexedObservation.observation)
        val permission = runtimeObject.checkPermissions(action = action, observation = indexedObservation.observation)
        val inspection = runtimeObject.inspect(action = action, observation = indexedObservation.observation)
        val queuedMessage = runtimeObject.queuedMessage(action = action)
        val progressMessage = runtimeObject.progressMessage(action = action, observation = indexedObservation.observation)
        if (!validation.valid) {
            val rejectedMessage = runtimeObject.rejectedMessage(action = action, validation = validation, permission = permission)
            return AgentExecutionResult(
                message = rejectedMessage,
                keepRunning = true,
                shouldImmediateReplan = true,
                toolRuntimePrompt = validation.prompt.ifBlank { inspection.prompt },
                toolRuntimeSummary = inspection.summary,
                toolRuntimeDetailLines = inspection.detailLines,
                toolRuntimeState = "rejected",
                toolRuntimeQueuedMessage = queuedMessage,
                toolRuntimeProgressMessage = progressMessage,
                toolRuntimeRejectedMessage = rejectedMessage,
            )
        }
        if (permission.behavior == "deny") {
            val rejectedMessage = runtimeObject.rejectedMessage(action = action, validation = validation, permission = permission)
            return AgentExecutionResult(
                message = rejectedMessage,
                keepRunning = false,
                toolRuntimePrompt = validation.prompt.ifBlank { inspection.prompt },
                toolRuntimeSummary = inspection.summary,
                toolRuntimeDetailLines = inspection.detailLines,
                toolRuntimeState = "rejected",
                toolRuntimeQueuedMessage = queuedMessage,
                toolRuntimeProgressMessage = progressMessage,
                toolRuntimeRejectedMessage = rejectedMessage,
            )
        }
        val result =
            runCatching {
                runtimeObject.execute(context, action)
            }.getOrElse { error ->
                val errorMessage = runtimeObject.errorMessage(action = action, throwable = error)
                AgentExecutionResult(
                    message = errorMessage,
                    keepRunning = false,
                    toolRuntimeDetailLines = listOf("exception=${error.javaClass.simpleName}"),
                    toolRuntimeState = "error",
                    toolRuntimeQueuedMessage = queuedMessage,
                    toolRuntimeProgressMessage = progressMessage,
                    toolRuntimeErrorMessage = errorMessage,
                )
            }
        return AgentToolRuntimeKernel.decorateResult(
            result =
                result.copy(
                    toolRuntimeQueuedMessage = result.toolRuntimeQueuedMessage.ifBlank { queuedMessage },
                    toolRuntimeProgressMessage = result.toolRuntimeProgressMessage.ifBlank { progressMessage },
                    toolRuntimeSuccessMessage =
                        result.toolRuntimeSuccessMessage.ifBlank {
                            if (result.toolRuntimeState != "error" && result.toolRuntimeState != "rejected") {
                                result.message
                            } else {
                                ""
                            }
                        },
                    toolRuntimeState =
                        result.toolRuntimeState.ifBlank {
                            if (result.keepRunning) {
                                "running"
                            } else {
                                "completed"
                            }
                        },
                ),
            inspection = inspection,
        )
    }

    fun registeredTools(): List<String> =
        AgentToolCatalog.names()
}

internal object SystemToolExecutor : AgentToolExecutor {
    override val toolType: AgentActionToolType = AgentActionToolType.SYSTEM

    override fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult =
        when (action) {
            is AgentAction.LaunchApp -> executeLaunchApp(context.service, action.packageName)
            AgentAction.Back -> {
                val success = context.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                AgentExecutionResult(
                    message = if (success) "已执行返回。" else "返回操作失败。",
                    keepRunning = success,
                    requiresObservationCheck = success,
                    recommendedWaitMs = 500L,
                )
            }

            AgentAction.Home ->
                executeGlobalAction(
                    service = context.service,
                    actionId = AccessibilityService.GLOBAL_ACTION_HOME,
                    successMessage = "已执行回到桌面。",
                    failureMessage = "回到桌面失败。",
                )

            AgentAction.Notifications ->
                executeGlobalAction(
                    service = context.service,
                    actionId = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                    successMessage = "已展开通知栏。",
                    failureMessage = "展开通知栏失败。",
                )

            AgentAction.QuickSettings ->
                executeGlobalAction(
                    service = context.service,
                    actionId = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                    successMessage = "已展开快捷设置。",
                    failureMessage = "展开快捷设置失败。",
                )

            AgentAction.Recents ->
                executeGlobalAction(
                    service = context.service,
                    actionId = AccessibilityService.GLOBAL_ACTION_RECENTS,
                    successMessage = "已打开最近任务。",
                    failureMessage = "打开最近任务失败。",
                )

            is AgentAction.OpenSettings -> executeOpenSettings(context.service, action.destination)

            is AgentAction.ShareText -> executeShareText(context.service, action.text)

            is AgentAction.CreateAlarm -> executeCreateAlarm(context.service, action.timeLabel, action.message)

            is AgentAction.CreateTimer -> PlatformTimerToolService.createTimer(context.service, action.durationLabel, action.message)

            AgentAction.OpenStopwatch -> PlatformTimerToolService.openStopwatch(context.service)

            is AgentAction.InsertCalendarEvent ->
                PlatformCalendarToolService.insertEvent(context.service, action.title, action.details, action.whenLabel)

            is AgentAction.DialNumber ->
                PlatformCommunicationUtilityService.dialNumber(context.service, action.number, action.contactName)

            is AgentAction.DraftSms ->
                PlatformCommunicationUtilityService.draftSms(context.service, action.recipient, action.body)

            is AgentAction.LookupContact ->
                PlatformContactLookupToolService.lookup(context.service, action.contactName, action.limit)

            is AgentAction.ReadSms ->
                PlatformSmsToolService.readSms(context.service, action.query, action.box, action.limit)

            is AgentAction.ReadCallLog ->
                PlatformCallLogToolService.readCallLog(context.service, action.query, action.type, action.limit)

            is AgentAction.ReadNotifications ->
                PlatformNotificationToolService.readActive(action.packageName, action.limit)

            is AgentAction.ReplyNotification ->
                PlatformNotificationToolService.reply(action.notificationKey, action.replyText, action.actionHint)

            is AgentAction.MediaControl ->
                PlatformMediaControlToolService.control(context.service, action.command)

            is AgentAction.AdjustVolume ->
                PlatformMediaControlToolService.adjustVolume(context.service, action.direction, action.stream, action.step)

            is AgentAction.OpenDevicePanel ->
                PlatformDeviceControlToolService.openPanel(context.service, action.panel)

            is AgentAction.ReadDeviceStatus ->
                PlatformAdvancedDeviceControlService.readStatus(context.service, action.target)

            is AgentAction.ReadCurrentLocation ->
                PlatformCurrentLocationToolService.readCurrentLocation(context.service, action.maxAgeMinutes)

            is AgentAction.SetBrightness ->
                PlatformAdvancedDeviceControlService.setBrightness(context.service, action.level)

            is AgentAction.SetDoNotDisturb ->
                PlatformAdvancedDeviceControlService.setDoNotDisturb(context.service, action.mode)

            is AgentAction.SetBatterySaver ->
                PlatformAdvancedDeviceControlService.setBatterySaver(context.service, action.mode)

            AgentAction.OpenPowerDialog ->
                PlatformAdvancedDeviceControlService.openPowerDialog(context.service)

            is AgentAction.CaptureScreenshot ->
                PlatformCaptureToolService.captureScreenshot(context.indexedObservation, action.note)

            is AgentAction.CapturePhoto ->
                PlatformCaptureToolService.capturePhoto(context.service, action.note)

            else -> unsupported(action)
        }

    private fun executeLaunchApp(
        service: AccessibilityService,
        packageName: String,
    ): AgentExecutionResult {
        AppLaunchResolver.resolve(service.packageManager, packageName)?.let { intent ->
            service.startActivity(intent)
            return AgentExecutionResult(
                message = "已尝试启动应用 $packageName。",
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 1_200L,
            )
        }
        // 主包名未装时，尝试同 connected app 的备选包名（如 Chrome→Edge/Brave/华为浏览器）。
        val descriptor =
            ConnectedAppCatalog.findByPackageName(packageName)
                ?: ConnectedAppCatalog.descriptors()
                    .firstOrNull { it.alternatePackageNames.contains(packageName) }
        if (descriptor != null) {
            descriptor.candidatePackageNames()
                .filter { it != packageName }
                .forEach { alt ->
                    AppLaunchResolver.resolve(service.packageManager, alt)?.let { intent ->
                        service.startActivity(intent)
                        return AgentExecutionResult(
                            message = "$packageName 未安装，改用同类应用 $alt 启动。",
                            keepRunning = true,
                            requiresObservationCheck = true,
                            recommendedWaitMs = 1_200L,
                        )
                    }
                }
        }
        return AgentExecutionResult(
            "找不到应用 $packageName 的可启动入口，可能是应用未安装或包可见性受限。",
            keepRunning = false,
        )
    }

    private fun executeGlobalAction(
        service: AccessibilityService,
        actionId: Int,
        successMessage: String,
        failureMessage: String,
    ): AgentExecutionResult {
        val success = service.performGlobalAction(actionId)
        return AgentExecutionResult(
            message = if (success) successMessage else failureMessage,
            keepRunning = success,
            requiresObservationCheck = success,
            recommendedWaitMs = 600L,
        )
    }

    private fun executeOpenSettings(
        service: AccessibilityService,
        destination: String,
    ): AgentExecutionResult {
        val normalized = destination.trim().lowercase()
        val intent =
            when {
                normalized.contains("accessibility") || normalized.contains("无障碍") ->
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                normalized.contains("notification") || normalized.contains("通知") ->
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                normalized.contains("wifi") || normalized.contains("无线") ->
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                normalized.contains("battery") || normalized.contains("电池") ->
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                normalized.contains("app") || normalized.contains("应用") ->
                    Intent(Settings.ACTION_APPLICATION_SETTINGS)
                else ->
                    Intent(Settings.ACTION_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runIntentAction(
            service = service,
            intent = intent,
            successMessage = "已尝试打开系统设置${destination.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}。",
            failureMessage = "打开系统设置失败。",
        )
    }

    private fun executeShareText(
        service: AccessibilityService,
        text: String,
    ): AgentExecutionResult {
        if (text.isBlank()) {
            return AgentExecutionResult(
                message = "分享文本为空，无法发起系统分享。",
                keepRunning = false,
            )
        }
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val chooser = Intent.createChooser(shareIntent, "分享文本").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runIntentAction(
            service = service,
            intent = chooser,
            successMessage = "已打开系统分享面板。",
            failureMessage = "打开系统分享面板失败。",
        )
    }

    private fun executeCreateAlarm(
        service: AccessibilityService,
        timeLabel: String,
        message: String,
    ): AgentExecutionResult {
        val time = parseHourMinute(timeLabel)
            ?: return AgentExecutionResult(
                message = "无法解析闹钟时间：${timeLabel.ifBlank { "-" }}，请使用 07:30 这样的格式。",
                keepRunning = false,
            )
        val intent =
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, time.first)
                putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                putExtra(AlarmClock.EXTRA_MESSAGE, message.take(80))
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runIntentAction(
            service = service,
            intent = intent,
            successMessage = "已尝试创建闹钟 ${timeLabel.ifBlank { "${time.first}:${time.second}" }}。",
            failureMessage = "创建闹钟失败。",
        )
    }

    private fun runIntentAction(
        service: AccessibilityService,
        intent: Intent,
        successMessage: String,
        failureMessage: String,
    ): AgentExecutionResult =
        runCatching {
            service.startActivity(intent)
        }.fold(
            onSuccess = {
                AgentExecutionResult(
                    message = successMessage,
                    keepRunning = true,
                    requiresObservationCheck = true,
                    recommendedWaitMs = 1_000L,
                )
            },
            onFailure = {
                AgentExecutionResult(
                    message = "$failureMessage ${it.message.orEmpty()}".trim(),
                    keepRunning = false,
                )
            },
        )

    private fun parseHourMinute(
        raw: String,
    ): Pair<Int, Int>? {
        val match = Regex("""(\d{1,2})[:：](\d{1,2})""").find(raw.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

internal object GuiActionExecutor : AgentToolExecutor {
    override val toolType: AgentActionToolType = AgentActionToolType.GUI
    private val imeEnterActionId: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id

    override fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult =
        when (action) {
            is AgentAction.Click -> executeClick(context, action.elementId)
            is AgentAction.SetText -> executeSetText(context, action.elementId, action.text)
            is AgentAction.Scroll -> executeScroll(context, action.elementId, action.direction)
            is AgentAction.LongClick -> executeLongClick(context.indexedObservation, action.elementId)
            is AgentAction.TapPoint -> executeTapPoint(context.service, action.x, action.y)
            is AgentAction.Swipe -> executeSwipe(context.service, action)
            AgentAction.PressEnter -> executePressEnter(context.indexedObservation)
            else -> unsupported(action)
        }

    private fun executeClick(
        context: AgentToolExecutionContext,
        elementId: String,
    ): AgentExecutionResult {
        val indexedObservation = context.indexedObservation
        val elementObservation =
            indexedObservation.observation.elements.firstOrNull { it.id == elementId }
                ?: indexedObservation.searchElementsById[elementId]
        val node = indexedObservation.nodesById[elementId]
        val virtualTarget = indexedObservation.virtualTargetsById[elementId]
        val matchedVirtualNode =
            virtualTarget?.matchedElementId
                ?.let { indexedObservation.nodesById[it] }
        val blockedMetadata = ActionExecutionSupport.metadataFor(elementObservation)
        val blockedFingerprintContext = (blockedMetadata.descriptorTokens + blockedMetadata.visualDescriptorTokens).distinct()
        val blockedFingerprintType =
            GroundingEntityFingerprint.inferType(
                primary = blockedMetadata.text,
                roleHint = blockedMetadata.roleHint,
                contextHints = blockedFingerprintContext.ifEmpty { elementObservation?.neighborTexts.orEmpty() },
            )
        val blockedFingerprintAnchors =
            GroundingEntityFingerprint.buildAnchors(
                primary = blockedMetadata.text,
                type = blockedFingerprintType,
                contextHints = blockedFingerprintContext,
            )
        val blockedReason = ActionExecutionSupport.resolveBlockedClickReason(node)
        if (blockedReason != null) {
            return AgentExecutionResult(
                message = "安全拦截：$blockedReason",
                keepRunning = true,
                requiresObservationCheck = false,
                shouldImmediateReplan = true,
                groundingSource = "tree_blocked",
                resolvedTargetText = blockedMetadata.text,
                resolvedTargetBounds = blockedMetadata.bounds,
                resolvedContainerId = blockedMetadata.containerId,
                resolvedTargetStableId = blockedMetadata.stableId,
                resolvedTargetAccessibilityLabel = blockedMetadata.accessibilityLabel,
                resolvedTargetCollectionPosition = blockedMetadata.collectionPosition,
                resolvedTargetVisualSignature = blockedMetadata.visualSignature,
                resolvedTargetSpatialSignature = blockedMetadata.spatialSignature,
                expectedEntityHint = blockedMetadata.text,
                targetContextHints = blockedFingerprintContext.take(4),
                targetDescriptorTokens = blockedMetadata.descriptorTokens.take(6),
                targetVisualDescriptorTokens = blockedMetadata.visualDescriptorTokens.take(6),
                entityFingerprintType = blockedFingerprintType,
                entityFingerprintAnchors = blockedFingerprintAnchors,
            )
        }
        if (node == null && virtualTarget != null) {
            val matchedTarget = ActionExecutionSupport.resolveClickableTarget(matchedVirtualNode)
            val dispatchResult =
                when {
                    matchedTarget != null -> ActionExecutionSupport.ActionDispatchResult(matchedTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true, "matched_node")
                    else ->
                        ActionExecutionSupport.tapVirtualTarget(
                            service = context.service,
                            target = virtualTarget,
                            packageName = indexedObservation.observation.packageName,
                            pageState = indexedObservation.observation.pageState,
                        )
                }
            val targetSuffix = ActionExecutionSupport.labelSuffix(virtualTarget.text)
            val targetMetadata = ActionExecutionSupport.metadataFor(virtualTarget)
            val fingerprintContext = (targetMetadata.descriptorTokens + targetMetadata.visualDescriptorTokens).distinct()
            val fingerprintType =
                GroundingEntityFingerprint.inferType(
                    primary = targetMetadata.text,
                    roleHint = listOf(targetMetadata.roleHint, targetMetadata.objectType).filter { it.isNotBlank() }.joinToString(" "),
                    contextHints = fingerprintContext.ifEmpty { virtualTarget.contextHints },
                )
            val fingerprintAnchors =
                GroundingEntityFingerprint.buildAnchors(
                    primary = targetMetadata.text,
                    type = fingerprintType,
                    contextHints = fingerprintContext,
                )
            return AgentExecutionResult(
                message =
                    if (dispatchResult.success) {
                        "已通过视觉候选点击 $elementId$targetSuffix。"
                    } else {
                        "视觉候选点击失败：$elementId$targetSuffix。"
                    },
                keepRunning = dispatchResult.success,
                requiresObservationCheck = dispatchResult.success,
                recommendedWaitMs = 700L,
                shouldImmediateReplan = !dispatchResult.success,
                resolvedElementId = virtualTarget.matchedElementId,
                groundingSource =
                    if (matchedTarget != null) {
                        "${virtualTarget.source.ifBlank { "visual_target" }}_matched_node"
                    } else {
                        "${virtualTarget.source.ifBlank { "visual_target" }}_${dispatchResult.mode}"
                    },
                resolvedTargetText = targetMetadata.text,
                resolvedTargetBounds = targetMetadata.bounds,
                resolvedContainerId = targetMetadata.containerId,
                resolvedTargetStableId = targetMetadata.stableId,
                resolvedTargetAccessibilityLabel = targetMetadata.accessibilityLabel,
                resolvedTargetCollectionPosition = targetMetadata.collectionPosition,
                resolvedTargetVisualSignature = targetMetadata.visualSignature,
                resolvedTargetSpatialSignature = targetMetadata.spatialSignature,
                resolvedParserRegionId = virtualTarget.parserRegionId,
                resolvedSearchScopeId = targetMetadata.searchScopeId.ifBlank { virtualTarget.searchScopeId },
                resolvedRegionHitSource = dispatchResult.regionSource,
                resolvedRegionHitX = dispatchResult.pointX,
                resolvedRegionHitY = dispatchResult.pointY,
                expectedEntityHint = targetMetadata.text,
                targetObjectType = targetMetadata.objectType,
                targetContextHints = fingerprintContext.take(4),
                targetDescriptorTokens = targetMetadata.descriptorTokens.take(6),
                targetVisualDescriptorTokens = targetMetadata.visualDescriptorTokens.take(6),
                entityFingerprintType = fingerprintType,
                entityFingerprintAnchors = fingerprintAnchors,
            )
        }
        // 点击前刷新节点，用当前状态而非规划快照的过期 bounds/可见性。若节点仍在树中但已不可见
        // （多半已滚动出屏），不点击幽灵节点，改为重新观察后规划。
        val nodeRefreshed = node?.refresh() ?: false
        if (node != null && nodeRefreshed && !node.isVisibleToUser) {
            return AgentExecutionResult(
                message = "目标 $elementId 已不在可见区域（可能已滚动），重新观察后再规划。",
                keepRunning = true,
                requiresObservationCheck = false,
                shouldImmediateReplan = true,
                groundingSource = "tree_stale_offscreen",
                resolvedElementId = elementId,
                resolvedTargetText = elementObservation?.text.orEmpty(),
                resolvedTargetBounds = elementObservation?.bounds.orEmpty(),
                resolvedContainerId = elementObservation?.containerId.orEmpty(),
            )
        }
        val target = ActionExecutionSupport.resolveClickableTarget(node)
        val success = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        val gestureFallback =
            if (!success && node != null) {
                ActionExecutionSupport.tapNodeObject(
                    service = context.service,
                    node = node,
                    roleHint = elementObservation?.roleHint.orEmpty(),
                    objectType = elementObservation?.source.orEmpty(),
                    descriptorTokens = elementObservation?.descriptorTokens.orEmpty(),
                    interactionRegions = elementObservation?.interactionRegions.orEmpty(),
                    packageName = indexedObservation.observation.packageName,
                    pageState = indexedObservation.observation.pageState,
                )
                } else {
                    ActionExecutionSupport.ActionDispatchResult(false, "")
                }
        val clickedLabel =
            elementObservation?.text
                ?.takeIf { it.isNotBlank() }
                ?: node?.readableText()?.takeIf { it.isNotBlank() }
        val resolvedBounds =
            elementObservation?.bounds
                ?: node?.boundsRect()?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" }
                .orEmpty()
        val targetMetadata = ActionExecutionSupport.metadataFor(elementObservation)
        val fingerprintContext = (targetMetadata.descriptorTokens + targetMetadata.visualDescriptorTokens).distinct()
        val fingerprintType =
            GroundingEntityFingerprint.inferType(
                primary = targetMetadata.text.ifBlank { clickedLabel.orEmpty() },
                roleHint = targetMetadata.roleHint,
                contextHints = fingerprintContext.ifEmpty { elementObservation?.neighborTexts.orEmpty() },
            )
        val fingerprintAnchors =
            GroundingEntityFingerprint.buildAnchors(
                primary = targetMetadata.text.ifBlank { clickedLabel.orEmpty() },
                type = fingerprintType,
                contextHints = fingerprintContext,
            )
        val sourceTag =
            when (elementObservation?.source) {
                "overflow_tree" -> "overflow_tree"
                "overflow_hidden" -> "overflow_hidden"
                "visual" -> "visual"
                else -> "tree"
            }
        val groundingSource =
            when {
                node == null -> "missing_target"
                success && target === node -> "${sourceTag}_node"
                success -> "${sourceTag}_ancestor"
                gestureFallback.success -> "${sourceTag}_${gestureFallback.mode}"
                else -> "${sourceTag}_failed"
            }
        val targetSuffix = ActionExecutionSupport.labelSuffix(clickedLabel)
        return AgentExecutionResult(
            message = when {
                node == null -> "点击失败：未找到元素 $elementId。"
                success && target === node -> "已点击 $elementId$targetSuffix。"
                success -> "已点击 $elementId$targetSuffix，并回退到可点击祖先节点执行。"
                gestureFallback.success -> "已通过区域命中点击 $elementId$targetSuffix。"
                else -> "点击 $elementId 失败。"
            },
            keepRunning = success || gestureFallback.success || node != null,
            requiresObservationCheck = success || gestureFallback.success,
            recommendedWaitMs = 700L,
            shouldImmediateReplan = !success && !gestureFallback.success && node != null,
            resolvedElementId = elementId.takeIf { node != null },
            groundingSource = groundingSource,
            resolvedTargetText = targetMetadata.text.ifBlank { clickedLabel.orEmpty() },
            resolvedTargetBounds = targetMetadata.bounds.ifBlank { resolvedBounds },
            resolvedContainerId = targetMetadata.containerId,
            resolvedTargetStableId = targetMetadata.stableId,
            resolvedTargetAccessibilityLabel = targetMetadata.accessibilityLabel,
            resolvedTargetCollectionPosition = targetMetadata.collectionPosition,
            resolvedTargetVisualSignature = targetMetadata.visualSignature,
            resolvedTargetSpatialSignature = targetMetadata.spatialSignature,
            resolvedParserRegionId = targetMetadata.parserRegionId,
            resolvedSearchScopeId = targetMetadata.searchScopeId,
            resolvedRegionHitSource = gestureFallback.regionSource,
            resolvedRegionHitX = gestureFallback.pointX,
            resolvedRegionHitY = gestureFallback.pointY,
            expectedEntityHint = targetMetadata.text.ifBlank { clickedLabel.orEmpty() },
            targetObjectType = targetMetadata.objectType,
            targetContextHints = fingerprintContext.take(4),
            targetDescriptorTokens = targetMetadata.descriptorTokens.take(6),
            targetVisualDescriptorTokens = targetMetadata.visualDescriptorTokens.take(6),
            entityFingerprintType = fingerprintType,
            entityFingerprintAnchors = fingerprintAnchors,
        )
    }

    private fun executeSetText(
        context: AgentToolExecutionContext,
        elementId: String,
        text: String,
    ): AgentExecutionResult {
        val indexedObservation = context.indexedObservation
        val node = indexedObservation.nodesById[elementId]
        val elementObservation =
            indexedObservation.observation.elements.firstOrNull { it.id == elementId }
                ?: indexedObservation.searchElementsById[elementId]
        val target =
            ActionExecutionSupport.resolveEditableTarget(
                node = node,
                indexedObservation = indexedObservation,
            )
        target?.refresh()
        val focused = target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
        var success = target?.setTextValue(text) == true
        var inputMethod = if (success) "set_text" else ""
        // 回退 1：点击聚焦后重试 setText（部分自绘 / WebView 输入框需先点击获焦点）。
        if (!success && target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            success = target.setTextValue(text)
            if (success) inputMethod = "click_then_set_text"
        }
        // 回退 2：剪贴板粘贴（ACTION_SET_TEXT 被输入框拦截时的兜底）。
        if (!success && target != null && text.isNotEmpty() && pasteTextViaClipboard(context.service, target, text)) {
            success = true
            inputMethod = "clipboard_paste"
        }
        return AgentExecutionResult(
            message = when {
                node == null -> "写入失败：未找到元素 $elementId。"
                target == null -> "写入失败：$elementId 附近没有可编辑节点。"
                success -> "已写入 $elementId（focus=$focused, 方式=$inputMethod）。"
                else -> "写入失败：$elementId 的 setText 与剪贴板粘贴均未生效。"
            },
            keepRunning = success || target != null,
            requiresObservationCheck = success,
            recommendedWaitMs = 500L,
            shouldImmediateReplan = !success && target != null,
            resolvedElementId = indexedObservation.nodesById.entries.firstOrNull { it.value === target }?.key,
            groundingSource =
                when {
                    node == null -> "tree_missing_input"
                    !success -> "tree_editable_failed"
                    inputMethod == "clipboard_paste" -> "tree_editable_paste"
                    inputMethod == "click_then_set_text" -> "tree_editable_click_retry"
                    target === node -> "tree_editable"
                    else -> "tree_editable_derived"
                },
            resolvedTargetText = text,
            resolvedTargetBounds =
                elementObservation?.bounds
                    ?: target?.boundsRect()?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" }
                    .orEmpty(),
            resolvedContainerId = elementObservation?.containerId.orEmpty(),
            expectedEntityHint = text,
            targetObjectType = elementObservation?.roleHint.orEmpty(),
            targetContextHints = (elementObservation?.neighborTexts.orEmpty() + elementObservation?.visualDescriptorTokens.orEmpty()).take(4),
        )
    }

    private fun pasteTextViaClipboard(
        service: AccessibilityService,
        target: AccessibilityNodeInfo,
        text: String,
    ): Boolean =
        runCatching {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("agent_input", text))
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)

    private fun executeScroll(
        context: AgentToolExecutionContext,
        elementId: String?,
        direction: String,
    ): AgentExecutionResult {
        val targetNode = ActionExecutionSupport.resolveScrollableTarget(context.indexedObservation, elementId)
        val normalizedDirection = direction.lowercase()
        val actionId =
            when (normalizedDirection) {
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        val success = targetNode?.performAction(actionId) == true
        val gestureFallback =
            if (!success) {
                ActionExecutionSupport.performScreenScrollGesture(context.service, normalizedDirection)
            } else {
                false
            }
        return AgentExecutionResult(
            message = when {
                success -> "已滚动 ${elementId ?: "默认容器"}，direction=$normalizedDirection。"
                gestureFallback -> "已通过手势滚动屏幕，direction=$normalizedDirection。"
                else -> "滚动失败，direction=$normalizedDirection。"
            },
            keepRunning = success || gestureFallback || targetNode != null,
            requiresObservationCheck = success || gestureFallback,
            recommendedWaitMs = 900L,
            shouldImmediateReplan = !success && !gestureFallback && targetNode != null,
            groundingSource = if (gestureFallback) "gesture_scroll" else "tree_scroll",
            resolvedElementId = elementId,
            resolvedTargetBounds =
                elementId?.let { targetId ->
                    context.indexedObservation.observation.elements.firstOrNull { it.id == targetId }?.bounds
                }.orEmpty(),
            resolvedContainerId =
                elementId?.let { targetId ->
                    context.indexedObservation.observation.elements.firstOrNull { it.id == targetId }?.containerId
                }.orEmpty(),
        )
    }

    private fun executeLongClick(
        indexedObservation: IndexedScreenObservation,
        elementId: String,
    ): AgentExecutionResult {
        val node = indexedObservation.nodesById[elementId]
        val target =
            generateSequence(node) { it.parent }
                .firstOrNull { current ->
                    current.isEnabled &&
                        (current.isLongClickable || current.supportsAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                }
        val success = target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        val elementObservation =
            indexedObservation.observation.elements.firstOrNull { it.id == elementId }
                ?: indexedObservation.searchElementsById[elementId]
        val targetMetadata = ActionExecutionSupport.metadataFor(elementObservation)
        val fingerprintContext = (targetMetadata.descriptorTokens + targetMetadata.visualDescriptorTokens).distinct()
        val fingerprintType =
            GroundingEntityFingerprint.inferType(
                primary = targetMetadata.text,
                roleHint = targetMetadata.roleHint,
                contextHints = fingerprintContext.ifEmpty { elementObservation?.neighborTexts.orEmpty() },
            )
        val fingerprintAnchors =
            GroundingEntityFingerprint.buildAnchors(
                primary = targetMetadata.text,
                type = fingerprintType,
                contextHints = fingerprintContext,
            )
        return AgentExecutionResult(
            message = when {
                node == null -> "长按失败：未找到元素 $elementId。"
                success -> "已长按 $elementId。"
                else -> "长按 $elementId 失败。"
            },
            keepRunning = success || node != null,
            requiresObservationCheck = success,
            recommendedWaitMs = 700L,
            shouldImmediateReplan = !success && node != null,
            resolvedElementId = elementId.takeIf { node != null },
            groundingSource =
                if (success) {
                    when (elementObservation?.source) {
                        "overflow_tree" -> "overflow_tree_long_click"
                        "overflow_recall" -> "overflow_recall_long_click"
                        "overflow_hidden" -> "overflow_hidden_long_click"
                        else -> "tree_long_click"
                    }
                } else {
                    when (elementObservation?.source) {
                        "overflow_tree" -> "overflow_tree_long_click_failed"
                        "overflow_recall" -> "overflow_recall_long_click_failed"
                        "overflow_hidden" -> "overflow_hidden_long_click_failed"
                        else -> "tree_long_click_failed"
                    }
                },
            resolvedTargetText = targetMetadata.text,
            resolvedTargetBounds = targetMetadata.bounds,
            resolvedContainerId = targetMetadata.containerId,
            resolvedTargetStableId = targetMetadata.stableId,
            resolvedTargetAccessibilityLabel = targetMetadata.accessibilityLabel,
            resolvedTargetCollectionPosition = targetMetadata.collectionPosition,
            resolvedTargetVisualSignature = targetMetadata.visualSignature,
            resolvedTargetSpatialSignature = targetMetadata.spatialSignature,
            expectedEntityHint = targetMetadata.text,
            targetObjectType = targetMetadata.roleHint,
            targetContextHints = fingerprintContext.take(4),
            targetDescriptorTokens = targetMetadata.descriptorTokens.take(6),
            targetVisualDescriptorTokens = targetMetadata.visualDescriptorTokens.take(6),
            entityFingerprintType = fingerprintType,
            entityFingerprintAnchors = fingerprintAnchors,
        )
    }

    private fun executeTapPoint(
        service: AccessibilityService,
        x: Int,
        y: Int,
    ): AgentExecutionResult {
        val success = ActionExecutionSupport.dispatchTap(service, x.toFloat(), y.toFloat())
        return AgentExecutionResult(
            message = if (success) "已点击坐标 ($x,$y)。" else "坐标点击失败：($x,$y)。",
            keepRunning = success,
            requiresObservationCheck = success,
            recommendedWaitMs = 700L,
            shouldImmediateReplan = !success,
            groundingSource = "coordinate",
            resolvedTargetBounds = "[$x,$y][$x,$y]",
        )
    }

    private fun executePressEnter(
        indexedObservation: IndexedScreenObservation,
    ): AgentExecutionResult {
        val target =
            indexedObservation.nodesById.values.firstOrNull { it.isFocused }
                ?: indexedObservation.nodesById.values.firstOrNull { it.isEditable }
        val success = target?.performAction(imeEnterActionId) == true
        return AgentExecutionResult(
            message = if (success) "已执行输入法确认。" else "输入法确认失败。",
            keepRunning = success,
            requiresObservationCheck = success,
            recommendedWaitMs = 500L,
            shouldImmediateReplan = !success,
            groundingSource = "ime_enter",
        )
    }

    private fun executeSwipe(
        service: AccessibilityService,
        action: AgentAction.Swipe,
    ): AgentExecutionResult {
        val success =
            ActionExecutionSupport.dispatchSwipe(
                service = service,
                startX = action.startX.toFloat(),
                startY = action.startY.toFloat(),
                endX = action.endX.toFloat(),
                endY = action.endY.toFloat(),
                durationMs = action.durationMs,
            )
        return AgentExecutionResult(
            message =
                if (success) {
                    "已执行滑动 ${action.startX},${action.startY} -> ${action.endX},${action.endY}。"
                } else {
                    "执行滑动失败。"
                },
            keepRunning = success,
            requiresObservationCheck = success,
            recommendedWaitMs = 850L,
            shouldImmediateReplan = !success,
            groundingSource = "coordinate_swipe",
            resolvedTargetBounds = "[${action.startX},${action.startY}][${action.endX},${action.endY}]",
        )
    }
}

internal object OnlineToolExecutor : AgentToolExecutor {
    override val toolType: AgentActionToolType = AgentActionToolType.ONLINE

    override fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult =
        when (action) {
            is AgentAction.CopyText -> executeCopyText(context.indexedObservation, action.elementId)
            is AgentAction.PasteClipboard -> executePasteClipboard(context.indexedObservation, action.elementId)
            is AgentAction.ReadSessionHistory -> executeReadSessionHistory(action.query)
            is AgentAction.SearchArtifacts -> executeSearchArtifacts(action.query, action.artifactType)
            is AgentAction.RecallMemory -> executeRecallMemory(action.query)
            AgentAction.ReadSessionNotebook -> executeReadSessionNotebook()
            is AgentAction.WriteSessionNote -> executeWriteSessionNote(action.note, action.tag)
            is AgentAction.SearchTools -> executeSearchTools(action.query)
            is AgentAction.WebSearch -> executeWebSearch(action.query)
            is AgentAction.WebFetch -> executeWebFetch(action.url)
            is AgentAction.ReadConnectedAppCapabilities -> executeReadConnectedAppCapabilities(action.appId)
            is AgentAction.ExecuteConnectedAppAction -> executeExecuteConnectedAppAction(context.service, action)
            AgentAction.ReadTodoBoard -> executeReadTodoBoard()
            is AgentAction.WriteTodoBoard -> executeWriteTodoBoard(action.content, action.mode)
            is AgentAction.DelegateLocalAgent -> executeDelegateLocalAgent(action.task, action.summary, action.role)
            AgentAction.ReadWorkerRoles -> executeReadWorkerRoles()
            is AgentAction.ReadWorkerMailbox -> executeReadWorkerMailbox(action.target, action.includeConsumed, action.limit)
            is AgentAction.ReplyWorkerMessage -> executeReplyWorkerMessage(action.messageId, action.decision, action.note)
            is AgentAction.AcknowledgeWorkerMessage -> executeAcknowledgeWorkerMessage(action.messageId, action.note)
            AgentAction.ReadSessionMemoryFile -> executeReadSessionMemoryFile()
            is AgentAction.ReadWorkerStatus -> executeReadWorkerStatus(action.target, action.includeChildren)
            is AgentAction.MergeWorkerResult -> executeMergeWorkerResult(action.messageId, action.note)
            else -> unsupported(action)
        }

    private fun executeCopyText(
        indexedObservation: IndexedScreenObservation,
        elementId: String,
    ): AgentExecutionResult {
        val node = indexedObservation.nodesById[elementId]
        val elementObservation =
            indexedObservation.observation.elements.firstOrNull { it.id == elementId }
                ?: indexedObservation.searchElementsById[elementId]
        val text = node?.readableText().orEmpty().ifBlank {
            elementObservation?.text.orEmpty()
        }
        if (text.isBlank()) {
            return AgentExecutionResult(
                message = "复制失败：$elementId 没有可复制文本。",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val clipboard = AppRuntimeContext.get()?.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("agent_copy", text))
        return AgentExecutionResult(
            message = "已复制文本到剪贴板：${text.take(30)}",
            keepRunning = true,
            requiresObservationCheck = false,
            resolvedElementId = elementId,
            groundingSource = "tree_read_text",
            resolvedTargetText = text,
            resolvedTargetBounds = elementObservation?.bounds.orEmpty(),
            resolvedContainerId = elementObservation?.containerId.orEmpty(),
            expectedEntityHint = text,
            targetObjectType = elementObservation?.roleHint.orEmpty(),
            targetContextHints = (elementObservation?.neighborTexts.orEmpty() + elementObservation?.visualDescriptorTokens.orEmpty()).take(4),
        )
    }

    private fun executePasteClipboard(
        indexedObservation: IndexedScreenObservation,
        elementId: String,
    ): AgentExecutionResult {
        val context = AppRuntimeContext.get()
        val clipboard = context?.getSystemService(ClipboardManager::class.java)
        val clipText =
            if (context == null) {
                ""
            } else {
                clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            }
        if (clipText.isBlank()) {
            return AgentExecutionResult(
                message = "粘贴失败：剪贴板为空。",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val node = indexedObservation.nodesById[elementId]
        val elementObservation =
            indexedObservation.observation.elements.firstOrNull { it.id == elementId }
                ?: indexedObservation.searchElementsById[elementId]
        val target = ActionExecutionSupport.resolveEditableTarget(node, indexedObservation)
        val focused = target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
        val success = target?.setTextValue(clipText) == true
        return AgentExecutionResult(
            message = when {
                node == null -> "粘贴失败：未找到元素 $elementId。"
                target == null -> "粘贴失败：$elementId 附近没有可编辑节点。"
                else -> {
                    val resolvedId =
                        indexedObservation.nodesById.entries.firstOrNull { it.value === target }?.key ?: "derived"
                    "已尝试粘贴到 $elementId -> $resolvedId，focus=$focused setText=$success。"
                }
            },
            keepRunning = success || target != null,
            requiresObservationCheck = success,
            recommendedWaitMs = 500L,
            shouldImmediateReplan = !success && target != null,
            resolvedElementId = indexedObservation.nodesById.entries.firstOrNull { it.value === target }?.key,
            groundingSource =
                when {
                    node == null -> "tree_missing_paste"
                    target === node -> "tree_paste"
                    target != null -> "tree_paste_derived"
                    else -> "tree_paste_failed"
                },
            resolvedTargetText = clipText,
            resolvedTargetBounds =
                elementObservation?.bounds
                    ?: target?.boundsRect()?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" }
                    .orEmpty(),
            resolvedContainerId = elementObservation?.containerId.orEmpty(),
            expectedEntityHint = clipText,
            targetObjectType = elementObservation?.roleHint.orEmpty(),
            targetContextHints = (elementObservation?.neighborTexts.orEmpty() + elementObservation?.visualDescriptorTokens.orEmpty()).take(4),
        )
    }

    private fun executeReadSessionHistory(
        query: String,
    ): AgentExecutionResult {
        val historySummary =
            if (query.isBlank()) {
                val snapshot = SessionPlatformFacade.readSessionHistory(limit = 6)
                snapshot.entries.map { entry ->
                    "${entry.statusCode} | ${entry.title.take(28)} | ${entry.summary.take(48)}"
                }
            } else {
                val result = SessionPlatformFacade.searchSessionHistory(query = query, limit = 6)
                result.matches.map { entry ->
                    "${entry.statusCode} | ${entry.title.take(28)} | ${entry.summary.take(48)}"
                }
            }
        return AgentExecutionResult(
            message =
                if (historySummary.isEmpty()) {
                    "未命中 session history${query.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}。"
                } else {
                    "已读取 session history${query.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}。"
                },
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_history",
            resolvedTargetText = query,
            toolRuntimeDetailLines = historySummary.take(6),
        )
    }

    private fun executeSearchArtifacts(
        query: String,
        artifactType: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        val requestedTypes = artifactType.takeIf { it.isNotBlank() }?.let { setOf(it) }.orEmpty()
        val localHits =
            SessionPlatformFacade.searchArtifacts(
                query = query,
                sessionId = session.sessionId,
                limit = 6,
                types = requestedTypes,
            )
        val hits =
            if (localHits.isNotEmpty() || session.sessionId.isBlank()) {
                localHits
            } else {
                SessionPlatformFacade.searchArtifacts(
                    query = query,
                    limit = 6,
                    types = requestedTypes,
                )
            }
        val lines =
            hits.map { hit ->
                "${hit.record.type} | turn=${hit.turn} | ${hit.record.summary.take(64)}"
            }
        return AgentExecutionResult(
            message =
                if (lines.isEmpty()) {
                    "未命中 artifact${artifactType.takeIf { it.isNotBlank() }?.let { " type=$it" }.orEmpty()}：${query.take(48)}。"
                } else {
                    "已检索 artifact 证据：${query.take(48)}。"
                },
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_artifact_search",
            resolvedTargetText = query,
            toolRuntimeDetailLines = lines.take(6),
        )
    }

    private fun executeRecallMemory(
        query: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        val recall =
            PersonalMemoryStore.recall(
                task = query,
                profileId = session.profileId,
            )
        val lines =
            (
                recall.shortTermNotes +
                    recall.longTermMemories +
                    recall.userPreferences +
                    recall.resultArtifacts +
                    recall.correctionTemplates
            ).distinct().take(6)
        return AgentExecutionResult(
            message =
                if (lines.isEmpty()) {
                    "未召回到相关长期记忆：${query.take(48)}。"
                } else {
                    "已召回长期记忆：${query.take(48)}。"
                },
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_memory_recall",
            resolvedTargetText = query,
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeReadSessionNotebook(
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        val snapshot = SessionMemoryNotebookStore.readSnapshot(session.sessionId)
        val lines =
            buildList {
                snapshot?.headline?.takeIf { it.isNotBlank() }?.let { add("headline: $it") }
                addAll(snapshot?.previewLines.orEmpty().take(5))
            }
        return AgentExecutionResult(
            message =
                if (snapshot == null) {
                    "当前 session notebook 为空。"
                } else {
                    "已读取当前 session notebook。"
                },
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_session_notebook",
            resolvedTargetText = snapshot?.headline.orEmpty(),
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeWriteSessionNote(
        note: String,
        tag: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        val snapshot =
            SessionMemoryNotebookStore.appendManualNote(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                note = note,
                tag = tag,
            )
        if (snapshot == null) {
            return AgentExecutionResult(
                message = "写入 session notebook 失败：当前没有可写 session。",
                keepRunning = false,
                shouldImmediateReplan = true,
            )
        }
        BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
            sessionId = session.sessionId,
            task = session.task,
            profileId = session.profileId,
        )
        return AgentExecutionResult(
            message = "已写入 session notebook：${note.take(48)}",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_session_notebook_write",
            resolvedTargetText = note,
            toolRuntimeDetailLines = snapshot.previewLines.take(6),
        )
    }

    private fun executeSearchTools(
        query: String,
    ): AgentExecutionResult {
        val lines = PlatformToolSearchService.search(query)
        return AgentExecutionResult(
            message = if (lines.isEmpty()) "没有匹配的工具能力。" else "已检索本地工具能力：${query.take(48)}。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_tool_search",
            resolvedTargetText = query,
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeWebSearch(
        query: String,
    ): AgentExecutionResult {
        val lines = PlatformWebResearchService.search(query)
        val session = SessionRuntime.State.runtimeState().session
        PlatformWebResearchTraceStore.record(
            sessionId = session.sessionId,
            type = "search",
            query = query,
            summary = lines.firstOrNull().orEmpty().ifBlank { query.take(96) },
            detailLines = lines,
        )
        return AgentExecutionResult(
            message = if (lines.isEmpty()) "网页搜索未返回结果。" else "已执行网页搜索：${query.take(48)}。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_web_search",
            resolvedTargetText = query,
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeWebFetch(
        url: String,
    ): AgentExecutionResult {
        val lines = PlatformWebResearchService.fetch(url)
        val session = SessionRuntime.State.runtimeState().session
        PlatformWebResearchTraceStore.record(
            sessionId = session.sessionId,
            type = "fetch",
            url = url,
            summary = lines.firstOrNull().orEmpty().ifBlank { url.take(96) },
            detailLines = lines,
        )
        return AgentExecutionResult(
            message = if (lines.isEmpty()) "网页抓取未返回内容。" else "已抓取网页：${url.take(64)}。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "platform_web_fetch",
            resolvedTargetText = url,
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeReadConnectedAppCapabilities(
        appId: String,
    ): AgentExecutionResult {
        val lines = PlatformConnectedAppToolService.readCapabilities(appId)
        return AgentExecutionResult(
            message = if (lines.isEmpty()) "未找到 connected app 能力。" else "已读取 connected app 能力。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "connected_app_catalog",
            resolvedTargetText = appId,
            toolRuntimeDetailLines = lines,
        )
    }

    private fun executeExecuteConnectedAppAction(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): AgentExecutionResult = PlatformConnectedAppToolService.execute(service, action)

    private fun executeReadTodoBoard(
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformTodoToolService.readCurrentBoard(session.sessionId)
    }

    private fun executeWriteTodoBoard(
        content: String,
        mode: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformTodoToolService.writeCurrentBoard(session.sessionId, content, mode)
    }

    private fun executeDelegateLocalAgent(
        task: String,
        summary: String,
        role: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformWorkerToolService.delegateCurrentSessionTask(session.sessionId, task, summary, role)
    }

    private fun executeReadWorkerRoles(): AgentExecutionResult = PlatformWorkerRoleToolService.readRoles()

    private fun executeReadWorkerMailbox(
        target: String,
        includeConsumed: Boolean,
        limit: Int,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformWorkerMailboxToolService.readMailbox(session.sessionId, target, includeConsumed, limit)
    }

    private fun executeReplyWorkerMessage(
        messageId: String,
        decision: String,
        note: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformWorkerMailboxToolService.replyToMessage(session.sessionId, messageId, decision, note)
    }

    private fun executeAcknowledgeWorkerMessage(
        messageId: String,
        note: String,
    ): AgentExecutionResult = PlatformWorkerMailboxToolService.acknowledgeMessage(messageId, note)

    private fun executeReadSessionMemoryFile(
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformSessionMemoryFileToolService.readCurrentFile(session.sessionId, session.task, session.profileId)
    }

    private fun executeReadWorkerStatus(
        target: String,
        includeChildren: Boolean,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformWorkerLifecycleToolService.readWorkerStatus(session.sessionId, target, includeChildren)
    }

    private fun executeMergeWorkerResult(
        messageId: String,
        note: String,
    ): AgentExecutionResult {
        val session = SessionRuntime.State.runtimeState().session
        return PlatformWorkerLifecycleToolService.mergeWorkerResult(
            sessionId = session.sessionId,
            task = session.task,
            profileId = session.profileId,
            messageId = messageId,
            note = note,
        )
    }
}

internal object MetaToolExecutor : AgentToolExecutor {
    override val toolType: AgentActionToolType = AgentActionToolType.META

    override fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult =
        when (action) {
            AgentAction.Wait ->
                AgentExecutionResult(
                    message = "等待页面稳定后继续规划。",
                    keepRunning = true,
                    recommendedWaitMs = 900L,
                )

            is AgentAction.Finish -> AgentExecutionResult("任务完成：${action.summary}", keepRunning = false)
            is AgentAction.Fail -> AgentExecutionResult("任务失败：${action.reason}", keepRunning = false)
            else -> unsupported(action)
        }
}

internal object SemanticToolExecutor : AgentToolExecutor {
    override val toolType: AgentActionToolType = AgentActionToolType.SEMANTIC

    override fun executeRaw(
        context: AgentToolExecutionContext,
        action: AgentAction,
    ): AgentExecutionResult {
        val resolvedAction =
            AgentSemanticActionResolver.resolve(
                indexedObservation = context.indexedObservation,
                action = action,
            ) ?: AgentAction.Wait
        if (resolvedAction == action) {
            return AgentExecutionResult(
                message = "语义动作 ${action.toolName} 当前没有可落地目标。",
                keepRunning = true,
                shouldImmediateReplan = true,
            )
        }
        val delegatedResult =
            when (resolvedAction.toolType) {
                AgentActionToolType.GUI -> GuiActionExecutor.executeRaw(context, resolvedAction)
                AgentActionToolType.SYSTEM -> SystemToolExecutor.executeRaw(context, resolvedAction)
                AgentActionToolType.ONLINE -> OnlineToolExecutor.executeRaw(context, resolvedAction)
                AgentActionToolType.META -> MetaToolExecutor.executeRaw(context, resolvedAction)
                AgentActionToolType.SEMANTIC -> unsupported(action)
            }
        return delegatedResult.copy(
            message = "语义动作 ${action.label} -> ${resolvedAction.label} | ${delegatedResult.message}",
        )
    }
}

private object ActionExecutionSupport {
    data class ActionDispatchResult(
        val success: Boolean,
        val mode: String,
        val regionSource: String = "",
        val pointX: Float = 0f,
        val pointY: Float = 0f,
    )

    private val blockedClickKeywords =
        listOf(
            "立即购买",
            "确认购买",
            "提交订单",
            "立即支付",
            "确认付款",
            "去支付",
            "结算",
            "下单",
            "加入购物车",
            "确认下单",
        )
    private val blockedShortCtaKeywords = setOf("付款", "支付", "购买", "下单", "结算")
    private val paymentStatsPattern = Regex("""[0-9一二三四五六七八九十百千万+\s]*人付款""")

    fun resolveBlockedClickReason(
        node: AccessibilityNodeInfo?,
    ): String? {
        if (node == null) return null
        var current = node
        while (current != null) {
            val semantics = buildNodeSemantics(current)
            val matched = semantics.firstNotNullOfOrNull(::matchBlockedSemantic)
            if (matched != null) {
                return "目标节点包含高风险文案“$matched”"
            }
            current = current.parent
        }
        return null
    }

    fun boundsCenter(bounds: String): Pair<Int, Int>? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        val left = values[0]
        val top = values[1]
        val right = values[2]
        val bottom = values[3]
        return ((left + right) / 2) to ((top + bottom) / 2)
    }

    fun tapVirtualTarget(
        service: AccessibilityService,
        target: VirtualUiTarget,
        packageName: String,
        pageState: String,
    ): ActionDispatchResult {
        val rect = parseBounds(target.bounds) ?: return ActionDispatchResult(false, "region_none")
        val learnedRegions =
            RegionHitTestLearningStore.suggest(
                packageName = packageName,
                pageState = pageState,
                roleHint = target.roleHint,
                objectType = target.objectType,
                descriptorTokens = (target.descriptorTokens + target.contextHints).distinct(),
                textFree = target.textFree,
            )
        val plan =
            RegionHitTestEngine.buildPlan(
                rect = rect,
                hotspotX = target.tapHotspotX,
                hotspotY = target.tapHotspotY,
                roleHint = target.roleHint,
                objectType = target.objectType,
                tapPattern = target.tapPattern,
                descriptorTokens = target.descriptorTokens,
                interactionRegions = target.interactionRegions,
                packageName = packageName,
                pageState = pageState,
                learnedRegions = learnedRegions,
                textFree = target.textFree,
            )
        plan.points.forEach { point ->
            if (dispatchTap(service, point.x, point.y)) {
                return ActionDispatchResult(
                    success = true,
                    mode = "region_${plan.mode}",
                    regionSource = point.source,
                    pointX = point.x,
                    pointY = point.y,
                )
            }
        }
        return ActionDispatchResult(success = false, mode = "region_none")
    }

    fun labelSuffix(
        label: String?,
    ): String =
        label
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "(${it.take(36)})" }
            .orEmpty()

    fun resolveClickableTarget(
        node: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isEnabled && (current.isClickable || current.supportsAction(AccessibilityNodeInfo.ACTION_CLICK))) {
                return current
            }
            current = current.parent
        }
        return node
    }

    fun resolveEditableTarget(
        node: AccessibilityNodeInfo?,
        indexedObservation: IndexedScreenObservation,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        node.firstDescendantOrSelf { it.isEditable }?.let { return it }
        var current = node.parent
        while (current != null) {
            current.firstDescendantOrSelf { it.isEditable }?.let { return it }
            current = current.parent
        }
        indexedObservation.nodesById.values.firstOrNull { it.isEditable && it.isFocused }?.let { return it }
        return indexedObservation.nodesById.values.firstOrNull { it.isEditable }
    }

    fun resolveScrollableTarget(
        indexedObservation: IndexedScreenObservation,
        elementId: String?,
    ): AccessibilityNodeInfo? {
        if (!elementId.isNullOrBlank()) {
            var current = indexedObservation.nodesById[elementId]
            while (current != null) {
                if (current.isScrollable || current.supportsAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return current
                }
                current = current.parent
            }
        }
        return indexedObservation.nodesById.values.firstOrNull { it.isScrollable }
    }

    fun tapNodeCenter(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): Boolean {
        val rect = node.boundsRect()
        if (rect.isEmpty) return false
        return dispatchTap(service, rect.exactCenterX(), rect.exactCenterY())
    }

    fun tapNodeObject(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        roleHint: String,
        objectType: String,
        descriptorTokens: List<String>,
        interactionRegions: List<InteractionRegion>,
        packageName: String,
        pageState: String,
    ): ActionDispatchResult {
        val rect = node.boundsRect()
        if (rect.isEmpty) return ActionDispatchResult(false, "region_none")
        val learnedRegions =
            RegionHitTestLearningStore.suggest(
                packageName = packageName,
                pageState = pageState,
                roleHint = roleHint,
                objectType = objectType,
                descriptorTokens = descriptorTokens,
                textFree = descriptorTokens.isEmpty(),
            )
        val plan =
            RegionHitTestEngine.buildPlan(
                rect = rect,
                hotspotX = 0.5f,
                hotspotY = 0.5f,
                roleHint = roleHint,
                objectType = objectType,
                tapPattern =
                    when {
                        descriptorTokens.any { it.contains("关闭") || it.contains("取消") } -> "corner_action"
                        descriptorTokens.any { it.contains("列表") || it.contains("会话") || it.contains("联系人") } -> "list_row"
                        else -> "center_bias"
                    },
                descriptorTokens = descriptorTokens,
                interactionRegions = interactionRegions,
                packageName = packageName,
                pageState = pageState,
                learnedRegions = learnedRegions,
                textFree = descriptorTokens.isEmpty(),
            )
        plan.points.forEach { point ->
            if (dispatchTap(service, point.x, point.y)) {
                return ActionDispatchResult(
                    success = true,
                    mode = "region_${plan.mode}",
                    regionSource = point.source,
                    pointX = point.x,
                    pointY = point.y,
                )
            }
        }
        return ActionDispatchResult(success = false, mode = "region_none")
    }

    fun dispatchTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun metadataFor(
        element: UiElementObservation?,
    ): GroundingTargetMetadata =
        if (element == null) {
            GroundingTargetMetadata()
        } else {
            GroundingTargetMetadata(
                text = element.text.ifBlank { element.accessibilityLabel }.ifBlank {
                    (element.visualDescriptorTokens + element.descriptorTokens).firstOrNull().orEmpty()
                },
                bounds = element.bounds,
                containerId = element.containerId,
                parserRegionId = "",
                searchScopeId = element.searchScopeId,
                stableId = element.accessibilityUniqueId,
                accessibilityLabel = element.accessibilityLabel,
                collectionPosition = element.collectionPosition,
                descriptorTokens = (element.descriptorTokens + element.neighborTexts).distinct().take(8),
                visualDescriptorTokens = element.visualDescriptorTokens.take(8),
                interactionRegions = element.interactionRegions,
                visualSignature = element.visualSignature,
                spatialSignature = element.spatialSignature,
                roleHint = element.roleHint,
                objectType = element.className.ifBlank { element.source },
            )
        }

    fun metadataFor(
        target: VirtualUiTarget,
    ): GroundingTargetMetadata =
        GroundingTargetMetadata(
            text = target.text.ifBlank { target.accessibilityLabel }.ifBlank {
                (target.descriptorTokens + target.contextHints).firstOrNull().orEmpty()
            },
            bounds = target.bounds,
            containerId = target.matchedContainerId,
            parserRegionId = target.parserRegionId,
            searchScopeId = target.searchScopeId,
            stableId = target.accessibilityUniqueId,
            accessibilityLabel = target.accessibilityLabel.ifBlank { target.text.takeIf { !target.textFree }.orEmpty() },
            collectionPosition = target.collectionPosition,
            descriptorTokens = (target.descriptorTokens + target.contextHints).distinct().take(8),
            visualDescriptorTokens = target.descriptorTokens.take(8),
            interactionRegions = target.interactionRegions,
            visualSignature = target.visualSignature,
            spatialSignature = target.spatialSignature,
            roleHint = target.roleHint,
            objectType = target.objectType,
        )

    private fun parseBounds(
        bounds: String,
    ): Rect? {
        val values = Regex("""-?\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        if (values.size < 4) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    fun dispatchSwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(120L)))
                .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun performScreenScrollGesture(
        service: AccessibilityService,
        direction: String,
    ): Boolean {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.toFloat().coerceAtLeast(1f)
        val height = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        val centerX = width * 0.5f
        return when (direction) {
            "up" ->
                dispatchSwipe(
                    service = service,
                    startX = centerX,
                    startY = height * 0.35f,
                    endX = centerX,
                    endY = height * 0.72f,
                    durationMs = 280L,
                )

            else ->
                dispatchSwipe(
                    service = service,
                    startX = centerX,
                    startY = height * 0.72f,
                    endX = centerX,
                    endY = height * 0.35f,
                    durationMs = 280L,
                )
        }
    }

    private fun matchBlockedSemantic(
        rawSemantic: String,
    ): String? {
        val semantic = rawSemantic.trim()
        if (semantic.isBlank()) return null
        if (paymentStatsPattern.containsMatchIn(semantic)) return null
        blockedClickKeywords.firstOrNull { keyword ->
            semantic.contains(keyword, ignoreCase = true)
        }?.let { return it }
        val compactSemantic = semantic.replace("\\s+".toRegex(), "")
        return blockedShortCtaKeywords.firstOrNull { keyword ->
            compactSemantic.equals(keyword, ignoreCase = true)
        }
    }

    private fun buildNodeSemantics(
        node: AccessibilityNodeInfo,
    ): List<String> {
        val values = linkedSetOf<String>()
        node.readableText().takeIf { it.isNotBlank() }?.let(values::add)
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let(values::add)
        node.className?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
        return values.toList()
    }
}

private fun unsupported(
    action: AgentAction,
): AgentExecutionResult =
    AgentExecutionResult(
        message = "当前执行器不支持 ${action.toolName}。",
        keepRunning = false,
    )
