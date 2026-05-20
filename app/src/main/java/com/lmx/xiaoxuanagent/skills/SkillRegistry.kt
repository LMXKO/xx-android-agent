package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.interpretHumanCorrection
import com.lmx.xiaoxuanagent.agent.ResumeCorrectionDirective
import com.lmx.xiaoxuanagent.agent.resolveResumeCorrectionDirective
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.SkillLayer
import com.lmx.xiaoxuanagent.agent.SkillRiskLevel
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.UiElementObservation

data class SkillSpec(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val keywords: List<String>,
    val profileIds: List<String> = emptyList(),
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
    val layer: SkillLayer = SkillLayer.GUARD,
    val riskLevel: SkillRiskLevel = SkillRiskLevel.LOW,
    val requiredTools: List<String> = emptyList(),
)

object SkillRegistry {
    private val executableSkills: List<ExecutableSkill> = SkillPackRegistry.allSkills()
    private val catalog: SkillCatalog = SkillCatalogFactory.build(executableSkills)

    fun resolve(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        history: List<AgentTurnRecord> = emptyList(),
    ): List<SkillContext> {
        val context =
            SkillDecisionContext(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                history = history,
            )
        return resolveExecutors(task, profileId)
            .filter { skill -> skill.shouldActivate(context) }
            .map { skill ->
            SkillContext(
                id = skill.spec.id,
                title = skill.spec.title,
                description = skill.spec.description,
                instructions = skill.spec.instructions,
                layer = skill.spec.layer,
                riskLevel = skill.spec.riskLevel,
                requiredTools = skill.spec.requiredTools,
                parameters = skill.extractParameters(context),
            )
        }
    }

    fun resolveExecutors(
        task: String,
        profileId: String,
    ): List<ExecutableSkill> {
        val config = SkillRegistryConfigStore.read()
        return catalog.resolveEntries(task, profileId)
            .map { it.executable }
            .filterNot { it.spec.id in config.disabledSkillIds }
            .sortedWith(
                compareByDescending<ExecutableSkill> { it.spec.id in config.pinnedSkillIds }
                    .thenByDescending { it.spec.priority + (config.priorityBoosts[it.spec.id] ?: 0) },
            )
    }

    fun getByIds(
        ids: Collection<String>,
    ): List<ExecutableSkill> = catalog.getByIds(ids)

    fun catalogLines(
        limit: Int = 10,
    ): List<String> = catalog.catalogLines(limit)

    fun providerSummaryLines(
        limit: Int = 6,
    ): List<String> = catalog.providerSummaryLines(limit)

    fun packSummaryLines(
        limit: Int = 8,
    ): List<String> = SkillPackRegistry.packSummaryLines(limit)
}

internal object AppStateGuardSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "app_state_guard",
            title = "前台对齐",
            description = "避免在已经位于目标 App 前台时重复启动同一应用。",
            instructions = "如果当前 observation.package_name 已经等于准备启动的包名，优先等待或继续观察，而不是重复 launch_app。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 28,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.launch_app"),
        )

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision =
        SkillGuardPolicies.rewriteLaunchAppIfAlreadyForeground(decision, context.observation) ?: decision

    override fun extractParameters(context: SkillDecisionContext): List<String> =
        listOf("foreground=${context.observation.packageName.ifBlank { "-" }}")
}

internal object InputTargetGuardSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "input_target_guard",
            title = "输入目标对齐",
            description = "根据当前阶段统一输入目标词，避免把原始整句或过早正文直接写进输入框。",
            instructions = "set_text 前先判断当前阶段更适合输入搜索词、联系人、目的地还是消息正文，不要把整句任务原样输入。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 24,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.set_text"),
        )

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision =
        SkillGuardPolicies.rewriteSetTextToCurrentTarget(
            task = context.task,
            observation = context.observation,
            decision = decision,
        ) ?: decision

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val constraints = TaskIntentParser.parse(context.task)
        return listOfNotNull(
            "intent=${constraints.intentType.name.lowercase()}",
            constraints.entryQuery.takeIf { it.isNotBlank() }?.let { "entry=$it" },
            constraints.recipientName?.takeIf { it.isNotBlank() }?.let { "recipient=$it" },
            constraints.messageBody?.takeIf { it.isNotBlank() }?.let { "message=$it" },
        )
    }
}

