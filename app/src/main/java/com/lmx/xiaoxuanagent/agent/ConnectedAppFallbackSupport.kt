package com.lmx.xiaoxuanagent.agent

internal object ConnectedAppFallbackSupport {
    fun fallbackChainFor(
        suggestion: ConnectedAppSuggestion,
        receipt: ConnectedAppExecutionReceipt?,
    ): List<String> {
        val receiptState = receipt?.state.orEmpty()
        val stateSpecific =
            when (suggestion.appId) {
                "wechat_assistant" ->
                    when (receiptState) {
                        "conversation_search_ready" ->
                            listOf(
                                "semantic.focus_primary_input",
                                "semantic.populate_primary_input",
                                "semantic.open_best_candidate",
                                "gui.click",
                            )

                        "message_draft_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.focus_primary_input",
                                "online.clipboard_paste",
                                "semantic.submit_primary_action",
                                "gui.press_enter",
                            )

                        "contact_share_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.focus_primary_input",
                                "semantic.open_best_candidate",
                                "semantic.submit_primary_action",
                                "gui.click",
                            )

                        else -> emptyList()
                    }

                "meituan_assistant" ->
                    when (receiptState) {
                        "service_search_ready", "hotel_search_ready", "groupbuy_search_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.focus_primary_input",
                                "online.clipboard_paste",
                                "semantic.open_best_candidate",
                                "semantic.scroll_for_more",
                                "gui.click",
                            )

                        "checkout_handoff_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.submit_primary_action",
                                "gui.press_enter",
                                "gui.click",
                            )

                        else -> emptyList()
                    }

                "amap_assistant" ->
                    when (receiptState) {
                        "place_search_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.focus_primary_input",
                                "semantic.populate_primary_input",
                                "semantic.open_best_candidate",
                            )

                        "route_card_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "semantic.submit_primary_action",
                                "gui.click",
                            )

                        "navigation_ready" ->
                            listOf(
                                "semantic.return_to_target_app",
                                "gui.click",
                            )

                        else -> emptyList()
                    }

                "shopping_search", "jd_assistant", "pdd_assistant", "xianyu_assistant", "alipay_assistant" ->
                    ConnectedAppGoldenPathRegistry.find(
                        appId = suggestion.appId,
                        operation = receipt?.operation.orEmpty().ifBlank { suggestion.operation },
                    )?.fallbackChain.orEmpty()

                else -> emptyList()
            }
        if (stateSpecific.isNotEmpty()) return stateSpecific
        return when (suggestion.appId) {
            "wechat_assistant" ->
                listOf("system.read_notifications", "system.reply_notification", "system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "gui.click")

            "meituan_assistant" ->
                listOf("system.capture_screenshot", "system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "gui.click")

            "amap_assistant" ->
                listOf("system.launch_app", "semantic.return_to_target_app", "semantic.populate_primary_input", "gui.click")

            "shopping_search", "jd_assistant", "pdd_assistant", "xianyu_assistant" ->
                listOf("system.launch_app", "semantic.return_to_target_app", "semantic.focus_primary_input", "online.clipboard_paste", "semantic.open_best_candidate", "system.capture_screenshot")

            "alipay_assistant" ->
                listOf("system.launch_app", "semantic.return_to_target_app", "semantic.open_best_candidate")

            else ->
                listOf("system.launch_app", "semantic.return_to_target_app", "gui.click")
        }
    }

    fun receiptHint(
        receipt: ConnectedAppExecutionReceipt?,
    ): String =
        when (receipt?.state.orEmpty()) {
            "conversation_search_ready" -> "receipt=conversation_search_ready"
            "message_draft_ready" -> "receipt=message_draft_ready"
            "contact_share_ready" -> "receipt=contact_share_ready"
            "service_search_ready" -> "receipt=service_search_ready"
            "hotel_search_ready" -> "receipt=hotel_search_ready"
            "groupbuy_search_ready" -> "receipt=groupbuy_search_ready"
            "checkout_handoff_ready" -> "receipt=checkout_handoff_ready"
            "place_search_ready" -> "receipt=place_search_ready"
            "route_card_ready" -> "receipt=route_card_ready"
            "navigation_ready" -> "receipt=navigation_ready"
            "product_search_ready" -> "receipt=product_search_ready"
            "product_research_ready" -> "receipt=product_research_ready"
            "commerce_checkout_handoff_ready" -> "receipt=commerce_checkout_handoff_ready"
            "used_goods_search_ready" -> "receipt=used_goods_search_ready"
            "bill_lookup_ready" -> "receipt=bill_lookup_ready"
            "payment_handoff_ready" -> "receipt=payment_handoff_ready"
            else -> ""
        }
}
