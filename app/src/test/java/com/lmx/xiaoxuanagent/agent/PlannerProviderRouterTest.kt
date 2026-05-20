package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlannerProviderRouterTest {
    @Test
    fun `buildRegistry creates openai binding for arbitrary provider id`() {
        val descriptor =
            PlannerProviderDescriptor(
                providerId = "team_openai_text",
                modality = PlannerProviderModality.TEXT,
                routeLabel = "team_text",
                backendId = PlannerProviderBackend.OPENAI.backendId,
                enabled = true,
            )

        val registry = PlannerProviderRouter.buildRegistry(descriptors = listOf(descriptor))
        val binding = registry.resolve("team_openai_text")

        assertNotNull(binding)
        assertEquals("team_openai_text", binding?.planner?.modeLabel)
    }

    @Test
    fun `fixed binding factory overrides default backend factory`() {
        val fixedPlanner =
            object : AgentPlanner {
                override val modeLabel: String = "FIXED_TEST"

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
                ): AgentDecision = error("not used in test")
            }
        val descriptor =
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.OPENAI_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = "text",
                backendId = PlannerProviderBackend.OPENAI.backendId,
                enabled = true,
            )

        val registry =
            PlannerProviderRouter.buildRegistry(
                descriptors = listOf(descriptor),
                factoryRegistry =
                    PlannerProviderFactoryRegistry.default(
                        extraFactories =
                            listOf(
                                FixedPlannerProviderBindingFactory(
                                    providerId = PlannerProviderKey.OPENAI_TEXT.providerId,
                                    planner = fixedPlanner,
                                ),
                            ),
                    ),
            )

        assertSame(fixedPlanner, registry.resolve(PlannerProviderKey.OPENAI_TEXT.providerId)?.planner)
    }

    @Test
    fun `buildRegistry creates semantic local binding`() {
        val descriptor =
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = "semantic_local",
                backendId = PlannerProviderBackend.SEMANTIC_LOCAL.backendId,
                enabled = true,
            )

        val registry = PlannerProviderRouter.buildRegistry(descriptors = listOf(descriptor))

        assertEquals(PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId, registry.resolve(descriptor.providerId)?.descriptor?.providerId)
    }

    @Test
    fun `routing prefers resume capable provider on resume context`() {
        val registry =
            PlannerProviderRouter.buildRegistry(
                descriptors =
                    listOf(
                        PlannerProviderDescriptor(
                            providerId = PlannerProviderKey.OPENAI_TEXT.providerId,
                            modality = PlannerProviderModality.TEXT,
                            routeLabel = "text",
                            backendId = PlannerProviderBackend.OPENAI.backendId,
                            priority = 100,
                            supportsStructuredOutput = true,
                            enabled = true,
                            capabilities = setOf("structured"),
                        ),
                        PlannerProviderDescriptor(
                            providerId = PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.providerId,
                            modality = PlannerProviderModality.TEXT,
                            routeLabel = "recovery_local",
                            backendId = PlannerProviderBackend.RECOVERY_HEURISTIC.backendId,
                            priority = 80,
                            enabled = true,
                            capabilities = setOf("resume", "recovery"),
                        ),
                    ),
            )

        val route =
            PlannerProviderRoutingPolicy.resolveRoute(
                input =
                    PlannerProviderRouteInput(
                        observation = emptyObservation(),
                        taskPlanState =
                            TaskPlanState(
                                currentStage = "submit_query",
                                resumeContext = ResumeContext(active = true, source = "manual_resume"),
                            ),
                        visualContext = VisualPerceptionContext(),
                        screenshot = null,
                        targetPackageName = "com.example.app",
                        providerRegistry = registry,
                        providerFailureCounts = emptyMap(),
                    ),
            )

        assertEquals(PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.providerId, route.providerId)
        assertEquals("text_resume_fast_path", route.routeLabel)
    }

    @Test
    fun `routing prefers semantic provider on browse stage`() {
        val registry =
            PlannerProviderRouter.buildRegistry(
                descriptors =
                    listOf(
                        PlannerProviderDescriptor(
                            providerId = PlannerProviderKey.OPENAI_TEXT.providerId,
                            modality = PlannerProviderModality.TEXT,
                            routeLabel = "text",
                            backendId = PlannerProviderBackend.OPENAI.backendId,
                            priority = 100,
                            supportsStructuredOutput = true,
                            enabled = true,
                            capabilities = setOf("structured"),
                        ),
                        PlannerProviderDescriptor(
                            providerId = PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId,
                            modality = PlannerProviderModality.TEXT,
                            routeLabel = "semantic_local",
                            backendId = PlannerProviderBackend.SEMANTIC_LOCAL.backendId,
                            priority = 70,
                            supportsStructuredOutput = true,
                            enabled = true,
                            capabilities = setOf("semantic_navigation", "structured"),
                        ),
                    ),
            )

        val route =
            PlannerProviderRoutingPolicy.resolveRoute(
                input =
                    PlannerProviderRouteInput(
                        observation = emptyObservation(),
                        taskPlanState = TaskPlanState(currentStage = "browse_candidates"),
                        visualContext = VisualPerceptionContext(),
                        screenshot = null,
                        targetPackageName = "com.example.app",
                        providerRegistry = registry,
                        providerFailureCounts = emptyMap(),
                    ),
            )

        assertEquals(PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId, route.providerId)
        assertEquals("text_semantic_navigation", route.routeLabel)
    }

    private fun emptyObservation(): ScreenObservation =
        ScreenObservation(
            packageName = "com.example.app",
            pageState = "LIST",
            signature = "sig",
            screenSummary = "summary",
            topTexts = emptyList(),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = "list",
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )
}
