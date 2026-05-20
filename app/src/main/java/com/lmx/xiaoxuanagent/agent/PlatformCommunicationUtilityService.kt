package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri

object PlatformCommunicationUtilityService {
    fun dialNumber(
        service: AccessibilityService,
        number: String,
        contactName: String,
    ): AgentExecutionResult {
        val sanitized = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        if (sanitized.isBlank()) {
            return AgentExecutionResult("拨号号码为空或非法。", keepRunning = false)
        }
        val intent =
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runIntent(
            service,
            intent,
            "已打开拨号界面：${contactName.ifBlank { sanitized }.take(24)}。",
            detailLines = listOf("receipt=dialer_opened", "number=${sanitized.take(24)}", "mode=user_confirm_call"),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.dial_number",
                summary = "已打开拨号界面：${contactName.ifBlank { sanitized }.take(24)}。",
                detailLines = listOf("number=${sanitized.take(24)}"),
                handoffRequired = true,
            )
        }
    }

    fun draftSms(
        service: AccessibilityService,
        recipient: String,
        body: String,
    ): AgentExecutionResult {
        val sanitized = recipient.filter { it.isDigit() || it == '+' || it == ';' || it == ',' }
        if (sanitized.isBlank()) {
            return AgentExecutionResult("短信收件人为空或非法。", keepRunning = false)
        }
        val intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$sanitized")).apply {
                putExtra("sms_body", body.take(200))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runIntent(
            service,
            intent,
            "已打开短信草稿：${sanitized.take(24)}。",
            detailLines = listOf("receipt=sms_draft_opened", "recipient=${sanitized.take(24)}", "mode=user_confirm_send"),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.draft_sms",
                summary = "已打开短信草稿：${sanitized.take(24)}。",
                detailLines = listOf("recipient=${sanitized.take(24)}"),
                handoffRequired = true,
            )
        }
    }

    private fun runIntent(
        service: AccessibilityService,
        intent: Intent,
        successMessage: String,
        detailLines: List<String> = emptyList(),
    ): AgentExecutionResult =
        runCatching {
            service.startActivity(intent)
            AgentExecutionResult(
                message = successMessage,
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 900L,
                groundingSource = "system_communication",
                toolRuntimeDetailLines = detailLines,
            )
        }.getOrElse { error ->
            AgentExecutionResult("系统通信入口失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }
}
