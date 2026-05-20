package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicy

object SafetyHandoffFormatter {
    fun enrich(
        confirmation: PendingSafetyConfirmation,
        policy: AutomationSupportPolicy,
    ): PendingSafetyConfirmation {
        if (!policy.requiresFinalHandoff) {
            return confirmation
        }
        val completionSummary =
            when (confirmation.actionFamily) {
                "message_send" -> "消息内容与会话已准备完成。"
                "content_publish" -> "发布内容已准备完成。"
                "route_confirm" -> "路线与目的地已准备完成。"
                "submit_in_context" -> "当前页面内容已准备完成。"
                "destructive" -> "模型已经定位到最后一步，等待你决定是否继续。"
                else -> "自动化已推进到最后确认前一步。"
            }
        val finalUserStep =
            when (confirmation.actionFamily) {
                "message_send" -> "确认发送"
                "content_publish" -> "确认发布"
                "route_confirm" -> "确认开始导航"
                "submit_in_context" -> confirmation.actionLabel.ifBlank { "完成最后提交" }
                "destructive" -> "确认是否执行该不可逆动作"
                else -> confirmation.actionLabel.ifBlank { "完成最后一步" }
            }
        val handoffReason =
            policy.handoffSummary.ifBlank {
                "该应用的最后确认步骤应交给用户亲自完成。"
            }
        val summary =
            buildString {
                append(completionSummary)
                append(' ')
                append(handoffReason)
                append(" 最后一步请你")
                append(finalUserStep)
                append('。')
                confirmation.summary.takeIf { it.isNotBlank() }?.let {
                    append(" 当前风险提示：").append(it)
                }
            }
        return confirmation.copy(
            summary = summary.take(220),
            handoffReason = handoffReason,
            completionSummary = completionSummary,
            finalUserStep = finalUserStep,
        )
    }
}
