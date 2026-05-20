package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.skills.SkillExecutionEngine
import com.lmx.xiaoxuanagent.taskprofile.TaskProfile

data class RecoveryRefinementResult(
    val policyDecision: AgentDecision,
    val finalDecision: AgentDecision,
    val policyChanged: Boolean,
    val skillChanged: Boolean,
)

data class RecoveryPlanHint(
    val diagnosis: RecoveryDiagnosis?,
    val recoveryDecision: AgentDecision?,
)

object RecoveryEngine {
    private val permissionKeywords =
        listOf("权限", "允许", "访问", "位置", "通知", "相机", "麦克风", "照片", "存储", "通讯录")
    private val softPopupKeywords =
        listOf("跳过", "关闭", "取消", "暂不", "稍后", "以后再说", "知道了", "我知道了")
    private val deepContextKeywords =
        listOf(
            "详情",
            "评论",
            "评价",
            "参数",
            "规格",
            "输入消息",
            "发送",
            "聊天",
            "路线",
            "导航",
            "视频",
            "文章",
            "直播",
            "播放",
            "正文",
            "个人主页",
        )

    fun resolveEarlySkillDecision(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord>,
    ): AgentDecision? =
        SkillExecutionEngine.prePlanDecision(
            task = task,
            profileId = profileId,
            observation = observation,
            memoryContext = memoryContext,
            taskPlanState = taskPlanState,
            activeSkillIds = activeSkillIds,
            history = history,
        )

    fun resolveSystemIntervention(
        task: String,
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext,
    ): AgentDecision? =
        SystemInterventionEngine.decide(
            task = task,
            observation = observation,
            visualContext = visualContext,
        )

    fun refineDecision(
        task: String,
        profile: TaskProfile,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        decision: AgentDecision,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord>,
    ): RecoveryRefinementResult {
        val policyDecision =
            profile.refineDecision(
                task = task,
                observation = observation,
                decision = decision,
            )
        val finalDecision =
            SkillExecutionEngine.refineDecision(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                decision = policyDecision,
                activeSkillIds = activeSkillIds,
                history = history,
            )
        return RecoveryRefinementResult(
            policyDecision = policyDecision,
            finalDecision = finalDecision,
            policyChanged = policyDecision != decision,
            skillChanged = finalDecision != policyDecision,
        )
    }

