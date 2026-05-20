package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore

enum class PlannerProviderKey(
    val providerId: String,
    val modality: PlannerProviderModality,
    val routeLabel: String,
) {
    OPENAI_VISION(
        providerId = "openai_vision_primary",
        modality = PlannerProviderModality.VISION,
        routeLabel = "vision",
    ),
    OPENAI_TEXT(
        providerId = "openai_text_primary",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "text",
    ),
    LOCAL_HEURISTIC_TEXT(
        providerId = "local_heuristic_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "local_text",
    ),
    SEMANTIC_LOCAL_TEXT(
        providerId = "semantic_local_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "semantic_local",
    ),
    RECOVERY_HEURISTIC_TEXT(
        providerId = "recovery_heuristic_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "recovery_local",
    ),
    MEMORY_LOCAL_TEXT(
        providerId = "memory_local_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "memory_local",
    ),
    ROUTINE_LOCAL_TEXT(
        providerId = "routine_local_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "routine_local",
    ),
    ARTIFACT_LOCAL_TEXT(
        providerId = "artifact_local_text",
        modality = PlannerProviderModality.TEXT,
        routeLabel = "artifact_local",
    ),
    ;

    companion object {
        fun fromProviderId(
            providerId: String,
        ): PlannerProviderKey? = entries.firstOrNull { it.providerId == providerId }

        fun resolveProviderId(
            raw: String,
        ): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            return entries.firstOrNull { entry ->
                entry.providerId.equals(trimmed, ignoreCase = true) ||
                    entry.name.equals(trimmed, ignoreCase = true)
            }?.providerId ?: trimmed.lowercase()
        }
    }
}

enum class PlannerProviderModality {
    TEXT,
    VISION,
}

enum class PlannerProviderBackend(
    val backendId: String,
) {
    OPENAI("openai"),
    LOCAL_HEURISTIC("local_heuristic"),
    SEMANTIC_LOCAL("semantic_local"),
    RECOVERY_HEURISTIC("recovery_heuristic"),
    MEMORY_LOCAL("memory_local"),
    ROUTINE_LOCAL("routine_local"),
    ARTIFACT_LOCAL("artifact_local"),
    ;

    companion object {
        fun fromBackendId(
            raw: String,
        ): PlannerProviderBackend? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            return entries.firstOrNull { backend ->
                backend.backendId.equals(trimmed, ignoreCase = true) ||
                    backend.name.equals(trimmed, ignoreCase = true)
            }
        }

        fun resolveBackendId(
            raw: String,
        ): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            return fromBackendId(trimmed)?.backendId ?: trimmed.lowercase()
        }
    }
}

enum class PlannerProviderLatencyTier {
    FAST,
    BALANCED,
    HEAVY,
}

enum class PlannerProviderCostTier {
    LOW,
    STANDARD,
    HIGH,
}

data class PlannerProviderDescriptor(
    val providerId: String,
    val modality: PlannerProviderModality,
    val routeLabel: String,
    val backendId: String = PlannerProviderBackend.OPENAI.backendId,
    val priority: Int = 0,
    val latencyTier: PlannerProviderLatencyTier = PlannerProviderLatencyTier.BALANCED,
    val costTier: PlannerProviderCostTier = PlannerProviderCostTier.STANDARD,
    val supportsScreenshot: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val enabled: Boolean = true,
    val tags: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
) {
    val backend: PlannerProviderBackend?
        get() = PlannerProviderBackend.fromBackendId(backendId)

    fun supportsCapability(
        capability: String,
    ): Boolean {
        val normalized = capability.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized in capabilities.map { it.lowercase() }.toSet() ||
            normalized in tags.map { it.lowercase() }.toSet() ||
            (normalized == "screenshot" && supportsScreenshot) ||
            (normalized == "structured" && supportsStructuredOutput)
    }
}

data class PlannerProviderBinding(
    val descriptor: PlannerProviderDescriptor,
    val planner: AgentPlanner,
)

interface PlannerProviderBindingFactory {
    val factoryId: String

    fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding?
}

class FixedPlannerProviderBindingFactory(
    private val providerId: String,
    private val planner: AgentPlanner,
) : PlannerProviderBindingFactory {
    override val factoryId: String = "fixed_${providerId.lowercase()}"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.providerId == providerId) {
            PlannerProviderBinding(descriptor = descriptor, planner = planner)
        } else {
            null
        }
}

class OpenAiPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "openai_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.OPENAI.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = OpenAiPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class LocalHeuristicPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "local_heuristic_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.LOCAL_HEURISTIC.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = LocalHeuristicPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class SemanticLocalPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "semantic_local_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.SEMANTIC_LOCAL.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = SemanticLocalPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class RecoveryHeuristicPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "recovery_heuristic_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.RECOVERY_HEURISTIC.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = RecoveryHeuristicPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class MemoryLocalPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "memory_local_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.MEMORY_LOCAL.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = MemoryLocalPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class RoutineLocalPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "routine_local_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.ROUTINE_LOCAL.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = RoutineLocalPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class ArtifactLocalPlannerBindingFactory : PlannerProviderBindingFactory {
    override val factoryId: String = "artifact_local_backend"