internal object HumanCorrectionSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "human_correction",
            title = "人工纠错恢复",
            description = "把用户给出的纠错语句转成通用的小步恢复动作，避免重复犯同类错误。",
            instructions = "如果 resume_context.user_correction 明确指出点错、进错、滚过头、返回上一页、重新找等意图，优先执行最小纠偏动作。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 32,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.back", "gui.scroll", "gui.click", "gui.set_text"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean =
        context.taskPlanState?.resumeContext?.userCorrection?.isNotBlank() == true ||
            rememberedHumanCorrectionTemplates(context).isNotEmpty()

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val correction = context.taskPlanState?.resumeContext?.userCorrection.orEmpty()
        if (correction.isNotBlank()) {
            val correctionDirective = resolveResumeCorrectionDirective(correction)
            val directAction =
                resolveDirectHumanCorrectionAction(
                    context = context,
                    directive = correctionDirective,
                    blockedKeywords = interpretHumanCorrection(correction).blockedTargetKeywords,
                ) ?: return null
            return AgentDecision(
                action = directAction,
                reason =
                    "技能预执行：用户纠错“$correction”，" +
                        correctionDirective.guidance.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty() +
                        "先执行最小纠偏动作 ${directAction.label}。",
                rawResponse =
                    """{"skill":"human_correction","phase":"pre_plan","correction":"${correction.take(80)}","action":"${directAction.label}","correction_intent":"${correctionDirective.intent.name.lowercase()}","correction_policy":"${correctionDirective.policy}"}""",
            )
        }
        val rememberedDirective = resolveRememberedHumanCorrectionDirective(context) ?: return null
        val rememberedAction =
            resolveHumanCorrectionActionFromDirective(
                context = context,
                directive = rememberedDirective,
                blockedKeywords = rememberedBlockedTargetKeywords(context),
            ) ?: return null
        return AgentDecision(
            action = rememberedAction,
            reason =
                "技能预执行：命中历史纠错经验，" +
                    rememberedDirective.guidance.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty() +
                    "先执行最小纠偏动作 ${rememberedAction.label}。",
            rawResponse =
                """{"skill":"human_correction","phase":"pre_plan","memory":"remembered_template","action":"${rememberedAction.label}","correction_intent":"${rememberedDirective.intent.name.lowercase()}","correction_policy":"${rememberedDirective.policy}"}""",
        )
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val correction = context.taskPlanState?.resumeContext?.userCorrection.orEmpty()
        val correctionInterpretation = interpretHumanCorrection(correction)
        val blockedKeywords = (correctionInterpretation.blockedTargetKeywords + rememberedBlockedTargetKeywords(context)).distinct()
        if (correction.isBlank() && blockedKeywords.isEmpty()) {
            return decision
        }
        val clickAction = decision.action as? AgentAction.Click
        if (clickAction != null && blockedKeywords.isNotEmpty()) {
            val target = context.observation.elements.firstOrNull { it.id == clickAction.elementId }
            val semantic = target?.let { listOf(it.text, it.viewId, it.className).joinToString(" ") }.orEmpty()
            if (blockedKeywords.any { semantic.contains(it, ignoreCase = true) }) {
                val replacement = resolveConservativeHumanCorrectionRecoveryAction(context, blockedKeywords)
                return decision.copy(
                    action = replacement,
                    reason =
                        "技能改写：用户纠错要求避开 ${blockedKeywords.joinToString("/")}，" +
                            "不再点击 ${target?.text?.ifBlank { clickAction.elementId } ?: clickAction.elementId}，改为 ${replacement.label}。",
                )
            }
        }
        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val correction = context.taskPlanState?.resumeContext?.userCorrection.orEmpty()
        return listOfNotNull(
            correction.takeIf { it.isNotBlank() }?.let { "correction=${it.take(48)}" },
            rememberedHumanCorrectionTemplates(context)
                .takeIf { it.isNotEmpty() }
                ?.let { templates ->
                    "remembered=${templates.take(2).joinToString("/") { template -> template.templateType + template.argument.takeIf { argument -> argument.isNotBlank() }?.let { argument -> ":$argument" }.orEmpty() }}"
                },
            context.taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { "stage=$it" },
        )
    }

}

internal object ResumeEntryAlignmentSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "resume_entry_alignment",
            title = "恢复入口对齐",
            description = "恢复继续后，优先把当前页面重新对齐到输入框、会话或目的地入口。",
            instructions = "只在恢复链路激活时执行低风险入口对齐；优先聚焦已有输入框，否则点击明显的搜索/联系人/目的地入口，不做重导航。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 30,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.click", "gui.set_text"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val taskPlanState = context.taskPlanState ?: return false
        val stage = taskPlanState.currentStage
        return stage in resumeAlignmentStages &&
            isResumeEntryAlignmentSource(taskPlanState.resumeContext) &&
            taskPlanState.resumeContext.userCorrection.isBlank()
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? = null

    override fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? = null

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val stage = context.taskPlanState?.currentStage.orEmpty()
        val resumeContext = context.taskPlanState?.resumeContext
        val target = resolveResumeAlignmentTarget(context.task, stage)
        return listOfNotNull(
            stage.takeIf { it.isNotBlank() }?.let { "stage=$it" },
            resumeContext?.source?.takeIf { it.isNotBlank() }?.let { "source=$it" },
            target.takeIf { it.isNotBlank() }?.let { "target=${it.take(32)}" },
            context.observation.primaryEditableId?.let { "editable=$it" },
            latestResumeAlignmentAttempt(context.history)?.action?.label?.let { "last_alignment=$it" },
        )
    }
}

internal object ResumeContinuationSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "resume_continuation",
            title = "恢复继续推进",
            description = "恢复回到入口后，优先补一次轻量 submit 或 confirm，避免又重新从头搜索。",
            instructions = "只在恢复链路激活时推进 submit_query、confirm_route、confirm_send；优先点击明确的提交/确认入口，连续失败时自动切换到更稳的备选动作。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 29,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("gui.click", "gui.press_enter", "gui.set_text"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val taskPlanState = context.taskPlanState ?: return false
        return taskPlanState.currentStage in resumeContinuationStages &&
            isResumeEntryAlignmentSource(taskPlanState.resumeContext) &&
            taskPlanState.resumeContext.userCorrection.isBlank()
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? = null

    override fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? = null

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val stage = context.taskPlanState?.currentStage.orEmpty()
        val resumeContext = context.taskPlanState?.resumeContext
        return listOfNotNull(
            stage.takeIf { it.isNotBlank() }?.let { "stage=$it" },
            resumeContext?.source?.takeIf { it.isNotBlank() }?.let { "source=$it" },
            latestResumeContinuationAttempt(context.history)?.action?.label?.let { "last_continue=$it" },
        )
    }
}

internal object ContextRecoverySkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "context_recovery",
            title = "路径自纠",
            description = "检测刚退回过的错误路径，避免再次点击回同一目标造成往返循环。",
            instructions = "如果最近已经点击某个目标后又 back 返回，本轮不要立即重复点回同一目标，优先滚动探索或等待重观察。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 22,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("system.back", "semantic.scroll_for_more", "meta.wait"),
        )

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision =
        SkillGuardPolicies.rewriteRepeatedBacktrackClick(
            observation = context.observation,
            decision = decision,
            history = context.history,
        ) ?: SkillGuardPolicies.rewriteRepeatedOverscroll(
            observation = context.observation,
            decision = decision,
            history = context.history,
        ) ?: SkillGuardPolicies.rewriteRepeatedStalledAction(
            observation = context.observation,
            decision = decision,
            history = context.history,
        ) ?: decision

    override fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? {
        val replacementAction =
            when (diagnosis.category) {
                RecoveryCategory.REPEATED_BACKTRACK,
                RecoveryCategory.NO_PROGRESS,
                -> if (!context.observation.defaultScrollableId.isNullOrBlank()) AgentAction.ScrollForMore("down") else AgentAction.Wait

                RecoveryCategory.OVERSCROLLED ->
                    if (!context.observation.defaultScrollableId.isNullOrBlank()) AgentAction.ScrollForMore("up") else AgentAction.Wait

                else -> null
            } ?: return null
        return AgentDecision(
            action = replacementAction,
            reason = "技能恢复：${diagnosis.summary}",
            rawResponse =
                """{"skill":"context_recovery","recovery":"${diagnosis.category.name.lowercase()}","action":"${replacementAction.label}"}""",
        )
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> =
        listOf(
            "history=${context.history.size}",
            "scrollable=${!context.observation.defaultScrollableId.isNullOrBlank()}",
            context.history.lastOrNull()?.recoveryCategory?.takeIf { it.isNotBlank() }?.let { "last_recovery=$it" }.orEmpty(),
        )
            .filter { it.isNotBlank() }
}

