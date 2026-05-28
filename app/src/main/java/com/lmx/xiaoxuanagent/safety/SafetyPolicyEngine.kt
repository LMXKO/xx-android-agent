package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import com.lmx.xiaoxuanagent.assistantos.AssistantNotificationRuntimeStore
import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.assistantos.AssistantPermissionMode
import com.lmx.xiaoxuanagent.assistantos.AssistantSafetyMode
import com.lmx.xiaoxuanagent.runtime.SessionRuntime

object SafetyPolicyEngine {
    private val neutralOverlayPackages =
        setOf(
            "android",
            "com.android.systemui",
        )

    private val blockKeywords =
        listOf(
            "支付",
            "付款",
            "付钱",
            "下单",
            "提交订单",
            "确认下单",
            "购买",
            "立即购买",
            "立即支付",
            "确认付款",
            "去支付",
            "转账",
            "转钱",
            "汇款",
            "充值",
            "提现吗",
            "提现",
            "结算",
            "加入购物车",
        )

    private val confirmKeywords =
        listOf(
            "发送",
            "发给",
            "发布",
            "发表",
            "回复",
            "提交评论",
            "提交反馈",
            "确认发布",
            "确认删除",
            "删除",
            "移除",
            "清空",
            "退出登录",
            "注销",
            "拉黑",
            "屏蔽",
            "申请加入",
            "保存并发送",
        )

    private val destructiveKeywords =
        listOf(
            "删除",
            "移除",
            "清空",
            "退出登录",
            "注销",
            "拉黑",
            "屏蔽",
        )

    private val messagingContextKeywords =
        listOf(
            "消息",
            "聊天",
            "发送",
            "回复",
            "联系人",
            "会话",
            "评论",
            "弹幕",
        )

