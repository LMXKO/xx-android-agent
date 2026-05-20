package com.lmx.xiaoxuanagent.harness

internal data class ScreenAutomationUrgency(
    val penalty: Int = 0,
    val reason: String = "",
)

internal fun screenAutomationUrgency(
    scenario: BenchmarkScenario,
): ScreenAutomationUrgency {
    val snapshot =
        ScreenAutomationCapabilityStore.snapshot(
            profileId = scenario.profileId,
            taskIntent = scenario.intentType,
        ) ?: return ScreenAutomationUrgency()
    val releaseGate = ScreenAutomationReleaseGateStore.snapshot(profileId = scenario.profileId, taskIntent = scenario.intentType)
    val weakestStage =
        scenario.expectedStageHints
            .mapNotNull { stage ->
                ScreenAutomationTemplateCapabilityStore.snapshot(
                    profileId = scenario.profileId,
                    taskIntent = scenario.intentType,
                    planStage = stage,
                )
            }.minByOrNull { snapshotForStage ->
                when (snapshotForStage.confidenceBand) {
                    "weak" -> 0
                    "monitor" -> 1
                    "new" -> 2
                    else -> 3
                }
            }
    val penalty =
        when (snapshot.confidenceBand) {
            "weak" -> 8
            "monitor" -> 4
            "new" -> 2
            else -> 0
        } + when (weakestStage?.confidenceBand) {
            "weak" -> 4
            "monitor" -> 2
            else -> 0
        } + when (releaseGate?.gateStatus) {
            "red" -> 6
            "yellow" -> 3
            else -> 0
        }
    if (penalty == 0) return ScreenAutomationUrgency()
    return ScreenAutomationUrgency(
        penalty = penalty,
        reason =
            buildString {
                append("screen_auto=")
                append(snapshot.confidenceBand)
                append(", recent=").append((snapshot.recentSuccessRate * 100.0).toInt()).append('%')
                if (snapshot.preferFinalHandoff) {
                    append(", handoff=").append((snapshot.handoffReadyRate * 100.0).toInt()).append('%')
                }
                weakestStage?.let { stage ->
                    append(", stage=").append(stage.planStage)
                    append(':').append(stage.confidenceBand)
                    append('/').append((stage.recentCleanRate * 100.0).toInt()).append('%')
                }
                releaseGate?.let { append(", gate=").append(it.gateStatus) }
            },
    )
}

internal fun screenAutomationDashboardLines(limit: Int = 2): List<String> =
    (ScreenAutomationCapabilityStore.dashboardLines(limit) + ScreenAutomationReleaseGateStore.dashboardLines()).take(limit + 3)
