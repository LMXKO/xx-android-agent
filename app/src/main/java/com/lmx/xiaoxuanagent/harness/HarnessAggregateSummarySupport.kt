package com.lmx.xiaoxuanagent.harness

import org.json.JSONObject

internal fun detailedSummaryFromAggregate(
    json: JSONObject,
    limit: Int = 5,
): List<String> = detailedSummaryFromSnapshot(snapshotFromAggregate(json), limit)

internal fun detailedSummaryFromSnapshot(
    snapshot: HarnessStore.AggregateSnapshot,
    limit: Int = 5,
): List<String> {
    val safeLimit = limit.coerceAtLeast(1)
    if (snapshot.runCount <= 0) {
        return listOf("暂无回归统计。")
    }
    val lines = mutableListOf<String>()

    lines += dashboardHeaderLine(snapshot)

    summarizeBucketsFromSnapshots(
        title = "高频任务意图",
        buckets = snapshot.intents,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 完成率${(bucket.successRate * 100.0).toInt()}%)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "高频场景",
        buckets = snapshot.scenarios,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 完成率${(bucket.successRate * 100.0).toInt()}%)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "高频结果类型",
        buckets = snapshot.resultIntents,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "低完成率任务意图",
        buckets = snapshot.intents,
        limit = safeLimit,
        sortBy = { bucket -> bucket.successRate },
        ascending = true,
        formatter = { name, bucket ->
            "$name(完成率${(bucket.successRate * 100.0).toInt()}%, ${bucket.runCount}次)"
        },
        predicate = { bucket -> bucket.runCount >= 2 },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "高轮数技能",
        buckets = snapshot.skills,
        limit = safeLimit,
        sortBy = { bucket -> bucket.avgTurns },
        formatter = { name, bucket ->
            "$name(平均轮数${"%.1f".format(bucket.avgTurns)}, ${bucket.runCount}次)"
        },
        predicate = { bucket -> bucket.runCount >= 2 },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "高频路由策略",
        buckets = snapshot.routePolicies,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 覆写${bucket.routeOverrideCount}, 回退${bucket.fallbackCount})"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "低完成率路由策略",
        buckets = snapshot.routePolicies,
        limit = safeLimit,
        sortBy = { bucket -> bucket.successRate },
        ascending = true,
        formatter = { name, bucket ->
            "$name(完成率${(bucket.successRate * 100.0).toInt()}%, 覆写${bucket.routeOverrideCount}, 回退${bucket.fallbackCount})"
        },
        predicate = { bucket -> bucket.runCount >= 2 },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "覆盖 suite",
        buckets = snapshot.suites,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 完成率${(bucket.successRate * 100.0).toInt()}%)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "覆盖 persona",
        buckets = snapshot.personas,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "覆盖 maturity",
        buckets = snapshot.maturities,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 完成率${(bucket.successRate * 100.0).toInt()}%)"
        },
    )?.let(lines::add)

    summarizeBucketsFromSnapshots(
        title = "外部等待热点",
        buckets = snapshot.profiles,
        limit = safeLimit,
        sortBy = { bucket ->
            (bucket.externalWaitEnteredCount + bucket.externalWaitResumeGuardCount).toDouble()
        },
        formatter = { name, bucket ->
            "$name(进入等待${bucket.externalWaitEnteredCount}, 恢复保护${bucket.externalWaitResumeGuardCount})"
        },
        predicate = { bucket ->
            bucket.externalWaitEnteredCount > 0 || bucket.externalWaitResumeGuardCount > 0
        },
    )?.let(lines::add)

    takeoverHeaderLine(snapshot)?.let(lines::add)

    return lines
}