    override fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        if (descriptor.backendId == PlannerProviderBackend.ARTIFACT_LOCAL.backendId) {
            PlannerProviderBinding(
                descriptor = descriptor,
                planner = ArtifactLocalPlanner(descriptor.providerId),
            )
        } else {
            null
        }
}

class PlannerProviderFactoryRegistry(
    factories: List<PlannerProviderBindingFactory>,
) {
    private val orderedFactories = factories.distinctBy { it.factoryId }

    fun createBinding(
        descriptor: PlannerProviderDescriptor,
    ): PlannerProviderBinding? =
        orderedFactories.firstNotNullOfOrNull { factory ->
            factory.createBinding(descriptor)
        }

    fun createBindings(
        descriptors: List<PlannerProviderDescriptor>,
    ): List<PlannerProviderBinding> =
        descriptors.mapNotNull(::createBinding)

    companion object {
        fun default(
            extraFactories: List<PlannerProviderBindingFactory> = emptyList(),
        ): PlannerProviderFactoryRegistry =
            PlannerProviderFactoryRegistry(
                extraFactories +
                    listOf(
                        OpenAiPlannerBindingFactory(),
                        LocalHeuristicPlannerBindingFactory(),
                        SemanticLocalPlannerBindingFactory(),
                        RecoveryHeuristicPlannerBindingFactory(),
                        MemoryLocalPlannerBindingFactory(),
                        RoutineLocalPlannerBindingFactory(),
                        ArtifactLocalPlannerBindingFactory(),
                    ),
            )
    }
}

class PlannerProviderRegistry(
    bindings: List<PlannerProviderBinding>,
) {
    private val configured =
        bindings.sortedWith(
            compareByDescending<PlannerProviderBinding> { it.descriptor.priority }
                .thenBy { it.descriptor.providerId },
        )
    private val enabledBindings = configured.filter { it.descriptor.enabled }
    private val indexed = enabledBindings.associateBy { it.descriptor.providerId }
    private val ordered =
        enabledBindings.sortedWith(
            compareByDescending<PlannerProviderBinding> { it.descriptor.priority }
                .thenBy { it.descriptor.providerId },
        )

    fun all(): List<PlannerProviderBinding> = ordered

    fun allConfigured(): List<PlannerProviderBinding> = configured

    fun resolve(
        providerId: String,
    ): PlannerProviderBinding? =
        indexed[PlannerProviderKey.resolveProviderId(providerId)]

    fun preferred(
        modality: PlannerProviderModality,
    ): PlannerProviderBinding? =
        ordered.firstOrNull { it.descriptor.modality == modality }

    fun alternate(
        currentProviderId: String,
        modality: PlannerProviderModality? = null,
    ): PlannerProviderBinding? =
        ordered.firstOrNull { binding ->
            binding.descriptor.providerId != currentProviderId &&
                (modality == null || binding.descriptor.modality == modality)
        }
}

data class PlannerProviderRoute(
    val providerId: String,
    val modality: PlannerProviderModality,
    val reason: String,
    val routeLabel: String,
    val fallbackApplied: Boolean = false,
    val learnedScore: Int = 0,
    val healthScore: Int = 0,
)

