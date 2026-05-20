package com.lmx.xiaoxuanagent.agent

data class ConnectedAppSuggestion(
    val appId: String,
    val operation: String,
    val primary: String = "",
    val secondary: String = "",
    val reason: String = "",
)

object ConnectedAppRouter {
    fun suggest(
        task: String,
        profileId: String,
        targetPackageName: String,
    ): ConnectedAppSuggestion? {
        val normalized = task.lowercase()
        val parsed = TaskIntentParser.parse(task)
        return when (profileId) {
            "system_settings_assistant" ->
                ConnectedAppSuggestion(
                    appId = "system_settings_assistant",
                    operation =
                        when {
                            normalized.contains("无障碍") || normalized.contains("accessibility") -> "open_accessibility"
                            normalized.contains("通知") || normalized.contains("notification") -> "open_notifications"
                            normalized.contains("wifi") || normalized.contains("wi-fi") || normalized.contains("无线") -> "open_wifi"
                            normalized.contains("电池") || normalized.contains("battery") -> "open_battery"
                            else -> "open_app_settings"
                        },
                    reason = "系统设置任务优先走结构化设置入口。",
                )

            "amap_assistant" ->
                ConnectedAppSuggestion(
                    appId = "amap_assistant",
                    operation =
                        when {
                            normalized.contains("导航") -> "start_navigation"
                            normalized.contains("路线") || normalized.contains("route") -> "open_route"
                            else -> "search_place"
                        },
                    primary = parsed.destination.orEmpty().ifBlank { parsed.searchQuery.orEmpty().ifBlank { parsed.normalizedGoal } },
                    reason = "地图任务优先通过结构化地图 Intent 进路线或地点搜索。",
                )

            "wechat_assistant" ->
                ConnectedAppSuggestion(
                    appId = "wechat_assistant",
                    operation =
                        when {
                            normalized.contains("转发") || normalized.contains("分享给") -> "prepare_contact_share_handoff"
                            normalized.contains("回复") || normalized.contains("回一下") || normalized.contains("回消息") -> "prepare_message_reply_handoff"
                            normalized.contains("发送") || normalized.contains("发一") || normalized.contains("发条") || normalized.contains("发消息") -> "prepare_message_handoff"
                            else -> "prepare_conversation_search"
                        },
                    primary = parsed.recipientName.orEmpty().ifBlank { parsed.searchQuery.orEmpty() },
                    secondary = parsed.messageBody.orEmpty(),
                    reason = "微信任务优先先准备会话定位与消息草稿，再把最终发送交回用户。",
                )

            "meituan_assistant" ->
                ConnectedAppSuggestion(
                    appId = "meituan_assistant",
                    operation =
                        when {
                            normalized.contains("下单") || normalized.contains("结算") || normalized.contains("支付") -> "prepare_checkout_handoff"
                            normalized.contains("外卖") || normalized.contains("奶茶") || normalized.contains("咖啡") || normalized.contains("汉堡") || normalized.contains("美食") -> "prepare_takeout_search"
                            normalized.contains("酒店") || normalized.contains("住宿") -> "prepare_hotel_search"
                            normalized.contains("团购") || normalized.contains("到店") -> "prepare_groupbuy_search"
                            else -> "prepare_service_search"
                        },
                    primary =
                        parsed.searchQuery.orEmpty()
                            .ifBlank { parsed.normalizedGoal }
                            .ifBlank { parsed.destination.orEmpty() },
                    secondary = parsed.keywordHints.firstOrNull().orEmpty(),
                    reason = "美团任务优先先准备搜索与筛选，再推进到支付前 handoff。",
                )

            "shopping_search", "jd_assistant", "pdd_assistant" ->
                ConnectedAppSuggestion(
                    appId = profileId,
                    operation =
                        when {
                            isCheckoutTask(normalized) -> "prepare_checkout_handoff"
                            isProductResearchTask(normalized) -> "prepare_product_research"
                            else -> "prepare_product_search"
                        },
                    primary = parsed.searchQuery.orEmpty().ifBlank { parsed.normalizedGoal },
                    secondary = parsed.keywordHints.firstOrNull().orEmpty(),
                    reason = "电商任务优先走商品搜索/详情信息 golden path，购买和支付前交回用户。",
                )

            "xianyu_assistant" ->
                ConnectedAppSuggestion(
                    appId = "xianyu_assistant",
                    operation = "prepare_used_goods_search",
                    primary = parsed.searchQuery.orEmpty().ifBlank { parsed.normalizedGoal },
                    secondary = parsed.keywordHints.firstOrNull().orEmpty(),
                    reason = "闲鱼任务优先准备闲置商品搜索和候选比较，联系卖家或下单前交回用户。",
                )

            "alipay_assistant" ->
                ConnectedAppSuggestion(
                    appId = "alipay_assistant",
                    operation =
                        if (isPaymentTask(normalized)) {
                            "prepare_payment_handoff"
                        } else {
                            "prepare_bill_lookup"
                        },
                    primary = parsed.searchQuery.orEmpty().ifBlank { parsed.normalizedGoal },
                    secondary = parsed.keywordHints.firstOrNull().orEmpty(),
                    reason = "支付宝任务只推进到账单/服务或支付前交接，付款转账必须用户最终确认。",
                )

            else ->
                ConnectedAppCatalog.findByPackageName(targetPackageName)?.let { descriptor ->
                    ConnectedAppSuggestion(
                        appId = descriptor.appId,
                        operation = descriptor.operations.firstOrNull().orEmpty(),
                        reason = "当前 profile 已有 connected app 能力，优先试结构化路径。",
                    )
                }
        }?.takeIf { it.operation.isNotBlank() }
    }

    private fun isCheckoutTask(normalized: String): Boolean =
        listOf("下单", "购买", "买下", "结算", "支付", "提交订单", "加入购物车").any { normalized.contains(it) }

    private fun isProductResearchTask(normalized: String): Boolean =
        listOf("评价", "参数", "详情", "评论", "规格", "配置", "比价", "测评", "口碑").any { normalized.contains(it) }

    private fun isPaymentTask(normalized: String): Boolean =
        listOf("付款", "转账", "支付", "收款", "付款码", "红包").any { normalized.contains(it) }
}
