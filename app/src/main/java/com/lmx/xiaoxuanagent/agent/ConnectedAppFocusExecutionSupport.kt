package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

internal data class ConnectedAppExecutionPlan(
    val intent: Intent,
    val successMessage: String,
    val detailLines: List<String> = emptyList(),
    val groundingSource: String = "connected_app",
    val recommendedWaitMs: Long = 1_000L,
    val receiptState: String = "",
    val handoffRequired: Boolean = false,
)

internal object ConnectedAppFocusExecutionSupport {
    fun buildPlan(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): ConnectedAppExecutionPlan? =
        when (action.appId) {
            "wechat_assistant" -> buildWechatPlan(service, action)
            "meituan_assistant" -> buildMeituanPlan(service, action)
            "shopping_search", "jd_assistant", "pdd_assistant", "xianyu_assistant", "alipay_assistant" ->
                buildGenericGoldenPathPlan(service, action)
            else -> null
        }

    private fun buildWechatPlan(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): ConnectedAppExecutionPlan? {
        val launchIntent = service.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null
        val contact = action.primary.trim().ifBlank { action.note.trim() }
        val draft = action.secondary.trim()
        val draftSeeded = seedClipboard(service, "wechat_draft", draft)
        val contactSeeded = seedClipboard(service, "wechat_contact", contact)
        val detailLines =
            buildList {
                add("connected_app=wechat_assistant")
                add("operation=${action.operation}")
                add("target_state=${wechatTargetState(action.operation)}")
                contact.takeIf { it.isNotBlank() }?.let { add("contact=$it") }
                draft.takeIf { it.isNotBlank() }?.let { add("draft=${it.take(64)}") }
                if (contactSeeded) add("clipboard_seed=contact")
                if (draftSeeded) add("clipboard_seed=message_draft")
                add("mode=structured_progression")
                add("followup=conversation_search_then_message_progression")
                add("handoff=send_or_share_requires_user_confirmation")
                add("handoff_step=${wechatHandoffStep(action.operation)}")
                add("resume_anchor=wechat:${contact.ifBlank { "last_chat" }}")
                add("search_strategy=${if (contact.isNotBlank()) "contact_exact_match" else "recent_chat_first"}")
                add("preferred_fallback=${wechatFallbackHint(action.operation)}")
            }
        val successMessage =
            when (action.operation) {
                "prepare_conversation_search" -> "已打开微信，并准备按联系人搜索进入会话。"
                "prepare_message_handoff" -> "已打开微信，并准备把消息推进到发送前最后一步。"
                "prepare_message_reply_handoff" -> "已打开微信，并准备把回复内容推进到发送前最后一步。"
                "prepare_contact_share_handoff" -> "已打开微信，并准备把联系人选择与分享推进到最后一步。"
                else -> "已通过 focus-app 结构化入口打开微信。"
            }
        return ConnectedAppExecutionPlan(
            intent = launchIntent,
            successMessage = successMessage,
            detailLines = detailLines,
            groundingSource = "connected_app_wechat",
            receiptState = wechatTargetState(action.operation),
            handoffRequired = action.operation != "prepare_conversation_search",
        )
    }

    private fun buildMeituanPlan(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): ConnectedAppExecutionPlan? {
        val primary = action.primary.trim().ifBlank { action.note.trim() }
        val querySeeded = seedClipboard(service, "meituan_query", primary)
        val launchIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse("imeituan://www.meituan.com")).apply {
                `package` = "com.sankuai.meituan"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.takeIf { it.resolveActivity(service.packageManager) != null }
                ?: service.packageManager.getLaunchIntentForPackage("com.sankuai.meituan")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ?: return null
        val detailLines =
            buildList {
                add("connected_app=meituan_assistant")
                add("operation=${action.operation}")
                add("target_state=${meituanTargetState(action.operation)}")
                primary.takeIf { it.isNotBlank() }?.let { add("query=$it") }
                action.secondary.trim().takeIf { it.isNotBlank() }?.let { add("secondary=${it.take(48)}") }
                if (querySeeded) add("clipboard_seed=service_query")
                add("mode=structured_progression")
                add("followup=service_search_then_selection_progression")
                add("handoff=payment_or_final_confirm_requires_user")
                add("channel=${meituanChannel(action.operation)}")
                add("handoff_step=${if (action.operation == "prepare_checkout_handoff") "review_order_then_pay" else "search_or_filter_then_choose"}")
                add("resume_anchor=meituan:${primary.ifBlank { "home" }}")
                add("preferred_fallback=${meituanFallbackHint(action.operation)}")
            }
        val successMessage =
            when (action.operation) {
                "prepare_takeout_search" -> "已打开美团，并准备按外卖任务推进到搜索与筛选阶段。"
                "prepare_hotel_search" -> "已打开美团，并准备按酒店任务推进到搜索与筛选阶段。"
                "prepare_groupbuy_search" -> "已打开美团，并准备按团购/到店任务推进到搜索与筛选阶段。"
                "prepare_service_search" -> "已打开美团，并准备按任务目标推进到搜索与筛选阶段。"
                "prepare_checkout_handoff" -> "已打开美团，并准备把任务推进到支付或最终确认前一步。"
                else -> "已通过 focus-app 结构化入口打开美团。"
            }
        return ConnectedAppExecutionPlan(
            intent = launchIntent,
            successMessage = successMessage,
            detailLines = detailLines,
            groundingSource = "connected_app_meituan",
            receiptState = meituanTargetState(action.operation),
            handoffRequired = action.operation == "prepare_checkout_handoff",
        )
    }

