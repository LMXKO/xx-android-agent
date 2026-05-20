package com.lmx.xiaoxuanagent.agent

object MultiStepTaskEngine {
    fun buildPlanState(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        resumeContext: ResumeContext = ResumeContext(),
    ): TaskPlanState {
        val normalizedTask = task.trim()
        val constraints = TaskIntentParser.parse(normalizedTask)
        val base =
            when (resolvePlanFamily(constraints, profileId)) {
                PlanFamily.SHOPPING -> buildShoppingPlan(normalizedTask, constraints, observation, history)
                PlanFamily.NAVIGATION -> buildNavigationPlan(normalizedTask, constraints, observation, history)
                PlanFamily.MESSAGING -> buildMessagingPlan(normalizedTask, constraints, observation, history)
                PlanFamily.CONTENT -> buildContentPlan(normalizedTask, constraints, observation, history)
                PlanFamily.GENERIC -> buildGenericPlan(normalizedTask, constraints, observation, history)
            }
        return SuspendResumeEngine.enrich(
            task = normalizedTask,
            observation = observation,
            history = history,
            base = base,
            resumeContext = resumeContext,
        )
    }

    private fun buildShoppingPlan(
        task: String,
        constraints: TaskConstraints,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
    ): TaskPlanState {
        val page = observation.pageState.uppercase()
        val enteredQuery = hasEnteredQuery(observation, history, constraints.entryQuery)
        val wantsInfo = wantsInformation(constraints)
        val currentStage =
            when {
                observation.packageName.isBlank() -> "launch_target_app"
                isSearchEntryPage(observation) && !enteredQuery -> "enter_query"
                isSearchEntryPage(observation) && enteredQuery -> "submit_query"
                isResultLikePage(observation) -> "browse_candidates"
                isDetailLikePage(observation) -> if (wantsInfo) "inspect_information" else "summarize"
                page.contains("REVIEW") || page.contains("PARAM") -> "summarize"
                else -> "observe"
            }

        val steps =
            listOf(
                TaskPlanStep("launch", "进入目标购物 App", stepStatus(observation.packageName.isNotBlank())),
                TaskPlanStep(
                    "query",
                    "输入并触发查询",
                    statusForStage(currentStage, "enter_query", "submit_query", doneWhen = enteredQuery && isResultLikePage(observation)),
                    constraints.entryQuery,
                ),
                TaskPlanStep(
                    "browse",
                    "浏览结果并挑选候选",
                    statusForStage(currentStage, "browse_candidates", doneWhen = isDetailLikePage(observation)),
                    observation.screenSummary,
                ),
                TaskPlanStep(
                    "inspect",
                    "读取评价或参数证据",
                    statusForStage(currentStage, "inspect_information", doneWhen = currentStage == "summarize"),
                    constraints.objectiveHint ?: if (wantsInfo) "需要额外信息" else "可直接给结论",
                ),
                TaskPlanStep(
                    "summarize",
                    "基于证据给出建议",
                    when (currentStage) {
                        "summarize" -> "ready"
                        else -> "pending"
                    },
                    constraints.normalizedGoal,
                ),
            )

        return TaskPlanState(
            planType = "shopping_research",
            currentStage = currentStage,
            stageSummary = "围绕 ${constraints.normalizedGoal.ifBlank { task }} 完成搜索、筛选、查看证据并总结。",
            nextObjective =
                when (currentStage) {
                    "launch_target_app" -> "先切到目标购物 App。"
                    "enter_query" -> "先把任务目标输入到主要搜索框。"
                    "submit_query" -> "触发搜索，进入结果列表。"
                    "browse_candidates" -> "从结果页打开更相关的商品详情。"
                    "inspect_information" -> if (wantsInfo) "补充查看评价或参数证据。" else "如果证据足够，可以开始总结。"
                    "summarize" -> "整理已读到的证据并结束任务。"
                    else -> "先理解当前页面与任务的关系。"
                },
            completionSignal = "已经进入商品详情/评价/参数页，并拿到足够证据形成结论。",
            steps = steps,
        )
    }