internal object RecoveryFollowThroughSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "recovery_follow_through",
            title = "恢复接管",
            description = "在上一轮已明确诊断失败原因时，优先执行恢复动作而不是重新走老路。",
            instructions = "如果上一轮已经产出 suggested_recovery_action，且当前页面仍兼容该动作，则优先执行它，避免模型重复试错。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 23,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("system.back", "semantic.scroll_for_more", "meta.wait", "semantic.open_best_candidate"),
        )

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val latestTurn = context.history.lastOrNull() ?: return null
        if (latestTurn.recoveryCategory.isBlank() || latestTurn.suggestedRecoveryAction.isBlank()) {
            return null
        }
        val recoveryAction = SkillActionParser.parseActionLabel(latestTurn.suggestedRecoveryAction) ?: return null
        if (!SkillActionParser.isCompatible(recoveryAction, context.observation)) {
            return null
        }
        if (context.history.takeLast(2).any { it.action == recoveryAction.label }) {
            return null
        }
        return AgentDecision(
            action = recoveryAction,
            reason =
                "技能预执行：上一轮已诊断 ${latestTurn.recoveryCategory.lowercase()}，" +
                    "优先执行恢复动作 ${recoveryAction.label}，避免重复试错。",
            rawResponse =
                """{"skill":"recovery_follow_through","phase":"pre_plan","recovery":"${latestTurn.recoveryCategory.lowercase()}","action":"${recoveryAction.label}"}""",
        )
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val latestTurn = context.history.lastOrNull() ?: return decision
        if (latestTurn.recoveryCategory.isBlank() || latestTurn.suggestedRecoveryAction.isBlank()) {
            return decision
        }
        val recoveryAction = SkillActionParser.parseActionLabel(latestTurn.suggestedRecoveryAction) ?: return decision
        if (!SkillActionParser.isCompatible(recoveryAction, context.observation)) {
            return decision
        }
        if (decision.action.label == latestTurn.action && recoveryAction.label != decision.action.label) {
            return decision.copy(
                action = recoveryAction,
                reason =
                    "技能改写：上一轮动作 ${latestTurn.action} 已被判定为 ${latestTurn.recoveryCategory.lowercase()}，" +
                        "本轮改走恢复动作 ${recoveryAction.label}。",
            )
        }
        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val latestTurn = context.history.lastOrNull()
        return listOfNotNull(
            latestTurn?.recoveryCategory?.takeIf { it.isNotBlank() }?.let { "last_recovery=${it.lowercase()}" },
            latestTurn?.suggestedRecoveryAction?.takeIf { it.isNotBlank() }?.let { "suggested=$it" },
        )
    }
}

internal object InterruptGuardSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "interrupt_guard",
            title = "中断处理",
            description = "优先关闭无关弹窗、引导、遮罩和软性授权提示。",
            instructions = "发现明显遮挡主流程的跳过、关闭、取消、知道了等按钮时，可先处理它们再继续主任务。",
            keywords = listOf("弹窗", "引导", "跳过", "关闭"),
            alwaysOn = true,
            priority = 20,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("semantic.dismiss_interrupt"),
        )

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val safeDismissHint =
            context.observation.interruptiveHints.firstOrNull { hint ->
                val text = hint.text
                listOf("跳过", "关闭", "取消", "暂不", "知道了", "我知道了", "以后再说", "稍后").any {
                    text.contains(it, ignoreCase = true)
                }
            } ?: return null
        return AgentDecision(
            action = AgentAction.DismissInterrupt(safeDismissHint.text),
            reason = "技能预执行：当前页面存在中断元素 ${safeDismissHint.text}，先清理干扰再继续。",
            rawResponse = """{"skill":"interrupt_guard","action":"dismiss_interrupt","hint":"${safeDismissHint.text}"}""",
        )
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> =
        listOf("interrupt_candidates=${context.observation.interruptiveHints.size}")

    override fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? {
        if (diagnosis.category !in setOf(RecoveryCategory.POPUP_BLOCKING, RecoveryCategory.PERMISSION_REQUIRED)) {
            return null
        }
        return maybePrePlan(context)?.copy(
            reason = "技能恢复：${diagnosis.summary}",
        )
    }
}

