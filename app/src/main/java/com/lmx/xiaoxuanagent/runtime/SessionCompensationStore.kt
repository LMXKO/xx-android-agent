package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class SessionCompensationStep(
    val stepId: String,
    val stepType: String = "action",
    val title: String,
    val summary: String,
    val action: AgentAction,
    val sourceActionLabel: String = "",
    val dependsOnStepIds: List<String> = emptyList(),
    val safetyLevel: String = "medium",
    val journalLines: List<String> = emptyList(),
    val status: String = "planned",
    val lastResult: String = "",
    val appliedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

internal data class SessionCompensationPlan(
    val planId: String = "",
    val sessionId: String,
    val turn: Int,
    val task: String = "",
    val pageState: String = "",
    val observationSignature: String = "",
    val sourceActionLabel: String = "",
    val summary: String = "",
    val transactionMode: String = "ordered_best_effort",
    val rollbackPolicy: String = "semantic_rollback",
    val safetyTier: String = "medium",
    val approvalRequired: Boolean = false,
    val snapshotSummaryLines: List<String> = emptyList(),
    val journalLines: List<String> = emptyList(),
    val status: String = "planned",
    val steps: List<SessionCompensationStep> = emptyList(),
    val lastExecutionId: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

internal data class SessionCompensationExecution(
    val success: Boolean,
    val summary: String,
    val plan: SessionCompensationPlan? = null,
    val stepResults: List<String> = emptyList(),
)

internal object SessionCompensationPlanner {
    fun derivePlan(
        sessionId: String,
        turn: Int,
        task: String,
        beforeObservation: ScreenObservation,
        action: AgentAction,
    ): SessionCompensationPlan? {
        val steps = deriveSteps(beforeObservation = beforeObservation, action = action)
        if (steps.isEmpty()) return null
        val now = System.currentTimeMillis()
        val safetyTier = safetyTierFor(action)
        val rollbackPolicy = rollbackPolicyFor(action)
        val approvalRequired = safetyTier != "low" || steps.any { it.safetyLevel != "low" }
        val snapshotSummaryLines = snapshotSummary(beforeObservation)
        return SessionCompensationPlan(
            planId = "$sessionId#$turn@$now",
            sessionId = sessionId,
            turn = turn,
            task = task,
            pageState = beforeObservation.pageState,
            observationSignature = beforeObservation.signature,
            sourceActionLabel = action.label,
            summary = buildSummary(action, steps),
            transactionMode = if (steps.size > 1) "ordered_strict" else "ordered_best_effort",
            rollbackPolicy = rollbackPolicy,
            safetyTier = safetyTier,
            approvalRequired = approvalRequired,
            snapshotSummaryLines = snapshotSummaryLines,
            journalLines =
                buildJournal(
                    task = task,
                    beforeObservation = beforeObservation,
                    action = action,
                    rollbackPolicy = rollbackPolicy,
                    safetyTier = safetyTier,
                    approvalRequired = approvalRequired,
                    snapshotSummaryLines = snapshotSummaryLines,
                    steps = steps,
                ),
            steps = steps,
            createdAtMs = now,
            updatedAtMs = now,
        )
    }

    private fun deriveSteps(
        beforeObservation: ScreenObservation,
        action: AgentAction,
    ): List<SessionCompensationStep> {
        val rawSteps =
            when (action) {
            is AgentAction.SetText -> {
                val previousText = beforeObservation.elements.firstOrNull { it.id == action.elementId }?.text.orEmpty()
                listOf(
                    SessionCompensationStep(
                        stepId = "restore_text_${action.elementId}",
                        stepType = "restore_input",
                        title = "恢复输入内容",
                        summary = if (previousText.isBlank()) "清空输入框" else "恢复为：$previousText",
                        action = AgentAction.SetText(action.elementId, previousText),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("element=${action.elementId}", "previous_text=${previousText.take(32)}"),
                    ),
                )
            }

            is AgentAction.PopulatePrimaryInput -> {
                val editableId =
                    beforeObservation.primaryEditableId
                        ?: beforeObservation.elements.firstOrNull { it.editable && it.enabled }?.id
                        ?: return emptyList()
                val previousText = beforeObservation.elements.firstOrNull { it.id == editableId }?.text.orEmpty()
                listOf(
                    SessionCompensationStep(
                        stepId = "restore_primary_input_$editableId",
                        stepType = "restore_input",
                        title = "恢复主输入内容",
                        summary = if (previousText.isBlank()) "清空主输入框" else "恢复为：$previousText",
                        action = AgentAction.PopulatePrimaryInput(previousText),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("element=$editableId", "previous_text=${previousText.take(32)}"),
                    ),
                )
            }

            is AgentAction.PasteClipboard -> {
                val previousText = beforeObservation.elements.firstOrNull { it.id == action.elementId }?.text.orEmpty()
                listOf(
                    SessionCompensationStep(
                        stepId = "restore_paste_${action.elementId}",
                        stepType = "restore_input",
                        title = "恢复粘贴前内容",
                        summary = if (previousText.isBlank()) "清空输入框" else "恢复为：$previousText",
                        action = AgentAction.SetText(action.elementId, previousText),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("element=${action.elementId}", "previous_text=${previousText.take(32)}"),
                    ),
                )
            }

            is AgentAction.Scroll ->
                listOf(
                    SessionCompensationStep(
                        stepId = "reverse_scroll_${action.elementId ?: "screen"}",
                        stepType = "reverse_scroll",
                        title = "反向滚动",
                        summary = "回退本轮滚动位移",
                        action = AgentAction.Scroll(action.elementId ?: beforeObservation.defaultScrollableId, oppositeDirection(action.direction)),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("direction=${action.direction}", "target=${action.elementId ?: beforeObservation.defaultScrollableId ?: "screen"}"),
                    ),
                )

            is AgentAction.Swipe ->
                listOf(
                    SessionCompensationStep(
                        stepId = "reverse_swipe_${action.startX}_${action.startY}",
                        stepType = "reverse_scroll",
                        title = "反向滑动",
                        summary = "尝试回到滑动前位置",
                        action = AgentAction.Swipe(action.endX, action.endY, action.startX, action.startY, action.durationMs),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("from=${action.startX},${action.startY}", "to=${action.endX},${action.endY}"),
                    ),
                )

            is AgentAction.ScrollForMore ->
                listOf(
                    SessionCompensationStep(
                        stepId = "reverse_semantic_scroll_${beforeObservation.defaultScrollableId ?: "screen"}",
                        stepType = "reverse_scroll",
                        title = "反向继续探索",
                        summary = "尝试回到滚动前位置",
                        action = AgentAction.Scroll(beforeObservation.defaultScrollableId, oppositeDirection(action.direction)),
                        sourceActionLabel = action.label,
                        safetyLevel = "low",
                        journalLines = listOf("direction=${action.direction}", "target=${beforeObservation.defaultScrollableId ?: "screen"}"),
                    ),
                )

            is AgentAction.LaunchApp ->
                beforeObservation.packageName
                    .takeIf { it.isNotBlank() && !it.equals(action.packageName, ignoreCase = true) }
                    ?.let { previousPackage ->
                        listOf(
                            SessionCompensationStep(
                                stepId = "return_app_${previousPackage}",
                                stepType = "restore_context",
                                title = "返回上一个应用",
                                summary = "恢复到 ${previousPackage}",
                                action = AgentAction.LaunchApp(previousPackage),
                                sourceActionLabel = action.label,
                                safetyLevel = "medium",
                                journalLines = listOf("previous_package=$previousPackage"),
                            ),
                        )
                    }.orEmpty()

            is AgentAction.ReturnToTargetApp ->
                beforeObservation.packageName
                    .takeIf { it.isNotBlank() && !it.equals(action.packageName, ignoreCase = true) }
                    ?.let { previousPackage ->
                        listOf(
                            SessionCompensationStep(
                                stepId = "return_origin_${previousPackage}",
                                stepType = "restore_context",
                                title = "恢复原前台应用",
                                summary = "切回 ${previousPackage}",
                                action = AgentAction.LaunchApp(previousPackage),
                                sourceActionLabel = action.label,
                                safetyLevel = "medium",
                                journalLines = listOf("previous_package=$previousPackage"),
                            ),
                        )
                    }.orEmpty()

            is AgentAction.Click,
            is AgentAction.LongClick,
            is AgentAction.TapPoint,
            AgentAction.PressEnter,
            is AgentAction.OpenBestCandidate,
            is AgentAction.SubmitPrimaryAction,
            -> listOf(buildBacktrackStep(action))

            is AgentAction.NavigateBack ->
                beforeObservation.packageName
                    .takeIf { it.isNotBlank() }
                    ?.let { previousPackage ->
                        listOf(
                            SessionCompensationStep(
                                stepId = "return_after_back_${previousPackage}",
                                stepType = "restore_context",
                                title = "恢复返回前上下文",
                                summary = "尝试返回 ${previousPackage}",
                                action = AgentAction.ReturnToTargetApp(previousPackage),
                                sourceActionLabel = action.label,
                                safetyLevel = "medium",
                                journalLines = listOf("previous_package=$previousPackage"),
                            ),
                        )
                    }.orEmpty()

            else -> emptyList()
        }
        return withGovernanceChain(
            beforeObservation = beforeObservation,
            action = action,
            steps = rawSteps,
        )
    }

    private fun buildSummary(
        action: AgentAction,
        steps: List<SessionCompensationStep>,
    ): String =
        "source=${action.label} | steps=${steps.size} | ${steps.joinToString(" / ") { it.title }}"

    private fun buildBacktrackStep(
        action: AgentAction,
    ): SessionCompensationStep =
        SessionCompensationStep(
            stepId = "backtrack_${action.label.hashCode().toString(16)}",
            stepType = "backtrack",
            title = "回退导航动作",
            summary = "尝试回到执行前页面上下文",
            action = AgentAction.NavigateBack(hint = "compensation"),
            sourceActionLabel = action.label,
            safetyLevel = "medium",
            journalLines = listOf("source=${action.label}"),
        )

    private fun rollbackPolicyFor(
        action: AgentAction,
    ): String =
        when (action) {
            is AgentAction.SetText,
            is AgentAction.PopulatePrimaryInput,
            is AgentAction.PasteClipboard,
            -> "field_restore"

            is AgentAction.Scroll,
            is AgentAction.Swipe,
            is AgentAction.ScrollForMore,
            -> "reverse_scroll"

            is AgentAction.LaunchApp,
            is AgentAction.ReturnToTargetApp,
            -> "context_restore"

            else -> "semantic_rollback"
        }

    private fun safetyTierFor(
        action: AgentAction,
    ): String =
        when (action) {
            is AgentAction.SetText,
            is AgentAction.PopulatePrimaryInput,
            is AgentAction.Scroll,
            is AgentAction.Swipe,
            is AgentAction.ScrollForMore,
            -> "low"

            else -> "medium"
        }

    private fun buildJournal(
        task: String,
        beforeObservation: ScreenObservation,
        action: AgentAction,
        rollbackPolicy: String,
        safetyTier: String,
        approvalRequired: Boolean,
        snapshotSummaryLines: List<String>,
        steps: List<SessionCompensationStep>,
    ): List<String> =
        listOf(
            "task=${task.take(80)}",
            "page_state=${beforeObservation.pageState}",
            "package=${beforeObservation.packageName}",
            "rollback_policy=$rollbackPolicy",
            "safety_tier=$safetyTier",
            "approval_required=$approvalRequired",
            "source=${action.label}",
            "steps=${steps.size}",
        ) +
            snapshotSummaryLines.map { "snapshot=$it" } +
            steps.flatMap { step ->
            listOf("step=${step.stepId}:${step.stepType}") + step.journalLines
        }

    private fun withGovernanceChain(
        beforeObservation: ScreenObservation,
        action: AgentAction,
        steps: List<SessionCompensationStep>,
    ): List<SessionCompensationStep> {
        if (steps.isEmpty()) return emptyList()
        val checkpointId = "checkpoint_${action.label.hashCode().toString(16)}"
        val checkpointStep =
            SessionCompensationStep(
                stepId = checkpointId,
                stepType = "checkpoint",
                title = "校验补偿上下文",
                summary = "确认仍处于补偿预期的页面上下文",
                action = AgentAction.Wait,
                sourceActionLabel = action.label,
                safetyLevel = "low",
                journalLines =
                    listOf(
                        "signature=${beforeObservation.signature}",
                        "page_state=${beforeObservation.pageState}",
                        "package=${beforeObservation.packageName}",
                    ),
            )
        val approvalStep =
            checkpointApprovalStep(action)
        val approvalId = approvalStep?.stepId.orEmpty()
        var previousStepId = approvalId.ifBlank { checkpointId }
        val dependentSteps =
            steps.mapIndexed { index, step ->
                val existingDependencies = step.dependsOnStepIds.filter { it.isNotBlank() }
                val chainDependencies =
                    buildList {
                        addAll(existingDependencies)
                        if (index == 0) {
                            add(previousStepId)
                        } else {
                            add(previousStepId)
                        }
                    }.distinct()
                val next =
                    step.copy(
                        dependsOnStepIds = chainDependencies,
                        journalLines =
                            step.journalLines +
                                listOf(
                                    "rollback_source=${action.label}",
                                    "dependency_chain=${chainDependencies.joinToString(",").ifBlank { "-" }}",
                                ),
                    )
                previousStepId = step.stepId
                next
            }
        return listOfNotNull(checkpointStep, approvalStep) + dependentSteps
    }

    private fun checkpointApprovalStep(
        action: AgentAction,
    ): SessionCompensationStep? {
        val safetyTier = safetyTierFor(action)
        if (safetyTier == "low") return null
        return SessionCompensationStep(
            stepId = "approval_${action.label.hashCode().toString(16)}",
            stepType = "approval_gate",
            title = "等待补偿审批",
            summary = "当前补偿涉及中风险以上动作，执行前需要显式批准",
            action = AgentAction.Wait,
            sourceActionLabel = action.label,
            dependsOnStepIds = listOf("checkpoint_${action.label.hashCode().toString(16)}"),
            safetyLevel = "confirm",
            journalLines =
                listOf(
                    "approval_reason=${action.label}",
                    "safety_tier=$safetyTier",
                ),
        )
    }

    private fun snapshotSummary(
        observation: ScreenObservation,
    ): List<String> =
        buildList {
            add("pkg=${observation.packageName.ifBlank { "-" }}")
            add("page=${observation.pageState.ifBlank { "-" }}")
            add("sig=${observation.signature.take(24)}")
            add("editable=${observation.primaryEditableId ?: "-"}")
            add("scrollable=${observation.defaultScrollableId ?: "-"}")
            observation.topTexts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add("top=${it.take(48)}") }
        }

    private fun oppositeDirection(
        direction: String,
    ): String =
        when (direction.trim().lowercase()) {
            "up" -> "down"
            "left" -> "right"
            "right" -> "left"
            else -> "up"
        }
}

internal object SessionCompensationStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_compensations.json"
    private val lock = Any()

    fun recordPlan(
        plan: SessionCompensationPlan,
    ): SessionCompensationPlan =
        synchronized(lock) {
            val current = readUnlocked()
            val next =
                (current.filterNot { it.sessionId == plan.sessionId && it.turn == plan.turn } + plan)
                    .sortedByDescending { it.updatedAtMs }
            writeUnlocked(next)
            plan
        }

    fun readSessionPlans(
        sessionId: String,
        limit: Int = 12,
    ): List<SessionCompensationPlan> =
        synchronized(lock) {
            readUnlocked()
                .filter { it.sessionId == sessionId }
                .sortedByDescending { it.turn }
                .take(limit.coerceAtLeast(1))
        }

    fun readPendingPlans(
        limit: Int = 12,
    ): List<SessionCompensationPlan> =
        synchronized(lock) {
            readUnlocked()
                .filter { plan -> plan.steps.any { it.status == "planned" } }
                .sortedByDescending { it.updatedAtMs }
                .take(limit.coerceAtLeast(1))
        }

    fun readPlan(
        sessionId: String,
        turn: Int,
    ): SessionCompensationPlan? =
        synchronized(lock) {
            readUnlocked().firstOrNull { it.sessionId == sessionId && it.turn == turn }
        }

    suspend fun runCompensation(
        sessionId: String,
        turn: Int,
        stepId: String = "",
        allowHighRisk: Boolean = false,
        executor: suspend (AgentAction) -> AgentExecutionResult,
    ): SessionCompensationExecution {
        val plan = readPlan(sessionId, turn) ?: return SessionCompensationExecution(false, "未找到补偿计划：$sessionId#$turn")
        if (plan.approvalRequired && !allowHighRisk) {
            return SessionCompensationExecution(
                success = false,
                summary = "补偿计划需要人工确认后才能执行，请显式允许高风险补偿。",
                plan = plan,
                stepResults =
                    listOf(
                        "approval_required | ${plan.safetyTier} | ${plan.summary}",
                    ),
            )
        }
        val targetSteps =
            plan.steps.filter { it.status == "planned" && (stepId.isBlank() || it.stepId == stepId) }
        if (targetSteps.isEmpty()) {
            return SessionCompensationExecution(false, "没有可执行的补偿步骤。", plan = plan)
        }
        val executionId = "exec_${System.currentTimeMillis()}"
        val stepResults = mutableListOf<String>()
        var allSuccess = true
        val targetStepIds = targetSteps.map { it.stepId }.toSet()
        targetSteps.forEach { step ->
            val unresolvedDependencies =
                step.dependsOnStepIds.filter { dependencyId ->
                    dependencyId in targetStepIds &&
                        readPlan(sessionId, turn)?.steps?.firstOrNull { it.stepId == dependencyId }?.status != "applied"
                }
            if (unresolvedDependencies.isNotEmpty()) {
                allSuccess = false
                stepResults += "${step.stepId} | skipped | 等待依赖 ${unresolvedDependencies.joinToString(",")}"
                updateStepStatus(
                    sessionId = sessionId,
                    turn = turn,
                    stepId = step.stepId,
                    status = "skipped",
                    lastResult = "等待依赖 ${unresolvedDependencies.joinToString(",")}",
                    executionId = executionId,
                )
                if (plan.transactionMode == "ordered_strict") {
                    return@forEach
                }
                return@forEach
            }
            val result = runCatching { executor(step.action) }.getOrElse { error ->
                AgentExecutionResult(
                    message = error.message ?: "补偿动作执行异常",
                    keepRunning = false,
                )
            }
            val success = result.keepRunning
            allSuccess = allSuccess && success
            stepResults += "${step.stepId} | ${if (success) "applied" else "failed"} | ${result.message}"
            updateStepStatus(
                sessionId = sessionId,
                turn = turn,
                stepId = step.stepId,
                status = if (success) "applied" else "failed",
                lastResult = result.message,
                executionId = executionId,
            )
            if (!success && plan.transactionMode == "ordered_strict") {
                return@forEach
            }
        }
        val updatedPlan = readPlan(sessionId, turn)
        return SessionCompensationExecution(
            success = allSuccess,
            summary =
                if (allSuccess) {
                    "补偿执行完成：${targetSteps.size} 步已应用。"
                } else {
                    "补偿执行结束，但存在失败步骤。"
                },
            plan = updatedPlan,
            stepResults = stepResults,
        )
    }

    private fun updateStepStatus(
        sessionId: String,
        turn: Int,
        stepId: String,
        status: String,
        lastResult: String,
        executionId: String,
    ) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val next =
                readUnlocked().map { plan ->
                    if (plan.sessionId != sessionId || plan.turn != turn) {
                        plan
                    } else {
                        val nextSteps =
                            plan.steps.map { step ->
                                if (step.stepId != stepId) {
                                    step
                                } else {
                                    step.copy(
                                        status = status,
                                        lastResult = lastResult,
                                        appliedAtMs = if (status == "applied") now else step.appliedAtMs,
                                        updatedAtMs = now,
                                    )
                                }
                            }
                        plan.copy(
                            status =
                                when {
                                    nextSteps.all { it.status == "applied" } -> "applied"
                                    nextSteps.any { it.status == "failed" } -> "partial_failure"
                                    nextSteps.any { it.status == "skipped" } -> "blocked"
                                    else -> "planned"
                                },
                            steps = nextSteps,
                            lastExecutionId = executionId,
                            updatedAtMs = now,
                        )
                    }
                }
            writeUnlocked(next)
        }
    }

    private fun readUnlocked(): List<SessionCompensationPlan> {
        val file = storeFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            JSONObject(file.readText()).optJSONArray("plans").toPlanList()
        }.getOrDefault(emptyList())
    }

    private fun writeUnlocked(
        plans: List<SessionCompensationPlan>,
    ) {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("plans", JSONArray().apply { plans.forEach { put(it.toJson()) } })
            }.toString(2),
        )
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }
}