    fun review(
        task: String,
        indexedObservation: IndexedScreenObservation,
        action: AgentAction,
    ): SafetyReview {
        val observation = indexedObservation.observation
        val targetElement = resolveTargetElement(indexedObservation, action)
        val targetText = targetElement?.text.orEmpty()
        val semanticText = listOfNotNull(targetText.takeIf { it.isNotBlank() }, actionSemanticText(action))
            .joinToString(" ")
            .trim()
        val safetyMode = AssistantOsController.currentSafetyMode()
        val permissionMode = AssistantOsController.currentPermissionMode()
        val strictReview =
            safetyMode == AssistantSafetyMode.STRICT ||
                permissionMode == AssistantPermissionMode.PROMPT_EACH_TIME
        val submitContext = looksLikeSubmitContext(observation)
        val destructiveAction = matchesAny(semanticText, destructiveKeywords)
        val confirmAction = matchesAny(semanticText, confirmKeywords)
        val actionFamily = deriveActionFamily(action, semanticText, submitContext, destructiveAction)
        val (crossAppSourcePackageName, crossAppTargetPackageName) =
            resolveCrossAppPackages(
                observationPackageName = observation.packageName,
                action = action,
                fallbackTargetPackageName = SessionRuntime.currentTaskProfile().packageName,
            )
        val screenRisk =
            ScreenPromptInjectionGuard.review(
                observation = observation,
                action = action,
                semanticText = semanticText,
            )
        val crossAppRisk =
            CrossAppDataLeakPolicy.review(
                observation = observation.copy(packageName = crossAppSourcePackageName),
                action = action,
                semanticText = semanticText,
                targetPackageName = crossAppTargetPackageName,
                declaredHandoffFields =
                    SessionRuntime.State.runtimeState().session.mission?.declaredHandoffFields ?: emptySet(),
            )
        val riskLevelLabel =
            when {
                matchesAny(semanticText, blockKeywords) -> "block"
                confirmAction || destructiveAction || submitContext -> "confirm"
                else -> "low"
            }
        val grantScopeHint = suggestGrantScope(permissionMode, actionFamily)
        val dataType = resolveDataType(semanticText)
        val contact =
            TaskIntentParser.parse(SessionRuntime.State.runtimeState().session.task).recipientName.orEmpty()
        val explicitRule =
            RuntimeSafetyPolicyStore.resolveRule(
                actionFamily = actionFamily,
                targetPackageName = observation.packageName,
                toolName = action.toolName,
                pageState = observation.pageState,
                targetText = semanticText,
                contact = contact,
                dataType = dataType,
            )
        val title =
            when (action) {
                is AgentAction.Click -> "模型准备点击可能会直接生效的按钮"
                is AgentAction.LongClick -> "模型准备长按可能会直接生效的目标"
                AgentAction.PressEnter -> "模型准备提交当前输入"
                is AgentAction.SubmitPrimaryAction -> "模型准备触发主提交动作"
                is AgentAction.OpenBestCandidate -> "模型准备打开一个语义匹配候选"
                is AgentAction.DismissInterrupt -> "模型准备关闭当前中断"
                else -> "模型准备执行高风险动作"
            }
        val actionLabel = renderActionLabel(action, targetText)
        val approvalKey = buildApprovalKey(task, action, targetText)
        val activeGrant =
            if (permissionMode == AssistantPermissionMode.PROMPT_EACH_TIME) {
                null
            } else {
                RuntimeSafetyGrantStore.resolveGrant(
                    packageName = observation.packageName,
                    actionFamily = actionFamily,
                    sessionId = SessionRuntime.State.runtimeState().session.sessionId,
                )
            }

        if (matchesAny(semanticText, blockKeywords)) {
            return SafetyReview(
                level = RiskLevel.BLOCK,
                title = "已阻止高风险动作",
                summary = "检测到 ${actionLabel}，这类动作可能涉及交易、转账或不可逆提交。",
                actionLabel = actionLabel,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "block",
                grantScopeHint = grantScopeHint,
            )
        }

        if (screenRisk.behavior == "deny" || crossAppRisk.behavior == "deny") {
            val denial = listOf(screenRisk, crossAppRisk).first { it.behavior == "deny" }
            return SafetyReview(
                level = RiskLevel.BLOCK,
                title = "已阻止屏幕安全风险",
                summary = "检测到 ${denial.summary.ifBlank { "屏幕内容存在高风险诱导或跨 App 泄露风险" }}，当前不允许继续执行 ${actionLabel}。",
                actionLabel = actionLabel,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "block",
                grantScopeHint = grantScopeHint,
            )
        }

        if (screenRisk.behavior == "ask" || crossAppRisk.behavior == "ask") {
            val review = listOf(screenRisk, crossAppRisk).first { it.behavior == "ask" }
            return SafetyReview(
                level = RiskLevel.CONFIRM,
                title = "检测到屏幕安全风险",
                summary = "当前页面存在 ${review.summary.ifBlank { "潜在注入、诱导或跨 App 数据外泄" }} 风险，请先人工确认再继续 ${actionLabel}。",
                actionLabel = actionLabel,
                approvalKey = approvalKey,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "confirm",
                grantScopeHint = grantScopeHint,
            )
        }

        if (explicitRule?.behavior == RuntimeSafetyPolicyBehavior.DENY) {
            return SafetyReview(
                level = RiskLevel.BLOCK,
                title = "已命中显式安全规则",
                summary = "检测到 ${actionLabel}，命中安全规则 deny，当前不允许执行。",
                actionLabel = actionLabel,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "block",
                grantScopeHint = explicitRule.targetPackageName.ifBlank { grantScopeHint },
            )
        }

        if (permissionMode == AssistantPermissionMode.PROMPT_EACH_TIME && (confirmAction || destructiveAction || submitContext)) {
            return SafetyReview(
                level = RiskLevel.CONFIRM,
                title = title,
                summary = "当前处于 prompt_each_time 模式，检测到 ${actionLabel} 需要逐次人工确认。",
                actionLabel = actionLabel,
                approvalKey = approvalKey,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "confirm",
                grantScopeHint = grantScopeHint,
            )
        }

        if (explicitRule?.behavior == RuntimeSafetyPolicyBehavior.ALLOW) {
            return SafetyReview(
                level = RiskLevel.LOW,
                summary = "命中显式安全规则 allow，允许继续执行 ${actionLabel}。",
                actionLabel = actionLabel,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = riskLevelLabel,
                grantScopeHint = explicitRule.targetPackageName.ifBlank { grantScopeHint },
            )
        }

        if (activeGrant != null) {
            return SafetyReview(
                level = RiskLevel.LOW,
                summary = "命中已授权规则 ${activeGrant.scope}，允许继续执行 ${actionLabel}。",
                actionLabel = actionLabel,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = riskLevelLabel,
                grantScopeHint = activeGrant.scope,
            )
        }

        if (explicitRule?.behavior == RuntimeSafetyPolicyBehavior.ASK) {
            return SafetyReview(
                level = RiskLevel.CONFIRM,
                title = "命中显式安全规则",
                summary = "检测到 ${actionLabel}，命中安全规则 ask，需要人工确认。",
                actionLabel = actionLabel,
                approvalKey = approvalKey,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "confirm",
                grantScopeHint = explicitRule.targetPackageName.ifBlank { grantScopeHint },
            )
        }

        if (strictReview && (confirmAction || destructiveAction || submitContext)) {
            return SafetyReview(
                level = RiskLevel.CONFIRM,
                title = title,
                summary = "当前处于 ${safetyMode.name.lowercase()} 安全模式，检测到 ${actionLabel} 需要人工确认。",
                actionLabel = actionLabel,
                approvalKey = approvalKey,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "confirm",
                grantScopeHint = grantScopeHint,
            )
        }

        // 自治策略（用户指令：尽量只在支付/转账时确认）：
        // 人工确认已收窄为 —— 支付/转账类(前面 BLOCK) + 不可逆破坏性操作(下方 CONFIRM) +
        // 跨 App 凭证泄露/屏幕注入(前面 deny→BLOCK) + 用户显式 ASK 规则。
        // 发送/发布/回复/提交/登录/搜索等一律自动执行，不再打断。
        if (destructiveAction) {
            return SafetyReview(
                level = RiskLevel.CONFIRM,
                title = title,
                summary = "检测到不可逆操作 ${actionLabel}（删除/清空/注销/拉黑等），请确认后再执行。",
                actionLabel = actionLabel,
                approvalKey = approvalKey,
                targetPackageName = observation.packageName,
                actionFamily = actionFamily,
                riskLevelLabel = "confirm",
                grantScopeHint = grantScopeHint,
            )
        }

        return SafetyReview(
            level = RiskLevel.LOW,
            actionLabel = action.label,
            targetPackageName = observation.packageName,
            actionFamily = actionFamily,
            riskLevelLabel = riskLevelLabel,
            grantScopeHint = grantScopeHint,
        )
    }

