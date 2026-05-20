package com.lmx.xiaoxuanagent.taskprofile

data class ScreenAutomationProductSupportSnapshot(
    val profileId: String = "",
    val packageName: String = "",
    val tier: String = "experimental",
    val summary: String = "",
    val focusApp: Boolean = false,
    val allowAutoSubmit: Boolean = false,
    val connectedFirstRecommended: Boolean = false,
    val supportCeiling: String = "",
    val consumerPermissionHint: String = "",
)

object ScreenAutomationProductSupportStore {
    private val focusProfiles =
        mapOf(
            "amap_assistant" to "精选地图自动化",
            "wechat_assistant" to "精选通讯自动化",
            "meituan_assistant" to "精选生活服务自动化",
            "system_settings_assistant" to "精选系统助手能力",
        )

    fun snapshot(
        profileId: String,
        packageName: String = "",
    ): ScreenAutomationProductSupportSnapshot {
        val label = focusProfiles[profileId]
        val focusApp = label != null
        return if (focusApp) {
            ScreenAutomationProductSupportSnapshot(
                profileId = profileId,
                packageName = packageName,
                tier = "focused",
                summary = "$label 已进入 select-app 打磨范围，可继续使用 release gate 管控自动推进深度。",
                focusApp = true,
                allowAutoSubmit = profileId == "system_settings_assistant",
                connectedFirstRecommended = profileId in setOf("amap_assistant", "system_settings_assistant", "wechat_assistant", "meituan_assistant"),
                supportCeiling =
                    when (profileId) {
                        "system_settings_assistant" -> "可通过结构化入口完成系统页面导航。"
                        "amap_assistant" -> "可优先结构化搜地点与路线，开始导航前交给用户。"
                        "wechat_assistant" -> "可优先准备会话定位与消息草稿，真正发送前交给用户。"
                        else -> "可优先准备搜索、筛选与下单前推进，支付前交给用户。"
                    },
                consumerPermissionHint = "建议按 App 开启，并保留高风险动作逐次确认。",
            )
        } else {
            ScreenAutomationProductSupportSnapshot(
                profileId = profileId,
                packageName = packageName,
                tier = "experimental",
                summary = "当前 App 不在 select-app 首发范围内，默认只建议自动推进到 handoff 前一步。",
                focusApp = false,
                allowAutoSubmit = false,
                connectedFirstRecommended = false,
                supportCeiling = "当前仅建议前台推进到 handoff 前一步。",
                consumerPermissionHint = "建议保持每次询问或仅允许低风险能力。",
            )
        }
    }
}