internal object QueryEntrySkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "query_entry",
            title = "查询输入",
            description = "在搜索框、输入框和查询入口出现时，优先完成目标词输入。",
            instructions = "当页面出现主要输入框时，优先写入 normalized_goal；若页面已有搜索按钮或回车入口，可继续触发搜索。",
            keywords = listOf("搜", "搜索", "查", "找", "导航", "路线", "推荐", "评价", "参数"),
            priority = 8,
            layer = SkillLayer.DOMAIN,
            requiredTools = listOf("semantic.populate_primary_input", "semantic.submit_primary_action", "gui.press_enter"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val constraints = TaskIntentParser.parse(context.task)
        return constraints.entryQuery.isNotBlank() ||
            context.observation.primaryEditableId != null ||
            context.taskPlanState?.currentStage in setOf("enter_query", "submit_query", "enter_destination")
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val constraints = TaskIntentParser.parse(context.task)
        val goal = constraints.entryQuery
        context.observation.primaryEditableId ?: return null
        if (goal.isBlank()) return null

        val existingTexts = context.observation.topTexts + context.observation.elements.map { it.text }
        val alreadyTyped = existingTexts.any { text -> text.contains(goal) }
        if (!alreadyTyped) {
            return AgentDecision(
                action = AgentAction.PopulatePrimaryInput(goal),
                reason = "技能预执行：当前页面存在输入框，先写入任务目标 $goal。",
                rawResponse = """{"skill":"query_entry","action":"populate_primary_input","text":"$goal"}""",
            )
        }

        val searchButton =
            context.observation.elements.firstOrNull { element ->
                element.clickable &&
                    element.enabled &&
                    listOf("搜索", "查找", "确认", "完成", "前往", "到这去").any { keyword ->
                        element.text.contains(keyword) || element.viewId.contains(keyword, ignoreCase = true)
                    }
            }
        return if (searchButton != null) {
            AgentDecision(
                action = AgentAction.SubmitPrimaryAction(searchButton.text.ifBlank { "搜索" }),
                reason = "技能预执行：输入已存在，继续触发查询入口 ${searchButton.text.ifBlank { searchButton.viewId }}。",
                rawResponse = """{"skill":"query_entry","action":"submit_primary_action","hint":"${searchButton.text.ifBlank { "搜索" }}"}""",
            )
        } else {
            null
        }
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val constraints = TaskIntentParser.parse(context.task)
        val stage = context.taskPlanState?.currentStage.orEmpty()
        return listOfNotNull(
            constraints.entryQuery.takeIf { it.isNotBlank() }?.let { "goal=$it" },
            constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { "objective=$it" },
            context.observation.primaryEditableId?.let { "editable=$it" },
            stage.takeIf { it.isNotBlank() }?.let { "stage=$it" },
        )
    }
}

