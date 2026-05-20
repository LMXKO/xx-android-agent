package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillGuardPolicies

data class TaskConstraints(
    val originalTask: String,
    val intentType: TaskIntentType,
    val normalizedGoal: String,
    val searchQuery: String?,
    val destination: String?,
    val recipientName: String?,
    val messageBody: String?,
    val objectiveHint: String?,
    val maxPriceYuan: Double?,
    val preferHighReview: Boolean,
    val preferHighSales: Boolean,
    val keywordHints: List<String>,
) {
    val entryQuery: String
        get() = destination?.takeIf { it.isNotBlank() }
            ?: recipientName?.takeIf { it.isNotBlank() }
            ?: searchQuery?.takeIf { it.isNotBlank() }
            ?: normalizedGoal
}

enum class TaskIntentType {
    NAVIGATION,
    SHOPPING,
    CONTENT,
    MESSAGING,
    GENERIC,
}

data class VisibleProductCandidate(
    val elementId: String,
    val text: String,
    val priceYuan: Double?,
    val score: Int,
)

object TaskIntentParser {
    private val navigationTriggerPatterns =
        listOf(
            Regex("""(?:去|到|前往|导航到|导航去|去往).{1,24}(?:多久|多长时间|多少时间|几分钟|几小时|怎么走|怎么去|路线|路程|有多远)"""),
            Regex("""从.{1,16}到.{1,24}(?:多久|多长时间|多少时间|几分钟|几小时|怎么走|路线|有多远)"""),
        )

    private val maxPricePatterns =
        listOf(
            Regex("""小于\s*(\d+(?:\.\d+)?)"""),
            Regex("""低于\s*(\d+(?:\.\d+)?)"""),
            Regex("""不超过\s*(\d+(?:\.\d+)?)"""),
            Regex("""(\d+(?:\.\d+)?)\s*元\s*(?:以下|以内)"""),
        )

    private val removablePhrases =
        listOf(
            "给我推荐",
            "帮我推荐",
            "帮我找",
            "帮我挑",
            "给我找",
            "找一下",
            "搜一下",
            "搜索",
            "评价最高",
            "评分最高",
            "高评价",
            "高评分",
            "销量最高",
            "销量高",
            "推荐一下",
        )

    private val commonLeadInPatterns =
        listOf(
            Regex("""^(?:帮我|请帮我|给我|麻烦你|麻烦|请|我想|想要|想|能不能|可以|先)\s*"""),
            Regex("""^(?:查下|查一下|看下|看一下|搜下|搜一下|搜索|搜|查|找|看看|看一眼|了解下|了解一下)\s*"""),
        )

    private val leadingAppPatterns =
        listOf(
            Regex("""^(?:打开|启动|进入|用)\s*[“"']?[A-Za-z0-9\p{IsHan}·._-]{1,20}?(?:App|APP|app)?[”"']?(?:[，,、:\s]+|(?=(?:搜|搜索|查|找|看|看下|查下|搜下|买|逛|发|联系|导航)))"""),
            Regex("""^去\s*[“"']?[A-Za-z0-9\p{IsHan}·._-]{1,20}?(?:App|APP|app)?[”"']?\s*(?=(?:给|发给|回复|告诉|搜|搜索|查|找|看|看下|查下|搜下|买|逛|发|联系|导航))"""),
        )

    private val entryVerbPatterns =
        listOf(
            Regex("""^(?:再|继续)?(?:搜|搜索|查|找|看|看下|看看|看一下|了解|了解下|阅读|播放|听|买|逛|比较|对比)\s*(.+)$"""),
            Regex("""^(?:帮我|请帮我|给我|麻烦你|麻烦|请)?(?:搜|搜索|查|找|看|看下|看看|看一下|了解|了解下|阅读|播放|听|买|逛|比较|对比)\s*(.+)$"""),
        )