    private fun buildNavigationPlan(
        task: String,
        constraints: TaskConstraints,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
    ): TaskPlanState {
        val destination = constraints.destination ?: constraints.entryQuery.ifBlank { task }
        val enteredDestination = hasEnteredQuery(observation, history, destination)
        val currentStage =
            when {
                observation.packageName.isBlank() -> "launch_target_app"
                isSearchEntryPage(observation) && !enteredDestination -> "enter_destination"
                hasRouteCue(observation) -> "confirm_route"
                enteredDestination -> "submit_query"
                else -> "observe"
            }
        return TaskPlanState(
            planType = "navigation",
            currentStage = currentStage,
            stageSummary = "先输入目的地 ${destination.ifBlank { "目标地点" }}，再确认路线并进入导航主流程。",
            nextObjective =
                when (currentStage) {
                    "launch_target_app" -> "先切到地图 App。"
                    "enter_destination" -> "输入目的地并触发搜索。"
                    "submit_query" -> "等待结果页出现并进入路线入口。"
                    "confirm_route" -> "进入路线或导航入口。"
                    else -> "识别当前是否已经在路线页。"
                },
            completionSignal = "已进入路线/导航主流程页面。",
            steps =
                listOf(
                    TaskPlanStep("launch", "进入地图 App", stepStatus(observation.packageName.isNotBlank())),
                    TaskPlanStep(
                        "search",
                        "输入目的地",
                        statusForStage(currentStage, "enter_destination", "submit_query", doneWhen = hasRouteCue(observation)),
                        destination,
                    ),
                    TaskPlanStep(
                        "route",
                        "进入路线页",
                        when (currentStage) {
                            "confirm_route" -> "in_progress"
                            else -> "pending"
                        },
                    ),
                ),
        )
    }

    private fun buildMessagingPlan(
        task: String,
        constraints: TaskConstraints,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
    ): TaskPlanState {
        val recipient = constraints.recipientName ?: constraints.entryQuery.ifBlank { task }
        val messageBody = constraints.messageBody
        val recipientVisible = recipient.isNotBlank() && observationSemantics(observation).contains(recipient, ignoreCase = true)
        val composeReady = hasComposeCue(observation)
        val hasTypedSomething = history.any { it.action.startsWith("set_text(") }
        val typedMessage =
            hasTypedSomething &&
                messageBody?.isNotBlank() == true &&
                observationSemantics(observation).contains(messageBody, ignoreCase = true)
        val currentStage =
            when {
                observation.packageName.isBlank() -> "launch_target_app"
                composeReady && (recipientVisible || hasTypedSomething) -> "confirm_send"
                isSearchEntryPage(observation) || !recipientVisible -> "find_conversation"
                typedMessage -> "confirm_send"
                else -> "observe"
            }
        return TaskPlanState(
            planType = "messaging",
            currentStage = currentStage,
            stageSummary =
                buildString {
                    append("先找到联系人/会话")
                    if (recipient.isNotBlank()) {
                        append(" ").append(recipient)
                    }
                    append("，再确认发送内容")
                    if (!messageBody.isNullOrBlank()) {
                        append(" ").append(messageBody)
                    }
                    append("，最后发送或结束。")
                },
            nextObjective =
                if (currentStage == "confirm_send") {
                    "核对发送内容和发送入口。"
                } else {
                    "先找到目标联系人或会话${recipient.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}。"
                },
            completionSignal = "已进入目标会话并准备好消息。",
            steps =
                listOf(
                    TaskPlanStep("launch", "进入通讯 App", stepStatus(observation.packageName.isNotBlank())),
                    TaskPlanStep(
                        "find",
                        "找到联系人或会话",
                        statusForStage(currentStage, "find_conversation", doneWhen = composeReady && recipientVisible),
                        recipient,
                    ),
                    TaskPlanStep(
                        "compose",
                        "输入内容并确认发送",
                        when (currentStage) {
                            "confirm_send" -> "in_progress"
                            else -> "pending"
                        },
                        messageBody.orEmpty(),
                    ),
                ),
        )
    }

