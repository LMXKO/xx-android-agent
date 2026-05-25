package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.ConnectedAppLifecycleStore
import com.lmx.xiaoxuanagent.runtime.UtilityLifecycleStore
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore
import com.lmx.xiaoxuanagent.taskprofile.ScreenAutomationProductSupportStore

data class PlanningToolPolicySnapshot(
    val preferredTools: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList(),
    val avoidTools: List<String> = emptyList(),
    val summary: String = "",
    val toolScores: Map<String, Int> = emptyMap(),
    val fallbackChains: Map<String, List<String>> = emptyMap(),
    val selectionHints: List<String> = emptyList(),
)

data class ToolPolicyRewrite(
    val decision: AgentDecision,
    val reason: String = "",
    val changed: Boolean = false,
)

object AgentToolSelectionPolicy {
    fun analyze(
        task: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        targetPackageName: String,
    ): PlanningToolPolicySnapshot {
        val preferred = linkedSetOf<String>()
        val avoid = linkedSetOf<String>()
        val allowed = linkedSetOf<String>()
        val scores = linkedMapOf<String, Int>()
        val currentStage = taskPlanState.currentStage
        val inTargetApp = observation.packageName.equals(targetPackageName, ignoreCase = true)
        val catalog = AgentToolCatalog.descriptors()
        val automationPolicy =
            AutomationSupportPolicyStore.resolve(
                profileId = SessionRuntimeProfileIdSupport.resolve(taskPlanState, targetPackageName),
                packageName = targetPackageName,
                taskIntent = TaskIntentParser.parse(SessionRuntime.State.runtimeState().session.task).intentType.name.lowercase(),
                planStage = currentStage,
                pageState = observation.pageState,
            )
        val connectedSuggestion =
            ConnectedAppRouter.suggest(
                task = task,
                profileId = SessionRuntimeProfileIdSupport.resolve(taskPlanState, targetPackageName),
                targetPackageName = targetPackageName,
            )
        val productSupport =
            ScreenAutomationProductSupportStore.snapshot(
                profileId = SessionRuntimeProfileIdSupport.resolve(taskPlanState, targetPackageName),
                packageName = targetPackageName,
            )

        fun prefer(vararg toolNames: String) {
            toolNames.forEach {
                if (it.isNotBlank()) {
                    preferred.add(it)
                    scores[it] = (scores[it] ?: 0) + 40
                }
            }
        }

        fun allow(vararg toolNames: String) {
            toolNames.forEach {
                if (it.isNotBlank()) {
                    allowed.add(it)
                    scores.putIfAbsent(it, 5)
                }
            }
        }

        fun block(vararg toolNames: String) {
            toolNames.forEach {
                if (it.isNotBlank()) {
                    avoid.add(it)
                    scores[it] = (scores[it] ?: 0) - 60
                }
            }
        }

        fun applyUtilityLifecycle(vararg toolNames: String) {
            toolNames.forEach { toolName ->
                if (!UtilityLifecycleStore.snapshot(toolName).enabled) {
                    block(toolName)
                }
            }
        }

        allow(*ActionExecutor.registeredTools().toTypedArray())

        when (currentStage) {
            "enter_query", "enter_destination", "find_conversation" ->
                prefer("semantic.populate_primary_input", "semantic.focus_primary_input", "semantic.open_best_candidate")

            "submit_query", "confirm_send", "confirm_route" ->
                prefer("semantic.submit_primary_action", "gui.press_enter")

            "browse_candidates", "inspect_information" ->
                prefer("semantic.open_best_candidate", "semantic.scroll_for_more")
        }

        if (observation.interruptiveHints.isNotEmpty() || !observation.primaryInterruptActionId.isNullOrBlank()) {
            prefer("semantic.dismiss_interrupt")
        }
        if (!inTargetApp && targetPackageName.isNotBlank()) {
            prefer("system.launch_app", "semantic.return_to_target_app")
        }
        if (automationPolicy.preferredExecutionPath == "connected_app_first" || connectedSuggestion != null) {
            val connectedAppId = connectedSuggestion?.appId.orEmpty().ifBlank { automationPolicy.connectedAppId }
            val connectedEnabled = connectedAppId.isBlank() || ConnectedAppLifecycleStore.snapshot(connectedAppId).connected
            if (connectedEnabled) {
                prefer("connected.execute_app_action", "connected.read_app_capabilities")
                allow("connected.execute_app_action", "connected.read_app_capabilities")
                block("gui.tap_point", "gui.swipe")
            } else {
                block("connected.execute_app_action")
            }
        }
        if (!productSupport.focusApp) {
            block("semantic.submit_primary_action", "gui.press_enter")
        }
        if (automationPolicy.supportsFill) {
            allow("gui.set_text", "semantic.populate_primary_input", "online.clipboard_paste")
        }
        if (!automationPolicy.supportsSubmit && currentStage in setOf("confirm_send", "confirm_route")) {
            block("semantic.submit_primary_action", "gui.press_enter")
        }
        if (observation.elements.size >= 10) {
            block("gui.tap_point", "gui.swipe")
        }
        if (memoryContext.sessionMemories.any { it.contains("tool_preference:", ignoreCase = true) }) {
            memoryContext.sessionMemories.forEach { line ->
                val toolName = line.substringAfter(':', "").trim()
                if (toolName.isNotBlank()) {
                    preferred.add(toolName)
                }
            }
        }
        if (task.contains("复制", ignoreCase = true) || task.contains("粘贴", ignoreCase = true)) {
            prefer("online.clipboard_copy", "online.clipboard_paste")
        }
        if (task.contains("闹钟") || task.contains("提醒我") || task.contains("叫醒")) {
            prefer("system.create_alarm")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("计时") || task.contains("倒计时") || task.contains("秒表")) {
            prefer("system.create_timer", "system.open_stopwatch")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("日历") || task.contains("日程") || task.contains("会议")) {
            prefer("system.insert_calendar_event")
        }
        if (task.contains("打电话") || task.contains("拨号") || task.contains("电话")) {
            prefer("system.lookup_contact", "system.dial_number")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("短信") || task.contains("发短信")) {
            prefer("system.lookup_contact", "system.draft_sms")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("联系人") || task.contains("号码") || task.contains("手机号")) {
            prefer("system.lookup_contact")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("分享") || task.contains("转给") || task.contains("发给自己")) {
            prefer("system.share_text")
        }
        if (task.contains("设置") || task.contains("通知权限") || task.contains("无障碍")) {
            prefer("connected.execute_app_action", "system.open_settings", "connected.read_app_capabilities")
        }
        if (task.contains("通知") || task.contains("未读") || task.contains("提醒")) {
            prefer("system.read_notifications", "system.reply_notification")
        }
        if (task.contains("音乐") || task.contains("暂停播放") || task.contains("下一首") || task.contains("媒体")) {
            prefer("system.media_control", "system.adjust_volume")
        }
        if (task.contains("音量") || task.contains("静音")) {
            prefer("system.adjust_volume")
        }
        if (task.contains("蓝牙") || task.contains("wifi", ignoreCase = true) || task.contains("网络") || task.contains("手电")) {
            prefer("system.read_device_status", "system.open_device_panel")
        }
        if (task.contains("当前位置") || task.contains("我在哪") || task.contains("在哪儿") || task.contains("附近") || task.contains("定位") || task.contains("location", ignoreCase = true)) {
            prefer("system.read_current_location", "connected.execute_app_action")
        }
        if (task.contains("亮度") || task.contains("屏幕亮") || task.contains("调亮") || task.contains("调暗")) {
            prefer("system.set_brightness", "system.read_device_status")
        }
        if (task.contains("免打扰") || task.contains("勿扰") || task.contains("dnd", ignoreCase = true)) {
            prefer("system.set_do_not_disturb", "system.read_device_status")
        }
        if (task.contains("电量") || task.contains("省电") || task.contains("battery", ignoreCase = true)) {
            prefer("system.set_battery_saver", "system.read_device_status", "system.open_device_panel")
        }
        if (task.contains("重启") || task.contains("关机") || task.contains("电源")) {
            prefer("system.open_power_dialog")
        }
        if (task.contains("拍照") || task.contains("相机")) {
            prefer("system.capture_photo", "system.capture_screenshot")
        }
        if (task.contains("截图") || task.contains("留证") || task.contains("存档")) {
            prefer("system.capture_screenshot")
        }
        if (task.contains("历史") || task.contains("上次") || task.contains("之前") || task.contains("回顾")) {
            prefer("platform.read_session_history", "platform.read_session_notebook", "platform.search_artifacts")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("记忆") || task.contains("偏好") || task.contains("联系人") || task.contains("地点")) {
            prefer("platform.recall_memory", "platform.read_session_notebook")
        }
        if (task.contains("证据") || task.contains("产物") || task.contains("截图") || task.contains("回放")) {
            prefer("platform.search_artifacts", "platform.read_session_history")
        }
        if (task.contains("笔记") || task.contains("记录") || task.contains("记一下") || task.contains("记住")) {
            prefer("platform.write_session_note", "platform.read_session_notebook")
        }
        if (task.contains("上网") || task.contains("网页") || task.contains("联网") || task.contains("官网") || task.contains("搜索一下")) {
            prefer("platform.web_search", "platform.web_fetch")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("工具") || task.contains("能力") || task.contains("怎么做")) {
            prefer("platform.search_tools", "platform.read_todo_board")
        }
        if (task.contains("待办") || task.contains("todo") || task.contains("清单") || task.contains("checklist")) {
            prefer("platform.write_todo_board", "platform.read_todo_board")
        }
        if (task.contains("子任务") || task.contains("并行") || task.contains("另开") || task.contains("委派") || task.contains("交给")) {
            prefer("platform.delegate_local_agent", "platform.write_todo_board")
        }
        if (task.contains("worker", ignoreCase = true) || task.contains("mailbox", ignoreCase = true) || task.contains("审批") || task.contains("回信") || task.contains("回复结果")) {
            prefer("platform.read_worker_mailbox", "platform.reply_worker_message", "platform.ack_worker_message")
            block("gui.tap_point", "gui.swipe")
        }
        if (task.contains("状态") || task.contains("join", ignoreCase = true) || task.contains("子 worker") || task.contains("合并结果")) {
            prefer("platform.read_worker_status", "platform.merge_worker_result", "platform.read_worker_mailbox")
        }
        if (task.contains("explore", ignoreCase = true) || task.contains("plan", ignoreCase = true) || task.contains("verification", ignoreCase = true) || task.contains("角色")) {
            prefer("platform.read_worker_roles", "platform.delegate_local_agent")
        }
        if (task.contains("current state", ignoreCase = true) || task.contains("memory file", ignoreCase = true) || task.contains("当前状态") || task.contains("连续性")) {
            prefer("platform.read_session_memory_file", "platform.read_session_notebook")
        }
        if (currentStage == "summarize" && memoryContext.resultArtifacts.isNotEmpty()) {
            prefer("platform.search_artifacts", "platform.read_session_notebook")
        }

        catalog.forEach { descriptor ->
            val baseScore =
                when (descriptor.type) {
                    AgentActionToolType.SEMANTIC -> 18
                    AgentActionToolType.SYSTEM -> 12
                    AgentActionToolType.GUI -> 8
                    AgentActionToolType.META -> 4
                    AgentActionToolType.ONLINE -> 10
                }
            scores[descriptor.name] = maxOf(scores[descriptor.name] ?: 0, baseScore)
            if (descriptor.alwaysLoad) {
                scores[descriptor.name] = (scores[descriptor.name] ?: 0) + 6
            }
            if (descriptor.shouldDefer && currentStage !in setOf("browse_candidates", "inspect_information")) {
                scores[descriptor.name] = (scores[descriptor.name] ?: 0) - 8
            }
            if (descriptor.requiresUserInteraction && taskPlanState.waitingForExternal) {
                scores[descriptor.name] = (scores[descriptor.name] ?: 0) - 12
            }
            if (descriptor.fallbackTools.isNotEmpty()) {
                scores[descriptor.name] = (scores[descriptor.name] ?: 0) + 2
            }
        }

        applyUtilityLifecycle(
            "system.read_notifications",
            "system.reply_notification",
            "system.media_control",
            "system.adjust_volume",
            "system.open_device_panel",
            "system.read_device_status",
            "system.read_current_location",
            "system.set_brightness",
            "system.set_do_not_disturb",
            "system.set_battery_saver",
            "system.open_power_dialog",
            "system.capture_screenshot",
            "system.capture_photo",
            "system.create_timer",
            "system.open_stopwatch",
            "system.dial_number",
            "system.draft_sms",
        )

        val rankedTools =
            scores.entries
                .sortedByDescending { it.value }
                .map { it.key }
        val fallbackChains =
            catalog
                .filter { it.fallbackTools.isNotEmpty() }
                .associate { descriptor ->
                    val prioritizedFallbacks =
                        descriptor.fallbackTools
                            .sortedByDescending { toolName -> scores[toolName] ?: 0 }
                    descriptor.name to prioritizedFallbacks
                }.toMutableMap()
        connectedSuggestion?.let { suggestion ->
            val utilityFallbacks =
                ConnectedAppFallbackSupport
                    .fallbackChainFor(
                        suggestion = suggestion,
                        receipt = ConnectedAppExecutionReceiptStore.latest(suggestion.appId),
                    ).sortedByDescending { toolName -> scores[toolName] ?: 0 }
            fallbackChains["connected.execute_app_action"] = utilityFallbacks
        }
        val selectionHints =
            buildList {
                add("stage=${currentStage.ifBlank { "observe" }}")
                add(if (inTargetApp) "scope=in_target_app" else "scope=return_target_app")
                rankedTools.take(3).forEachIndexed { index, toolName ->
                    add("top_${index + 1}=$toolName:${scores[toolName] ?: 0}")
                }
                if (avoid.isNotEmpty()) {
                    add("avoid_dense_layout=${avoid.joinToString(limit = 2, truncated = "...")}")
                }
                add("automation=${automationPolicy.supervisionMode}")
                if (automationPolicy.requiresFinalHandoff) {
                    add("final_handoff=true")
                }
                connectedSuggestion?.let { suggestion ->
                    add("connected=${suggestion.appId}:${suggestion.operation}")
                    ConnectedAppFallbackSupport.receiptHint(
                        ConnectedAppExecutionReceiptStore.latest(suggestion.appId),
                    ).takeIf { it.isNotBlank() }?.let(::add)
                }
                if (automationPolicy.releaseGateStatus.isNotBlank() && automationPolicy.releaseGateStatus != "ungated") {
                    add("release_gate=${automationPolicy.releaseGateStatus}")
                }
                add("product_tier=${productSupport.tier}")
            }

        return PlanningToolPolicySnapshot(
            preferredTools = rankedTools.filter { it in preferred }.take(6),
            allowedTools = allowed.toList(),
            avoidTools = avoid.toList(),
            summary =
                buildString {
                    append("stage=").append(currentStage.ifBlank { "observe" })
                    append(" | preferred=").append(rankedTools.filter { it in preferred }.joinToString(limit = 4, truncated = "..."))
                    if (avoid.isNotEmpty()) {
                        append(" | avoid=").append(avoid.joinToString(limit = 3, truncated = "..."))
                    }
                    rankedTools.firstOrNull()?.let { topTool ->
                        append(" | top=").append(topTool).append(':').append(scores[topTool] ?: 0)
                    }
                },
            toolScores = scores.toList().sortedByDescending { it.second }.take(10).toMap(),
            fallbackChains = fallbackChains.filterKeys { key -> key in rankedTools.take(8).toSet() || key in preferred },
            selectionHints = selectionHints,
        )
    }