class PlannerProviderRouter(
    private val factoryRegistry: PlannerProviderFactoryRegistry = PlannerProviderFactoryRegistry.default(),
    private val registryOverride: PlannerProviderRegistry? = null,
) : AgentPlanner {
    constructor(
        visionPlanner: AgentPlanner = OpenAiPlanner(PlannerProviderKey.OPENAI_VISION.providerId),
        textPlanner: AgentPlanner = OpenAiPlanner(PlannerProviderKey.OPENAI_TEXT.providerId),
        localTextPlanner: AgentPlanner = LocalHeuristicPlanner(PlannerProviderKey.LOCAL_HEURISTIC_TEXT.providerId),
        semanticLocalPlanner: AgentPlanner = SemanticLocalPlanner(PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId),
        recoveryHeuristicPlanner: AgentPlanner = RecoveryHeuristicPlanner(PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.providerId),
        memoryLocalPlanner: AgentPlanner = MemoryLocalPlanner(PlannerProviderKey.MEMORY_LOCAL_TEXT.providerId),
        routineLocalPlanner: AgentPlanner = RoutineLocalPlanner(PlannerProviderKey.ROUTINE_LOCAL_TEXT.providerId),
        artifactLocalPlanner: AgentPlanner = ArtifactLocalPlanner(PlannerProviderKey.ARTIFACT_LOCAL_TEXT.providerId),
        registryOverride: PlannerProviderRegistry? = null,
    ) : this(
        factoryRegistry =
            PlannerProviderFactoryRegistry.default(
                extraFactories =
                    listOf(
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.OPENAI_VISION.providerId, visionPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.OPENAI_TEXT.providerId, textPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.LOCAL_HEURISTIC_TEXT.providerId, localTextPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId, semanticLocalPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.providerId, recoveryHeuristicPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.MEMORY_LOCAL_TEXT.providerId, memoryLocalPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.ROUTINE_LOCAL_TEXT.providerId, routineLocalPlanner),
                        FixedPlannerProviderBindingFactory(PlannerProviderKey.ARTIFACT_LOCAL_TEXT.providerId, artifactLocalPlanner),
                    ),
            ),
        registryOverride = registryOverride,
    )

    override val modeLabel: String = "PROVIDER_ROUTER"

    override suspend fun plan(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload?,
        targetPackageName: String,
    ): AgentDecision {
        val providerFailures = PlannerProviderPolicyStore.recentFailureCountsByProvider(limit = 24)
        val providerRegistry =
            registryOverride ?: buildRegistry(
                descriptors = PlannerProviderRegistryStore.read(),
                factoryRegistry = factoryRegistry,
            )
        val providerFeedback =
            PlannerProviderFeedbackStore.feedbackScores(
                providerIds = providerRegistry.all().map { it.descriptor.providerId },
                targetPackageName = targetPackageName,
                stage = taskPlanState.currentStage,
            )
        val route =
            PlannerProviderRoutingPolicy.resolveRoute(
                PlannerProviderRouteInput(
                    observation = observation,
                    task = task,
                    taskPlanState = taskPlanState,
                    visualContext = visualContext,
                    screenshot = screenshot,
                    targetPackageName = targetPackageName,
                    artifactHints = artifactHints,
                    memoryContext = memoryContext,
                    providerRegistry = providerRegistry,
                    providerFailureCounts = providerFailures,
                    providerFeedback = providerFeedback,
                ),
            )
        val resolvedBinding =
            providerRegistry.resolve(route.providerId)
                ?: providerRegistry.preferred(route.modality)
                ?: error("未找到可用 planner provider: ${route.providerId}")
        PlatformTraceStore.record(
            category = "planner_route",
            summary = "${route.providerId} | ${route.reason}",
            metadata =
                buildMap {
                    put("provider_id", route.providerId)
                    put("provider_modality", route.modality.name.lowercase())
                    put("route_label", route.routeLabel)
                    put("fallback_applied", route.fallbackApplied.toString())
                    put("learned_score", route.learnedScore.toString())
                    put("health_score", route.healthScore.toString())
                    put("page_state", observation.pageState)
                    put("target_package", targetPackageName)
                    providerFailures.forEach { (providerId, failureCount) ->
                        put("failures_$providerId", failureCount.toString())
                    }
                },
        )
        val decision =
            try {
                resolvedBinding.planner.plan(
                    task = task,
                    observation = observation,
                    history = history,
                    artifactHints = artifactHints,
                    memoryContext = memoryContext,
                    activeSkills = activeSkills,
                    taskPlanState = taskPlanState,
                    visualContext = visualContext,
                    screenshot = screenshot,
                    targetPackageName = targetPackageName,
                )
            } catch (error: Throwable) {
                PlannerProviderFeedbackStore.recordOutcome(
                    providerId = route.providerId,
                    targetPackageName = targetPackageName,
                    stage = taskPlanState.currentStage,
                    routeLabel = route.routeLabel,
                    success = false,
                    fallbackApplied = route.fallbackApplied,
                )
                throw error
            }
        PlannerProviderFeedbackStore.recordOutcome(
            providerId = route.providerId,
            targetPackageName = targetPackageName,
            stage = taskPlanState.currentStage,
            routeLabel = route.routeLabel,
            success = decision.action !is AgentAction.Fail,
            fallbackApplied = route.fallbackApplied,
        )
        return decision.copy(
            rawResponse =
                buildString {
                    append("[planner_provider=").append(route.providerId).append("]")
                    append("[planner_modality=").append(route.modality.name.lowercase()).append("]")
                    append("[route=").append(route.routeLabel).append("]")
                    append("[health=").append(route.healthScore).append("]")
                    append("[learned_score=").append(route.learnedScore).append("]")
                    append(decision.rawResponse)
                },
        )
    }

    companion object {
        fun buildRegistry(
            descriptors: List<PlannerProviderDescriptor> = PlannerProviderRegistryStore.read(),
            factoryRegistry: PlannerProviderFactoryRegistry = PlannerProviderFactoryRegistry.default(),
        ): PlannerProviderRegistry {
            val bindings = factoryRegistry.createBindings(descriptors)
            return PlannerProviderRegistry(
                bindings = if (bindings.isNotEmpty()) bindings else emptyList(),
            )
        }
    }
}