private fun SessionCompensationPlan.toJson(): JSONObject =
    JSONObject().apply {
        put("plan_id", planId)
        put("session_id", sessionId)
        put("turn", turn)
        put("task", task)
        put("page_state", pageState)
        put("observation_signature", observationSignature)
        put("source_action_label", sourceActionLabel)
        put("summary", summary)
        put("transaction_mode", transactionMode)
        put("rollback_policy", rollbackPolicy)
        put("safety_tier", safetyTier)
        put("approval_required", approvalRequired)
        put("snapshot_summary_lines", JSONArray(snapshotSummaryLines))
        put("journal_lines", JSONArray(journalLines))
        put("status", status)
        put("last_execution_id", lastExecutionId)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

private fun SessionCompensationStep.toJson(): JSONObject =
    JSONObject().apply {
        put("step_id", stepId)
        put("step_type", stepType)
        put("title", title)
        put("summary", summary)
        put("source_action_label", sourceActionLabel)
        put("depends_on_step_ids", JSONArray(dependsOnStepIds))
        put("safety_level", safetyLevel)
        put("journal_lines", JSONArray(journalLines))
        put("status", status)
        put("last_result", lastResult)
        put("applied_at_ms", appliedAtMs)
        put("updated_at_ms", updatedAtMs)
        put("action", action.toJson())
    }

private fun AgentAction.toJson(): JSONObject =
    JSONObject().apply {
        when (this@toJson) {
            is AgentAction.LaunchApp -> {
                put("type", "launch_app")
                put("package_name", packageName)
            }

            is AgentAction.SetText -> {
                put("type", "set_text")
                put("element_id", elementId)
                put("text", text)
            }

            is AgentAction.Scroll -> {
                put("type", "scroll")
                put("element_id", elementId)
                put("direction", direction)
            }

            is AgentAction.Swipe -> {
                put("type", "swipe")
                put("start_x", startX)
                put("start_y", startY)
                put("end_x", endX)
                put("end_y", endY)
                put("duration_ms", durationMs)
            }

            is AgentAction.NavigateBack -> {
                put("type", "navigate_back")
                put("prefer_scroll", preferScroll)
                put("hint", hint)
            }

            is AgentAction.ReturnToTargetApp -> {
                put("type", "return_to_target_app")
                put("package_name", packageName)
            }

            is AgentAction.PopulatePrimaryInput -> {
                put("type", "populate_primary_input")
                put("text", text)
            }

            AgentAction.Wait -> {
                put("type", "wait")
            }

            else -> {
                put("type", "unsupported")
                put("label", label)
            }
        }
    }

private fun JSONArray?.toPlanList(): List<SessionCompensationPlan> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                SessionCompensationPlan(
                    planId = item.optString("plan_id"),
                    sessionId = item.optString("session_id"),
                    turn = item.optInt("turn"),
                    task = item.optString("task"),
                    pageState = item.optString("page_state"),
                    observationSignature = item.optString("observation_signature"),
                    sourceActionLabel = item.optString("source_action_label"),
                    summary = item.optString("summary"),
                    transactionMode = item.optString("transaction_mode", "ordered_best_effort"),
                    rollbackPolicy = item.optString("rollback_policy", "semantic_rollback"),
                    safetyTier = item.optString("safety_tier", "medium"),
                    approvalRequired = item.optBoolean("approval_required", false),
                    snapshotSummaryLines = item.optJSONArray("snapshot_summary_lines").toCompensationStringList(),
                    journalLines = item.optJSONArray("journal_lines").toCompensationStringList(),
                    status = item.optString("status", "planned"),
                    steps = item.optJSONArray("steps").toCompensationSteps(),
                    lastExecutionId = item.optString("last_execution_id"),
                    createdAtMs = item.optLong("created_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

private fun JSONArray?.toCompensationSteps(): List<SessionCompensationStep> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val action = item.optJSONObject("action").toCompensationAction() ?: continue
            add(
                SessionCompensationStep(
                    stepId = item.optString("step_id"),
                    stepType = item.optString("step_type", "action"),
                    title = item.optString("title"),
                    summary = item.optString("summary"),
                    action = action,
                    sourceActionLabel = item.optString("source_action_label"),
                    dependsOnStepIds = item.optJSONArray("depends_on_step_ids").toCompensationStringList(),
                    safetyLevel = item.optString("safety_level", "medium"),
                    journalLines = item.optJSONArray("journal_lines").toCompensationStringList(),
                    status = item.optString("status", "planned"),
                    lastResult = item.optString("last_result"),
                    appliedAtMs = item.optLong("applied_at_ms"),
                    updatedAtMs = item.optLong("updated_at_ms"),
                ),
            )
        }
    }
}

private fun JSONObject?.toCompensationAction(): AgentAction? {
    if (this == null || length() == 0) return null
    return when (optString("type")) {
        "launch_app" -> AgentAction.LaunchApp(optString("package_name"))
        "set_text" -> AgentAction.SetText(optString("element_id"), optString("text"))
        "scroll" -> AgentAction.Scroll(optString("element_id").takeIf { it.isNotBlank() }, optString("direction", "down"))
        "swipe" ->
            AgentAction.Swipe(
                startX = optInt("start_x"),
                startY = optInt("start_y"),
                endX = optInt("end_x"),
                endY = optInt("end_y"),
                durationMs = optLong("duration_ms", 350L),
            )

        "navigate_back" ->
            AgentAction.NavigateBack(
                preferScroll = optBoolean("prefer_scroll"),
                hint = optString("hint"),
            )

        "return_to_target_app" -> AgentAction.ReturnToTargetApp(optString("package_name"))
        "populate_primary_input" -> AgentAction.PopulatePrimaryInput(optString("text"))
        "wait" -> AgentAction.Wait
        else -> null
    }
}

private fun JSONArray?.toCompensationStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