    fun diagnoseExecutionFailure(
        task: String,
        targetPackageName: String,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        afterObservation: ScreenObservation?,
        executionResult: AgentExecutionResult? = null,
        history: List<AgentTurnRecord>,
        finishRejected: Boolean = false,
    ): RecoveryDiagnosis? {
        if (finishRejected) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.FINISH_REJECTED,
                summary = "当前页面尚未满足完成条件，不能提前结束任务。",
            )
        }

        if (afterObservation == null || afterObservation.elements.isEmpty()) {
            return RecoveryDiagnosis(
                category =
                    if (
                        beforeObservation.elements.isNotEmpty() &&
                        executionResult?.groundingSource?.contains("visual", ignoreCase = true) == true
                    ) {
                        RecoveryCategory.PARTIAL_TREE_MISS
                    } else {
                        RecoveryCategory.EMPTY_OBSERVATION
                    },
                summary =
                    if (
                        beforeObservation.elements.isNotEmpty() &&
                        executionResult?.groundingSource?.contains("visual", ignoreCase = true) == true
                    ) {
                        "动作后只拿到了局部或失真的页面树，像是树结构不稳定或视觉落点后页面未完成加载。"
                    } else {
                        "动作后没有拿到稳定的无障碍页面树，先等待页面稳定再继续。"
                    },
                suggestedAction = AgentAction.Wait,
            )
        }

        val semantics = observationSemantics(afterObservation)
        val constraints = TaskIntentParser.parse(task)
        val entityDescriptor = GroundingEntityFingerprint.fromTaskConstraints(constraints, executionResult)
        val expectedTargetText = entityDescriptor.primary
        val targetContextHints = entityDescriptor.contextTokens
        if (
            actionCouldChangeFocusedEntity(action) &&
            (expectedTargetText.isNotBlank() || targetContextHints.isNotEmpty()) &&
            beforeObservation.packageName == afterObservation.packageName
        ) {
            val beforeEntityMatch = GroundingEntityFingerprint.matchObservation(beforeObservation, entityDescriptor)
            val afterEntityMatch = GroundingEntityFingerprint.matchObservation(afterObservation, entityDescriptor)
            val beforeContainsTarget = observationContainsTarget(beforeObservation, expectedTargetText)
            val afterContainsTarget = observationContainsTarget(afterObservation, expectedTargetText)
            val beforeContextScore = observationContextScore(beforeObservation, targetContextHints)
            val afterContextScore = observationContextScore(afterObservation, targetContextHints)
            if (
                (beforeEntityMatch.verified && !afterEntityMatch.verified) ||
                (beforeContainsTarget && !afterContainsTarget) ||
                (beforeContextScore >= 2 && afterContextScore == 0)
            ) {
                return RecoveryDiagnosis(
                    category =
                        when {
                            executionResult?.groundingSource?.contains("visual", ignoreCase = true) == true ->
                                RecoveryCategory.VISUAL_FALSE_POSITIVE

                            executionResult?.resolvedContainerId?.isNotBlank() == true ->
                                RecoveryCategory.WRONG_LIST_ITEM

                            else -> RecoveryCategory.TARGET_MISMATCH
                        },
                    summary =
                        when {
                            executionResult?.groundingSource?.contains("visual", ignoreCase = true) == true ->
                                "动作后页面虽然变化，但未能通过实体指纹确认视觉候选“$expectedTargetText”，像是视觉命中出现了误判。"

                            executionResult?.resolvedContainerId?.isNotBlank() == true ->
                                "动作后进入了错误的列表项或相邻容器，目标“$expectedTargetText”未能通过实体指纹继续确认。"

                            else ->
                                "动作后未能通过实体指纹确认目标“$expectedTargetText”，像是点击到了错误对象。"
                        },
                    suggestedAction =
                        AgentSemanticActionComposer.backtrackAction(
                            observation = afterObservation,
                            hint = "target_mismatch",
                        ),
                )
            }
        }
        if (permissionKeywords.any { semantics.contains(it, ignoreCase = true) }) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.PERMISSION_REQUIRED,
                summary = "当前页面更像权限或系统授权提示，需要先处理系统弹窗。",
            )
        }

        if (
            afterObservation.interruptiveHints.isNotEmpty() ||
            softPopupKeywords.any { semantics.contains(it, ignoreCase = true) }
        ) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.POPUP_BLOCKING,
                summary = "当前页面存在遮挡主流程的弹窗或引导，需要先清理干扰。",
            )
        }

        if (
            targetPackageName.isNotBlank() &&
            afterObservation.packageName.isNotBlank() &&
            afterObservation.packageName != targetPackageName
        ) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.WRONG_CONTEXT,
                summary = "动作后进入了非目标前台 ${afterObservation.packageName}，需要纠正回目标上下文。",
                suggestedAction = AgentAction.LaunchApp(targetPackageName),
            )
        }

        if (looksLikeRepeatedBacktrack(history, action)) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.REPEATED_BACKTRACK,
                summary = "最近几轮出现了点击后又返回的往返路径，需要换一条探索路径。",
                suggestedAction = AgentAction.ScrollForMore("down"),
            )
        }

        if (looksLikeOverscrolled(task, action, beforeObservation, afterObservation)) {
            return RecoveryDiagnosis(
                category = RecoveryCategory.OVERSCROLLED,
                summary = "滚动后任务相关锚点明显减少，像是滚动过头了，下一轮应回拉或改用其他入口。",
                suggestedAction = AgentAction.ScrollForMore("up"),
            )
        }

        return RecoveryDiagnosis(
            category = RecoveryCategory.NO_PROGRESS,
            summary = "动作后页面没有产生足够进展，需要更换动作类型或候选目标。",
                suggestedAction =
                    when {
                        afterObservation.defaultScrollableId != null && action !is AgentAction.Scroll ->
                            AgentAction.ScrollForMore("down")

                        else -> AgentAction.Wait
                    },
        )
    }

    fun diagnoseSemanticDetour(
        task: String,
        taskPlanState: TaskPlanState?,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        afterObservation: ScreenObservation,
        executionResult: AgentExecutionResult? = null,
        history: List<AgentTurnRecord>,
    ): RecoveryDiagnosis? {
        if (
            action !is AgentAction.Click &&
            action !is AgentAction.LongClick &&
            action !is AgentAction.TapPoint &&
            action != AgentAction.PressEnter
        ) {
            return null
        }
        if (afterObservation.packageName != beforeObservation.packageName) {
            return null
        }
        if (
            afterObservation.signature == beforeObservation.signature &&
            afterObservation.pageState == beforeObservation.pageState
        ) {
            return null
        }

        val beforeScore = taskCueScore(task, beforeObservation)
        val afterScore = taskCueScore(task, afterObservation)
        val stage = taskPlanState?.currentStage.orEmpty()
        val afterSemantics = observationSemantics(afterObservation)
        val constraints = TaskIntentParser.parse(task)
        val entityDescriptor = GroundingEntityFingerprint.fromTaskConstraints(constraints, executionResult)
        val expectedTargetText = entityDescriptor.primary
        val targetContextHints = entityDescriptor.contextTokens
        val enteredDeepContext =
            afterObservation.pageState.uppercase().let { state ->
                listOf("DETAIL", "CHAT", "REVIEW", "PARAM", "ROUTE", "PLAYER", "ARTICLE").any { state.contains(it) }
            } || deepContextKeywords.any { afterSemantics.contains(it, ignoreCase = true) }
        val stillHasEntryCue =
            listOf("搜索", "查找", "联系人", "输入", "目的地").any {
                afterSemantics.contains(it, ignoreCase = true)
            }
        val likelyDetour =
            enteredDeepContext &&
                afterScore == 0 &&
                (beforeScore > 0 || stage in setOf("find_conversation", "enter_query", "enter_destination", "browse_candidates"))
        if (!likelyDetour) {
            return null
        }
        if (history.lastOrNull()?.recoveryCategory == RecoveryCategory.SUBGOAL_MISMATCH.name) {
            return null
        }
        val summary =
            when {
                stillHasEntryCue ->
                    "动作后虽然进入了更深页面，但当前语义仍停留在入口/搜索阶段，像是误入了无关流程。"

                stage.isNotBlank() ->
                    "动作后进入了与当前子目标 $stage 不一致的页面上下文，建议先退出当前页面再重新定位。"

                else ->
                    "动作后进入了与当前任务语义不一致的页面上下文，建议先退出当前页面再继续。"
            }
        return RecoveryDiagnosis(
            category =
                if (
                    !GroundingEntityFingerprint.matchObservation(afterObservation, entityDescriptor).verified &&
                    (
                        (expectedTargetText.isNotBlank() && !observationContainsTarget(afterObservation, expectedTargetText)) ||
                            (targetContextHints.isNotEmpty() && observationContextScore(afterObservation, targetContextHints) == 0)
                    )
                ) {
                    RecoveryCategory.TARGET_MISMATCH
                } else {
                    RecoveryCategory.SUBGOAL_MISMATCH
                },
            summary = summary,
            suggestedAction = AgentSemanticActionComposer.backtrackAction(
                observation = afterObservation,
                hint = "semantic_detour",
            ),
        )
    }

    fun planRecovery(
        task: String,
        profile: TaskProfile,
        profileId: String,
        observation: ScreenObservation,
        visualContext: VisualPerceptionContext = VisualPerceptionContext(),
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord>,
        diagnosis: RecoveryDiagnosis?,
    ): RecoveryPlanHint {
        if (diagnosis == null) {
            return RecoveryPlanHint(diagnosis = null, recoveryDecision = null)
        }

        val systemDecision =
            when (diagnosis.category) {
                RecoveryCategory.PERMISSION_REQUIRED,
                RecoveryCategory.POPUP_BLOCKING,
                -> resolveSystemIntervention(task, observation, visualContext)

                else -> null
            }
        if (systemDecision != null) {
            return RecoveryPlanHint(diagnosis = diagnosis, recoveryDecision = systemDecision)
        }

        val skillDecision =
            SkillExecutionEngine.recover(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                activeSkillIds = activeSkillIds,
                history = history,
                diagnosis = diagnosis,
            )
        if (skillDecision != null) {
            return RecoveryPlanHint(diagnosis = diagnosis, recoveryDecision = skillDecision)
        }

        val stageAwareDecision =
            stageAwareRecoveryDecision(
                task = task,
                observation = observation,
                taskPlanState = taskPlanState,
                diagnosis = diagnosis,
            )
        if (stageAwareDecision != null) {
            return RecoveryPlanHint(diagnosis = diagnosis, recoveryDecision = stageAwareDecision)
        }

        val profileDecision =
            profile.refineDecision(
                task = task,
                observation = observation,
                decision =
                    diagnosis.suggestedAction?.let {
                        AgentDecision(
                            action = it,
                            reason = "恢复建议：${diagnosis.summary}",
                            rawResponse = """{"recovery":"${diagnosis.category.name.lowercase()}","action":"${it.label}"}""",
                        )
                    } ?: AgentDecision(
                        action = AgentAction.Wait,
                        reason = "恢复建议：${diagnosis.summary}",
                        rawResponse = """{"recovery":"${diagnosis.category.name.lowercase()}","action":"wait()"}""",
                    ),
            )

        return RecoveryPlanHint(
            diagnosis = diagnosis,
            recoveryDecision = profileDecision,
        )
    }

    private fun stageAwareRecoveryDecision(
        task: String,
        observation: ScreenObservation,
        taskPlanState: TaskPlanState,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? {
        val constraints = TaskIntentParser.parse(task)
        val stage = taskPlanState.currentStage
        val stageAction =
            when (stage) {
                "enter_query", "enter_destination", "find_conversation" ->
                    entryStageRecoveryAction(observation, constraints)

                "submit_query" ->
                    submitStageRecoveryAction(observation)

                "browse_candidates" ->
                    browseStageRecoveryAction(observation, constraints)

                "inspect_information", "summarize" ->
                    inspectStageRecoveryAction(observation, constraints)

                "confirm_route" -> AgentAction.SubmitPrimaryAction("导航")

                else -> null
            } ?: return null

        return AgentDecision(
            action = stageAction,
            reason = "阶段恢复：${diagnosis.summary} 当前阶段=$stage，优先执行更贴近子目标的恢复动作。",
            rawResponse = """{"recovery":"${diagnosis.category.name.lowercase()}","stage":"$stage","action":"${stageAction.label}"}""",
        )
    }

    private fun observationSemantics(
        observation: ScreenObservation,
    ): String = AgentSemanticActionComposer.observationSemantics(observation)

    private fun looksLikeRepeatedBacktrack(
        history: List<AgentTurnRecord>,
        action: AgentAction,
    ): Boolean {
        if (history.size < 3 || action !is AgentAction.Click) return false
        val recent = history.takeLast(3)
        val backCount = recent.count { it.action == AgentAction.Back.label }
        val repeatedFailure =
            recent.any { it.recoveryCategory == RecoveryCategory.REPEATED_BACKTRACK.name }
        return backCount >= 1 && repeatedFailure
    }

    private fun looksLikeOverscrolled(
        task: String,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        afterObservation: ScreenObservation,
    ): Boolean {
        if (action !is AgentAction.Scroll && action !is AgentAction.Swipe) return false
        val constraints = TaskIntentParser.parse(task)
        val keywords =
            constraints.keywordHints + listOfNotNull(
                constraints.entryQuery.takeIf { it.isNotBlank() },
                constraints.recipientName?.takeIf { it.isNotBlank() },
                constraints.destination?.takeIf { it.isNotBlank() },
            )
        if (keywords.isEmpty()) return false

        val beforeScore = matchTaskCueScore(beforeObservation, keywords)
        val afterScore = matchTaskCueScore(afterObservation, keywords)
        val pageChanged = beforeObservation.signature != afterObservation.signature
        return pageChanged && beforeScore >= 1 && afterScore == 0
    }

    private fun matchTaskCueScore(
        observation: ScreenObservation,
        keywords: List<String>,
    ): Int {
        val semantics = observationSemantics(observation)
        return keywords.distinct().count { keyword ->
            keyword.isNotBlank() && semantics.contains(keyword, ignoreCase = true)
        }
    }

    private fun taskCueScore(
        task: String,
        observation: ScreenObservation,
    ): Int {
        val constraints = TaskIntentParser.parse(task)
        val cues =
            listOfNotNull(
                constraints.entryQuery.takeIf { it.isNotBlank() },
                constraints.normalizedGoal.takeIf { it.isNotBlank() },
                constraints.destination?.takeIf { it.isNotBlank() },
                constraints.recipientName?.takeIf { it.isNotBlank() },
                constraints.objectiveHint?.takeIf { it.isNotBlank() },
            ) + constraints.keywordHints
        return matchTaskCueScore(observation, cues.distinct())
    }

    private fun actionCouldChangeFocusedEntity(
        action: AgentAction,
    ): Boolean =
        action is AgentAction.Click ||
            action is AgentAction.LongClick ||
            action is AgentAction.TapPoint ||
            action is AgentAction.OpenBestCandidate ||
            action is AgentAction.NavigateBack

    private fun observationContainsTarget(
        observation: ScreenObservation,
        targetText: String,
    ): Boolean {
        if (targetText.isBlank()) return false
        val semantics = observationSemantics(observation)
        if (semantics.contains(targetText, ignoreCase = true)) return true
        return observation.elements.any { element ->
            element.text.contains(targetText, ignoreCase = true) ||
                element.neighborTexts.any { it.contains(targetText, ignoreCase = true) }
        }
    }

    private fun observationContextScore(
        observation: ScreenObservation,
        contextHints: List<String>,
    ): Int {
        if (contextHints.isEmpty()) return 0
        val semantics = observationSemantics(observation)
        return contextHints
            .map(String::trim)
            .filter { it.length >= 2 }
            .distinct()
            .count { hint -> semantics.contains(hint, ignoreCase = true) }
    }

    private fun entryStageRecoveryAction(
        observation: ScreenObservation,
        constraints: TaskConstraints,
    ): AgentAction? {
        val entryTarget = constraints.entryQuery
        return AgentSemanticActionComposer.entryAlignmentAction(
            targetText = entryTarget,
            stage = "enter_query",
            observation = observation,
        )
    }

    private fun submitStageRecoveryAction(
        observation: ScreenObservation,
    ): AgentAction? =
        if (observation.primaryEditableId != null) {
            AgentAction.SubmitPrimaryAction("搜索")
        } else {
            AgentAction.OpenBestCandidate(targetText = "搜索", roleHint = "submit")
        }

    private fun browseStageRecoveryAction(
        observation: ScreenObservation,
        constraints: TaskConstraints,
    ): AgentAction? {
        val candidate =
            findBestCandidateByKeywords(
                observation = observation,
                keywords = buildBrowseKeywords(constraints),
                negativeKeywords = listOf("综合", "筛选", "更多", "店铺", "品牌", "频道", "首页"),
            )
        if (candidate != null) {
            return AgentAction.OpenBestCandidate(
                targetText = candidate.text,
                roleHint = "entry",
            )
        }
        return observation.defaultScrollableId?.let { AgentAction.ScrollForMore("down") }
    }

    private fun inspectStageRecoveryAction(
        observation: ScreenObservation,
        constraints: TaskConstraints,
    ): AgentAction? {
        val infoTarget =
            findElementByKeywords(
                observation = observation,
                keywords = buildInspectKeywords(constraints),
            )
        if (infoTarget != null) {
            return AgentAction.OpenBestCandidate(
                targetText = infoTarget.text,
                roleHint = "detail",
            )
        }
        return observation.defaultScrollableId?.let { AgentAction.ScrollForMore("down") }
    }

    private fun buildBrowseKeywords(
        constraints: TaskConstraints,
    ): List<String> =
        listOfNotNull(
            constraints.entryQuery.takeIf { it.isNotBlank() },
            constraints.normalizedGoal.takeIf { it.isNotBlank() },
            constraints.recipientName?.takeIf { it.isNotBlank() },
            constraints.destination?.takeIf { it.isNotBlank() },
            constraints.objectiveHint?.takeIf { it.isNotBlank() && it.length <= 6 },
        ) + constraints.keywordHints

    private fun buildInspectKeywords(
        constraints: TaskConstraints,
    ): List<String> =
        buildList {
            constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let(::add)
            when (constraints.intentType) {
                TaskIntentType.SHOPPING -> addAll(listOf("评价", "评论", "参数", "规格", "详情"))
                TaskIntentType.CONTENT -> {
                    addAll(listOf("评论", "评论区", "热评", "高赞", "弹幕", "详情", "正文"))
                    if (constraints.originalTask.contains("高赞") || constraints.originalTask.contains("热评")) {
                        addAll(listOf("高赞", "最热", "热评"))
                    }
                }

                else -> {}
            }
        }.distinct()

    private fun findElementByKeywords(
        observation: ScreenObservation,
        keywords: List<String>,
    ): UiElementObservation? =
        observation.elements
            .asSequence()
            .filter { it.clickable && it.enabled }
            .mapNotNull { element ->
                val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
                val score =
                    keywords.fold(0) { acc, keyword ->
                        when {
                            keyword.isBlank() -> acc
                            element.text.equals(keyword, ignoreCase = true) -> acc + 90
                            element.text.contains(keyword, ignoreCase = true) -> acc + 50
                            semantic.contains(keyword, ignoreCase = true) -> acc + 24
                            else -> acc
                        }
                    }
                if (score <= 0) null else element to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull()

    private fun findBestCandidateByKeywords(
        observation: ScreenObservation,
        keywords: List<String>,
        negativeKeywords: List<String>,
    ): UiElementObservation? =
        observation.elements
            .asSequence()
            .filter { it.clickable && it.enabled }
            .mapNotNull { element ->
                val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
                var score = 0
                keywords.forEach { keyword ->
                    when {
                        keyword.isBlank() -> Unit
                        element.text.equals(keyword, ignoreCase = true) -> score += 120
                        element.text.contains(keyword, ignoreCase = true) -> score += 75
                        semantic.contains(keyword, ignoreCase = true) -> score += 35
                    }
                }
                negativeKeywords.forEach { keyword ->
                    if (semantic.contains(keyword, ignoreCase = true)) {
                        score -= keyword.length * 20
                    }
                }
                if (element.text.length >= 8) score += 10
                if (score <= 0) null else element to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull()
}