    private fun buildContentPlan(
        task: String,
        constraints: TaskConstraints,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
    ): TaskPlanState {
        val enteredQuery = hasEnteredQuery(observation, history, constraints.entryQuery)
        val currentStage =
            when {
                observation.packageName.isBlank() -> "launch_target_app"
                isSearchEntryPage(observation) && !enteredQuery -> "enter_query"
                isSearchEntryPage(observation) && enteredQuery -> "submit_query"
                isResultLikePage(observation) || isFeedLikePage(observation) -> "browse_candidates"
                isContentDetailLikePage(observation) -> "inspect_information"
                hasContentObjectiveCue(observation, constraints) -> "summarize"
                else -> "observe"
            }

        val steps =
            listOf(
                TaskPlanStep("launch", "进入目标内容 App", stepStatus(observation.packageName.isNotBlank())),
                TaskPlanStep(
                    "query",
                    "搜索目标内容",
                    statusForStage(currentStage, "enter_query", "submit_query", doneWhen = currentStage in setOf("browse_candidates", "inspect_information", "summarize")),
                    constraints.entryQuery,
                ),
                TaskPlanStep(
                    "browse",
                    "定位目标内容",
                    statusForStage(currentStage, "browse_candidates", doneWhen = currentStage in setOf("inspect_information", "summarize")),
                    constraints.normalizedGoal,
                ),
                TaskPlanStep(
                    "inspect",
                    "读取评论或关键信息",
                    statusForStage(currentStage, "inspect_information", doneWhen = currentStage == "summarize"),
                    constraints.objectiveHint.orEmpty(),
                ),
                TaskPlanStep(
                    "summarize",
                    "整理内容证据并收口",
                    if (currentStage == "summarize") "ready" else "pending",
                    constraints.objectiveHint ?: constraints.normalizedGoal,
                ),
            )

        return TaskPlanState(
            planType = "content_research",
            currentStage = currentStage,
            stageSummary = "围绕 ${constraints.entryQuery.ifBlank { task }} 定位目标内容，并读取 ${constraints.objectiveHint ?: "关键信息"}。",
            nextObjective =
                when (currentStage) {
                    "launch_target_app" -> "先切到目标内容 App。"
                    "enter_query" -> "先搜索目标作者、栏目或主题。"
                    "submit_query" -> "触发查询，进入候选内容列表。"
                    "browse_candidates" -> "打开最贴近任务目标的内容对象。"
                    "inspect_information" -> "进入评论区、详情页或正文，读取目标证据。"
                    "summarize" -> "整理读取到的内容证据并结束任务。"
                    else -> "先判断当前页面与目标内容的关系。"
                },
            completionSignal = "已进入目标内容或评论区，并拿到可用于回答任务的问题证据。",
            steps = steps,
        )
    }

    private fun buildGenericPlan(
        task: String,
        constraints: TaskConstraints,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
    ): TaskPlanState {
        val enteredQuery = hasEnteredQuery(observation, history, constraints.entryQuery)
        val currentStage =
            when {
                observation.packageName.isBlank() -> "launch_target_app"
                constraints.entryQuery.isNotBlank() && isSearchEntryPage(observation) && !enteredQuery -> "enter_query"
                constraints.entryQuery.isNotBlank() && isSearchEntryPage(observation) && enteredQuery -> "submit_query"
                isResultLikePage(observation) || isFeedLikePage(observation) -> "browse_candidates"
                isDetailLikePage(observation) || hasContentObjectiveCue(observation, constraints) -> "inspect_information"
                history.isEmpty() -> "understand_task"
                else -> "continue_execution"
            }
        return TaskPlanState(
            planType = "generic",
            currentStage = currentStage,
            stageSummary = "围绕任务逐步进入目标 App、定位入口、完成关键信息读取或动作执行。",
            nextObjective =
                when (currentStage) {
                    "launch_target_app" -> "先进入目标 App。"
                    "enter_query" -> "优先把目标词输入当前入口。"
                    "submit_query" -> "触发查询，等待列表或结果页出现。"
                    "browse_candidates" -> "从候选入口中挑选更贴近任务目标的对象。"
                    "inspect_information" -> "确认当前页面是否已经包含任务所需信息。"
                    "understand_task" -> "先识别当前页面与任务之间的映射关系。"
                    else -> "根据当前页面识别下一步最小动作。"
                },
            completionSignal = "页面状态与任务要求对齐，且已经拿到足够结果或完成关键动作。",
            steps =
                listOf(
                    TaskPlanStep("task", "理解任务目标", if (task.isNotBlank()) "done" else "pending", task),
                    TaskPlanStep(
                        "enter",
                        "进入目标流程",
                        when (currentStage) {
                            "launch_target_app" -> "pending"
                            "understand_task" -> "in_progress"
                            else -> "done"
                        },
                        observation.packageName,
                    ),
                    TaskPlanStep(
                        "act",
                        "完成关键动作或获取结果",
                        when (currentStage) {
                            "browse_candidates", "inspect_information", "continue_execution" -> "in_progress"
                            else -> "pending"
                        },
                        constraints.objectiveHint ?: constraints.entryQuery,
                    ),
                ),
        )
    }

    private fun resolvePlanFamily(
        constraints: TaskConstraints,
        profileId: String,
    ): PlanFamily =
        when (constraints.intentType) {
            TaskIntentType.SHOPPING -> PlanFamily.SHOPPING
            TaskIntentType.NAVIGATION -> PlanFamily.NAVIGATION
            TaskIntentType.MESSAGING -> PlanFamily.MESSAGING
            TaskIntentType.CONTENT -> PlanFamily.CONTENT
            TaskIntentType.GENERIC -> inferProfileFamily(profileId)
        }

