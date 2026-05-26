package com.lmx.xiaoxuanagent.agent

data class ActionVerificationResult(
    val verified: Boolean,
    val message: String,
    val shouldImmediateReplan: Boolean,
)

object ActionVerifier {
    fun verify(
        action: AgentAction,
        before: ScreenObservation,
        afterIndexedObservation: IndexedScreenObservation?,
        resolvedElementId: String? = null,
        executionResult: AgentExecutionResult? = null,
        afterScreenshot: ScreenshotPayload? = null,
        afterVisualContext: VisualPerceptionContext? = null,
        afterFrames: List<GroundingVerificationFrame> = emptyList(),
    ): ActionVerificationResult {
        val after = afterIndexedObservation?.observation
        val effectiveResolvedElementId = executionResult?.resolvedElementId ?: resolvedElementId
        val entityDescriptor = GroundingEntityFingerprint.fromExecutionResult(executionResult)
        val expectedTargetText = entityDescriptor.primary
        val targetContextHints = entityDescriptor.contextTokens
        if (after == null) {
            return when (action) {
                is AgentAction.LaunchApp -> ActionVerificationResult(
                    verified = false,
                    message = "启动应用后暂时读不到页面，继续等待系统切换。",
                    shouldImmediateReplan = false,
                )

                is AgentAction.Click,
                is AgentAction.SetText,
                is AgentAction.Scroll,
                is AgentAction.LongClick,
                is AgentAction.CopyText,
                is AgentAction.PasteClipboard,
                is AgentAction.ReadSessionHistory,
                is AgentAction.SearchArtifacts,
                is AgentAction.RecallMemory,
                AgentAction.ReadSessionNotebook,
                is AgentAction.WriteSessionNote,
                is AgentAction.SearchTools,
                is AgentAction.WebSearch,
                is AgentAction.WebFetch,
                is AgentAction.ReadConnectedAppCapabilities,
                is AgentAction.ExecuteConnectedAppAction,
                AgentAction.ReadTodoBoard,
                is AgentAction.WriteTodoBoard,
                is AgentAction.DelegateLocalAgent,
                AgentAction.ReadWorkerRoles,
                is AgentAction.ReadWorkerMailbox,
                is AgentAction.ReplyWorkerMessage,
                is AgentAction.AcknowledgeWorkerMessage,
                AgentAction.ReadSessionMemoryFile,
                is AgentAction.ReadWorkerStatus,
                is AgentAction.MergeWorkerResult,
                is AgentAction.TapPoint,
                is AgentAction.Swipe,
                AgentAction.Back,
                AgentAction.Home,
                AgentAction.Notifications,
                AgentAction.QuickSettings,
                AgentAction.Recents,
                is AgentAction.OpenSettings,
                is AgentAction.ShareText,
                is AgentAction.CreateAlarm,
                is AgentAction.CreateTimer,
                AgentAction.OpenStopwatch,
                is AgentAction.InsertCalendarEvent,
                is AgentAction.DialNumber,
                is AgentAction.DraftSms,
                is AgentAction.LookupContact,
                is AgentAction.ReadCallLog,
                is AgentAction.ReadNotifications,
                is AgentAction.ReplyNotification,
                is AgentAction.MediaControl,
                is AgentAction.AdjustVolume,
                is AgentAction.ReadDeviceStatus,
                is AgentAction.ReadCurrentLocation,
                is AgentAction.SetBrightness,
                is AgentAction.SetDoNotDisturb,
                is AgentAction.SetBatterySaver,
                AgentAction.OpenPowerDialog,
                is AgentAction.OpenDevicePanel,
                is AgentAction.CaptureScreenshot,
                is AgentAction.CapturePhoto,
                AgentAction.PressEnter,
                AgentAction.FocusPrimaryInput,
                is AgentAction.PopulatePrimaryInput,
                is AgentAction.SubmitPrimaryAction,
                is AgentAction.DismissInterrupt,
                is AgentAction.OpenBestCandidate,
                is AgentAction.ScrollForMore,
                is AgentAction.ReturnToTargetApp,
                is AgentAction.NavigateBack,
                -> ActionVerificationResult(
                    verified = false,
                    message = "动作后无法重新读到页面，准备重新规划。",
                    shouldImmediateReplan = true,
                )

                AgentAction.Wait,
                is AgentAction.Finish,
                is AgentAction.Fail,
                -> ActionVerificationResult(
                    verified = true,
                    message = "该动作无需额外验证。",
                    shouldImmediateReplan = false,
                )
            }
        }

        val result =
            when (action) {
            is AgentAction.LaunchApp -> verifyLaunchApp(before, action, after)
            is AgentAction.Click ->
                verifyClick(
                    before = before,
                    after = after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    entityDescriptor = entityDescriptor,
                    expectedTargetText = expectedTargetText,
                    targetContextHints = targetContextHints,
                    requireTargetPresence = expectedTargetText.isNotBlank(),
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.SetText -> verifySetText(action, before, after, effectiveResolvedElementId)
            is AgentAction.Scroll -> verifyScroll(before, after)
            is AgentAction.LongClick ->
                verifyClick(
                    before = before,
                    after = after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "长按后页面已变化。",
                    entityDescriptor = entityDescriptor,
                    expectedTargetText = expectedTargetText,
                    targetContextHints = targetContextHints,
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.CopyText -> ActionVerificationResult(true, "已复制文本到剪贴板。", false)
            is AgentAction.ReadSessionHistory -> ActionVerificationResult(true, "已读取会话历史。", false)
            is AgentAction.SearchArtifacts -> ActionVerificationResult(true, "已检索 artifact 证据。", false)
            is AgentAction.RecallMemory -> ActionVerificationResult(true, "已召回长期记忆。", false)
            AgentAction.ReadSessionNotebook -> ActionVerificationResult(true, "已读取 session notebook。", false)
            is AgentAction.WriteSessionNote -> ActionVerificationResult(true, "已写入 session notebook。", false)
            is AgentAction.SearchTools -> ActionVerificationResult(true, "已检索工具能力。", false)
            is AgentAction.WebSearch -> ActionVerificationResult(true, "已完成网页搜索。", false)
            is AgentAction.WebFetch -> ActionVerificationResult(true, "已抓取网页内容。", false)
            is AgentAction.ReadConnectedAppCapabilities -> ActionVerificationResult(true, "已读取 connected app 能力。", false)
            is AgentAction.ExecuteConnectedAppAction -> ActionVerificationResult(true, "已执行 connected app 动作。", false)
            AgentAction.ReadTodoBoard -> ActionVerificationResult(true, "已读取 todo board。", false)
            is AgentAction.WriteTodoBoard -> ActionVerificationResult(true, "已更新 todo board。", false)
            is AgentAction.DelegateLocalAgent -> ActionVerificationResult(true, "已委派本地 worker。", false)
            AgentAction.ReadWorkerRoles -> ActionVerificationResult(true, "已读取 worker 角色目录。", false)
            is AgentAction.ReadWorkerMailbox -> ActionVerificationResult(true, "已读取 worker mailbox。", false)
            is AgentAction.ReplyWorkerMessage -> ActionVerificationResult(true, "已回复 worker 消息。", false)
            is AgentAction.AcknowledgeWorkerMessage -> ActionVerificationResult(true, "已确认 worker 消息。", false)
            AgentAction.ReadSessionMemoryFile -> ActionVerificationResult(true, "已读取 session memory file。", false)
            is AgentAction.ReadWorkerStatus -> ActionVerificationResult(true, "已读取 worker 状态。", false)
            is AgentAction.MergeWorkerResult -> ActionVerificationResult(true, "已合并 worker 结果。", false)
            is AgentAction.PasteClipboard ->
                verifyClick(
                    before = before,
                    after = after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "粘贴后页面已变化。",
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.TapPoint ->
                verifyClick(
                    before = before,
                    after = after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "坐标点击后页面已变化。",
                    entityDescriptor = entityDescriptor,
                    expectedTargetText = expectedTargetText,
                    targetContextHints = targetContextHints,
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.Swipe -> verifyScroll(before, after, successMessage = "手势滑动后页面内容已变化。")
            AgentAction.Back -> verifyBack(before, after)
            AgentAction.Home -> verifyGlobalAction(after, "已切回桌面或系统首页。")
            AgentAction.Notifications -> verifyGlobalAction(after, "已切换到通知相关界面。")
            AgentAction.QuickSettings -> verifyGlobalAction(after, "已切换到快捷设置界面。")
            AgentAction.Recents -> verifyGlobalAction(after, "已切换到最近任务界面。")
            is AgentAction.OpenSettings -> verifyGlobalAction(after, "已切换到系统设置相关界面。")
            is AgentAction.ShareText -> verifyGlobalAction(after, "已打开系统分享相关界面。")
            is AgentAction.CreateAlarm -> verifyGlobalAction(after, "已切换到系统闹钟相关界面。")
            is AgentAction.CreateTimer -> verifyGlobalAction(after, "已切换到系统计时器相关界面。")
            AgentAction.OpenStopwatch -> verifyGlobalAction(after, "已切换到系统秒表相关界面。")
            is AgentAction.InsertCalendarEvent -> verifyGlobalAction(after, "已切换到系统日历相关界面。")
            is AgentAction.DialNumber -> verifyGlobalAction(after, "已切换到拨号相关界面。")
            is AgentAction.DraftSms -> verifyGlobalAction(after, "已切换到短信草稿相关界面。")
            is AgentAction.LookupContact -> ActionVerificationResult(true, "已读取联系人候选。", false)
            is AgentAction.ReadCallLog -> ActionVerificationResult(true, "已读取通话记录摘要。", false)
            is AgentAction.ReadNotifications -> ActionVerificationResult(true, "已读取通知摘要。", false)
            is AgentAction.ReplyNotification -> ActionVerificationResult(true, "已尝试回复通知。", false)
            is AgentAction.MediaControl -> ActionVerificationResult(true, "已发送媒体控制。", false)
            is AgentAction.AdjustVolume -> ActionVerificationResult(true, "已调节系统音量。", false)
            is AgentAction.ReadDeviceStatus -> ActionVerificationResult(true, "已读取设备状态。", false)
            is AgentAction.ReadCurrentLocation -> ActionVerificationResult(true, "已读取当前位置。", false)
            is AgentAction.SetBrightness -> ActionVerificationResult(true, "已尝试设置屏幕亮度。", false)
            is AgentAction.SetDoNotDisturb -> ActionVerificationResult(true, "已尝试设置免打扰模式。", false)
            is AgentAction.SetBatterySaver -> ActionVerificationResult(true, "已处理省电模式调整。", false)
            AgentAction.OpenPowerDialog -> ActionVerificationResult(true, "已打开系统电源对话框。", false)
            is AgentAction.OpenDevicePanel -> verifyGlobalAction(after, "已打开设备控制面板。")
            is AgentAction.CaptureScreenshot -> ActionVerificationResult(true, "已记录屏幕截图。", false)
            is AgentAction.CapturePhoto -> verifyGlobalAction(after, "已打开系统相机。")
            AgentAction.PressEnter ->
                verifyClick(
                    before = before,
                    after = after,
                    successMessage = "输入法确认后页面已变化。",
                    afterFrames = afterFrames,
                )
            AgentAction.FocusPrimaryInput -> verifyFocusPrimaryInput(before, after)
            is AgentAction.PopulatePrimaryInput ->
                if (!resolvedElementId.isNullOrBlank() || !before.primaryEditableId.isNullOrBlank()) {
                    verifySetText(
                        action = AgentAction.SetText(resolvedElementId ?: before.primaryEditableId.orEmpty(), action.text),
                        before = before,
                        after = after,
                        resolvedElementId = effectiveResolvedElementId,
                    )
                } else {
                    verifyClick(
                        before,
                        after,
                        afterIndexedObservation = afterIndexedObservation,
                        executionResult = executionResult,
                        successMessage = "已重新定位并填充主输入入口。",
                        expectedTargetText = action.text,
                        targetContextHints = targetContextHints,
                        afterScreenshot = afterScreenshot,
                        afterVisualContext = afterVisualContext,
                        afterFrames = afterFrames,
                    )
                }
            is AgentAction.SubmitPrimaryAction ->
                verifyClick(
                    before,
                    after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "主动作提交后页面已变化。",
                    expectedTargetText = action.hint,
                    targetContextHints = targetContextHints,
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.DismissInterrupt ->
                verifyClick(
                    before,
                    after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "中断已被关闭或页面已恢复。",
                    expectedTargetText = action.hint,
                    targetContextHints = targetContextHints,
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.OpenBestCandidate ->
                verifyClick(
                    before = before,
                    after = after,
                    afterIndexedObservation = afterIndexedObservation,
                    executionResult = executionResult,
                    successMessage = "已打开更贴近目标的候选对象。",
                    expectedTargetText = action.targetText.ifBlank { expectedTargetText },
                    targetContextHints = targetContextHints,
                    requireTargetPresence = action.targetText.isNotBlank() || expectedTargetText.isNotBlank(),
                    afterScreenshot = afterScreenshot,
                    afterVisualContext = afterVisualContext,
                    afterFrames = afterFrames,
                )
            is AgentAction.ScrollForMore -> verifyScroll(before, after, successMessage = "继续探索后页面内容已变化。")
            is AgentAction.ReturnToTargetApp ->
                verifyLaunchApp(before, AgentAction.LaunchApp(action.packageName), after)
            is AgentAction.NavigateBack -> verifyBack(before, after)
            AgentAction.Wait -> ActionVerificationResult(true, "等待动作完成。", false)
            is AgentAction.Finish -> ActionVerificationResult(true, "任务已结束。", false)
            is AgentAction.Fail -> ActionVerificationResult(true, "任务已终止。", false)
        }
        if (result.verified && executionResult != null) {
            ScreenParserLearningStore.recordVerified(before, after, executionResult, entityDescriptor)
            RegionHitTestLearningStore.recordVerified(after, executionResult)
            ScreenParserModelStore.recordVerified(
                observation = after,
                executionResult = executionResult,
                entityDescriptor = entityDescriptor,
                visualContext = afterFrames.lastOrNull()?.visualContext ?: afterVisualContext,
            )
            ActionRegionHeadStore.recordVerified(after, executionResult)
        }
        return result
    }

    private fun verifyLaunchApp(
        before: ScreenObservation,
        action: AgentAction.LaunchApp,
        after: ScreenObservation,
    ): ActionVerificationResult {
        val success = after.packageName == action.packageName
        return ActionVerificationResult(
            verified = success,
            message =
                if (success) {
                    "已进入目标应用 ${action.packageName}。"
                } else if (after.packageName == before.packageName) {
                    "启动应用后前台未变化，准备重新规划。"
                } else {
                    "启动应用后当前前台仍为 ${after.packageName.ifBlank { "-" }}，准备重新规划。"
                },
            shouldImmediateReplan = !success,
        )
    }

    private fun verifyClick(
        before: ScreenObservation,
        after: ScreenObservation,
        afterIndexedObservation: IndexedScreenObservation? = null,
        executionResult: AgentExecutionResult? = null,
        successMessage: String = "点击后页面已变化。",
        entityDescriptor: GroundingEntityDescriptor = GroundingEntityDescriptor(),
        expectedTargetText: String = "",
        targetContextHints: List<String> = emptyList(),
        requireTargetPresence: Boolean = false,
        afterScreenshot: ScreenshotPayload? = null,
        afterVisualContext: VisualPerceptionContext? = null,
        afterFrames: List<GroundingVerificationFrame> = emptyList(),
    ): ActionVerificationResult {
        val changed = observationMeaningfullyChanged(before, after)
        val normalizedTarget = expectedTargetText.trim()
        val afterSemantics = observationSemantics(after)
        val beforeSemantics = observationSemantics(before)
        val contextScoreBefore = entityFingerprintScore(beforeSemantics, normalizedTarget, targetContextHints)
        val contextScoreAfter = entityFingerprintScore(afterSemantics, normalizedTarget, targetContextHints)
        val beforeEntityMatch = GroundingEntityFingerprint.matchObservation(before, entityDescriptor)
        val afterEntityMatch = GroundingEntityFingerprint.matchObservation(after, entityDescriptor)
        val reverification =
            GroundingReverificationEngine.reverify(
                afterIndexedObservation = afterIndexedObservation,
                executionResult = executionResult,
                descriptor = entityDescriptor,
                expectedTargetText = normalizedTarget,
                targetContextHints = targetContextHints,
                afterScreenshot = afterScreenshot,
                afterVisualContext = afterVisualContext,
                afterFrames = afterFrames,
            )
        val targetVisibleAfter =
            normalizedTarget.isNotBlank() &&
                afterSemantics.contains(normalizedTarget, ignoreCase = true)
        val targetVisibleBefore =
            normalizedTarget.isNotBlank() &&
                beforeSemantics.contains(normalizedTarget, ignoreCase = true)
        val success =
            when {
                reverification.verified -> true
                afterEntityMatch.verified -> true
                targetVisibleAfter -> true
                contextScoreAfter >= 2 && changed -> true
                requireTargetPresence &&
                    beforeEntityMatch.verified &&
                    !afterEntityMatch.verified &&
                    changed &&
                    before.packageName == after.packageName -> false
                requireTargetPresence &&
                    normalizedTarget.isNotBlank() &&
                    targetVisibleBefore &&
                    changed &&
                    before.packageName == after.packageName -> false
                requireTargetPresence &&
                    contextScoreBefore > 0 &&
                    contextScoreAfter == 0 &&
                    changed &&
                    before.packageName == after.packageName -> false
                else -> changed
            }
        return ActionVerificationResult(
            verified = success,
                message =
                    if (success) {
                    if (reverification.verified) {
                        "$successMessage ${reverification.message}"
                    } else if (afterEntityMatch.verified && entityDescriptor.type.isNotBlank()) {
                        "$successMessage 已通过${GroundingEntityFingerprint.displayLabel(entityDescriptor.type)}实体指纹确认当前对象一致。"
                    } else if (afterEntityMatch.stableIdMatches > 0) {
                        "$successMessage 已通过稳定无障碍标识确认当前对象一致。"
                    } else if (afterEntityMatch.visualMatches > 0 && afterEntityMatch.spatialMatches > 0) {
                        "$successMessage 已通过视觉/空间指纹确认当前对象一致。"
                    } else if (targetVisibleAfter) {
                        "$successMessage 已确认目标“$normalizedTarget”出现在当前页面。"
                    } else if (contextScoreAfter >= 2) {
                        "$successMessage 已通过目标上下文线索确认当前实体一致。"
                    } else {
                        successMessage
                    }
                } else {
                    if (requireTargetPresence && normalizedTarget.isNotBlank()) {
                        "点击后虽然页面变化，但未确认目标“$normalizedTarget”。${reverification.message.ifBlank { "准备重新规划。" }}"
                    } else {
                        "点击后页面无明显变化。${reverification.message.ifBlank { "准备重新规划。" }}"
                    }
                },
            shouldImmediateReplan = !success,
        )
    }

    private fun verifySetText(
        action: AgentAction.SetText,
        before: ScreenObservation,
        after: ScreenObservation,
        resolvedElementId: String?,
    ): ActionVerificationResult {
        val targetIds = listOfNotNull(resolvedElementId, action.elementId).toSet()
        val hasText =
            after.elements.any { it.id in targetIds && it.text.contains(action.text) } ||
                after.topTexts.any { it.contains(action.text) }
        val success = hasText || observationMeaningfullyChanged(before, after)
        return ActionVerificationResult(
            verified = success,
            message =
                if (success) {
                    if (!resolvedElementId.isNullOrBlank() && resolvedElementId != action.elementId) {
                        "输入内容已出现在实际输入节点 $resolvedElementId 中。"
                    } else {
                        "输入内容已出现在页面中。"
                    }
                } else {
                    "输入后未检测到目标文本，准备重新规划。"
                },
            shouldImmediateReplan = !success,
        )
    }

    private fun verifyScroll(
        before: ScreenObservation,
        after: ScreenObservation,
        successMessage: String = "滚动后页面内容已变化。",
    ): ActionVerificationResult {
        val success = observationMeaningfullyChanged(before, after)
        return ActionVerificationResult(
            verified = success,
            message =
                if (success) {
                    successMessage
                } else {
                    "滚动后页面无变化，准备重新规划。"
                },
            shouldImmediateReplan = !success,
        )
    }

    private fun verifyBack(
        before: ScreenObservation,
        after: ScreenObservation,
    ): ActionVerificationResult {
        val success = observationMeaningfullyChanged(before, after)
        return ActionVerificationResult(
            verified = success,
            message =
                if (success) {
                    "返回后页面已变化。"
                } else {
                    "返回后页面无变化，准备重新规划。"
                },
            shouldImmediateReplan = !success,
        )
    }

    private fun verifyFocusPrimaryInput(
        before: ScreenObservation,
        after: ScreenObservation,
    ): ActionVerificationResult {
        val success =
            !after.primaryEditableId.isNullOrBlank() &&
                (
                    after.focusedElementId == after.primaryEditableId ||
                        after.primaryEditableId != before.primaryEditableId ||
                        after.focusedElementId != before.focusedElementId
                )
        return ActionVerificationResult(
            verified = success,
            message = if (success) "主输入框已重新获得焦点。" else "主输入焦点没有明显恢复，准备重新规划。",
            shouldImmediateReplan = !success,
        )
    }

    private fun verifyGlobalAction(
        after: ScreenObservation,
        successMessage: String,
    ): ActionVerificationResult =
        ActionVerificationResult(
            verified = after.signature.isNotBlank(),
            message = successMessage,
            shouldImmediateReplan = false,
        )

    private fun observationMeaningfullyChanged(
        before: ScreenObservation,
        after: ScreenObservation,
    ): Boolean {
        if (before.signature != after.signature) return true
        if (before.packageName != after.packageName) return true
        if (before.pageState != after.pageState) return true
        if (before.focusedElementId != after.focusedElementId) return true
        if (before.primaryEditableId != after.primaryEditableId) return true
        if (before.topTexts != after.topTexts) return true

        val beforeElementSemantics =
            before.elements.map {
                "${it.text}|${it.accessibilityLabel}|${it.accessibilityUniqueId}|${it.bounds}|${it.className}|${it.collectionPosition}|${it.visualSignature}|${it.spatialSignature}"
            }
        val afterElementSemantics =
            after.elements.map {
                "${it.text}|${it.accessibilityLabel}|${it.accessibilityUniqueId}|${it.bounds}|${it.className}|${it.collectionPosition}|${it.visualSignature}|${it.spatialSignature}"
            }
        if (beforeElementSemantics != afterElementSemantics) return true

        return false
    }

    private fun observationSemantics(
        observation: ScreenObservation,
    ): String =
        (
            observation.topTexts +
                observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) } +
                observation.elements.mapNotNull { it.accessibilityLabel.takeIf(String::isNotBlank) } +
                observation.elements.mapNotNull { it.accessibilityUniqueId.takeIf(String::isNotBlank) } +
                observation.elements.mapNotNull { it.visualSignature.takeIf(String::isNotBlank) } +
                observation.elements.mapNotNull { it.spatialSignature.takeIf(String::isNotBlank) } +
                observation.elements.flatMap { it.visualDescriptorTokens } +
                observation.elements.flatMap { it.neighborTexts } +
                observation.elements.flatMap { it.descriptorTokens } +
                observation.structureHints
        ).joinToString(" ")

    private fun entityFingerprintScore(
        semantics: String,
        targetText: String,
        contextHints: List<String>,
    ): Int {
        val tokens =
            (contextHints + targetText.takeIf { it.isNotBlank() })
                .filterNotNull()
                .map(String::trim)
                .filter { it.length >= 2 }
                .distinct()
        return tokens.count { token -> semantics.contains(token, ignoreCase = true) }
    }
}