    private val shoppingObjectiveSuffixes =
        listOf(
            "价格",
            "多少钱",
            "价钱",
            "评价",
            "评论",
            "口碑",
            "参数",
            "规格",
            "配置",
            "测评",
            "推荐",
        )

    private val contentObjectiveSuffixes =
        listOf(
            "最新一期点赞最高的评论",
            "最近一期点赞最高的评论",
            "最新一期点赞最高",
            "最近一期点赞最高",
            "点赞最高的评论",
            "点赞最高",
            "高赞评论",
            "高赞",
            "热评",
            "最新评论",
            "最新一期",
            "最近一期",
            "评论区",
            "评论",
            "弹幕",
            "视频",
            "文章",
            "笔记",
            "内容",
            "直播",
            "作品",
        )

    private val fillerSuffixPattern = Regex("""[吗呢呀吧啊哦啦]+$""")
    private val leadingPossessivePattern = Regex("""^(?:一下|一个|这个|那个|关于|有关)\s*""")
    private val trailingMessageWrapperPattern = Regex("""(?:的?(?:消息|信息|微信|短信))$""")
    private val messagingTriggerPatterns =
        listOf(
            Regex("""(?:给|发给|告诉|联系|回复)\s*[A-Za-z0-9\p{IsHan}·._-]{2,20}\s*(?:发|发送|回复|说|带句话|留句话)"""),
            Regex("""(?:发|发送|回复).{0,12}(?:消息|微信|短信)"""),
        )

    fun parse(task: String): TaskConstraints {
        val normalizedTask = normalizeWhitespace(task)
        val maxPrice = maxPricePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(normalizedTask)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }
        val preferHighReview =
            listOf("评价最高", "评分最高", "高评价", "高评分", "好评").any {
                normalizedTask.contains(it)
            }
        val preferHighSales =
            listOf("销量最高", "销量高", "卖得最好").any {
                normalizedTask.contains(it)
        }
        val intentType = detectIntentType(normalizedTask)
        val destination = extractDestination(normalizedTask).takeIf { intentType == TaskIntentType.NAVIGATION }
        val recipientName = extractRecipient(normalizedTask).takeIf { intentType == TaskIntentType.MESSAGING }
        val messageBody = extractMessageBody(normalizedTask).takeIf { intentType == TaskIntentType.MESSAGING }
        val objectiveHint = extractObjectiveHint(normalizedTask, intentType)
        val searchQuery =
            when (intentType) {
                TaskIntentType.NAVIGATION -> destination
                TaskIntentType.MESSAGING -> recipientName ?: extractSearchQuery(normalizedTask, intentType)
                else -> extractSearchQuery(normalizedTask, intentType)
            }
        val fallbackGoal = cleanupGoal(stripConstraints(normalizedTask))
        val normalizedGoal =
            listOfNotNull(destination, recipientName, searchQuery, fallbackGoal.takeIf { it.isNotBlank() }, normalizedTask)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

        val keywordHints =
            extractKeywordHints(
                listOfNotNull(
                    normalizedGoal,
                    recipientName,
                    searchQuery,
                    destination,
                    messageBody?.takeIf { it.length <= 24 },
                    objectiveHint,
                ).joinToString(" "),
            )

