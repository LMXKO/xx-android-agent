package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanner
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuntimeServiceDriverTest {
    @Test
    fun `onObservation claims batch and releases every session`() =
        runBlocking {
            val acquireLimits = mutableListOf<Int>()
            val releases = mutableListOf<String>()
            val executionOrder = mutableListOf<String>()
            val scheduler =
                object : SessionExecutionSchedulerGateway {
                    private var firstAcquire = true

                    override fun readParallelismBudget(): Int = 2

                    override fun acquirePlanningClaims(
                        indexedObservation: IndexedScreenObservation,
                        owner: String,
                        limit: Int,
                    ): SessionExecutionClaimBatch {
                        synchronized(acquireLimits) {
                            acquireLimits += limit
                        }
                        return if (firstAcquire) {
                            firstAcquire = false
                            SessionExecutionClaimBatch(
                                claims =
                                    listOf(
                                        SessionExecutionClaim(sessionId = "session-a"),
                                        SessionExecutionClaim(sessionId = "session-b"),
                                    ),
                            )
                        } else {
                            SessionExecutionClaimBatch()
                        }
                    }

                    override fun releaseLease(
                        owner: String,
                        sessionId: String,
                        reason: String,
                    ) {
                        synchronized(releases) {
                            releases += "$sessionId:$reason"
                        }
                    }
                }
            val executor =
                object : SessionRuntimeDispatchExecutor {
                    override suspend fun executeClaim(
                        claim: SessionExecutionClaim,
                        seedObservation: IndexedScreenObservation,
                        dependencies: SessionRuntimeTurnDependencies,
                    ): SessionRuntimeClaimExecutionResult {
                        synchronized(executionOrder) {
                            executionOrder += claim.sessionId
                        }
                        delay(25L)
                        return SessionRuntimeClaimExecutionResult(releaseReason = "test_complete")
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val driver =
                    SessionRuntimeServiceDriver(
                        owner = "test_owner",
                        serviceScope = scope,
                        dependencies = testDependencies(),
                        schedulerGateway = scheduler,
                        dispatchExecutor = executor,
                    )

                driver.onObservation(testObservation())

                withTimeout(3_000L) {
                    while (true) {
                        val releaseCount =
                            synchronized(releases) {
                                releases.size
                            }
                        if (releaseCount >= 2) {
                            break
                        }
                        delay(10L)
                    }
                }

                assertEquals(2, synchronized(executionOrder) { executionOrder.size })
                assertTrue(synchronized(acquireLimits) { acquireLimits.firstOrNull() == 2 })
                assertEquals(
                    setOf("session-a:test_complete", "session-b:test_complete"),
                    synchronized(releases) { releases.toSet() },
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `onObservation allows planning claims to execute concurrently`() =
        runBlocking {
            val scheduler =
                object : SessionExecutionSchedulerGateway {
                    private var firstAcquire = true

                    override fun readParallelismBudget(): Int = 2

                    override fun acquirePlanningClaims(
                        indexedObservation: IndexedScreenObservation,
                        owner: String,
                        limit: Int,
                    ): SessionExecutionClaimBatch =
                        if (firstAcquire) {
                            firstAcquire = false
                            SessionExecutionClaimBatch(
                                claims =
                                    listOf(
                                        SessionExecutionClaim(sessionId = "parallel-a"),
                                        SessionExecutionClaim(sessionId = "parallel-b"),
                                    ),
                            )
                        } else {
                            SessionExecutionClaimBatch()
                        }

                    override fun releaseLease(
                        owner: String,
                        sessionId: String,
                        reason: String,
                    ) = Unit
                }
            val guard = Mutex()
            var running = 0
            var maxConcurrent = 0
            val executionSessions = mutableListOf<String>()
            val executor =
                object : SessionRuntimeDispatchExecutor {
                    override suspend fun executeClaim(
                        claim: SessionExecutionClaim,
                        seedObservation: IndexedScreenObservation,
                        dependencies: SessionRuntimeTurnDependencies,
                    ): SessionRuntimeClaimExecutionResult {
                        guard.withLock {
                            running += 1
                            maxConcurrent = maxOf(maxConcurrent, running)
                            executionSessions += SessionRuntimeStore.currentContextSessionId()
                        }
                        delay(80L)
                        guard.withLock {
                            running -= 1
                        }
                        return SessionRuntimeClaimExecutionResult()
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val driver =
                    SessionRuntimeServiceDriver(
                        owner = "test_owner_parallel",
                        serviceScope = scope,
                        dependencies = testDependencies(),
                        schedulerGateway = scheduler,
                        dispatchExecutor = executor,
                    )
                driver.onObservation(testObservation())
                withTimeout(3_000L) {
                    while (true) {
                        if (guard.withLock { running == 0 && maxConcurrent >= 2 }) break
                        delay(10L)
                    }
                }
                assertTrue(maxConcurrent >= 2)
                assertEquals(setOf("parallel-a", "parallel-b"), executionSessions.toSet())
                assertNotEquals(executionSessions.getOrNull(0), "")
            } finally {
                scope.cancel()
            }
        }

    private fun testDependencies(): SessionRuntimeTurnDependencies =
        SessionRuntimeTurnDependencies(
            planner =
                object : AgentPlanner {
                    override val modeLabel: String = "TEST"

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
                },
            plannerTimeoutMs = 1_000L,
            captureScreenshotPayload = { null },
            buildVisualContext = { _, _ -> VisualPerceptionContext() },
            observeCurrentScreen = { testObservation() },
            executeAction = { _, _ -> error("not used in test") },
        )

    private fun testObservation(): IndexedScreenObservation =
        IndexedScreenObservation(
            observation =
                ScreenObservation(
                    packageName = "com.test.app",
                    pageState = "HOME",
                    signature = "sig-home",
                    screenSummary = "home",
                    topTexts = listOf("home"),
                    primaryEditableId = null,
                    focusedElementId = null,
                    defaultScrollableId = null,
                    primaryInterruptActionId = null,
                    interruptiveHints = emptyList(),
                    elements = emptyList(),
                ),
            nodesById = emptyMap(),
        )
}
