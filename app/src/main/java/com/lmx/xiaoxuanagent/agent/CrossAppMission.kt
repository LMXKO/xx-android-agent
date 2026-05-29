package com.lmx.xiaoxuanagent.agent

/**
 * 一个跨 App 任务里的单腿（leg）。每条 leg 仍是一次"单 App"子任务，
 * 复用现有 MultiStepTaskEngine / Planner / Executor 机器。
 */
data class MissionLeg(
    val legId: String,
    val profileId: String,
    val targetPackageName: String,
    val subTask: String,
    val intentType: String = "shopping",
    val status: String = "pending",
    // 上一腿交接进来的结构化实体槽位（如 query/price/entity），与 subTask 里的自然语言注释并存，便于校验与下游使用。
    val handoff: Map<String, String> = emptyMap(),
)

/**
 * 跨 App mission：把一个用户目标拆成有序的单 App leg 序列，
 * leg 之间用 [TaskResultPayload] 在 [blackboard] 上传递结果。
 *
 * 设计要点：活跃 leg 的 profileId/targetPackageName/subTask 会被镜像进
 * RuntimeSession 的标准字段，因此现有规划/执行/恢复链无需改动。
 */
data class CrossAppMission(
    val missionId: String,
    val goal: String,
    val legs: List<MissionLeg>,
    val activeLegIndex: Int = 0,
    val blackboard: List<TaskResultPayload> = emptyList(),
    val declaredHandoffFields: Set<String> = emptySet(),
    // mission 类型：compare（电商比价收口）/ general（通用多腿任务收口）。决定 composeFinalResult 的产出形态，
    // 避免把通用任务都套成"比价"。
    val kind: String = "general",
) {
    fun activeLeg(): MissionLeg? = legs.getOrNull(activeLegIndex)

    fun hasNextLeg(): Boolean = activeLegIndex in 0 until (legs.size - 1)

    fun failedLegCount(): Int = legs.count { it.status == "failed" }

    /** 记录当前腿产出并推进到下一腿；当前腿标记 done。 */
    fun recordAndAdvance(payload: TaskResultPayload?): CrossAppMission =
        copy(
            blackboard = if (payload != null) blackboard + payload else blackboard,
            activeLegIndex = (activeLegIndex + 1).coerceAtMost(legs.size),
            legs =
                legs.mapIndexed { index, leg ->
                    if (index == activeLegIndex) leg.copy(status = "done") else leg
                },
        )

    /** 当前腿无法完成时标记为 failed 并跳到下一腿（mission 级降级，避免在某腿原地无限重试）。 */
    fun markActiveLegFailed(): CrossAppMission =
        copy(
            activeLegIndex = (activeLegIndex + 1).coerceAtMost(legs.size),
            legs =
                legs.mapIndexed { index, leg ->
                    if (index == activeLegIndex) leg.copy(status = "failed") else leg
                },
        )

    /** 只记录产出、不推进（用于最后一腿收口时合并结果）。 */
    fun record(payload: TaskResultPayload?): CrossAppMission =
        if (payload != null) copy(blackboard = blackboard + payload) else this
}