        return TaskConstraints(
            originalTask = normalizedTask,
            intentType = intentType,
            normalizedGoal = normalizedGoal,
            searchQuery = searchQuery,
            destination = destination,
            recipientName = recipientName,
            messageBody = messageBody,
            objectiveHint = objectiveHint,
            maxPriceYuan = maxPrice,
            preferHighReview = preferHighReview,
            preferHighSales = preferHighSales,
            keywordHints = keywordHints,
        )
    }

    private fun detectIntentType(task: String): TaskIntentType {
        val navigationScore =
            score(task, listOf("导航", "路线", "路程", "到这去", "打车", "开车", "步行", "骑行", "公交", "地铁")) +
                navigationTriggerPatterns.count { it.containsMatchIn(task) } * 5
        val shoppingScore =
            score(task, listOf("价格", "多少钱", "元", "评价", "参数", "规格", "配置", "购买", "下单", "商品", "二手", "比价", "销量", "店铺"))
        val messagingScore =
            score(task, listOf("发消息", "消息", "回复", "联系", "告诉", "通知", "聊天", "短信", "发给", "转发", "发送", "发一条")) +
                messagingTriggerPatterns.count { it.containsMatchIn(task) } * 4
        val contentScore =
            score(task, listOf("视频", "文章", "笔记", "评论", "弹幕", "点赞", "最新一期", "最近一期", "播放", "听", "直播", "高赞"))

        return when {
            navigationScore >= 5 -> TaskIntentType.NAVIGATION
            shoppingScore >= 2 && shoppingScore >= contentScore -> TaskIntentType.SHOPPING
            messagingScore >= 4 -> TaskIntentType.MESSAGING
            contentScore >= 3 -> TaskIntentType.CONTENT
            else -> TaskIntentType.GENERIC
        }
    }

    private fun score(
        task: String,
        keywords: List<String>,
    ): Int =
        keywords.fold(0) { acc, keyword ->
            when {
                task.equals(keyword, ignoreCase = true) -> acc + 3
                task.contains(keyword, ignoreCase = true) -> acc + 1
                else -> acc
            }
        }

    private fun extractDestination(task: String): String? {
        val candidates =
            listOf(
                Regex("""从.{1,16}?到\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30}?)(?:需要|大概|约|大约)?(?:多久|多长时间|多少时间|几分钟|几小时|怎么走|怎么去|路线|路程|有多远|远不远|$)"""),
                Regex("""(?:去|到|前往|导航到|导航去|去往)\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30}?)(?:需要|大概|约|大约)?(?:多久|多长时间|多少时间|几分钟|几小时|怎么走|怎么去|路线|路程|有多远|远不远|$)"""),
                Regex("""(?:查下|查一下|看下|看一下|帮我查下|帮我看下)\s*(?:到|去)\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30}?)(?:需要|大概|约|大约)?(?:多久|多长时间|多少时间|几分钟|几小时|怎么走|怎么去|路线|路程|有多远|远不远|$)"""),
                Regex("""导航(?:到|去)\s*([A-Za-z0-9\p{IsHan}·._\-/()（）\s]{2,30})$"""),
            )
        return candidates.firstNotNullOfOrNull { regex ->
            regex.find(task)?.groupValues?.getOrNull(1)?.let(::cleanupGoal)
        }?.takeIf { it.length >= 2 }
    }

    private fun extractObjectiveHint(
        task: String,
        intentType: TaskIntentType,
    ): String? =
        when (intentType) {
            TaskIntentType.NAVIGATION ->
                when {
                    task.contains("多久") || task.contains("多长时间") || task.contains("多少时间") -> "通勤时间"
                    task.contains("有多远") || task.contains("路程") -> "路程距离"
                    task.contains("怎么走") || task.contains("怎么去") || task.contains("路线") -> "路线方案"
                    else -> "导航"
                }

            TaskIntentType.SHOPPING ->
                shoppingObjectiveSuffixes.firstOrNull { task.contains(it) }

            TaskIntentType.CONTENT ->
                contentObjectiveSuffixes.firstOrNull { task.contains(it) }

            TaskIntentType.MESSAGING ->
                "消息发送"

            TaskIntentType.GENERIC ->
                null
        }

    private fun extractSearchQuery(
        task: String,
        intentType: TaskIntentType,
    ): String? {
        val withoutApp = stripLeadingAppDirective(task)
        val withoutLeadIn = stripLeadInPhrases(withoutApp)
        val candidate =
            entryVerbPatterns.firstNotNullOfOrNull { regex ->
                regex.find(withoutLeadIn)?.groupValues?.getOrNull(1)
            } ?: withoutLeadIn

        val strippedTail = stripObjectiveSuffix(candidate, intentType)
        val cleaned = cleanupGoal(stripConstraints(strippedTail))
        return cleaned.takeIf { it.length >= 2 }
    }

    private fun extractRecipient(task: String): String? {
        val normalized = stripLeadInPhrases(stripLeadingAppDirective(task))
        val patterns =
            listOf(
                Regex("""(?:给|发给|告诉|联系|回复)\s*[“"']?([A-Za-z0-9\p{IsHan}·._-]{2,20}?)[”"']?\s*(?=(?:发|发送|回复|说|带句话|留句话|消息|微信|短信|聊))"""),
                Regex("""(?:给|发给)\s*[“"']?([A-Za-z0-9\p{IsHan}·._-]{2,20}?)[”"']?\s*(?:发|发送)(?:一条)?(?:微信|消息|短信)?"""),
                Regex("""联系\s*[“"']?([A-Za-z0-9\p{IsHan}·._-]{2,20})[”"']?$"""),
            )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)?.let(::cleanupRecipient)
        }?.takeIf { it.length >= 2 }
    }

    private fun extractMessageBody(task: String): String? {
        val normalized = stripLeadInPhrases(stripLeadingAppDirective(task))
        val patterns =
            listOf(
                Regex("""(?:说|回复)\s*[“"']?(.+?)[”"']?\s*$"""),
                Regex("""(?:给|发给)\s*[A-Za-z0-9\p{IsHan}·._-]{2,20}\s*(?:发|发送)(?:一条)?(?:微信|消息|短信)?\s*[“"']?(.+?)[”"']?\s*$"""),
                Regex("""(?:带句话|留句话)\s*[“"']?(.+?)[”"']?\s*$"""),
            )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)?.let(::cleanupMessageBody)
        }?.takeIf { it.length in 1..80 }
    }

    private fun stripLeadingAppDirective(task: String): String {
        var result = task
        leadingAppPatterns.forEach { regex ->
            result = result.replaceFirst(regex, "")
        }
        return normalizeWhitespace(result)
    }

    private fun stripLeadInPhrases(task: String): String {
        var result = task
        commonLeadInPatterns.forEach { regex ->
            result = result.replaceFirst(regex, "")
        }
        return normalizeWhitespace(result)
    }

    private fun stripObjectiveSuffix(
        text: String,
        intentType: TaskIntentType,
    ): String {
        var result = normalizeWhitespace(text)

        val directionalPatterns =
            listOf(
                Regex("""(.+?)的(?:价格|多少钱|价钱|评价|参数|规格|配置|评论|弹幕|视频|文章|笔记|内容|路线|攻略|教程)$"""),
                Regex("""(.+?)(?:价格|多少钱|价钱|评价|参数|规格|配置|评论|弹幕|视频|文章|笔记|内容|路线|攻略|教程)$"""),
            )
        directionalPatterns.forEach { regex ->
            regex.find(result)?.groupValues?.getOrNull(1)?.let { matched ->
                result = matched
            }
        }

        val suffixes =
            when (intentType) {
                TaskIntentType.SHOPPING -> shoppingObjectiveSuffixes
                TaskIntentType.CONTENT -> contentObjectiveSuffixes
                else -> shoppingObjectiveSuffixes + contentObjectiveSuffixes
            }.sortedByDescending { it.length }

        suffixes.forEach { suffix ->
            if (result.endsWith(suffix)) {
                result = result.removeSuffix(suffix)
            }
        }

        return normalizeWhitespace(result)
    }

    private fun stripConstraints(task: String): String =
        removablePhrases.fold(task) { acc, phrase ->
            acc.replace(phrase, "")
        }.replace(Regex("""小于\s*\d+(?:\.\d+)?"""), "")
            .replace(Regex("""低于\s*\d+(?:\.\d+)?"""), "")
            .replace(Regex("""不超过\s*\d+(?:\.\d+)?"""), "")
            .replace(Regex("""\d+(?:\.\d+)?\s*元\s*(以下|以内)"""), "")
            .let(::normalizeWhitespace)

    private fun cleanupGoal(text: String): String =
        normalizeWhitespace(
            text.replace(leadingPossessivePattern, "")
                .replace(fillerSuffixPattern, "")
                .trim('，', ',', '。', '.', '；', ';', '：', ':', '、', ' ')
                .removePrefix("去")
                .removePrefix("到")
                .removePrefix("看")
                .removePrefix("查")
                .removePrefix("搜")
                .removePrefix("找")
                .removePrefix("一下")
                .removePrefix("一下子"),
        ).ifBlank { normalizeWhitespace(text) }

    private fun cleanupRecipient(text: String): String =
        normalizeWhitespace(
            text.trim()
                .trim('，', ',', '。', '.', '；', ';', '：', ':', '、', '“', '”', '"', '\'', ' '),
        )

    private fun cleanupMessageBody(text: String): String =
        normalizeWhitespace(
            text.trim()
                .replace(trailingMessageWrapperPattern, "")
                .trim('，', ',', '。', '.', '；', ';', '：', ':', '、', '“', '”', '"', '\'', ' '),
        )

    private fun normalizeWhitespace(text: String): String =
        text.replace(Regex("""[\u3000\s]+"""), " ").trim()

    private fun extractKeywordHints(
        normalizedGoal: String,
    ): List<String> {
        val compact = normalizedGoal.trim()
        if (compact.isBlank()) return emptyList()

        val hints = linkedSetOf<String>()
        hints += compact

        Regex("""[A-Za-z0-9]+""").findAll(compact).forEach { match ->
            val token = match.value.trim()
            if (token.length >= 2) {
                hints += token
            }
        }

        val cjkOnly = compact.replace(Regex("""[^\\p{IsHan}]"""), "")
        if (cjkOnly.length in 2..6) {
            hints += cjkOnly
        } else if (cjkOnly.length > 6) {
            for (size in 4 downTo 2) {
                for (index in 0..cjkOnly.length - size) {
                    hints += cjkOnly.substring(index, index + size)
                    if (hints.size >= 8) break
                }
                if (hints.size >= 8) break
            }
        }

        return hints
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
            .take(8)
    }
}

