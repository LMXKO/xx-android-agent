package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PlannerProviderPolicy(
    val enabled: Boolean = true,
    val preferTextOnArtifactHeavyStage: Boolean = true,
    val preferTextOnResume: Boolean = true,
    val preferMemoryLocalOnAlignment: Boolean = true,
    val preferRoutineLocalOnRoutineTasks: Boolean = true,
    val preferArtifactLocalOnInspect: Boolean = true,
    val sparseNodeVisionFailureFallbackGap: Int = 1,
    val visualPageVisionFailureFallbackGap: Int = 1,
    val textFailureVisionFallbackGap: Int = 2,
    val stageOverrides: Map<String, String> = mapOf("summarize" to PlannerProviderKey.OPENAI_TEXT.providerId),
    val packageOverrides: Map<String, String> = emptyMap(),
)

data class PlannerProviderRouteInput(
    val observation: ScreenObservation,
    val task: String = "",
    val taskPlanState: TaskPlanState,
    val visualContext: VisualPerceptionContext,
    val screenshot: ScreenshotPayload?,
    val targetPackageName: String,
    val artifactHints: List<PlannerArtifactHint> = emptyList(),
    val memoryContext: PlanningMemoryContext = PlanningMemoryContext(),
    val providerRegistry: PlannerProviderRegistry,
    val providerFailureCounts: Map<String, Int>,
    val providerFeedback: Map<String, PlannerProviderHealthSnapshot> = emptyMap(),
)

object PlannerProviderPolicyStore {
    private const val POLICY_DIR = "runtime"
    private const val POLICY_FILE = "planner_provider_policy.json"
    private val lock = Any()
    private var hydrated = false
    private var policy = PlannerProviderPolicy()

    fun read(): PlannerProviderPolicy =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy
        }

    fun update(
        next: PlannerProviderPolicy,
    ): PlannerProviderPolicy =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy = next
            persistUnlocked()
            policy
        }

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy.toJson()
        }

    fun importJson(
        json: JSONObject?,
    ): PlannerProviderPolicy =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy = json?.toPolicy() ?: PlannerProviderPolicy()
            persistUnlocked()
            policy
        }

    fun recentFailureCountsByProvider(
        limit: Int = 24,
    ): Map<String, Int> {
        val recentFailures = PlatformTraceStore.readRecent(limit = limit)
        return recentFailures
            .asSequence()
            .filter { it.category == "planner_request_failure" }
            .mapNotNull { entry ->
                val providerId =
                    PlannerProviderKey.resolveProviderId(
                        entry.metadata["provider_id"]
                            ?: entry.metadata["planner_mode"].orEmpty(),
                    )
                providerId?.takeIf { it.isNotBlank() }
            }.groupingBy { it }
            .eachCount()
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = policyFile() ?: return
        if (!file.exists()) return
        runCatching {
            policy = JSONObject(file.readText()).toPolicy()
        }
    }

    private fun persistUnlocked() {
        val file = policyFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(policy.toJson().toString(2))
    }

    private fun policyFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, POLICY_DIR), POLICY_FILE)
    }

    private fun PlannerProviderPolicy.toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("prefer_text_on_artifact_heavy_stage", preferTextOnArtifactHeavyStage)
            put("prefer_text_on_resume", preferTextOnResume)
            put("prefer_memory_local_on_alignment", preferMemoryLocalOnAlignment)
            put("prefer_routine_local_on_routine_tasks", preferRoutineLocalOnRoutineTasks)
            put("prefer_artifact_local_on_inspect", preferArtifactLocalOnInspect)
            put("sparse_node_vision_failure_fallback_gap", sparseNodeVisionFailureFallbackGap)
            put("visual_page_vision_failure_fallback_gap", visualPageVisionFailureFallbackGap)
            put("text_failure_vision_fallback_gap", textFailureVisionFallbackGap)
            put("stage_overrides", stageOverrides.toProviderMapJson("stage"))
            put("package_overrides", packageOverrides.toProviderMapJson("package_name"))
        }

    private fun JSONObject.toPolicy(): PlannerProviderPolicy =
        PlannerProviderPolicy(
            enabled = optBoolean("enabled", true),
            preferTextOnArtifactHeavyStage = optBoolean("prefer_text_on_artifact_heavy_stage", true),
            preferTextOnResume = optBoolean("prefer_text_on_resume", true),
            preferMemoryLocalOnAlignment = optBoolean("prefer_memory_local_on_alignment", true),
            preferRoutineLocalOnRoutineTasks = optBoolean("prefer_routine_local_on_routine_tasks", true),
            preferArtifactLocalOnInspect = optBoolean("prefer_artifact_local_on_inspect", true),
            sparseNodeVisionFailureFallbackGap = optInt("sparse_node_vision_failure_fallback_gap", 1),
            visualPageVisionFailureFallbackGap = optInt("visual_page_vision_failure_fallback_gap", 1),
            textFailureVisionFallbackGap = optInt("text_failure_vision_fallback_gap", 2),
            stageOverrides = optJSONArray("stage_overrides").toProviderMap("stage"),
            packageOverrides = optJSONArray("package_overrides").toProviderMap("package_name"),
        )

    private fun Map<String, String>.toProviderMapJson(
        keyField: String,
    ): JSONArray =
        JSONArray().apply {
            forEach { (key, providerId) ->
                put(
                    JSONObject().apply {
                        put(keyField, key)
                        put("provider", providerId)
                    },
                )
            }
        }

    private fun JSONArray?.toProviderMap(
        keyField: String,
    ): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val key = json.optString(keyField)
                val providerId = PlannerProviderKey.resolveProviderId(json.optString("provider"))
                if (key.isNotBlank() && !providerId.isNullOrBlank()) {
                    put(key, providerId)
                }
            }
        }
    }
}

