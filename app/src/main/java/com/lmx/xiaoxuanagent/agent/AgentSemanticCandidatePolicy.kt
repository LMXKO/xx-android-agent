package com.lmx.xiaoxuanagent.agent

internal data class AgentSemanticCandidatePolicy(
    val roleHint: String,
    val roleKeywords: List<String> = emptyList(),
    val roleBoost: (UiElementObservation) -> Int = { 0 },
    val accepts: (UiElementObservation, List<String>) -> Boolean = { _, _ -> true },
)

internal object AgentSemanticCandidatePolicies {
    fun forRole(
        roleHint: String,
    ): AgentSemanticCandidatePolicy =
        when (roleHint.trim().lowercase()) {
            "conversation" ->
                AgentSemanticCandidatePolicy(
                    roleHint = roleHint,
                    roleKeywords = listOf("联系人", "会话", "聊天"),
                    roleBoost = { element ->
                        if (element.text.contains("群", ignoreCase = true) || element.text.contains("公众号", ignoreCase = true)) {
                            -80
                        } else {
                            14
                        }
                    },
                    accepts = { element, _ ->
                        !looksInformationalStatusLabel(element)
                    },
                )

            "comment" ->
                basicPolicy(roleHint, listOf("评论", "评价", "热评", "高赞"))

            "detail" ->
                basicPolicy(roleHint, listOf("详情", "参数", "正文", "简介"))

            "route" ->
                basicPolicy(roleHint, listOf("导航", "开始导航", "到这去", "路线"))

            "submit" ->
                basicPolicy(roleHint, listOf("发送", "确认", "完成", "搜索", "查找"))

            "dismiss" ->
                basicPolicy(roleHint, listOf("关闭", "跳过", "取消", "知道了"))

            "entry" ->
                basicPolicy(roleHint, listOf("搜索", "查找", "进入", "打开"))

            "system_action" ->
                AgentSemanticCandidatePolicy(
                    roleHint = roleHint,
                    roleKeywords =
                        listOf(
                            "允许",
                            "仅此一次",
                            "始终",
                            "继续",
                            "打开",
                            "关闭",
                            "取消",
                            "跳过",
                            "稍后",
                            "以后再说",
                            "知道了",
                            "我知道了",
                            "同意并继续",
                            "去开启",
                        ),
                    roleBoost = { element ->
                        if (looksActionControl(element)) 12 else -24
                    },
                    accepts = { element, keywords ->
                        val semantic = buildSemanticText(element)
                        val effectiveKeywords = keywords.ifEmpty { systemActionKeywords() }
                        val keywordHit = effectiveKeywords.any { semantic.contains(it, ignoreCase = true) }
                        keywordHit && looksActionControl(element) && !looksInformationalStatusLabel(element)
                    },
                )

            else ->
                AgentSemanticCandidatePolicy(roleHint = roleHint)
        }

    private fun basicPolicy(
        roleHint: String,
        keywords: List<String>,
    ): AgentSemanticCandidatePolicy =
        AgentSemanticCandidatePolicy(
            roleHint = roleHint,
            roleKeywords = keywords,
            roleBoost = { 10 },
        )

    internal fun looksInformationalStatusLabel(
        element: UiElementObservation,
    ): Boolean {
        val text = element.text.trim()
        if (text.isBlank()) return false
        if ((text.contains('，') || text.contains(',') || text.contains('。') || text.contains(':') || text.contains('：')) && text.length >= 10) {
            return true
        }
        return STATUS_PATTERNS.any { it.containsMatchIn(text) }
    }

    internal fun looksActionControl(
        element: UiElementObservation,
    ): Boolean {
        val text = element.text.trim()
        val viewId = element.viewId.trim()
        val className = element.className.trim()
        val semantic = buildSemanticText(element)
        if (className.contains("Button", ignoreCase = true) || className.contains("ImageButton", ignoreCase = true)) {
            return true
        }
        if (element.accessibilityLabel.isNotBlank() && element.accessibilityLabel.length <= 10) {
            return true
        }
        if (ACTION_CONTROL_KEYWORDS.any { keyword -> viewId.contains(keyword, ignoreCase = true) }) {
            return true
        }
        if (text.isBlank()) {
            return false
        }
        if (text.length <= 8) {
            return true
        }
        return ACTION_CONTROL_KEYWORDS.any { keyword -> semantic.contains(keyword, ignoreCase = true) }
    }

    internal fun buildSemanticText(
        element: UiElementObservation,
    ): String =
        listOf(
            element.text,
            element.viewId,
            element.className,
            element.accessibilityLabel,
            element.containerTitle,
            element.paneTitle,
            element.stateDescription,
            element.collectionPosition,
            element.visualSignature,
            element.spatialSignature,
            element.visualDescriptorTokens.joinToString(" "),
            element.roleHint,
            element.source,
            element.descriptorTokens.joinToString(" "),
            element.neighborTexts.joinToString(" "),
        ).joinToString(" ")

    internal fun systemActionKeywords(): List<String> = ACTION_CONTROL_KEYWORDS

    private val ACTION_CONTROL_KEYWORDS =
        listOf(
            "允许",
            "仅此一次",
            "始终",
            "继续",
            "打开",
            "关闭",
            "取消",
            "跳过",
            "稍后",
            "以后再说",
            "知道了",
            "我知道了",
            "同意并继续",
            "去开启",
            "拒绝",
            "不允许",
        )

    private val STATUS_PATTERNS =
        listOf(
            Regex(""".+已(登录|关闭|开启|连接|同步|完成).*"""),
            Regex(""".*(Mac|Windows|电脑|平板).*(已登录|在线|同步).*"""),
        )
}