internal object ShoppingResearchSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "shopping_research",
            title = "商品调研",
            description = "适合商品搜索、价格约束、详情页与评价浏览。",
            instructions = "先完成搜索，再优先打开满足约束的商品详情；需要证据时进入评价页或参数页，不要先做无关排序。",
            keywords = listOf("推荐", "比价", "评价", "参数", "商品", "价格", "购买", "零食", "手机", "电脑"),
            priority = 10,
            layer = SkillLayer.DOMAIN,
            requiredTools = listOf("semantic.open_best_candidate", "semantic.scroll_for_more"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val constraints = TaskIntentParser.parse(context.task)
        return constraints.intentType == com.lmx.xiaoxuanagent.agent.TaskIntentType.SHOPPING ||
            context.taskPlanState?.planType == "shopping_research"
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val wantsReviews = context.task.contains("评价") || context.task.contains("评论") || context.task.contains("口碑")
        val wantsSpecs = context.task.contains("参数") || context.task.contains("规格") || context.task.contains("配置")
        val infoTarget =
            when {
                wantsReviews -> findClickableElement(context.observation, listOf("评价", "评论", "全部评价", "用户评价"))
                wantsSpecs -> findClickableElement(context.observation, listOf("参数", "规格", "配置", "详情"))
                else -> null
            }

        if (infoTarget != null && decision.action is AgentAction.Wait) {
            return decision.copy(
                action =
                    AgentAction.OpenBestCandidate(
                        targetText = infoTarget.text.ifBlank { if (wantsReviews) "评价" else "参数" },
                        roleHint = if (wantsReviews) "comment" else "detail",
                    ),
                reason = "技能改写：当前任务需要补充商品信息，优先进入 ${infoTarget.text.ifBlank { infoTarget.viewId }}。",
            )
        }

        if (infoTarget != null && decision.action is AgentAction.Finish) {
            return decision.copy(
                action =
                    AgentAction.OpenBestCandidate(
                        targetText = infoTarget.text.ifBlank { if (wantsReviews) "评价" else "参数" },
                        roleHint = if (wantsReviews) "comment" else "detail",
                    ),
                reason = "技能改写：在给出结论前，先补充读取 ${infoTarget.text.ifBlank { infoTarget.viewId }}。",
            )
        }

        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val constraints = TaskIntentParser.parse(context.task)
        return buildList {
            add("goal=${constraints.entryQuery}")
            constraints.maxPriceYuan?.let { add("maxPrice=$it") }
            constraints.objectiveHint?.let { add("objective=$it") }
            if (constraints.preferHighReview) add("prefer=review")
            if (constraints.preferHighSales) add("prefer=sales")
        }
    }
}

internal object ContentResearchSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "content_research",
            title = "内容检索",
            description = "适合内容搜索、定位目标作品、进入评论区并提取高赞或最新信息。",
            instructions = "先搜索目标作者或主题，再打开最匹配的内容对象；若任务要评论/高赞/热评，优先进入评论区或对应入口。",
            keywords = listOf("评论", "高赞", "热评", "最新一期", "最近一期", "视频", "文章", "笔记", "弹幕"),
            priority = 9,
            layer = SkillLayer.DOMAIN,
            requiredTools = listOf("semantic.open_best_candidate", "semantic.scroll_for_more", "semantic.populate_primary_input"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val constraints = TaskIntentParser.parse(context.task)
        return constraints.intentType == com.lmx.xiaoxuanagent.agent.TaskIntentType.CONTENT ||
            context.taskPlanState?.planType == "content_research"
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val stage = context.taskPlanState?.currentStage.orEmpty()
        if (stage != "inspect_information" && stage != "summarize") return null
        val target = findContentObjectiveTarget(context)
        return target?.let {
            AgentDecision(
                action =
                    AgentAction.OpenBestCandidate(
                        targetText = it.text,
                        roleHint = if (context.task.contains("评论")) "comment" else "detail",
                    ),
                reason = "技能预执行：当前已进入内容对象，优先打开 ${it.text.ifBlank { it.viewId }} 获取任务证据。",
                rawResponse = """{"skill":"content_research","action":"open_best_candidate","target":"${it.text}"}""",
            )
        }
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val stage = context.taskPlanState?.currentStage.orEmpty()
        val observation = context.observation

        if (stage == "browse_candidates") {
            val clickAction = decision.action as? AgentAction.Click
            val target = clickAction?.let { action -> observation.elements.firstOrNull { it.id == action.elementId } }
            val bestCandidate = rankContentCandidates(context).firstOrNull()
            if (
                target != null &&
                bestCandidate != null &&
                shouldRewriteContentClick(target, bestCandidate)
            ) {
                return decision.copy(
                    action = AgentAction.OpenBestCandidate(bestCandidate.text, "detail"),
                    reason = "技能改写：当前更应该打开目标内容 ${bestCandidate.text.take(36)}，而不是点击 ${target.text.ifBlank { target.id }}。",
                )
            }
        }

        if (stage == "inspect_information" || stage == "summarize") {
            val target = findContentObjectiveTarget(context)
            if (target != null && (decision.action is AgentAction.Wait || decision.action is AgentAction.Finish || decision.action is AgentAction.Scroll)) {
                return decision.copy(
                    action =
                        AgentAction.OpenBestCandidate(
                            targetText = target.text,
                            roleHint = if (context.task.contains("评论")) "comment" else "detail",
                        ),
                    reason = "技能改写：先进入 ${target.text.ifBlank { target.viewId }} 读取评论或关键信息，再决定是否结束。",
                )
            }
        }

        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> {
        val constraints = TaskIntentParser.parse(context.task)
        return listOfNotNull(
            constraints.entryQuery.takeIf { it.isNotBlank() }?.let { "entry=$it" },
            constraints.objectiveHint?.takeIf { it.isNotBlank() }?.let { "objective=$it" },
            context.taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { "stage=$it" },
        )
    }
}

