package com.lmx.xiaoxuanagent.agent

data class ConnectedAppGoldenPath(
    val operation: String,
    val title: String,
    val intentHints: List<String>,
    val targetState: String,
    val handoffPoint: String,
    val verificationSignals: List<String>,
    val fallbackChain: List<String>,
    val riskLevel: String = "medium",
    val requiresFinalHandoff: Boolean = true,
    val flowKind: String = "foreground_app_task",
    val flowStages: List<String> = emptyList(),
    val automationBoundary: String = "",
    val finalActionPolicy: String = "",
)

object ConnectedAppGoldenPathFlowLogic {
    const val STAGE_ENTRY = "entry"
    const val STAGE_DISCOVERY = "discovery"
    const val STAGE_SELECTION = "selection"
    const val STAGE_PARAMETER_FILL = "parameter_fill"
    const val STAGE_REVIEW = "review"
    const val STAGE_HANDOFF = "handoff"
    const val STAGE_VERIFICATION = "verification"
    const val STAGE_RECOVERY = "recovery"

    fun inferFlowKind(
        operation: String,
        targetState: String,
    ): String {
        val haystack = "$operation $targetState"
        return when {
            haystack.contains("payment") || haystack.contains("bill") || haystack.contains("pay") ->
                "payment_service"
            haystack.contains("checkout") || haystack.contains("product") || haystack.contains("goods") ->
                "commerce"
            haystack.contains("message") || haystack.contains("conversation") || haystack.contains("share") ->
                "messaging"
            haystack.contains("navigation") || haystack.contains("route") || haystack.contains("place") ->
                "map_route"
            haystack.contains("hotel") || haystack.contains("takeout") || haystack.contains("groupbuy") || haystack.contains("service") ->
                "local_service"
            haystack.contains("settings") || haystack.contains("accessibility") || haystack.contains("wifi") || haystack.contains("battery") ->
                "system_settings"
            else ->
                "foreground_app_task"
        }
    }

    fun stagesFor(
        flowKind: String,
        requiresFinalHandoff: Boolean,
    ): List<String> {
        val middle =
            when (flowKind) {
                "system_settings" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION)
                "map_route" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_REVIEW)
                "messaging" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_PARAMETER_FILL, STAGE_REVIEW)
                "local_service" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_PARAMETER_FILL, STAGE_REVIEW)
                "commerce" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_PARAMETER_FILL, STAGE_REVIEW)
                "payment_service" ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_REVIEW)
                else ->
                    listOf(STAGE_DISCOVERY, STAGE_SELECTION, STAGE_REVIEW)
            }
        return buildList {
            add(STAGE_ENTRY)
            addAll(middle)
            if (requiresFinalHandoff) add(STAGE_HANDOFF)
            add(STAGE_VERIFICATION)
            add(STAGE_RECOVERY)
        }.distinct()
    }

    fun boundaryFor(
        flowKind: String,
        riskLevel: String,
        requiresFinalHandoff: Boolean,
    ): String =
        when {
            flowKind == "payment_service" ->
                "stop_before_money_movement_or_payment_code"
            flowKind == "commerce" && requiresFinalHandoff ->
                "stop_before_cart_checkout_order_submit_or_payment"
            flowKind == "messaging" && requiresFinalHandoff ->
                "stop_before_send_share_or_external_delivery"
            flowKind == "system_settings" && requiresFinalHandoff ->
                "stop_before_permission_toggle_or_system_commit"
            riskLevel == "critical" ->
                "stop_before_critical_external_effect"
            riskLevel == "high" || requiresFinalHandoff ->
                "stop_before_final_external_commit"
            else ->
                "auto_until_verified_target_state"
        }

    fun finalActionPolicyFor(
        flowKind: String,
        requiresFinalHandoff: Boolean,
    ): String =
        when {
            !requiresFinalHandoff ->
                "assistant_may_complete_after_verification"
            flowKind == "payment_service" ->
                "user_only_for_payment_transfer_code_or_final_submit"
            flowKind == "commerce" ->
                "user_only_for_cart_checkout_order_submit_or_payment"
            flowKind == "messaging" ->
                "user_only_for_send_share_or_contact_delivery"
            flowKind == "system_settings" ->
                "user_only_for_permission_or_system_setting_commit"
            else ->
                "user_only_for_final_irreversible_action"
        }
}