object GenericTaskDecisionPolicy {
    private val metaControlKeywords =
        listOf(
            "综合",
            "销量",
            "筛选",
            "排序",
            "更多",
            "品牌",
            "店铺",
            "分类",
            "标签",
            "tab",
            "sort",
            "filter",
            "menu",
        )

    private val pricePattern = Regex("""(\d+(?:\.\d+)?)\s*元""")

    fun refineDecision(
        task: String,
        observation: ScreenObservation,
        decision: AgentDecision,
    ): AgentDecision {
        val constraints = TaskIntentParser.parse(task)
        val rewrittenLaunchApp = SkillGuardPolicies.rewriteLaunchAppIfAlreadyForeground(decision, observation)
        if (rewrittenLaunchApp != null) {
            return rewrittenLaunchApp
        }
        val rewrittenSetText =
            SkillGuardPolicies.rewriteSetTextToCurrentTarget(
                task = task,
                observation = observation,
                decision = decision,
            )
        if (rewrittenSetText != null) {
            return rewrittenSetText
        }

        if (!isResultLikePage(observation.pageState)) {
            return decision
        }
        val clickAction = decision.action as? AgentAction.Click ?: return decision
        val targetElement = observation.elements.firstOrNull { it.id == clickAction.elementId } ?: return decision

        if (!isMetaControl(targetElement)) {
            return decision
        }

        val candidates = extractVisibleCandidates(observation, constraints)
        val bestCandidate = candidates.firstOrNull()

        if (bestCandidate != null && shouldPreferCandidateOverMetaControl(targetElement, constraints)) {
            return decision.copy(
                action = AgentAction.OpenBestCandidate(
                    targetText = bestCandidate.text,
                    roleHint = "detail",
                ),
                reason =
                    "本地策略改写：结果页已有更相关的候选商品 ${bestCandidate.text.take(40)}，" +
                        "优先打开商品详情，而不是执行 ${targetElement.text.ifBlank { targetElement.viewId.ifBlank { clickAction.elementId } }}。",
            )
        }

        if (constraints.preferHighReview && observation.defaultScrollableId != null) {
            return decision.copy(
                action = AgentAction.ScrollForMore("down"),
                reason =
                    "本地策略改写：当前任务更关注评价，且结果页未发现明确更优候选，" +
                        "先滚动查看更多商品，而不是执行 ${targetElement.text.ifBlank { clickAction.elementId }}。",
            )
        }

        return decision
    }