internal fun routePolicySummaryFromSnapshot(
    snapshot: HarnessStore.AggregateSnapshot,
    limit: Int = 3,
): String {
    if (snapshot.routePolicies.isEmpty()) {
        return "暂无路由回归统计。"
    }
    val safeLimit = limit.coerceAtLeast(1)
    val lines = mutableListOf<String>()
    summarizeBucketsFromSnapshots(
        title = "高频路由策略",
        buckets = snapshot.routePolicies,
        limit = safeLimit,
        sortBy = { bucket -> bucket.runCount.toDouble() },
        formatter = { name, bucket ->
            "$name(${bucket.runCount}次, 覆写${bucket.routeOverrideCount}, 回退${bucket.fallbackCount})"
        },
    )?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "低完成率路由策略",
        buckets = snapshot.routePolicies,
        limit = safeLimit,
        sortBy = { bucket -> bucket.successRate },
        ascending = true,
        formatter = { name, bucket ->
            "$name(完成率${(bucket.successRate * 100.0).toInt()}%, 覆写${bucket.routeOverrideCount}, 回退${bucket.fallbackCount})"
        },
        predicate = { bucket -> bucket.runCount >= 2 },
    )?.let(lines::add)
    return lines.joinToString("\n").ifBlank { "暂无路由回归统计。" }
}

internal fun takeoverSummaryFromSnapshot(
    snapshot: HarnessStore.AggregateSnapshot,
    limit: Int = 3,
): String {
    val header = takeoverHeaderLine(snapshot) ?: return "暂无人工接管回归统计。"
    val safeLimit = limit.coerceAtLeast(1)
    val lines = mutableListOf(header)
    resumeSnapshotHeaderLine(snapshot)?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "高频接管类型",
        buckets = snapshot.takeovers,
        limit = safeLimit,
        sortBy = { bucket ->
            (
                bucket.externalWaitEnteredCount +
                    bucket.safetyConfirmationRequestedCount +
                    bucket.manualResumeCount +
                    bucket.resumeSnapshotRestoredCount
                ).toDouble()
        },
        formatter = { name, bucket ->
            val total =
                bucket.externalWaitEnteredCount +
                    bucket.safetyConfirmationRequestedCount +
                    bucket.manualResumeCount +
                    bucket.resumeSnapshotRestoredCount
            "$name($total 次, 已恢复${bucket.takeoverResolvedSessionCount}, 拒绝${bucket.takeoverRejectedSessionCount})"
        },
        predicate = { bucket ->
            bucket.takeoverSessionCount > 0 ||
                bucket.externalWaitEnteredCount > 0 ||
                bucket.safetyConfirmationRequestedCount > 0 ||
                bucket.manualResumeCount > 0 ||
                bucket.resumeSnapshotRestoredCount > 0
        },
    )?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "恢复快照链路",
        buckets = snapshot.takeovers,
        limit = safeLimit,
        sortBy = { bucket ->
            (bucket.resumeSnapshotRestoredCount + bucket.resumeSnapshotContinueCount).toDouble()
        },
        formatter = { name, bucket ->
            "$name(已恢复${bucket.resumeSnapshotRestoredCount}, 已继续${bucket.resumeSnapshotContinueCount})"
        },
        predicate = { bucket ->
            bucket.resumeSnapshotRestoredCount > 0 || bucket.resumeSnapshotContinueCount > 0
        },
    )?.let(lines::add)
    resumeContinuationHeaderLine(snapshot)?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "恢复继续链路",
        buckets = snapshot.takeovers,
        limit = safeLimit,
        sortBy = { bucket ->
            (bucket.resumeContinuationAttemptCount + bucket.resumeContinuationSuccessCount).toDouble()
        },
        formatter = { _, bucket ->
            "resume_snapshot(尝试${bucket.resumeContinuationAttemptCount}, 命中${bucket.resumeContinuationSuccessCount}, 转恢复${bucket.resumeContinuationRecoveryCount})"
        },
        predicate = { bucket ->
            bucket.name == "resume_snapshot" &&
                (
                    bucket.resumeContinuationAttemptCount > 0 ||
                        bucket.resumeContinuationSuccessCount > 0 ||
                        bucket.resumeContinuationRecoveryCount > 0
                )
        },
    )?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "恢复继续阶段",
        buckets = snapshot.takeovers,
        limit = safeLimit,
        sortBy = { bucket ->
            (bucket.resumeContinuationAttemptCount + bucket.resumeContinuationRecoveryCount).toDouble()
        },
        formatter = { name, bucket ->
            "${resumeContinuationStageLabel(name)}(尝试${bucket.resumeContinuationAttemptCount}, 命中${bucket.resumeContinuationSuccessCount}, 转恢复${bucket.resumeContinuationRecoveryCount}, ${resumeContinuationFailureSummary(name, bucket)})"
        },
        predicate = { bucket ->
            bucket.name.startsWith("resume_continuation_") &&
                (
                    bucket.resumeContinuationAttemptCount > 0 ||
                        bucket.resumeContinuationSuccessCount > 0 ||
                        bucket.resumeContinuationRecoveryCount > 0
                )
        },
    )?.let(lines::add)
    summarizeBucketsFromSnapshots(
        title = "二次卡住热点",
        buckets = snapshot.profiles,
        limit = safeLimit,
        sortBy = { bucket -> bucket.externalWaitResumeGuardCount.toDouble() },
        formatter = { name, bucket ->
            "$name(恢复保护${bucket.externalWaitResumeGuardCount}, 外部等待${bucket.externalWaitEnteredCount})"
        },
        predicate = { bucket -> bucket.externalWaitResumeGuardCount > 0 },
    )?.let(lines::add)
    return lines.joinToString("\n")
}