object PlannerProviderRoutingPolicy {
    fun resolveRoute(
        input: PlannerProviderRouteInput,
        policy: PlannerProviderPolicy = PlannerProviderPolicyStore.read(),
    ): PlannerProviderRoute {
        val hasVisualSignals =
            input.screenshot != null ||
                input.visualContext.captureAvailable ||
                input.visualContext.ocrTexts.isNotEmpty() ||
                input.visualContext.groundedTexts.isNotEmpty()
        val preferredText =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = input.taskPlanState.resumeContext.active || input.taskPlanState.waitingForExternal,
                requiredCapabilities = setOf("structured"),
                input = input,
            )
        val preferredVision =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.VISION,
                preferFast = false,
                requiredCapabilities = if (hasVisualSignals) setOf("screenshot") else emptySet(),
                input = input,
            )
        val preferredResume =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("resume"),
                input = input,
            )
        val preferredRecovery =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("recovery"),
                input = input,
            )
        val preferredSemantic =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("semantic_navigation"),
                input = input,
            )
        val preferredMemory =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("memory_alignment"),
                input = input,
            )
        val preferredRoutine =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("routine"),
                input = input,
            )
        val preferredArtifact =
            selectPreferredProvider(
                registry = input.providerRegistry,
                modality = PlannerProviderModality.TEXT,
                preferFast = true,
                requiredCapabilities = setOf("artifact_focus"),
                input = input,
            )
        val artifactHeavyStage =
            input.taskPlanState.currentStage == "summarize" ||
                input.taskPlanState.waitingForExternal ||
                (policy.preferTextOnResume && input.taskPlanState.resumeContext.active)
        val hasMemoryRecall =
            input.memoryContext.contacts.isNotEmpty() ||
                input.memoryContext.locations.isNotEmpty() ||
                input.memoryContext.appPreferences.isNotEmpty()
        val routineTask =
            listOf("提醒", "待办", "今天", "跟进", "回顾", "节奏", "routine", "agenda").any {
                input.task.contains(it, ignoreCase = true)
            }
        val packageOverride =
            policy.packageOverrides[input.targetPackageName]
                ?.let(input.providerRegistry::resolve)
        val stageOverride =
            input.taskPlanState.currentStage
                .takeIf { it.isNotBlank() }
                ?.let(policy.stageOverrides::get)
                ?.let(input.providerRegistry::resolve)
        if (policy.enabled && packageOverride != null) {
            return PlannerProviderRoute(
                providerId = packageOverride.descriptor.providerId,
                modality = packageOverride.descriptor.modality,
                reason = "命中包级 provider override",
                routeLabel = "package_override",
                learnedScore = packageOverride.descriptor.providerId.learnedScoreOf(input),
                healthScore = packageOverride.descriptor.providerId.healthScoreOf(input),
            )
        }
        if (policy.enabled && stageOverride != null) {
            return PlannerProviderRoute(
                providerId = stageOverride.descriptor.providerId,
                modality = stageOverride.descriptor.modality,
                reason = "命中阶段 provider override",
                routeLabel = "stage_override",
                learnedScore = stageOverride.descriptor.providerId.learnedScoreOf(input),
                healthScore = stageOverride.descriptor.providerId.healthScoreOf(input),
            )
        }

        val visionFailureCount = preferredVision?.descriptor?.providerId?.failureCountOf(input) ?: 0
        val textFailureCount = preferredText?.descriptor?.providerId?.failureCountOf(input) ?: 0
        return when {
            input.taskPlanState.resumeContext.active &&
                preferredResume != null ->
                PlannerProviderRoute(
                    providerId = preferredResume.descriptor.providerId,
                    modality = preferredResume.descriptor.modality,
                    reason = "当前处于恢复/续跑态，优先低延迟 resume provider",
                    routeLabel = "text_resume_fast_path",
                    learnedScore = preferredResume.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredResume.descriptor.providerId.healthScoreOf(input),
                )

            policy.preferMemoryLocalOnAlignment &&
                hasMemoryRecall &&
                input.taskPlanState.currentStage in setOf("find_conversation", "enter_destination", "enter_query", "browse_candidates") &&
                preferredMemory != null ->
                PlannerProviderRoute(
                    providerId = preferredMemory.descriptor.providerId,
                    modality = preferredMemory.descriptor.modality,
                    reason = "命中个人记忆与入口对齐场景，优先 memory local provider",
                    routeLabel = "text_memory_alignment",
                    learnedScore = preferredMemory.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredMemory.descriptor.providerId.healthScoreOf(input),
                )

            policy.preferRoutineLocalOnRoutineTasks &&
                routineTask &&
                preferredRoutine != null ->
                PlannerProviderRoute(
                    providerId = preferredRoutine.descriptor.providerId,
                    modality = preferredRoutine.descriptor.modality,
                    reason = "任务偏日程/跟进治理，优先 routine local provider",
                    routeLabel = "text_routine_local",
                    learnedScore = preferredRoutine.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredRoutine.descriptor.providerId.healthScoreOf(input),
                )

            policy.preferArtifactLocalOnInspect &&
                input.artifactHints.isNotEmpty() &&
                input.taskPlanState.currentStage in setOf("browse_candidates", "inspect_information", "summarize") &&
                preferredArtifact != null ->
                PlannerProviderRoute(
                    providerId = preferredArtifact.descriptor.providerId,
                    modality = preferredArtifact.descriptor.modality,
                    reason = "当前已有 artifact 线索，优先 artifact local provider",
                    routeLabel = "text_artifact_local",
                    learnedScore = preferredArtifact.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredArtifact.descriptor.providerId.healthScoreOf(input),
                )

            input.taskPlanState.currentStage in setOf("browse_candidates", "inspect_information", "confirm_send", "confirm_route") &&
                !hasVisualSignals &&
                preferredSemantic != null ->
                PlannerProviderRoute(
                    providerId = preferredSemantic.descriptor.providerId,
                    modality = preferredSemantic.descriptor.modality,
                    reason = "当前阶段偏语义导航，优先 semantic local provider",
                    routeLabel = "text_semantic_navigation",
                    learnedScore = preferredSemantic.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredSemantic.descriptor.providerId.healthScoreOf(input),
                )

            input.taskPlanState.currentStage in setOf("browse_candidates", "inspect_information", "summarize") &&
                textFailureCount > 0 &&
                preferredRecovery != null ->
                PlannerProviderRoute(
                    providerId = preferredRecovery.descriptor.providerId,
                    modality = preferredRecovery.descriptor.modality,
                    reason = "近期文本路由存在失败，先切 recovery heuristic 收敛",
                    routeLabel = "text_recovery_heuristic",
                    learnedScore = preferredRecovery.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredRecovery.descriptor.providerId.healthScoreOf(input),
                )

            policy.preferTextOnArtifactHeavyStage &&
                artifactHeavyStage &&
                preferredText != null &&
                textFailureCount <= visionFailureCount ->
                PlannerProviderRoute(
                    providerId = preferredText.descriptor.providerId,
                    modality = preferredText.descriptor.modality,
                    reason = "当前阶段更适合结构化收口，优先文本 route",
                    routeLabel = "text_artifact_heavy_stage",
                    learnedScore = preferredText.descriptor.providerId.learnedScoreOf(input),
                    healthScore = preferredText.descriptor.providerId.healthScoreOf(input),
                )

            input.observation.elements.isEmpty() && hasVisualSignals && preferredVision != null ->
                resolveFallbackAwareRoute(
                    input = input,
                    primary = preferredVision,
                    fallback = preferredText ?: preferredVision,
                    primaryFailures = visionFailureCount,
                    fallbackFailures = textFailureCount,
                    failureGap = policy.sparseNodeVisionFailureFallbackGap,
                    primaryReason = "节点稀疏，优先视觉 route",
                    fallbackReason = "节点稀疏但近期视觉路由失败偏多，回退文本 route",
                    primaryLabel = "vision_sparse_nodes",
                    fallbackLabel = "text_fallback_sparse_nodes",
                )

            hasVisualSignals &&
                preferredVision != null &&
                (
                    input.observation.pageState.contains("image", ignoreCase = true) ||
                        input.observation.pageState.contains("detail", ignoreCase = true) ||
                        input.observation.screenSummary.contains("截图", ignoreCase = true)
                ) ->
                resolveFallbackAwareRoute(
                    input = input,
                    primary = preferredVision,
                    fallback = preferredText ?: preferredVision,
                    primaryFailures = visionFailureCount,
                    fallbackFailures = textFailureCount,
                    failureGap = policy.visualPageVisionFailureFallbackGap,
                    primaryReason = "当前页面更依赖视觉上下文",
                    fallbackReason = "页面偏视觉，但近期视觉路由失败偏多，暂退文本 route",
                    primaryLabel = "vision_page",
                    fallbackLabel = "text_fallback_visual_page",
                )

            else -> {
                val defaultText = preferredText ?: preferredVision ?: error("没有可用 planner provider")
                val defaultVision = preferredVision ?: preferredText ?: defaultText
                resolveFallbackAwareRoute(
                    input = input,
                    primary = defaultText,
                    fallback = defaultVision,
                    primaryFailures = textFailureCount,
                    fallbackFailures = visionFailureCount,
                    failureGap = policy.textFailureVisionFallbackGap,
                    primaryReason = "结构化 observation 已足够",
                    fallbackReason = "文本 route 近期失败偏多，当前退回视觉 route",
                    primaryLabel = "text_default",
                    fallbackLabel = "vision_fallback_text_default",
                )
            }
        }
    }

    private fun resolveFallbackAwareRoute(
        input: PlannerProviderRouteInput,
        primary: PlannerProviderBinding,
        fallback: PlannerProviderBinding,
        primaryFailures: Int,
        fallbackFailures: Int,
        failureGap: Int,
        primaryReason: String,
        fallbackReason: String,
        primaryLabel: String,
        fallbackLabel: String,
    ): PlannerProviderRoute {
        val fallbackApplied =
            primary.descriptor.providerId != fallback.descriptor.providerId &&
                primaryFailures > fallbackFailures + failureGap
        val resolved = if (fallbackApplied) fallback else primary
        return PlannerProviderRoute(
            providerId = resolved.descriptor.providerId,
            modality = resolved.descriptor.modality,
            reason = if (fallbackApplied) fallbackReason else primaryReason,
            routeLabel = if (fallbackApplied) fallbackLabel else primaryLabel,
            fallbackApplied = fallbackApplied,
            learnedScore = resolved.descriptor.providerId.learnedScoreOf(input),
            healthScore = resolved.descriptor.providerId.healthScoreOf(input),
        )
    }

    private fun String.failureCountOf(
        input: PlannerProviderRouteInput,
    ): Int = input.providerFailureCounts[this] ?: 0

    private fun selectPreferredProvider(
        registry: PlannerProviderRegistry,
        modality: PlannerProviderModality,
        preferFast: Boolean,
        requiredCapabilities: Set<String>,
        input: PlannerProviderRouteInput,
    ): PlannerProviderBinding? {
        val candidates =
            registry.all().filter { binding ->
                binding.descriptor.modality == modality &&
                    requiredCapabilities.all(binding.descriptor::supportsCapability)
            }
        val fallback = registry.preferred(modality)
        return candidates
            .sortedWith(
                compareByDescending<PlannerProviderBinding> { providerRankingScore(it, preferFast, input) }
                    .thenByDescending { it.descriptor.priority }
                    .thenBy { if (preferFast) it.descriptor.latencyTier.ordinal else PlannerProviderLatencyTier.BALANCED.ordinal }
                    .thenBy { it.descriptor.costTier.ordinal }
                    .thenBy { it.descriptor.providerId },
            ).firstOrNull() ?: fallback
    }

    private fun providerRankingScore(
        binding: PlannerProviderBinding,
        preferFast: Boolean,
        input: PlannerProviderRouteInput,
    ): Long {
        val feedback = binding.descriptor.providerId.feedbackOf(input)
        val fastnessBonus =
            if (preferFast) {
                when (binding.descriptor.latencyTier) {
                    PlannerProviderLatencyTier.FAST -> 180L
                    PlannerProviderLatencyTier.BALANCED -> 90L
                    PlannerProviderLatencyTier.HEAVY -> 0L
                }
            } else {
                0L
            }
        return binding.descriptor.priority * 1_000L + feedback.learnedScore * 10L + fastnessBonus
    }

    private fun String.feedbackOf(
        input: PlannerProviderRouteInput,
    ): PlannerProviderHealthSnapshot =
        input.providerFeedback[this] ?: PlannerProviderHealthSnapshot(providerId = this)

    private fun String.learnedScoreOf(
        input: PlannerProviderRouteInput,
    ): Int = feedbackOf(input).learnedScore

    private fun String.healthScoreOf(
        input: PlannerProviderRouteInput,
    ): Int = feedbackOf(input).healthScore
}