object ConnectedAppGoldenPathRegistry {
    private val pathsByAppId =
        mapOf(
            "system_settings_assistant" to
                listOf(
                    path(
                        operation = "open_accessibility",
                        title = "打开无障碍设置",
                        intentHints = listOf("无障碍", "accessibility", "辅助功能"),
                        targetState = "accessibility_settings_ready",
                        handoffPoint = "用户在系统设置中确认开启服务",
                        verificationSignals = listOf("settings_page", "accessibility_service_label", "service_toggle"),
                        fallbackChain = listOf("system.open_settings", "system.launch_app", "semantic.open_best_candidate"),
                        riskLevel = "low",
                        requiresFinalHandoff = true,
                    ),
                    path(
                        operation = "open_notifications",
                        title = "打开通知/通知监听设置",
                        intentHints = listOf("通知", "notification", "通知监听"),
                        targetState = "notification_settings_ready",
                        handoffPoint = "用户确认通知或通知监听授权",
                        verificationSignals = listOf("settings_page", "notification", "listener"),
                        fallbackChain = listOf("system.open_settings", "semantic.open_best_candidate"),
                        riskLevel = "low",
                        requiresFinalHandoff = true,
                    ),
                    path(
                        operation = "open_wifi",
                        title = "打开 Wi-Fi 设置",
                        intentHints = listOf("wifi", "wi-fi", "无线", "网络"),
                        targetState = "wifi_settings_ready",
                        handoffPoint = "用户确认具体网络切换",
                        verificationSignals = listOf("settings_page", "wifi", "network"),
                        fallbackChain = listOf("system.open_device_panel", "system.open_settings"),
                        riskLevel = "low",
                        requiresFinalHandoff = false,
                    ),
                    path(
                        operation = "open_battery",
                        title = "打开电池设置",
                        intentHints = listOf("电池", "battery", "省电"),
                        targetState = "battery_settings_ready",
                        handoffPoint = "用户确认省电/后台白名单策略",
                        verificationSignals = listOf("settings_page", "battery", "power"),
                        fallbackChain = listOf("system.open_settings", "system.read_device_status"),
                        riskLevel = "low",
                    ),
                    path(
                        operation = "open_app_settings",
                        title = "打开应用设置",
                        intentHints = listOf("应用设置", "权限设置", "app settings"),
                        targetState = "app_settings_ready",
                        handoffPoint = "用户确认应用权限或系统开关",
                        verificationSignals = listOf("settings_page", "app_info", "permission"),
                        fallbackChain = listOf("system.open_settings", "semantic.open_best_candidate"),
                        riskLevel = "low",
                    ),
                ),
            "amap_assistant" to
                listOf(
                    path(
                        operation = "search_place",
                        title = "搜索地点",
                        intentHints = listOf("地点", "附近", "搜索", "search"),
                        targetState = "place_search_ready",
                        handoffPoint = "用户确认候选地点或继续查看详情",
                        verificationSignals = listOf("map_page", "search_result", "poi_card"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "semantic.populate_primary_input", "semantic.open_best_candidate"),
                        riskLevel = "low",
                        requiresFinalHandoff = false,
                    ),
                    path(
                        operation = "open_route",
                        title = "规划路线",
                        intentHints = listOf("路线", "怎么走", "到", "route"),
                        targetState = "route_card_ready",
                        handoffPoint = "开始导航前由用户确认路线",
                        verificationSignals = listOf("route_card", "duration", "distance"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.submit_primary_action", "gui.click"),
                    ),
                    path(
                        operation = "start_navigation",
                        title = "导航前交接",
                        intentHints = listOf("导航", "开始导航", "开车", "步行", "骑行"),
                        targetState = "navigation_ready",
                        handoffPoint = "用户点开始导航或确认出行方式",
                        verificationSignals = listOf("navigation_button", "route_preview", "start"),
                        fallbackChain = listOf("semantic.return_to_target_app", "gui.click"),
                    ),
                ),
            "wechat_assistant" to
                listOf(
                    path(
                        operation = "prepare_conversation_search",
                        title = "定位会话",
                        intentHints = listOf("微信", "联系人", "群聊", "聊天"),
                        targetState = "conversation_search_ready",
                        handoffPoint = "打开目标会话后继续确认内容",
                        verificationSignals = listOf("wechat_home", "search_box", "conversation_candidate"),
                        fallbackChain = listOf("semantic.focus_primary_input", "semantic.populate_primary_input", "semantic.open_best_candidate", "gui.click"),
                        riskLevel = "medium",
                        requiresFinalHandoff = false,
                    ),
                    path(
                        operation = "prepare_message_handoff",
                        title = "消息草稿到发送前",
                        intentHints = listOf("发消息", "发送", "告诉", "发一条"),
                        targetState = "message_draft_ready",
                        handoffPoint = "发送按钮由用户最终点击",
                        verificationSignals = listOf("chat_input", "draft_text", "send_button"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.submit_primary_action", "gui.press_enter"),
                        riskLevel = "high",
                    ),
                    path(
                        operation = "prepare_message_reply_handoff",
                        title = "回复草稿到发送前",
                        intentHints = listOf("回复", "回消息", "回一下"),
                        targetState = "message_reply_ready",
                        handoffPoint = "回复发送前由用户确认",
                        verificationSignals = listOf("chat_input", "reply_context", "send_button"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.submit_primary_action"),
                        riskLevel = "high",
                    ),
                    path(
                        operation = "prepare_contact_share_handoff",
                        title = "分享给联系人到确认前",
                        intentHints = listOf("分享给", "转发", "发给"),
                        targetState = "contact_share_ready",
                        handoffPoint = "分享确认前由用户确认对象和内容",
                        verificationSignals = listOf("contact_picker", "share_preview", "confirm_button"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.open_best_candidate", "semantic.submit_primary_action", "gui.click"),
                        riskLevel = "high",
                    ),
                ),
            "meituan_assistant" to
                listOf(
                    path(
                        operation = "prepare_service_search",
                        title = "本地服务搜索",
                        intentHints = listOf("美团", "餐厅", "服务", "附近"),
                        targetState = "service_search_ready",
                        handoffPoint = "用户确认候选商家或服务",
                        verificationSignals = listOf("search_box", "merchant_card", "rating"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate", "semantic.scroll_for_more", "gui.click"),
                        riskLevel = "medium",
                        requiresFinalHandoff = false,
                    ),
                    path(
                        operation = "prepare_takeout_search",
                        title = "外卖搜索",
                        intentHints = listOf("外卖", "奶茶", "咖啡", "美食"),
                        targetState = "takeout_search_ready",
                        handoffPoint = "下单/支付前由用户确认",
                        verificationSignals = listOf("takeout_tab", "merchant_card", "delivery_fee"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate", "semantic.scroll_for_more"),
                    ),
                    path(
                        operation = "prepare_hotel_search",
                        title = "酒店搜索",
                        intentHints = listOf("酒店", "住宿", "入住"),
                        targetState = "hotel_search_ready",
                        handoffPoint = "预订/支付前由用户确认",
                        verificationSignals = listOf("hotel_card", "date", "price"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate"),
                    ),
                    path(
                        operation = "prepare_groupbuy_search",
                        title = "团购/到店搜索",
                        intentHints = listOf("团购", "到店", "优惠券"),
                        targetState = "groupbuy_search_ready",
                        handoffPoint = "购买或核销前由用户确认",
                        verificationSignals = listOf("deal_card", "price", "merchant"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate"),
                    ),
                    path(
                        operation = "prepare_checkout_handoff",
                        title = "结算前交接",
                        intentHints = listOf("下单", "结算", "支付"),
                        targetState = "checkout_handoff_ready",
                        handoffPoint = "支付或最终提交由用户完成",
                        verificationSignals = listOf("order_review", "pay_button", "submit_order"),
                        fallbackChain = listOf("semantic.return_to_target_app", "semantic.submit_primary_action", "gui.press_enter", "gui.click"),
                        riskLevel = "high",
                    ),
                ),
            "shopping_search" to shoppingPaths("淘宝商品搜索", "com.taobao.taobao"),
            "jd_assistant" to shoppingPaths("京东商品搜索", "com.jingdong.app.mall"),
            "pdd_assistant" to shoppingPaths("拼多多商品搜索", "com.xunmeng.pinduoduo"),
            "xianyu_assistant" to
                listOf(
                    path(
                        operation = "prepare_used_goods_search",
                        title = "闲置商品搜索",
                        intentHints = listOf("闲鱼", "二手", "闲置", "求购"),
                        targetState = "used_goods_search_ready",
                        handoffPoint = "联系卖家、出价或下单前由用户确认",
                        verificationSignals = listOf("search_box", "item_card", "seller", "price"),
                        fallbackChain = listOf("system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate"),
                        riskLevel = "high",
                    ),
                ),
            "alipay_assistant" to
                listOf(
                    path(
                        operation = "prepare_bill_lookup",
                        title = "账单/生活服务查询",
                        intentHints = listOf("账单", "生活缴费", "出行", "支付宝"),
                        targetState = "bill_lookup_ready",
                        handoffPoint = "缴费、转账、付款前由用户确认",
                        verificationSignals = listOf("alipay_home", "service_card", "bill_detail"),
                        fallbackChain = listOf("system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "semantic.open_best_candidate"),
                        riskLevel = "high",
                    ),
                    path(
                        operation = "prepare_payment_handoff",
                        title = "支付/转账前交接",
                        intentHints = listOf("付款", "转账", "支付", "收款"),
                        targetState = "payment_handoff_ready",
                        handoffPoint = "所有付款、转账和收款码展示由用户完成",
                        verificationSignals = listOf("payment_page", "amount", "confirm_button"),
                        fallbackChain = listOf("system.launch_app", "semantic.return_to_target_app", "semantic.open_best_candidate"),
                        riskLevel = "critical",
                    ),
                ),
        )

    fun paths(appId: String): List<ConnectedAppGoldenPath> = pathsByAppId[appId].orEmpty()

    fun firstPath(appId: String): ConnectedAppGoldenPath? = paths(appId).firstOrNull()

    fun find(
        appId: String,
        operation: String,
    ): ConnectedAppGoldenPath? = paths(appId).firstOrNull { it.operation == operation }

    fun operations(appId: String): List<String> = paths(appId).map { it.operation }

    fun descriptors(): Map<String, List<ConnectedAppGoldenPath>> = pathsByAppId

    fun lines(
        appId: String = "",
        limit: Int = 24,
    ): List<String> {
        val entries =
            if (appId.isBlank()) {
                pathsByAppId.entries
            } else {
                pathsByAppId.filterKeys { it == appId }.entries
            }
        return entries
            .flatMap { (id, paths) ->
                paths.map { path ->
                    "golden_path | $id | ${path.operation} | flow=${path.flowKind} | state=${path.targetState} | risk=${path.riskLevel} | boundary=${path.automationBoundary} | handoff=${path.handoffPoint}"
                }
            }
            .take(limit.coerceAtLeast(1))
    }

    private fun shoppingPaths(
        titlePrefix: String,
        packageName: String,
    ): List<ConnectedAppGoldenPath> =
        listOf(
            path(
                operation = "prepare_product_search",
                title = "$titlePrefix 到搜索结果",
                intentHints = listOf(packageName, "商品", "搜索", "比价", "价格"),
                targetState = "product_search_ready",
                handoffPoint = "用户确认候选商品后继续查看详情",
                verificationSignals = listOf("search_box", "product_card", "price", "sales"),
                fallbackChain = listOf("system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate"),
                riskLevel = "medium",
                requiresFinalHandoff = false,
            ),
            path(
                operation = "prepare_product_research",
                title = "$titlePrefix 详情/参数/评价",
                intentHints = listOf("评价", "参数", "详情", "评论", "规格"),
                targetState = "product_research_ready",
                handoffPoint = "只做信息提取和候选总结，不自动购买",
                verificationSignals = listOf("product_detail", "review_tab", "spec_tab", "price"),
                fallbackChain = listOf("semantic.return_to_target_app", "semantic.open_best_candidate", "semantic.scroll_for_more", "system.capture_screenshot"),
                riskLevel = "medium",
                requiresFinalHandoff = false,
            ),
            path(
                operation = "prepare_checkout_handoff",
                title = "$titlePrefix 下单前交接",
                intentHints = listOf("下单", "购买", "结算", "支付"),
                targetState = "commerce_checkout_handoff_ready",
                handoffPoint = "加入购物车、提交订单、支付前交给用户",
                verificationSignals = listOf("cart", "buy_now", "submit_order", "pay_button"),
                fallbackChain = listOf("semantic.return_to_target_app", "semantic.submit_primary_action", "gui.click"),
                riskLevel = "high",
            ),
        )

    private fun path(
        operation: String,
        title: String,
        intentHints: List<String>,
        targetState: String,
        handoffPoint: String,
        verificationSignals: List<String>,
        fallbackChain: List<String>,
        riskLevel: String = "medium",
        requiresFinalHandoff: Boolean = true,
        flowKind: String = ConnectedAppGoldenPathFlowLogic.inferFlowKind(operation, targetState),
    ): ConnectedAppGoldenPath {
        val flowStages = ConnectedAppGoldenPathFlowLogic.stagesFor(flowKind, requiresFinalHandoff)
        return ConnectedAppGoldenPath(
            operation = operation,
            title = title,
            intentHints = intentHints,
            targetState = targetState,
            handoffPoint = handoffPoint,
            verificationSignals = verificationSignals,
            fallbackChain = fallbackChain,
            riskLevel = riskLevel,
            requiresFinalHandoff = requiresFinalHandoff,
            flowKind = flowKind,
            flowStages = flowStages,
            automationBoundary = ConnectedAppGoldenPathFlowLogic.boundaryFor(flowKind, riskLevel, requiresFinalHandoff),
            finalActionPolicy = ConnectedAppGoldenPathFlowLogic.finalActionPolicyFor(flowKind, requiresFinalHandoff),
        )
    }
}