    fun summarizeConstraints(task: String): String {
        val constraints = TaskIntentParser.parse(task)
        return buildString {
            append("intent=").append(constraints.intentType.name.lowercase())
            append("goal=").append(constraints.normalizedGoal)
            constraints.destination?.let { append(", destination=").append(it) }
            constraints.recipientName?.let { append(", recipient=").append(it) }
            constraints.messageBody?.let { append(", message=").append(it) }
            constraints.searchQuery?.let { append(", searchQuery=").append(it) }
            constraints.objectiveHint?.let { append(", objective=").append(it) }
            constraints.maxPriceYuan?.let { append(", maxPrice=").append(it) }
            append(", preferHighReview=").append(constraints.preferHighReview)
            append(", preferHighSales=").append(constraints.preferHighSales)
            if (constraints.keywordHints.isNotEmpty()) {
                append(", keywords=").append(constraints.keywordHints.joinToString("/"))
            }
        }
    }

    private fun shouldPreferCandidateOverMetaControl(
        targetElement: UiElementObservation,
        constraints: TaskConstraints,
    ): Boolean {
        val semantic = listOf(targetElement.text, targetElement.viewId, targetElement.className)
            .joinToString(" ")
        if (constraints.preferHighReview && semantic.contains("销量")) {
            return true
        }
        return metaControlKeywords.any { semantic.contains(it, ignoreCase = true) }
    }