    fun normalizeDecision(
        decision: AgentDecision,
        planningContext: AgentPlanningContext,
    ): ToolPolicyRewrite {
        val observation = planningContext.observation
        val currentStage = planningContext.taskPlanState.currentStage
        val rewrittenAction =
            when (val action = decision.action) {
                is AgentAction.SetText ->
                    if (action.elementId == observation.primaryEditableId && action.text.isNotBlank()) {
                        AgentAction.PopulatePrimaryInput(action.text)
                    } else {
                        action
                    }

                is AgentAction.Click -> {
                    val target = observation.elements.firstOrNull { it.id == action.elementId }
                    when {
                        action.elementId == observation.primaryInterruptActionId ->
                            AgentAction.DismissInterrupt("close_interrupt")

                        target?.editable == true && action.elementId == observation.primaryEditableId ->
                            AgentAction.FocusPrimaryInput

                        currentStage in setOf("submit_query", "confirm_send", "confirm_route") &&
                            target != null &&
                            looksSubmitAction(target.text) ->
                            AgentAction.SubmitPrimaryAction(submitHintForStage(currentStage, target.text))

                        else -> action
                    }
                }

                is AgentAction.Scroll ->
                    if (action.elementId == observation.defaultScrollableId &&
                        currentStage in setOf("browse_candidates", "inspect_information")
                    ) {
                        AgentAction.ScrollForMore(action.direction)
                    } else {
                        action
                    }

                AgentAction.PressEnter ->
                    if (currentStage in setOf("submit_query", "confirm_send", "confirm_route")) {
                        AgentAction.SubmitPrimaryAction(submitHintForStage(currentStage))
                    } else {
                        action
                    }

                else -> action
            }

        if (rewrittenAction == decision.action) {
            return ToolPolicyRewrite(decision = decision)
        }
        return ToolPolicyRewrite(
            decision =
                decision.copy(
                    action = rewrittenAction,
                    reason = "${decision.reason} 本地 tool policy 已将动作规范化为 ${rewrittenAction.toolName}。",
                ),
            reason = "${decision.action.toolName} -> ${rewrittenAction.toolName}",
            changed = true,
        )
    }

    private fun looksSubmitAction(
        text: String,
    ): Boolean {
        if (text.isBlank()) return false
        return listOf("发送", "确认", "完成", "搜索", "查找", "导航", "确定").any {
            text.contains(it, ignoreCase = true)
        }
    }

    private fun submitHintForStage(
        stage: String,
        fallback: String = "",
    ): String =
        when (stage) {
            "confirm_send" -> fallback.ifBlank { "发送" }
            "confirm_route" -> fallback.ifBlank { "导航" }
            else -> fallback.ifBlank { "搜索" }
        }
}
