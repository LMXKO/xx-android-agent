package com.lmx.xiaoxuanagent.harness

internal object ScreenAutomationFocusStageSupport {
    private val genericLabels =
        mapOf(
            "enter_query" to "输入搜索词",
            "submit_query" to "提交搜索",
            "enter_destination" to "输入目的地",
            "confirm_route" to "确认路线",
            "find_conversation" to "定位会话",
            "confirm_send" to "确认发送",
            "prepare_message_handoff" to "准备消息待发送状态",
            "prepare_message_reply_handoff" to "准备回复待发送状态",
            "prepare_contact_share_handoff" to "准备联系人分享",
            "prepare_takeout_search" to "进入外卖搜索",
            "prepare_hotel_search" to "进入酒店搜索",
            "prepare_groupbuy_search" to "进入团购搜索",
            "prepare_checkout_handoff" to "推进到支付前确认",
            "search_place" to "搜索地点",
            "start_navigation" to "准备开始导航",
        )

    private val profileLabels =
        mapOf(
            "wechat_assistant" to
                mapOf(
                    "find_conversation" to "定位微信会话",
                    "confirm_send" to "发送前最后确认",
                    "prepare_message_handoff" to "准备微信消息待发送状态",
                    "prepare_message_reply_handoff" to "准备微信回复待发送状态",
                    "prepare_contact_share_handoff" to "准备微信联系人分享",
                ),
            "meituan_assistant" to
                mapOf(
                    "enter_query" to "输入美团搜索词",
                    "submit_query" to "提交搜索并进入结果页",
                    "prepare_takeout_search" to "进入外卖搜索与筛选阶段",
                    "prepare_hotel_search" to "进入酒店搜索与筛选阶段",
                    "prepare_groupbuy_search" to "进入团购搜索与筛选阶段",
                    "prepare_checkout_handoff" to "推进到支付前最后确认",
                ),
            "amap_assistant" to
                mapOf(
                    "enter_destination" to "输入路线目的地",
                    "confirm_route" to "确认路线并准备导航",
                    "search_place" to "准备地点搜索结果",
                    "start_navigation" to "准备开始导航",
                ),
        )

    fun criticalStagesForProfile(
        profileId: String,
    ): List<String> =
        when (profileId) {
            "wechat_assistant" -> listOf("find_conversation", "prepare_message_handoff", "prepare_message_reply_handoff", "prepare_contact_share_handoff", "confirm_send")
            "meituan_assistant" -> listOf("prepare_takeout_search", "prepare_hotel_search", "prepare_groupbuy_search", "enter_query", "submit_query", "prepare_checkout_handoff")
            "amap_assistant" -> listOf("search_place", "enter_destination", "confirm_route", "start_navigation")
            else -> listOf("enter_destination", "confirm_route")
        }

    fun isFillStage(
        profileId: String,
        stage: String,
    ): Boolean =
        normalize(stage) in
            when (profileId) {
                "wechat_assistant" -> setOf("find_conversation", "enter_query", "prepare_message_handoff", "prepare_message_reply_handoff", "prepare_contact_share_handoff")
                "meituan_assistant" -> setOf("enter_query", "prepare_service_search", "prepare_takeout_search", "prepare_hotel_search", "prepare_groupbuy_search", "browse_results", "filter_results")
                "amap_assistant" -> setOf("enter_destination", "search_place", "start_navigation")
                else -> setOf("enter_query", "enter_destination", "find_conversation")
            }

    fun isSubmitStage(
        profileId: String,
        stage: String,
    ): Boolean =
        normalize(stage) in
            when (profileId) {
                "wechat_assistant" -> setOf("confirm_send", "prepare_contact_share_handoff")
                "meituan_assistant" -> setOf("submit_query", "prepare_checkout_handoff")
                "amap_assistant" -> setOf("confirm_route", "start_navigation")
                else -> setOf("submit_query", "confirm_send", "confirm_route")
            }

    fun stageLabel(
        profileId: String,
        stage: String,
    ): String {
        val normalized = normalize(stage)
        return profileLabels[profileId]?.get(normalized)
            ?: genericLabels[normalized]
            ?: normalized.ifBlank { "-" }
    }

    fun recoveryHint(
        profileId: String,
        stage: String,
        recentRecoveryRate: Double,
        latestRecoveryCategory: String,
    ): String? {
        if (recentRecoveryRate < 0.25) return null
        val stageLabel = stageLabel(profileId = profileId, stage = stage)
        val recovery = latestRecoveryCategory.ifBlank { "RECOVERY" }.lowercase()
        return when (profileId) {
            "wechat_assistant" ->
                "$stageLabel 最近恢复率偏高，建议优先校验会话命中和待发送文案，避免误发。recovery=$recovery"

            "meituan_assistant" ->
                "$stageLabel 最近恢复率偏高，建议先确认搜索词、筛选条件和结果页稳定度，再推进到支付前。recovery=$recovery"

            "amap_assistant" ->
                "$stageLabel 最近恢复率偏高，建议先核对起终点和路线卡片，再决定是否继续自动推进。recovery=$recovery"

            else ->
                "$stageLabel 最近恢复率偏高，建议先人工核对当前页面再继续自动化。recovery=$recovery"
        }
    }

    private fun normalize(
        value: String,
    ): String = value.trim().lowercase()
}
