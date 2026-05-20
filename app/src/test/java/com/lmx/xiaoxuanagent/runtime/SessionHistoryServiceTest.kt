package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHistoryServiceTest {
    @Test
    fun `build session title prefers final task result title`() {
        val title =
            SessionHistoryService.buildSessionTitle(
                ReplaySessionSnapshot(
                    sessionId = "session_1",
                    profileId = "profile",
                    targetPackageName = "pkg",
                    task = "帮我给韩威发一条消息并确认发出",
                    taskIntent = "messaging",
                    entrySource = "app",
                    routeReason = "route",
                    routePolicyTag = "",
                    routeSelectedProfileId = "",
                    routeFallbackReason = "",
                    routeMemoryHints = emptyList(),
                    startedAt = 0L,
                    updatedAt = 0L,
                    finishedAt = 0L,
                    turnCount = 3,
                    finalTaskResult =
                        TaskResultPayload(
                            intentType = "message",
                            title = "已成功发送给韩威",
                            summary = "done",
                        ),
                ),
            )

        assertEquals("已成功发送给韩威", title)
    }

    @Test
    fun `build session title falls back to task and plan stage`() {
        val title =
            SessionHistoryService.buildSessionTitle(
                ReplaySessionSnapshot(
                    sessionId = "session_2",
                    profileId = "profile",
                    targetPackageName = "pkg",
                    task = "帮我预订明天下午的高铁票并确认时间",
                    taskIntent = "travel",
                    entrySource = "app",
                    routeReason = "route",
                    routePolicyTag = "",
                    routeSelectedProfileId = "",
                    routeFallbackReason = "",
                    routeMemoryHints = emptyList(),
                    startedAt = 0L,
                    updatedAt = 0L,
                    finishedAt = 0L,
                    turnCount = 1,
                    latestTurn =
                        ReplayTurnSummary(
                            turn = 1,
                            timestamp = 0L,
                            observationSignature = "sig",
                            pageState = "page",
                            packageName = "pkg",
                            actionLabel = "tap",
                            result = "ok",
                            keepRunning = true,
                            planType = "multi_step",
                            planStage = "confirm_route",
                            currentSubgoalId = "route",
                            nextObjective = "确认时间",
                            recoveryCategory = "",
                            recoverySummary = "",
                            suggestedRecoveryAction = "",
                        ),
                ),
            )

        assertEquals("帮我预订明天下午的高铁票并确认时间 @ confirm_route", title)
    }
}