    private fun resolveTargetElement(
        indexedObservation: IndexedScreenObservation,
        action: AgentAction,
    ): UiElementObservation? {
        val elementId =
            when (action) {
                is AgentAction.Click -> action.elementId
                is AgentAction.SetText -> action.elementId
                is AgentAction.LongClick -> action.elementId
                is AgentAction.CopyText -> action.elementId
                is AgentAction.PasteClipboard -> action.elementId
                AgentAction.FocusPrimaryInput -> indexedObservation.observation.primaryEditableId
                is AgentAction.PopulatePrimaryInput -> indexedObservation.observation.primaryEditableId
                else -> null
            } ?: return null
        return indexedObservation.observation.elements.firstOrNull { it.id == elementId }
    }

    internal fun resolveCrossAppPackages(
        observationPackageName: String,
        action: AgentAction,
        fallbackTargetPackageName: String,
        notificationPackageLookup: (String) -> String = AssistantNotificationRuntimeStore::findPackageName,
    ): Pair<String, String> {
        if (action !is AgentAction.ReplyNotification) {
            return observationPackageName to fallbackTargetPackageName
        }
        val notificationPackageName = notificationPackageLookup(action.notificationKey).ifBlank { fallbackTargetPackageName }
        val sourcePackageName =
            if (observationPackageName in neutralOverlayPackages && notificationPackageName.isNotBlank()) {
                notificationPackageName
            } else {
                observationPackageName
            }
        return sourcePackageName to notificationPackageName
    }

    private fun renderActionLabel(
        action: AgentAction,
        targetText: String,
    ): String =
        when (action) {
            is AgentAction.Click ->
                if (targetText.isBlank()) {
                    "点击按钮"
                } else {
                    "点击“$targetText”"
                }

            is AgentAction.LongClick ->
                if (targetText.isBlank()) {
                    "长按目标"
                } else {
                    "长按“$targetText”"
                }

            is AgentAction.SetText ->
                "写入文本"
            is AgentAction.ReplyNotification ->
                "回复通知"
            is AgentAction.ExecuteConnectedAppAction ->
                "执行结构化应用动作"
            is AgentAction.CreateTimer ->
                "创建计时器"
            AgentAction.OpenStopwatch ->
                "打开秒表"
            is AgentAction.DialNumber ->
                "拨号"
            is AgentAction.DraftSms ->
                "打开短信草稿"
            AgentAction.PressEnter ->
                "提交输入"
            AgentAction.FocusPrimaryInput ->
                "聚焦主输入框"
            is AgentAction.SubmitPrimaryAction ->
                "触发主动作${action.hint.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}"
            is AgentAction.DismissInterrupt ->
                "关闭当前中断"
            is AgentAction.OpenBestCandidate ->
                "打开最匹配候选${action.targetText.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}"
            else -> action.label
        }

