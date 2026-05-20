package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior

internal object AppPermissionSemanticSupport {
    fun behaviorLabel(
        behavior: RuntimeSafetyPolicyBehavior,
    ): String =
        when (behavior) {
            RuntimeSafetyPolicyBehavior.ALLOW -> "总是允许"
            RuntimeSafetyPolicyBehavior.ASK -> "每次询问"
            RuntimeSafetyPolicyBehavior.DENY -> "不允许"
        }

    fun actionFamilyLabel(
        family: String,
    ): String =
        when (family) {
            "message_send" -> "发送与回复"
            "transaction", "route_confirm" -> "交易与确认"
            "destructive" -> "删除与移除"
            "write_input", "submit_in_context" -> "填写与提交"
            "content_publish" -> "发布内容"
            "interrupt_continue" -> "继续与恢复"
            "general_mutation" -> "页面操作"
            else -> family.ifBlank { "通用能力" }
        }

    fun packageLabel(
        packageName: String,
    ): String =
        when (packageName) {
            "com.tencent.mm" -> "微信"
            "com.sankuai.meituan" -> "美团"
            "com.autonavi.minimap" -> "高德地图"
            "com.android.settings" -> "系统设置"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "*" -> "全部应用"
            else -> packageName
        }
}