    private fun isMetaControl(
        element: UiElementObservation,
    ): Boolean {
        val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
        return metaControlKeywords.any { semantic.contains(it, ignoreCase = true) }
    }

    private fun isResultLikePage(
        pageState: String,
    ): Boolean {
        val normalized = pageState.uppercase()
        return listOf("RESULT", "LIST", "FEED", "GRID").any { normalized.contains(it) }
    }

    private fun extractVisibleCandidates(
        observation: ScreenObservation,
        constraints: TaskConstraints,
    ): List<VisibleProductCandidate> {
        return observation.elements.mapNotNull { element ->
            val price = pricePattern.find(element.text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val relevant =
                constraints.keywordHints.count { keyword ->
                    element.text.contains(keyword, ignoreCase = true)
                }
            val looksLikeContent =
                element.clickable &&
                    (element.text.length >= 8 || price != null || relevant >= 2)
            val underPrice =
                when {
                    constraints.maxPriceYuan == null -> true
                    price == null -> false
                    price <= constraints.maxPriceYuan -> true
                    else -> false
                }
            if (!looksLikeContent) {
                return@mapNotNull null
            }
            val score =
                (if (underPrice) 120 else -120) +
                    relevant * 18 +
                    (if (price != null) (100 - price.toInt()).coerceAtLeast(0) else 0)
            VisibleProductCandidate(
                elementId = element.id,
                text = element.text,
                priceYuan = price,
                score = score,
            )
        }.sortedByDescending { it.score }
    }
}