internal object NavigationSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "navigation",
            title = "导航出行",
            description = "适合路线规划、附近地点、打车与步行导航。",
            instructions = "先确定目的地，再选择合适路线；路线页面优先点击导航/开始，而不是无关入口。",
            keywords = listOf("导航", "路线", "附近", "打车", "步行", "骑行", "开车", "到这去"),
            priority = 8,
            layer = SkillLayer.DOMAIN,
            requiredTools = listOf("semantic.populate_primary_input", "semantic.submit_primary_action"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val constraints = TaskIntentParser.parse(context.task)
        return constraints.intentType == com.lmx.xiaoxuanagent.agent.TaskIntentType.NAVIGATION ||
            context.taskPlanState?.planType == "navigation"
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val routeAction =
            findClickableElement(
                observation = context.observation,
                keywords = listOf("到这去", "路线", "导航", "开始导航"),
            ) ?: return null
        if (context.task.contains("导航") || context.task.contains("路线") || context.task.contains("到")) {
            return AgentDecision(
                action = AgentAction.SubmitPrimaryAction(routeAction.text.ifBlank { "导航" }),
                reason = "技能预执行：当前页面已出现路线入口，优先进入导航主流程。",
                rawResponse = """{"skill":"navigation","action":"submit_primary_action","hint":"${routeAction.text.ifBlank { "导航" }}"}""",
            )
        }
        return null
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val routeAction =
            findClickableElement(
                observation = context.observation,
                keywords = listOf("到这去", "路线", "导航", "开始导航", "开始"),
            )
        val currentAction = decision.action
        if (
            routeAction != null &&
            (currentAction is AgentAction.Wait || currentAction is AgentAction.Scroll)
        ) {
            return decision.copy(
                action = AgentAction.SubmitPrimaryAction(routeAction.text.ifBlank { "导航" }),
                reason = "技能改写：当前页面已出现路线入口，优先进入导航主流程。",
            )
        }
        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> =
        listOfNotNull(
            TaskIntentParser.parse(context.task).destination?.takeIf { it.isNotBlank() }?.let { "destination=$it" },
            context.taskPlanState?.currentStage?.let { "stage=$it" },
        )
}

