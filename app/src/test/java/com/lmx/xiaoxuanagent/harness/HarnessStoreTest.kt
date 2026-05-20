package com.lmx.xiaoxuanagent.harness

import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessStoreTest {
    @Test
    fun `detailed summary includes key aggregate buckets`() {
        val snapshot =
            HarnessStore.AggregateSnapshot(
                runCount = 8,
                successRate = 0.75,
                avgTurns = 4.5,
                externalWaitEnteredCount = 3,
                externalWaitResolvedCount = 2,
                externalWaitResumeGuardCount = 1,
                takeoverSessionCount = 3,
                takeoverResolvedSessionCount = 2,
                takeoverResumeSuccessCount = 1,
                intents =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(name = "content", runCount = 4, successRate = 1.0, avgTurns = 3.5),
                        HarnessStore.AggregateBucketSnapshot(name = "messaging", runCount = 3, successRate = 0.33, avgTurns = 6.0),
                        HarnessStore.AggregateBucketSnapshot(name = "navigation", runCount = 1, successRate = 1.0, avgTurns = 2.0),
                    ),
                resultIntents =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(name = "content", runCount = 3, successRate = 1.0, avgTurns = 3.0),
                        HarnessStore.AggregateBucketSnapshot(name = "message", runCount = 2, successRate = 0.5, avgTurns = 5.0),
                    ),
                skills =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(name = "content_research", runCount = 4, successRate = 1.0, avgTurns = 5.2),
                        HarnessStore.AggregateBucketSnapshot(name = "messaging", runCount = 3, successRate = 0.33, avgTurns = 7.0),
                    ),
                profiles =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "dynamic_app_com.tencent.mm",
                            runCount = 3,
                            successRate = 0.33,
                            avgTurns = 6.0,
                            externalWaitEnteredCount = 2,
                            externalWaitResumeGuardCount = 1,
                        ),
                    ),
                routePolicies =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "llm_direct",
                            runCount = 4,
                            successRate = 1.0,
                            avgTurns = 3.0,
                            routeOverrideCount = 0,
                            fallbackCount = 0,
                        ),
                        HarnessStore.AggregateBucketSnapshot(
                            name = "memory_bias_override",
                            runCount = 3,
                            successRate = 0.33,
                            avgTurns = 6.5,
                            routeOverrideCount = 3,
                            fallbackCount = 1,
                        ),
                    ),
                takeovers =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "external_wait",
                            runCount = 3,
                            successRate = 0.66,
                            avgTurns = 5.0,
                            externalWaitEnteredCount = 3,
                            externalWaitResolvedCount = 2,
                            externalWaitResumeGuardCount = 1,
                            takeoverSessionCount = 3,
                            takeoverResolvedSessionCount = 2,
                            takeoverResumeSuccessCount = 1,
                        ),
                    ),
            )

        val lines = HarnessStore.detailedSummaryFromSnapshot(snapshot, limit = 2)

        assertTrue(lines.first().contains("总任务: 8"))
        assertTrue(lines.any { it.contains("高频任务意图:") && it.contains("content") })
        assertTrue(lines.any { it.contains("高频结果类型:") && it.contains("content") })
        assertTrue(lines.any { it.contains("低完成率任务意图:") && it.contains("messaging") })
        assertTrue(lines.any { it.contains("高轮数技能:") && it.contains("messaging") })
        assertTrue(lines.any { it.contains("高频路由策略:") && it.contains("llm_direct") })
        assertTrue(lines.any { it.contains("低完成率路由策略:") && it.contains("memory_bias_override") })
        assertTrue(lines.any { it.contains("外部等待热点:") && it.contains("dynamic_app_com.tencent.mm") })
        assertTrue(lines.any { it.contains("人工接管:") && it.contains("恢复后完成率") })
    }

    @Test
    fun `route policy summary highlights route strategy risks`() {
        val snapshot =
            HarnessStore.AggregateSnapshot(
                runCount = 6,
                successRate = 0.66,
                avgTurns = 4.0,
                routePolicies =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "llm_direct",
                            runCount = 3,
                            successRate = 1.0,
                            avgTurns = 2.5,
                            routeOverrideCount = 0,
                            fallbackCount = 0,
                        ),
                        HarnessStore.AggregateBucketSnapshot(
                            name = "memory_bias_override",
                            runCount = 3,
                            successRate = 0.33,
                            avgTurns = 6.0,
                            routeOverrideCount = 3,
                            fallbackCount = 1,
                        ),
                    ),
            )

        val summary = HarnessStore.routePolicySummaryFromSnapshot(snapshot, limit = 2)

        assertTrue(summary.contains("高频路由策略"))
        assertTrue(summary.contains("llm_direct"))
        assertTrue(summary.contains("低完成率路由策略"))
        assertTrue(summary.contains("memory_bias_override"))
    }

    @Test
    fun `takeover summary highlights recovery quality`() {
        val snapshot =
            HarnessStore.AggregateSnapshot(
                runCount = 5,
                successRate = 0.6,
                avgTurns = 4.2,
                externalWaitEnteredCount = 2,
                externalWaitResolvedCount = 1,
                externalWaitResumeGuardCount = 1,
                safetyConfirmationRequestedCount = 2,
                safetyConfirmationApprovedCount = 1,
                safetyConfirmationRejectedCount = 1,
                takeoverSessionCount = 4,
                takeoverResolvedSessionCount = 2,
                takeoverRejectedSessionCount = 1,
                takeoverResumeSuccessCount = 1,
                profiles =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "dynamic_app_com.tencent.mm",
                            runCount = 3,
                            successRate = 0.33,
                            avgTurns = 6.0,
                            externalWaitEnteredCount = 2,
                            externalWaitResumeGuardCount = 1,
                        ),
                    ),
                takeovers =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "external_wait",
                            runCount = 2,
                            successRate = 0.5,
                            avgTurns = 5.0,
                            externalWaitEnteredCount = 2,
                            externalWaitResolvedCount = 1,
                            externalWaitResumeGuardCount = 1,
                            takeoverSessionCount = 2,
                            takeoverResolvedSessionCount = 1,
                            takeoverResumeSuccessCount = 1,
                        ),
                        HarnessStore.AggregateBucketSnapshot(
                            name = "safety_confirmation",
                            runCount = 2,
                            successRate = 0.5,
                            avgTurns = 4.0,
                            safetyConfirmationRequestedCount = 2,
                            safetyConfirmationApprovedCount = 1,
                            safetyConfirmationRejectedCount = 1,
                            takeoverSessionCount = 2,
                            takeoverResolvedSessionCount = 1,
                            takeoverRejectedSessionCount = 1,
                        ),
                    ),
            )

        val summary = HarnessStore.takeoverSummaryFromSnapshot(snapshot, limit = 2)

        assertTrue(summary.contains("人工接管:"))
        assertTrue(summary.contains("高频接管类型"))
        assertTrue(summary.contains("external_wait"))
        assertTrue(summary.contains("safety_confirmation"))
        assertTrue(summary.contains("二次卡住热点"))
    }

    @Test
    fun `regression runner prioritizes weak intents`() {
        val snapshot =
            HarnessStore.AggregateSnapshot(
                runCount = 6,
                successRate = 0.66,
                avgTurns = 4.0,
                intents =
                    listOf(
                        HarnessStore.AggregateBucketSnapshot(
                            name = "messaging",
                            runCount = 3,
                            successRate = 0.33,
                            avgTurns = 6.0,
                        ),
                        HarnessStore.AggregateBucketSnapshot(
                            name = "navigation",
                            runCount = 3,
                            successRate = 1.0,
                            avgTurns = 3.0,
                        ),
                    ),
            )

        val lines = RegressionRunner.dashboardLines(snapshot, limit = 3)

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.contains("通讯发送任务") || it.contains("恢复续跑任务") || it.contains("Messaging") })
    }
}