    private fun actionSemanticText(action: AgentAction): String =
        when (action) {
            is AgentAction.SetText -> action.text
            is AgentAction.PopulatePrimaryInput -> action.text
            is AgentAction.LaunchApp -> action.packageName
            is AgentAction.SubmitPrimaryAction -> action.hint
            is AgentAction.DismissInterrupt -> action.hint
            is AgentAction.OpenBestCandidate -> listOf(action.targetText, action.roleHint).joinToString(" ")
            is AgentAction.ReturnToTargetApp -> action.packageName
            is AgentAction.ReplyNotification -> action.replyText
            is AgentAction.ExecuteConnectedAppAction -> listOf(action.appId, action.operation, action.primary, action.secondary, action.note).joinToString(" ")
            is AgentAction.CreateTimer -> listOf(action.durationLabel, action.message).joinToString(" ")
            is AgentAction.DialNumber -> listOf(action.number, action.contactName).joinToString(" ")
            is AgentAction.DraftSms -> listOf(action.recipient, action.body).joinToString(" ")
            is AgentAction.MediaControl -> action.command
            is AgentAction.AdjustVolume -> listOf(action.direction, action.stream).joinToString(" ")
            is AgentAction.OpenDevicePanel -> action.panel
            is AgentAction.CaptureScreenshot -> action.note
            is AgentAction.CapturePhoto -> action.note
            is AgentAction.Finish -> action.summary
            is AgentAction.Fail -> action.reason
            else -> action.label
        }

    private fun buildApprovalKey(
        task: String,
        action: AgentAction,
        targetText: String,
    ): String =
        "${task.trim().take(32)}|${action::class.simpleName.orEmpty()}|${targetText.trim()}|${actionSemanticText(action).trim()}"

    private fun looksLikeSubmitContext(observation: ScreenObservation): Boolean {
        val semanticTexts =
            buildList {
                addAll(observation.topTexts)
                addAll(observation.elements.take(12).map { it.text })
            }.joinToString(" ")
        return observation.primaryEditableId != null &&
            messagingContextKeywords.any { semanticTexts.contains(it, ignoreCase = true) }
    }

    private fun matchesAny(
        text: String,
        keywords: List<String>,
    ): Boolean =
        keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }

    /** 把动作语义归类到数据类型维度，供持久化权限规则（如 verification_code → deny）匹配。 */
    private fun resolveDataType(semanticText: String): String =
        when {
            listOf("验证码", "verification code", "otp", "动态码").any { semanticText.contains(it, ignoreCase = true) } -> "verification_code"
            listOf("密码", "password", "支付密码").any { semanticText.contains(it, ignoreCase = true) } -> "password"
            listOf("¥", "￥", "金额", "转账", "付款", "余额").any { semanticText.contains(it) } -> "amount"
            else -> ""
        }

    private fun deriveActionFamily(
        action: AgentAction,
        semanticText: String,
        submitContext: Boolean,
        destructiveAction: Boolean,
    ): String =
        when {
            matchesAny(semanticText, blockKeywords) -> "transaction"
            destructiveAction -> "destructive"
            action is AgentAction.ReplyNotification -> "message_send"
            submitContext && matchesAny(semanticText, listOf("发送", "回复", "评论")) -> "message_send"
            submitContext -> "submit_in_context"
            action is AgentAction.SubmitPrimaryAction && matchesAny(semanticText, listOf("导航", "路线")) -> "route_confirm"
            action is AgentAction.DismissInterrupt -> "interrupt_continue"
            matchesAny(semanticText, listOf("发布", "发表")) -> "content_publish"
            else -> "general_mutation"
        }

    private fun suggestGrantScope(
        permissionMode: AssistantPermissionMode,
        actionFamily: String,
    ): String =
        when (permissionMode) {
            AssistantPermissionMode.PROMPT_EACH_TIME -> "once"
            AssistantPermissionMode.ASSISTED -> "session"
            AssistantPermissionMode.HANDS_FREE ->
                if (actionFamily in setOf("transaction", "destructive", "message_send", "content_publish")) {
                    "session"
                } else {
                    "package"
                }
        }
}