internal object MessagingSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "messaging",
            title = "通讯发送",
            description = "适合联系人、聊天、公众号和小程序入口任务。",
            instructions = "先定位联系人或会话，再执行发送；如果任务里已给出联系人和正文，优先搜索联系人，进入会话后再写入正文。",
            keywords = listOf("微信", "发消息", "聊天", "联系人", "群聊", "公众号"),
            priority = 6,
            layer = SkillLayer.DOMAIN,
            requiredTools = listOf("semantic.populate_primary_input", "semantic.open_best_candidate", "semantic.navigate_back", "semantic.scroll_for_more"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean {
        val constraints = TaskIntentParser.parse(context.task)
        return constraints.intentType == com.lmx.xiaoxuanagent.agent.TaskIntentType.MESSAGING ||
            context.taskPlanState?.planType == "messaging"
    }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val constraints = TaskIntentParser.parse(context.task)
        val recipient = constraints.recipientName?.trim().orEmpty()
        if (recipient.isBlank()) return null

        val diagnosis = analyzeMessagingTargeting(context.observation, recipient, context.taskPlanState)
        if (!diagnosis.shouldRecoverByBack) return null

        return AgentDecision(
            action = AgentAction.NavigateBack(hint = "recipient_mismatch"),
            reason =
                "技能预执行：当前页面更像误入了与目标联系人 $recipient 不匹配的消息上下文" +
                    "（${diagnosis.recoveryReason}），先返回继续纠正。",
            rawResponse = """{"skill":"messaging","action":"navigate_back","reason":"recipient_mismatch"}""",
        )
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val constraints = TaskIntentParser.parse(context.task)
        val recipient = constraints.recipientName?.trim().orEmpty()
        if (recipient.isBlank()) return decision

        val diagnosis = analyzeMessagingTargeting(context.observation, recipient, context.taskPlanState)
        val recentlyRejectedLabels = recentRejectedMessagingLabels(context.history)
        if (diagnosis.shouldRecoverByBack && decision.action !is AgentAction.NavigateBack) {
            return decision.copy(
                action = AgentAction.NavigateBack(hint = "recipient_mismatch"),
                reason =
                    "技能改写：当前页面存在强不匹配信号，尚未确认目标联系人 $recipient" +
                        "（${diagnosis.recoveryReason}），先返回纠正路径。",
            )
        }

        val clickAction = decision.action as? AgentAction.Click
        if (clickAction != null) {
            val target = context.observation.elements.firstOrNull { it.id == clickAction.elementId }
            val searchEntry = findMessagingSearchEntry(context.observation)
            val targetLabel = target?.text?.trim().orEmpty()
            if (targetLabel.isNotBlank() && targetLabel in recentlyRejectedLabels) {
                return decision.copy(
                    action = AgentAction.ScrollForMore("down"),
                    reason =
                        "技能改写：候选 $targetLabel 刚刚已触发错误上下文，" +
                            "本轮先滚动查找其他联系人入口，避免再次点回同一目标。",
                )
            }
            val bestCandidate =
                diagnosis.rankedCandidates
                    .firstOrNull { it.score >= 80 && it.label !in recentlyRejectedLabels }
                    ?: diagnosis.bestCandidate
            if (!diagnosis.conversationVerified && bestCandidate == null) {
                if (searchEntry != null && target?.id != searchEntry.id) {
                    return decision.copy(
                        action = AgentAction.OpenBestCandidate(searchEntry.text.ifBlank { "搜索" }, "entry"),
                        reason =
                            "技能改写：当前尚未发现明确匹配 $recipient 的联系人，" +
                                "先进入搜索/查找入口 ${searchEntry.text.ifBlank { searchEntry.viewId.ifBlank { searchEntry.id } }}。",
                    )
                }
                if (target != null && !skillRegistryLooksLikeMessagingSearchEntry(target)) {
                    return decision.copy(
                        action = AgentAction.ScrollForMore("down"),
                        reason =
                            "技能改写：当前没有足够可信的联系人候选，先滚动查看更多结果，避免误点无关会话。",
                    )
                }
            }
            if (
                target != null &&
                bestCandidate != null &&
                shouldRewriteMessagingClick(target, bestCandidate, recipient)
            ) {
                return decision.copy(
                    action = AgentAction.OpenBestCandidate(bestCandidate.label, "conversation"),
                    reason =
                        "技能改写：联系人/会话任务优先点击更匹配目标 $recipient 的候选" +
                            " ${bestCandidate.label}，避免误入无关会话。",
                )
            }
        }

        val pendingInputText =
            when (val action = decision.action) {
                is AgentAction.SetText -> action.text
                is AgentAction.PopulatePrimaryInput -> action.text
                else -> null
            }
        val messageBody = constraints.messageBody?.trim().orEmpty()
        if (
            pendingInputText != null &&
            messageBody.isNotBlank() &&
            pendingInputText == messageBody &&
            !diagnosis.conversationVerified
        ) {
            return if (diagnosis.shouldRecoverByBack) {
                decision.copy(
                    action = AgentAction.NavigateBack(hint = "recipient_not_verified"),
                    reason =
                        "技能改写：当前尚未确认目标联系人 $recipient，先返回上一层纠正，再重新定位会话。",
                )
            } else {
                decision.copy(
                    action = AgentAction.PopulatePrimaryInput(recipient),
                    reason =
                        "技能改写：消息正文只能在确认目标会话后输入，当前先改成主输入语义动作定位联系人 $recipient。",
                )
            }
        }

        return decision
    }

    override fun extractParameters(context: SkillDecisionContext): List<String> =
        TaskIntentParser.parse(context.task).let { constraints ->
            listOfNotNull(
                constraints.recipientName?.takeIf { it.isNotBlank() }?.let { "recipient=$it" },
                constraints.messageBody?.takeIf { it.isNotBlank() }?.let { "message=$it" },
                constraints.entryQuery.takeIf { it.isNotBlank() }?.let { "entry=$it" },
                context.taskPlanState?.currentStage?.let { "stage=$it" },
            )
        }
}