    private fun inferProfileFamily(
        profileId: String,
    ): PlanFamily {
        val lowerProfile = profileId.lowercase()
        return when {
            listOf("taobao", "jd", "pdd", "xianyu", "idlefish").any(lowerProfile::contains) -> PlanFamily.SHOPPING
            listOf("amap", "autonavi", "map").any(lowerProfile::contains) -> PlanFamily.NAVIGATION
            listOf("wechat", "tencent.mm", "message", "sms").any(lowerProfile::contains) -> PlanFamily.MESSAGING
            listOf("bili", "bilibili", "xiaohongshu", "douyin", "kuaishou", "zhihu", "weibo").any(lowerProfile::contains) -> PlanFamily.CONTENT
            else -> PlanFamily.GENERIC
        }
    }

    private fun isSearchEntryPage(
        observation: ScreenObservation,
    ): Boolean =
        observation.primaryEditableId != null ||
            listOf("搜索", "查找", "搜索框", "输入", "目的地", "联系人", "会话").any {
                observationSemantics(observation).contains(it, ignoreCase = true)
            }

    private fun hasRouteCue(
        observation: ScreenObservation,
    ): Boolean =
        listOf("路线", "导航", "到这去", "开始导航", "预计", "公里", "分钟").any {
            observationSemantics(observation).contains(it, ignoreCase = true)
        }

    private fun hasComposeCue(
        observation: ScreenObservation,
    ): Boolean =
        listOf("发送", "输入消息", "发消息", "回车发送", "按住说话").any {
            observationSemantics(observation).contains(it, ignoreCase = true)
        }

    private fun hasContentObjectiveCue(
        observation: ScreenObservation,
        constraints: TaskConstraints,
    ): Boolean {
        val semantics = observationSemantics(observation)
        return listOfNotNull(
            constraints.objectiveHint,
            "评论",
            "评论区",
            "热评",
            "高赞",
            "弹幕",
            "正文",
            "简介",
            "详情",
        ).any { semantics.contains(it, ignoreCase = true) }
    }

    private fun isResultLikePage(
        observation: ScreenObservation,
    ): Boolean =
        listOf("RESULT", "LIST", "GRID").any { observation.pageState.uppercase().contains(it) }

    private fun isFeedLikePage(
        observation: ScreenObservation,
    ): Boolean =
        observation.pageState.uppercase().contains("FEED") ||
            listOf("推荐", "首页", "关注", "视频", "结果", "列表").any {
                observation.screenSummary.contains(it, ignoreCase = true) ||
                    observation.topTexts.any { text -> text.contains(it, ignoreCase = true) }
            }

    private fun isDetailLikePage(
        observation: ScreenObservation,
    ): Boolean =
        listOf("DETAIL", "REVIEW", "PARAM", "ARTICLE", "PLAYER", "CHAT").any {
            observation.pageState.uppercase().contains(it)
        } || listOf("评价", "评论", "参数", "规格", "详情", "正文", "聊天").any {
            observationSemantics(observation).contains(it, ignoreCase = true)
        }

    private fun isContentDetailLikePage(
        observation: ScreenObservation,
    ): Boolean =
        listOf("DETAIL", "ARTICLE", "PLAYER", "COMMENT").any {
            observation.pageState.uppercase().contains(it)
        } || listOf("评论", "评论区", "热评", "高赞", "弹幕", "播放", "正文", "简介").any {
            observationSemantics(observation).contains(it, ignoreCase = true)
        }

    private fun hasEnteredQuery(
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        query: String,
    ): Boolean {
        if (query.isBlank()) return false
        return history.any { it.action.startsWith("set_text(") } ||
            observationSemantics(observation).contains(query, ignoreCase = true)
    }

    private fun observationSemantics(
        observation: ScreenObservation,
    ): String =
        buildString {
            append(observation.pageState).append(' ')
            append(observation.screenSummary).append(' ')
            append(observation.topTexts.joinToString(" ")).append(' ')
            append(observation.elements.joinToString(" ") { element -> "${element.text} ${element.viewId}" })
        }

    private fun wantsInformation(
        constraints: TaskConstraints,
    ): Boolean =
        constraints.objectiveHint in setOf("评价", "评论", "口碑", "参数", "规格", "配置", "测评")

    private fun stepStatus(
        done: Boolean,
    ): String = if (done) "done" else "pending"

    private fun statusForStage(
        currentStage: String,
        vararg activeStages: String,
        doneWhen: Boolean = false,
    ): String =
        when {
            doneWhen -> "done"
            currentStage in activeStages -> "in_progress"
            else -> "pending"
        }
}

private enum class PlanFamily {
    SHOPPING,
    NAVIGATION,
    MESSAGING,
    CONTENT,
    GENERIC,
}