internal fun dashboardHeaderLine(snapshot: HarnessStore.AggregateSnapshot): String {
    val runCount = snapshot.runCount
    val successRate = (snapshot.successRate * 100.0).toInt()
    val avgTurns = snapshot.avgTurns
    return "总任务: $runCount | 完成率: ${successRate}% | 平均轮数: ${"%.1f".format(avgTurns)}"
}

internal fun takeoverHeaderLine(snapshot: HarnessStore.AggregateSnapshot): String? {
    val hasTakeover = snapshot.takeoverSessionCount > 0
    if (!hasTakeover) {
        return null
    }
    val totalTakeovers =
        snapshot.externalWaitEnteredCount +
            snapshot.safetyConfirmationRequestedCount +
            snapshot.manualResumeCount +
            snapshot.resumeSnapshotRestoredCount
    val totalResolvedSessions = snapshot.takeoverResolvedSessionCount
    val totalRejectedSessions = snapshot.takeoverRejectedSessionCount
    val totalResumeSuccess = snapshot.takeoverResumeSuccessCount
    val totalReblocked = snapshot.externalWaitResumeGuardCount
    val resumeSuccessRate =
        if (totalResolvedSessions <= 0) {
            0
        } else {
            ((totalResumeSuccess.toDouble() / totalResolvedSessions.toDouble()) * 100.0).toInt()
        }
    return "人工接管: $totalTakeovers 次 | 已恢复会话: $totalResolvedSessions | 恢复后完成率: ${resumeSuccessRate}% | 二次卡住: $totalReblocked | 拒绝: $totalRejectedSessions"
}

internal fun resumeSnapshotHeaderLine(snapshot: HarnessStore.AggregateSnapshot): String? {
    if (snapshot.resumeSnapshotRestoredCount <= 0 && snapshot.resumeSnapshotContinueCount <= 0) {
        return null
    }
    return "恢复快照链路: 已恢复 ${snapshot.resumeSnapshotRestoredCount} | 已继续 ${snapshot.resumeSnapshotContinueCount}"
}

internal fun resumeContinuationHeaderLine(snapshot: HarnessStore.AggregateSnapshot): String? {
    if (
        snapshot.resumeContinuationAttemptCount <= 0 &&
        snapshot.resumeContinuationSuccessCount <= 0 &&
        snapshot.resumeContinuationRecoveryCount <= 0 &&
        snapshot.resumeContinuationNoProgressCount <= 0 &&
        snapshot.resumeContinuationEmptyObservationCount <= 0
    ) {
        return null
    }
    return "恢复继续链路: 尝试 ${snapshot.resumeContinuationAttemptCount} | 命中 ${snapshot.resumeContinuationSuccessCount} | 转恢复 ${snapshot.resumeContinuationRecoveryCount} | 入口未推进 ${snapshot.resumeContinuationNoProgressCount} | 页面未稳定 ${snapshot.resumeContinuationEmptyObservationCount}"
}