    private fun wechatTargetState(
        operation: String,
    ): String =
        when (operation) {
            "prepare_contact_share_handoff" -> "contact_share_ready"
            "prepare_message_reply_handoff" -> "message_reply_ready"
            "prepare_message_handoff" -> "message_draft_ready"
            else -> "conversation_search_ready"
        }

    private fun wechatHandoffStep(
        operation: String,
    ): String =
        when (operation) {
            "prepare_contact_share_handoff" -> "pick_contact_then_confirm_share"
            "prepare_message_reply_handoff" -> "review_reply_then_send"
            "prepare_message_handoff" -> "review_message_then_send"
            else -> "open_target_conversation"
        }

    private fun wechatFallbackHint(
        operation: String,
    ): String =
        when (operation) {
            "prepare_contact_share_handoff" -> "semantic.return_to_target_app>semantic.open_best_candidate>semantic.submit_primary_action"
            "prepare_message_reply_handoff" -> "semantic.return_to_target_app>semantic.focus_primary_input>online.clipboard_paste>semantic.submit_primary_action"
            "prepare_message_handoff" -> "semantic.return_to_target_app>semantic.focus_primary_input>online.clipboard_paste>semantic.submit_primary_action"
            else -> "semantic.return_to_target_app>semantic.open_best_candidate>semantic.focus_primary_input"
        }

    private fun meituanTargetState(
        operation: String,
    ): String =
        when (operation) {
            "prepare_takeout_search" -> "takeout_search_ready"
            "prepare_hotel_search" -> "hotel_search_ready"
            "prepare_groupbuy_search" -> "groupbuy_search_ready"
            "prepare_checkout_handoff" -> "checkout_handoff_ready"
            else -> "service_search_ready"
        }

    private fun meituanChannel(
        operation: String,
    ): String =
        when (operation) {
            "prepare_takeout_search" -> "takeout"
            "prepare_hotel_search" -> "hotel"
            "prepare_groupbuy_search" -> "groupbuy"
            "prepare_checkout_handoff" -> "checkout"
            else -> "service"
        }

    private fun meituanFallbackHint(
        operation: String,
    ): String =
        when (operation) {
            "prepare_checkout_handoff" -> "semantic.return_to_target_app>semantic.open_best_candidate>semantic.submit_primary_action"
            else -> "semantic.return_to_target_app>semantic.focus_primary_input>online.clipboard_paste>semantic.open_best_candidate"
        }

    private fun buildGenericGoldenPathPlan(
        service: AccessibilityService,
        action: AgentAction.ExecuteConnectedAppAction,
    ): ConnectedAppExecutionPlan? {
        val descriptor = ConnectedAppCatalog.findByAppId(action.appId) ?: return null
        val goldenPath =
            ConnectedAppGoldenPathRegistry.find(action.appId, action.operation)
                ?: ConnectedAppGoldenPathRegistry.firstPath(action.appId)
                ?: return null
        val launchIntent = service.packageManager.getLaunchIntentForPackage(descriptor.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null
        val primary = action.primary.trim().ifBlank { action.note.trim() }
        val secondary = action.secondary.trim()
        val querySeeded = seedClipboard(service, "${action.appId}_query", primary)
        val secondarySeeded = seedClipboard(service, "${action.appId}_secondary", secondary)
        val detailLines =
            buildList {
                add("connected_app=${action.appId}")
                add("operation=${goldenPath.operation}")
                add("target_state=${goldenPath.targetState}")
                add("golden_path=${goldenPath.title}")
                primary.takeIf { it.isNotBlank() }?.let { add("primary=${it.take(64)}") }
                secondary.takeIf { it.isNotBlank() }?.let { add("secondary=${it.take(64)}") }
                if (querySeeded) add("clipboard_seed=primary")
                if (secondarySeeded) add("clipboard_seed=secondary")
                add("mode=golden_path_progression")
                add("flow_kind=${goldenPath.flowKind}")
                add("flow_stages=${goldenPath.flowStages.joinToString(">")}")
                add("automation_boundary=${goldenPath.automationBoundary}")
                add("final_action_policy=${goldenPath.finalActionPolicy}")
                add("handoff=${goldenPath.handoffPoint}")
                add("risk=${goldenPath.riskLevel}")
                add("verification=${goldenPath.verificationSignals.joinToString(",").ifBlank { "-" }}")
                add("preferred_fallback=${goldenPath.fallbackChain.joinToString(">")}")
            }
        return ConnectedAppExecutionPlan(
            intent = launchIntent,
            successMessage = "已打开${descriptor.title}，并准备推进 golden path：${goldenPath.title}。",
            detailLines = detailLines,
            groundingSource = "connected_app_${action.appId}",
            receiptState = goldenPath.targetState,
            handoffRequired = goldenPath.requiresFinalHandoff,
        )
    }

    private fun seedClipboard(
        service: AccessibilityService,
        label: String,
        value: String,
    ): Boolean {
        if (value.isBlank()) return false
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        }.isSuccess
    }
}
