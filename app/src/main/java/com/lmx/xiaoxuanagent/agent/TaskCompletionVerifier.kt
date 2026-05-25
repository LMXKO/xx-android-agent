package com.lmx.xiaoxuanagent.agent

object TaskCompletionVerifier {
    fun verifyProgress(
        task: String,
        taskPlanState: TaskPlanState?,
        action: AgentAction,
        before: ScreenObservation,
        after: ScreenObservation?,
        executionResult: AgentExecutionResult? = null,
    ): TaskStageVerificationResult {
        if (after == null) {
            return TaskStageVerificationResult(
                verified = false,
                shouldImmediateReplan = false,
                message = "",
            )
        }

        val constraints = TaskIntentParser.parse(task)
        val pageSignals = PageSignals.from(after)
        return when (action) {
            is AgentAction.LaunchApp -> {
                val switchedApp = after.packageName.isNotBlank() && after.packageName != before.packageName
                if (switchedApp) {
                    TaskStageVerificationResult(
                        verified = true,
                        shouldImmediateReplan = false,
                        message = "已进入目标应用 ${after.packageName}。",
                    )
                } else {
                    TaskStageVerificationResult(false, false, "")
                }
            }

            is AgentAction.SetText -> {
                val expected =
                    listOfNotNull(
                        constraints.entryQuery.takeIf { it.isNotBlank() },
                        constraints.recipientName?.takeIf { it.isNotBlank() },
                        constraints.destination?.takeIf { it.isNotBlank() },
                    )
                if (expected.any { pageSignals.semantics.contains(it, ignoreCase = true) }) {
                    TaskStageVerificationResult(
                        verified = true,
                        shouldImmediateReplan = false,
                        message = "输入目标已反映到当前页面。",
                    )
                } else {
                    TaskStageVerificationResult(false, false, "")
                }
            }

            is AgentAction.PopulatePrimaryInput -> {
                if (action.text.isNotBlank() && pageSignals.semantics.contains(action.text, ignoreCase = true)) {
                    TaskStageVerificationResult(
                        verified = true,
                        shouldImmediateReplan = false,
                        message = "主输入内容已重新对齐到当前任务目标。",
                    )
                } else {
                    TaskStageVerificationResult(false, false, "")
                }
            }

            is AgentAction.Click,
            is AgentAction.LongClick,
            is AgentAction.TapPoint,
            AgentAction.PressEnter,
            AgentAction.FocusPrimaryInput,
            is AgentAction.SubmitPrimaryAction,
            is AgentAction.DismissInterrupt,
            is AgentAction.OpenBestCandidate,
            is AgentAction.NavigateBack,
            -> verifyIntentProgress(constraints, taskPlanState, action, before, after, pageSignals, executionResult)

            is AgentAction.Scroll,
            is AgentAction.Swipe,
            is AgentAction.ScrollForMore,
            AgentAction.Back,
            -> {
                val changed = after.signature != before.signature || after.pageState != before.pageState
                if (changed) {
                    TaskStageVerificationResult(
                        verified = true,
                        shouldImmediateReplan = false,
                        message = "页面上下文已发生变化。",
                    )
                } else {
                    TaskStageVerificationResult(false, false, "")
                }
            }

            AgentAction.Home,
            AgentAction.Notifications,
            AgentAction.QuickSettings,
            AgentAction.Recents,
            is AgentAction.OpenSettings,
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
            is AgentAction.CreateTimer,
            AgentAction.OpenStopwatch,
            is AgentAction.DialNumber,
            is AgentAction.DraftSms,
            is AgentAction.LookupContact,
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
            is AgentAction.ShareText,
            is AgentAction.CreateAlarm,
            is AgentAction.InsertCalendarEvent,
            is AgentAction.ReturnToTargetApp,
            AgentAction.Wait,
            is AgentAction.Finish,
            is AgentAction.Fail,
            is AgentAction.CopyText,
            is AgentAction.PasteClipboard,
            -> TaskStageVerificationResult(false, false, "")
        }
    }

    fun verifyFinish(
        task: String,
        taskPlanState: TaskPlanState?,
        observation: ScreenObservation,
    ): TaskStageVerificationResult {
        val constraints = TaskIntentParser.parse(task)
        val pageSignals = PageSignals.from(observation)
        val verified =
            when (constraints.intentType) {
                TaskIntentType.SHOPPING -> canFinishShopping(taskPlanState, pageSignals)
                TaskIntentType.NAVIGATION -> canFinishNavigation(taskPlanState, pageSignals)
                TaskIntentType.MESSAGING -> canFinishMessaging(constraints, taskPlanState, pageSignals)
                TaskIntentType.CONTENT -> canFinishContent(taskPlanState, pageSignals)
                TaskIntentType.GENERIC -> canFinishGeneric(taskPlanState, pageSignals)
            }
        return if (verified) {
            TaskStageVerificationResult(
                verified = true,
                shouldImmediateReplan = false,
                message = "当前页面已经满足任务收口条件，可以结束。",
            )
        } else {
            TaskStageVerificationResult(
                verified = false,
                shouldImmediateReplan = true,
                message = "当前页面还没有满足任务完成条件，不应提前结束。",
            )
        }
    }