internal fun resumeContinuationStageLabel(
    bucketName: String,
): String =
    when (bucketName.removePrefix("resume_continuation_")) {
        "submit_query" -> "提交查询"
        "confirm_route" -> "路线确认"
        "confirm_send" -> "发送确认"
        else -> bucketName
    }

internal fun resumeContinuationFailureSummary(
    bucketName: String,
    bucket: HarnessStore.AggregateBucketSnapshot,
): String =
    when (bucketName.removePrefix("resume_continuation_")) {
        "submit_query" ->
            "仍停留搜索入口${bucket.resumeContinuationNoProgressCount}, 页面未稳定${bucket.resumeContinuationEmptyObservationCount}"

        "confirm_route" ->
            "仍停留路线确认面${bucket.resumeContinuationNoProgressCount}, 页面未稳定${bucket.resumeContinuationEmptyObservationCount}"

        "confirm_send" ->
            "仍停留发送确认面${bucket.resumeContinuationNoProgressCount}, 页面未稳定${bucket.resumeContinuationEmptyObservationCount}"

        else ->
            "入口未推进${bucket.resumeContinuationNoProgressCount}, 页面未稳定${bucket.resumeContinuationEmptyObservationCount}"
    }

internal fun summarizeBucketsFromSnapshots(
    title: String,
    buckets: List<HarnessStore.AggregateBucketSnapshot>,
    limit: Int,
    sortBy: (HarnessStore.AggregateBucketSnapshot) -> Double,
    formatter: (String, HarnessStore.AggregateBucketSnapshot) -> String,
    ascending: Boolean = false,
    predicate: (HarnessStore.AggregateBucketSnapshot) -> Boolean = { true },
): String? {
    val entries =
        buckets.asSequence()
            .filter(predicate)
            .map { bucket -> Triple(bucket.name, bucket, sortBy(bucket)) }
            .sortedWith(
                compareBy<Triple<String, HarnessStore.AggregateBucketSnapshot, Double>> { it.third }
                    .let { comparator -> if (ascending) comparator else comparator.reversed() },
            )
            .take(limit)
            .map { (name, bucket, _) -> formatter(name, bucket) }
            .toList()
    return entries.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = "$title: ")
}

