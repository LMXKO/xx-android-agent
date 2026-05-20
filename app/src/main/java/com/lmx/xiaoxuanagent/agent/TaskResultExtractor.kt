package com.lmx.xiaoxuanagent.agent

object TaskResultExtractor {
    fun extract(
        task: String,
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload? {
        val constraints = TaskIntentParser.parse(task)
        val semantics = observationSemantics(observation)
        return when (resolveResultFamily(constraints, taskPlanState)) {
            ResultFamily.SHOPPING -> extractShoppingResult(constraints, semantics, taskPlanState)
            ResultFamily.NAVIGATION -> extractNavigationResult(constraints, semantics, taskPlanState)
            ResultFamily.MESSAGING -> extractMessagingResult(constraints, semantics, taskPlanState)
            ResultFamily.CONTENT -> extractContentResult(constraints, semantics, taskPlanState)
            ResultFamily.GENERIC -> extractGenericResult(constraints, semantics, taskPlanState)
        }
    }

    fun renderBriefSummary(payload: TaskResultPayload): String =
        buildString {
            append(payload.summary)
            if (payload.highlights.isNotEmpty()) {
                append(" | 证据: ").append(payload.highlights.take(3).joinToString(" / "))
            }
        }

    private fun extractShoppingResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "shopping",
            title = constraints.entryQuery.ifBlank { "商品调研结果" },
            summary =
                buildString {
                    append("已围绕 ").append(constraints.entryQuery.ifBlank { constraints.normalizedGoal })
                    append(" 完成商品信息收集")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，当前重点证据是 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，可进入结果整理阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("评价", "评论", "参数", "规格", "详情", "¥", "元"),
                ),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("query", "目标商品", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "关注点", it) },
                ),
        )

    private fun extractNavigationResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "navigation",
            title = constraints.destination?.ifBlank { constraints.entryQuery.ifBlank { "路线结果" } } ?: constraints.entryQuery.ifBlank { "路线结果" },
            summary =
                buildString {
                    append("已定位到 ")
                    append(constraints.destination?.ifBlank { constraints.entryQuery.ifBlank { "目标地点" } } ?: constraints.entryQuery.ifBlank { "目标地点" })
                    append(" 的路线信息")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，当前关注 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "confirm_route") {
                        append("，已接近路线确认阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("路线", "导航", "到这去", "预计", "分钟", "公里"),
                ),
            fields =
                listOfNotNull(
                    constraints.destination?.takeIf { it.isNotBlank() }?.let { TaskResultField("destination", "目的地", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "输出目标", it) },
                ),
        )

    private fun extractMessagingResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "messaging",
            title = constraints.recipientName?.ifBlank { "通讯结果" } ?: "通讯结果",
            summary =
                buildString {
                    append("已定位通讯任务")
                    constraints.recipientName?.takeIf { it.isNotBlank() }?.let {
                        append("，目标联系人为 ").append(it)
                    }
                    constraints.messageBody?.takeIf { it.isNotBlank() }?.let {
                        append("，正文为 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "confirm_send") {
                        append("，当前已到发送前确认阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("发送", "输入消息", "聊天", "联系人"),
                ),
            fields =
                listOfNotNull(
                    constraints.recipientName?.takeIf { it.isNotBlank() }?.let { TaskResultField("recipient", "联系人", it) },
                    constraints.messageBody?.takeIf { it.isNotBlank() }?.let { TaskResultField("message", "消息内容", it) },
                ),
        )

    private fun extractContentResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "content",
            title = constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "内容检索结果" } },
            summary =
                buildString {
                    append("已围绕 ")
                    append(constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "目标内容" } })
                    append(" 收集内容证据")
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let {
                        append("，重点为 ").append(it)
                    }
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，已进入可收口阶段")
                    }
                },
            highlights =
                extractEvidenceSnippets(
                    semantics = semantics,
                    keywords = listOf("评论", "评论区", "热评", "高赞", "弹幕", "最新一期", "最近一期"),
                ),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("query", "目标内容", it) },
                    constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { TaskResultField("objective", "关注点", it) },
                ),
        )

    private fun extractGenericResult(
        constraints: TaskConstraints,
        semantics: List<String>,
        taskPlanState: TaskPlanState?,
    ): TaskResultPayload =
        TaskResultPayload(
            intentType = "generic",
            title = constraints.entryQuery.ifBlank { constraints.normalizedGoal.ifBlank { "任务结果" } },
            summary =
                buildString {
                    append("已完成任务相关页面收集")
                    if (taskPlanState?.currentStage == "summarize") {
                        append("，当前证据已足够收口")
                    }
                },
            highlights = extractEvidenceSnippets(semantics, constraints.keywordHints),
            fields =
                listOfNotNull(
                    constraints.entryQuery.takeIf { it.isNotBlank() }?.let { TaskResultField("goal", "目标", it) },
                    taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { TaskResultField("stage", "当前阶段", it) },
                ),
        )

    private fun observationSemantics(
        observation: ScreenObservation,
    ): List<String> =
        buildList {
            add(observation.pageState)
            add(observation.screenSummary)
            addAll(observation.topTexts)
            addAll(observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun extractEvidenceSnippets(
        semantics: List<String>,
        keywords: List<String>,
    ): List<String> {
        if (keywords.isEmpty()) return semantics.take(3)
        return semantics
            .filter { line ->
                keywords.any { keyword -> keyword.isNotBlank() && line.contains(keyword, ignoreCase = true) }
            }
            .take(4)
            .ifEmpty { semantics.take(3) }
    }

    private fun resolveResultFamily(
        constraints: TaskConstraints,
        taskPlanState: TaskPlanState?,
    ): ResultFamily =
        when (constraints.intentType) {
            TaskIntentType.SHOPPING -> ResultFamily.SHOPPING
            TaskIntentType.NAVIGATION -> ResultFamily.NAVIGATION
            TaskIntentType.MESSAGING -> ResultFamily.MESSAGING
            TaskIntentType.CONTENT -> ResultFamily.CONTENT
            TaskIntentType.GENERIC ->
                when (taskPlanState?.planType) {
                    "shopping_research" -> ResultFamily.SHOPPING
                    "navigation" -> ResultFamily.NAVIGATION
                    "messaging" -> ResultFamily.MESSAGING
                    "content_research" -> ResultFamily.CONTENT
                    else -> ResultFamily.GENERIC
                }
        }
}

private enum class ResultFamily {
    SHOPPING,
    NAVIGATION,
    MESSAGING,
    CONTENT,
    GENERIC,
}
