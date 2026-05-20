package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.assistantos.AssistantNotificationRuntimeStore

object PlatformNotificationToolService {
    fun readActive(
        packageName: String,
        limit: Int,
    ): AgentExecutionResult {
        val rows = AssistantNotificationRuntimeStore.readActive(packageName = packageName, limit = limit)
        val lines =
            rows.map { row ->
                "${row.packageName} | ${row.title.take(24)} | ${row.text.take(64)} | reply=${row.canReply} | key=${row.key.takeLast(18)}"
            }
        return AgentExecutionResult(
            message = if (lines.isEmpty()) "当前没有可读通知。" else "已读取 ${rows.size} 条当前通知摘要。",
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_notifications",
            resolvedTargetText = packageName,
            toolRuntimeDetailLines =
                lines.ifEmpty { listOf("notification | none") } +
                    listOf(
                        "receipt=notifications_read",
                        "package_filter=${packageName.ifBlank { "*" }}",
                    ),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.read_notifications",
                summary = if (lines.isEmpty()) "当前没有可读通知。" else "已读取 ${rows.size} 条当前通知摘要。",
                detailLines = lines.take(3),
                handoffRequired = false,
            )
        }
    }

    fun reply(
        notificationKey: String,
        replyText: String,
        actionHint: String,
    ): AgentExecutionResult {
        val result = AssistantNotificationRuntimeStore.reply(notificationKey, replyText, actionHint)
        val packageName = AssistantNotificationRuntimeStore.findPackageName(notificationKey)
        return AgentExecutionResult(
            message = result,
            keepRunning = !result.contains("失败") && !result.contains("不可用") && !result.contains("未找到") && !result.contains("不支持"),
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_notification_reply",
            resolvedTargetText = replyText,
            toolRuntimeDetailLines =
                listOf(
                    "notification_key=${notificationKey.take(48)}",
                    "notification_package=${packageName.ifBlank { "-" }}",
                    "delivery=remote_input",
                    "handoff=none",
                ),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.reply_notification",
                summary = result,
                detailLines = listOf("notification_package=${packageName.ifBlank { "-" }}", "delivery=remote_input"),
                handoffRequired = false,
            )
        }
    }
}