    private fun verifyIntentProgress(
        constraints: TaskConstraints,
        taskPlanState: TaskPlanState?,
        action: AgentAction,
        before: ScreenObservation,
        after: ScreenObservation,
        pageSignals: PageSignals,
        executionResult: AgentExecutionResult?,
    ): TaskStageVerificationResult {
        val entityDescriptor = GroundingEntityFingerprint.fromTaskConstraints(constraints, executionResult)
        val expectedTargetText = entityDescriptor.primary
        val targetContextHints = entityDescriptor.contextTokens
        val contextMatches =
            targetContextHints.count { hint ->
                hint.length >= 2 && pageSignals.semantics.contains(hint, ignoreCase = true)
            }
        val entityMatch = GroundingEntityFingerprint.matchObservation(after, entityDescriptor)
        if (
            entityMatch.verified ||
            (expectedTargetText.isNotBlank() && pageSignals.semantics.contains(expectedTargetText, ignoreCase = true)) ||
            contextMatches >= 2
        ) {
            return TaskStageVerificationResult(
                verified = true,
                shouldImmediateReplan = false,
                message =
                    if (entityMatch.verified && entityDescriptor.type.isNotBlank()) {
                        "当前页面已经通过${GroundingEntityFingerprint.displayLabel(entityDescriptor.type)}实体指纹确认目标对象一致。"
                    } else if (expectedTargetText.isNotBlank() && pageSignals.semantics.contains(expectedTargetText, ignoreCase = true)) {
                        "目标“$expectedTargetText”已出现在当前页面语义中。"
                    } else {
                        "当前页面已经命中目标实体的上下文线索。"
                    },
            )
        }
        verifyEntryAlignmentProgress(
            constraints = constraints,
            taskPlanState = taskPlanState,
            action = action,
            before = before,
            after = after,
            pageSignals = pageSignals,
        )?.let { return it }

        val verified =
            when (constraints.intentType) {
                TaskIntentType.SHOPPING ->
                    pageSignals.isResultLike || pageSignals.isDetailLike || after.signature != before.signature
                TaskIntentType.NAVIGATION ->
                    pageSignals.hasRouteCue || pageSignals.semantics.contains(constraints.entryQuery, ignoreCase = true)
                TaskIntentType.MESSAGING -> {
                    val recipient = constraints.recipientName.orEmpty()
                    recipient.isNotBlank() &&
                        (pageSignals.semantics.contains(recipient, ignoreCase = true) || pageSignals.hasSearchCue)
                }
                TaskIntentType.CONTENT ->
                    pageSignals.hasContentCue || after.signature != before.signature
                TaskIntentType.GENERIC ->
                    taskPlanState?.currentStage == "continue_execution" && after.signature != before.signature
            }
        return if (verified) {
            TaskStageVerificationResult(
                verified = true,
                shouldImmediateReplan = false,
                message = "任务链路已推进到更相关的页面阶段。",
            )
        } else {
            TaskStageVerificationResult(false, false, "")
        }
    }

    private fun verifyEntryAlignmentProgress(
        constraints: TaskConstraints,
        taskPlanState: TaskPlanState?,
        action: AgentAction,
        before: ScreenObservation,
        after: ScreenObservation,
        pageSignals: PageSignals,
    ): TaskStageVerificationResult? {
        val stage = taskPlanState?.currentStage.orEmpty()
        if (stage !in setOf("enter_query", "find_conversation", "enter_destination")) {
            return null
        }

        val clickedElementId =
            when (action) {
                is AgentAction.Click -> action.elementId
                is AgentAction.LongClick -> action.elementId
                AgentAction.FocusPrimaryInput -> before.primaryEditableId
                is AgentAction.PopulatePrimaryInput -> before.primaryEditableId
                else -> null
            }
        val clickedElement =
            clickedElementId?.let { elementId ->
                before.elements.firstOrNull { it.id == elementId }
            }
        val clickedSemantic =
            clickedElement?.let { element ->
                listOf(element.text, element.viewId, element.className)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }.orEmpty()
        val expectedTarget =
            when (stage) {
                "find_conversation" -> constraints.recipientName.orEmpty().ifBlank { constraints.entryQuery }
                "enter_destination" -> constraints.destination.orEmpty().ifBlank { constraints.entryQuery }
                else -> constraints.entryQuery
            }
        val targetVisible =
            expectedTarget.isNotBlank() &&
                pageSignals.semantics.contains(expectedTarget, ignoreCase = true)
        val regainedEditable =
            after.primaryEditableId != null &&
                (
                    after.primaryEditableId != before.primaryEditableId ||
                        after.focusedElementId == after.primaryEditableId ||
                        clickedElementId == after.primaryEditableId
                )
        val stageCueMatched =
            entryCueKeywords(stage).any { keyword ->
                clickedSemantic.contains(keyword, ignoreCase = true)
            }

        return if (regainedEditable || (targetVisible && (stageCueMatched || pageSignals.hasSearchCue))) {
            TaskStageVerificationResult(
                verified = true,
                shouldImmediateReplan = false,
                message =
                    "已重新对齐到 ${entryStageLabel(stage)}，" +
                        "恢复后可以继续当前子目标。",
            )
        } else {
            null
        }
    }

