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
        val directNumber = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        val contactQuery = contactName.ifBlank { number.takeIf { directNumber.isBlank() }.orEmpty() }
        val resolvedContact =
            if (directNumber.isBlank() && contactQuery.isNotBlank()) {
                PlatformContactLookupToolService.resolveBestPhoneNumber(service, contactQuery)
            } else {
                null
            }
        val resolvedNumber = directNumber.ifBlank { resolvedContact?.phoneNumber.orEmpty() }
        val sanitized = resolvedNumber.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        if (sanitized.isBlank()) {
            return if (contactQuery.isNotBlank() && !PlatformContactLookupToolService.hasReadContactsPermission(service)) {
                AgentExecutionResult("需要联系人读取权限后才能按姓名拨号。", keepRunning = false)
            } else {
                AgentExecutionResult("拨号号码为空或未找到联系人号码。", keepRunning = false)
            }
        }
        val intent =
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvedName = contactName.ifBlank { resolvedContact?.displayName.orEmpty() }
        return runIntent(
            service,
            intent,
            "已打开拨号界面：${resolvedName.ifBlank { sanitized }.take(24)}。",
            detailLines =
                listOf(
                    "receipt=dialer_opened",
                    "number=${sanitized.take(24)}",
                    "contact=${resolvedName.ifBlank { "-" }.take(24)}",
                    "mode=user_confirm_call",
                ),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.dial_number",
                summary = "已打开拨号界面：${resolvedName.ifBlank { sanitized }.take(24)}。",
                detailLines = listOf("number=${sanitized.take(24)}", "contact=${resolvedName.ifBlank { "-" }.take(24)}"),
                handoffRequired = true,
            )
        }
    }

    fun draftSms(
        service: AccessibilityService,
        recipient: String,
        body: String,
    ): AgentExecutionResult {
        val directRecipient = recipient.filter { it.isDigit() || it == '+' || it == ';' || it == ',' }
        val resolvedContact =
            if (directRecipient.isBlank() && recipient.isNotBlank()) {
                PlatformContactLookupToolService.resolveBestPhoneNumber(service, recipient)
            } else {
                null
            }
        val sanitized = directRecipient.ifBlank {
            resolvedContact?.phoneNumber.orEmpty().filter { it.isDigit() || it == '+' || it == ';' || it == ',' }
        }
        if (sanitized.isBlank()) {
            return if (recipient.isNotBlank() && !PlatformContactLookupToolService.hasReadContactsPermission(service)) {
                AgentExecutionResult("需要联系人读取权限后才能按姓名创建短信草稿。", keepRunning = false)
            } else {
                AgentExecutionResult("短信收件人为空或未找到联系人号码。", keepRunning = false)
            }
        }
        val intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$sanitized")).apply {
                putExtra("sms_body", body.take(200))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val resolvedName = resolvedContact?.displayName.orEmpty()
        return runIntent(
            service,
            intent,
            "已打开短信草稿：${resolvedName.ifBlank { sanitized }.take(24)}。",
            detailLines =
                listOf(
                    "receipt=sms_draft_opened",
                    "recipient=${sanitized.take(24)}",
                    "contact=${resolvedName.ifBlank { "-" }.take(24)}",
                    "mode=user_confirm_send",
                ),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.draft_sms",
                summary = "已打开短信草稿：${resolvedName.ifBlank { sanitized }.take(24)}。",
                detailLines = listOf("recipient=${sanitized.take(24)}", "contact=${resolvedName.ifBlank { "-" }.take(24)}"),
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