internal fun snapshotFromAggregate(
    json: JSONObject,
): HarnessStore.AggregateSnapshot =
    HarnessStore.AggregateSnapshot(
        runCount = json.optInt("run_count", 0),
        successRate = json.optDouble("success_rate", 0.0),
        avgTurns = json.optDouble("avg_turns", 0.0),
        externalWaitEnteredCount = json.optInt("external_wait_entered_count", 0),
        externalWaitResolvedCount = json.optInt("external_wait_resolved_count", 0),
        externalWaitResumeGuardCount = json.optInt("external_wait_resume_guard_count", 0),
        safetyConfirmationRequestedCount = json.optInt("safety_confirmation_requested_count", 0),
        safetyConfirmationApprovedCount = json.optInt("safety_confirmation_approved_count", 0),
        safetyConfirmationRejectedCount = json.optInt("safety_confirmation_rejected_count", 0),
        manualResumeCount = json.optInt("manual_resume_count", 0),
        resumeSnapshotRestoredCount = json.optInt("resume_snapshot_restored_count", 0),
        resumeSnapshotContinueCount = json.optInt("resume_snapshot_continue_count", 0),
        resumeContinuationAttemptCount = json.optInt("resume_continuation_attempt_count", 0),
        resumeContinuationSuccessCount = json.optInt("resume_continuation_success_count", 0),
        resumeContinuationRecoveryCount = json.optInt("resume_continuation_recovery_count", 0),
        resumeContinuationNoProgressCount = json.optInt("resume_continuation_no_progress_count", 0),
        resumeContinuationEmptyObservationCount = json.optInt("resume_continuation_empty_observation_count", 0),
        takeoverSessionCount = json.optInt("takeover_session_count", 0),
        takeoverResolvedSessionCount = json.optInt("takeover_resolved_session_count", 0),
        takeoverRejectedSessionCount = json.optInt("takeover_rejected_session_count", 0),
        takeoverResumeSuccessCount = json.optInt("takeover_resume_success_count", 0),
        intents = bucketSnapshots(json.optJSONObject("intents") ?: JSONObject()),
        scenarios = bucketSnapshots(json.optJSONObject("scenarios") ?: JSONObject()),
        resultIntents = bucketSnapshots(json.optJSONObject("result_intents") ?: JSONObject()),
        skills = bucketSnapshots(json.optJSONObject("skills") ?: JSONObject()),
        profiles = bucketSnapshots(json.optJSONObject("profiles") ?: JSONObject()),
        planTypes = bucketSnapshots(json.optJSONObject("plan_types") ?: JSONObject()),
        suites = bucketSnapshots(json.optJSONObject("suites") ?: JSONObject()),
        personas = bucketSnapshots(json.optJSONObject("personas") ?: JSONObject()),
        maturities = bucketSnapshots(json.optJSONObject("maturities") ?: JSONObject()),
        routePolicies = bucketSnapshots(json.optJSONObject("route_policies") ?: JSONObject()),
        takeovers = bucketSnapshots(json.optJSONObject("takeovers") ?: JSONObject()),
    )

internal fun bucketSnapshots(
    buckets: JSONObject,
): List<HarnessStore.AggregateBucketSnapshot> =
    buckets.keys().asSequence()
        .mapNotNull { name ->
            val bucket = buckets.optJSONObject(name) ?: return@mapNotNull null
            HarnessStore.AggregateBucketSnapshot(
                name = name,
                runCount = bucket.optInt("run_count", 0),
                successRate = bucket.optDouble("success_rate", 0.0),
                avgTurns = bucket.optDouble("avg_turns", 0.0),
                externalWaitEnteredCount = bucket.optInt("external_wait_entered_count", 0),
                externalWaitResolvedCount = bucket.optInt("external_wait_resolved_count", 0),
                externalWaitResumeGuardCount = bucket.optInt("external_wait_resume_guard_count", 0),
                safetyConfirmationRequestedCount = bucket.optInt("safety_confirmation_requested_count", 0),
                safetyConfirmationApprovedCount = bucket.optInt("safety_confirmation_approved_count", 0),
                safetyConfirmationRejectedCount = bucket.optInt("safety_confirmation_rejected_count", 0),
                manualResumeCount = bucket.optInt("manual_resume_count", 0),
                resumeSnapshotRestoredCount = bucket.optInt("resume_snapshot_restored_count", 0),
                resumeSnapshotContinueCount = bucket.optInt("resume_snapshot_continue_count", 0),
                resumeContinuationAttemptCount = bucket.optInt("resume_continuation_attempt_count", 0),
                resumeContinuationSuccessCount = bucket.optInt("resume_continuation_success_count", 0),
                resumeContinuationRecoveryCount = bucket.optInt("resume_continuation_recovery_count", 0),
                resumeContinuationNoProgressCount = bucket.optInt("resume_continuation_no_progress_count", 0),
                resumeContinuationEmptyObservationCount = bucket.optInt("resume_continuation_empty_observation_count", 0),
                takeoverSessionCount = bucket.optInt("takeover_session_count", 0),
                takeoverResolvedSessionCount = bucket.optInt("takeover_resolved_session_count", 0),
                takeoverRejectedSessionCount = bucket.optInt("takeover_rejected_session_count", 0),
                takeoverResumeSuccessCount = bucket.optInt("takeover_resume_success_count", 0),
                routeOverrideCount = bucket.optInt("route_override_count", 0),
                fallbackCount = bucket.optInt("route_fallback_count", 0),
            )
        }.toList()