    private fun canFinishShopping(
        taskPlanState: TaskPlanState?,
        pageSignals: PageSignals,
    ): Boolean =
        pageSignals.isDetailLike ||
            taskPlanState?.currentStage == "summarize" ||
            (
                !pageSignals.isResultLike &&
                    listOf("评价", "评论", "参数", "规格", "详情").any {
                        pageSignals.semantics.contains(it, ignoreCase = true)
                    }
            )

    private fun canFinishNavigation(
        taskPlanState: TaskPlanState?,
        pageSignals: PageSignals,
    ): Boolean =
        pageSignals.hasRouteCue ||
            taskPlanState?.currentStage == "confirm_route" ||
            listOf("预计", "分钟", "公里", "路线", "到这去", "开始导航").any {
                pageSignals.semantics.contains(it, ignoreCase = true)
            }

    private fun canFinishMessaging(
        constraints: TaskConstraints,
        taskPlanState: TaskPlanState?,
        pageSignals: PageSignals,
    ): Boolean {
        val recipient = constraints.recipientName.orEmpty()
        val message = constraints.messageBody.orEmpty()
        val recipientVerified = recipient.isBlank() || pageSignals.semantics.contains(recipient, ignoreCase = true)
        if (!recipientVerified) return false
        if (message.isBlank()) {
            return pageSignals.hasComposeCue || taskPlanState?.currentStage == "confirm_send"
        }
        return pageSignals.hasComposeCue ||
            pageSignals.semantics.contains(message, ignoreCase = true) ||
            taskPlanState?.currentStage == "confirm_send"
    }

    private fun canFinishContent(
        taskPlanState: TaskPlanState?,
        pageSignals: PageSignals,
    ): Boolean =
        pageSignals.hasContentCue ||
            taskPlanState?.currentStage == "summarize" ||
            listOf("热评", "高赞", "评论区", "弹幕", "最新一期", "最近一期").any {
                pageSignals.semantics.contains(it, ignoreCase = true)
            }

    private fun canFinishGeneric(
        taskPlanState: TaskPlanState?,
        pageSignals: PageSignals,
    ): Boolean =
        taskPlanState?.currentStage in setOf("summarize", "finish") ||
            (
                pageSignals.packageReady &&
                    pageSignals.pageKnown &&
                    !pageSignals.isResultLike &&
                    !pageSignals.hasSearchCue
            )

    private data class PageSignals(
        val semantics: String,
        val packageReady: Boolean,
        val pageKnown: Boolean,
        val isResultLike: Boolean,
        val isDetailLike: Boolean,
        val hasSearchCue: Boolean,
        val hasComposeCue: Boolean,
        val hasRouteCue: Boolean,
        val hasContentCue: Boolean,
    ) {
        companion object {
            fun from(observation: ScreenObservation): PageSignals {
                val semantics =
                    buildList {
                        add(observation.pageState)
                        add(observation.screenSummary)
                        addAll(observation.topTexts)
                        addAll(observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
                    }.joinToString(" ")
                return PageSignals(
                    semantics = semantics,
                    packageReady = observation.packageName.isNotBlank(),
                    pageKnown = !observation.pageState.equals("UNKNOWN", ignoreCase = true),
                    isResultLike = listOf("RESULT", "LIST", "FEED", "GRID").any { observation.pageState.uppercase().contains(it) },
                    isDetailLike = listOf("DETAIL", "REVIEW", "PARAM").any { observation.pageState.uppercase().contains(it) } ||
                        listOf("评价", "评论", "参数", "规格", "详情").any { semantics.contains(it, ignoreCase = true) },
                    hasSearchCue = listOf("搜索", "查找", "放大镜", "联系人", "输入关键字").any { semantics.contains(it, ignoreCase = true) },
                    hasComposeCue = listOf("发送", "输入消息", "发消息", "按住说话", "回车发送").any { semantics.contains(it, ignoreCase = true) },
                    hasRouteCue = listOf("路线", "导航", "到这去", "开始导航", "预计", "公里", "分钟").any { semantics.contains(it, ignoreCase = true) },
                    hasContentCue = listOf("评论", "评论区", "热评", "高赞", "弹幕", "最新一期", "最近一期", "播放").any {
                        semantics.contains(it, ignoreCase = true)
                    },
                )
            }
        }
    }

    private fun entryCueKeywords(
        stage: String,
    ): List<String> =
        when (stage) {
            "find_conversation" -> listOf("搜索", "查找", "联系人", "通讯录", "会话", "聊天")
            "enter_destination" -> listOf("搜索地点", "输入目的地", "目的地", "到哪去", "前往")
            "enter_query" -> listOf("搜索", "查找", "输入", "搜索框", "关键词")
            else -> emptyList()
        }

    private fun entryStageLabel(
        stage: String,
    ): String =
        when (stage) {
            "find_conversation" -> "会话/联系人入口"
            "enter_destination" -> "目的地入口"
            "enter_query" -> "搜索入口"
            else -> "继续入口"
        }
}
