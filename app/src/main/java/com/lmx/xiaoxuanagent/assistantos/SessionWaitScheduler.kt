package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.runtime.SessionResumeSnapshot

/**
 * 长程等待/定时恢复的编排器。把"挂起会话 → 等条件/到点再续跑"落到两个已持久化、跨重启安全的源：
 *
 * - 外部信号等待：[AssistantSignalWaitStore] 绑定（挂起会话 ↔ 目标 App 的 APP_FOREGROUND 信号）。
 * - 时间等待：写入 [AssistantProactiveTaskStore] 的 SCHEDULED_TASK（此前该枚举无任何生产者），
 *   到点由 [PersistentAssistantEngine] 经协调器 bootstrap 续跑 + 拉起目标 App。
 *
 * 本对象只负责"登记 + 计算下次唤醒时刻 + 解析绑定"，真正的恢复（协调器 bootstrap）由
 * [PersistentAssistantEngine] 执行，避免把执行回路耦合进来。
 */
object SessionWaitScheduler {
    const val WAIT_KIND_KEY = "wait_kind"
    const val WAIT_KIND_TIME_RESUME = "time_resume"
    private const val TARGET_PKG_KEY = "target_package_name"

    // nextPlanEligibleAtMs 必须是"未来、且在合理窗口内"的 epoch 毫秒才用来排精确唤醒，
    // 防止把 elapsedRealtime/异常值误当成定时点。
    private const val MIN_LEAD_MS = 30_000L
    private const val MAX_LEAD_MS = 7L * 24 * 60 * 60 * 1000

    data class WaitSyncResult(
        val signalBindings: Int = 0,
        val timeResumes: Int = 0,
    )

    /**
     * 从可恢复快照登记等待：对挂在外部墙（登录/验证/权限/页面未稳）且未待确认的会话，
     * 绑定其目标 App 的 APP_FOREGROUND 信号；若快照带未来的 nextPlanEligibleAtMs，则排定时恢复。
     * 幂等（绑定按 session+type 去重、定时任务按 dedupeKey 去重），可每个自治周期重复调用。
     */
    fun syncSuspendedSessionBindings(
        snapshots: List<SessionResumeSnapshot>,
        nowMs: Long = System.currentTimeMillis(),
    ): WaitSyncResult {
        var signalBindings = 0
        var timeResumes = 0
        snapshots.forEach { snapshot ->
            if (!isSuspendedWaitingExternal(snapshot)) return@forEach
            val sessionId = snapshot.sessionId.takeIf { it.isNotBlank() } ?: return@forEach
            val targetPackageName = snapshot.targetPackageName.takeIf { it.isNotBlank() } ?: return@forEach
            AssistantSignalWaitStore.bind(
                sessionId = sessionId,
                targetPackageName = targetPackageName,
                signalType = AssistantExternalSignalType.APP_FOREGROUND,
                resumeEvent = snapshot.externalWaitState?.event.orEmpty(),
                reason = snapshot.externalWaitState?.reason.orEmpty(),
            )?.let { signalBindings += 1 }
            val fireAtMs = snapshot.nextPlanEligibleAtMs
            if (fireAtMs in (nowMs + MIN_LEAD_MS)..(nowMs + MAX_LEAD_MS)) {
                scheduleTimeResume(
                    sessionId = sessionId,
                    targetPackageName = targetPackageName,
                    fireAtMs = fireAtMs,
                    reason = "next_plan_eligible",
                )
                timeResumes += 1
            }
        }
        return WaitSyncResult(signalBindings = signalBindings, timeResumes = timeResumes)
    }

    /** 登记一个到点恢复会话的定时任务（SCHEDULED_TASK 的首个真实生产者；供外部信号外的定时跟进使用）。 */
    fun scheduleTimeResume(
        sessionId: String,
        targetPackageName: String,
        fireAtMs: Long,
        reason: String,
    ): AssistantProactiveTask? {
        if (sessionId.isBlank() || fireAtMs <= 0L) return null
        return AssistantProactiveTaskStore.schedule(
            type = AssistantProactiveTaskType.SCHEDULED_TASK,
            capability = SessionCapabilityKey.RESUME_SESSION,
            dedupeKey = "time_resume:$sessionId",
            title = "定时续跑",
            summary = "到点后自动恢复会话 $sessionId（$reason）",
            sessionId = sessionId,
            fireAtMs = fireAtMs,
            source = "session_wait_scheduler",
            metadata =
                mapOf(
                    WAIT_KIND_KEY to WAIT_KIND_TIME_RESUME,
                    TARGET_PKG_KEY to targetPackageName,
                    "reason" to reason,
                ),
        )
    }

    fun isTimeResumeTask(task: AssistantProactiveTask): Boolean =
        task.type == AssistantProactiveTaskType.SCHEDULED_TASK &&
            task.metadata[WAIT_KIND_KEY] == WAIT_KIND_TIME_RESUME

    /** 用入站信号解析应被唤醒的挂起会话绑定。 */
    fun resolveSignalBinding(signal: AssistantExternalSignal): AssistantSignalWaitBinding? {
        val packageName = signal.payload["package_name"].orEmpty()
        if (packageName.isBlank()) return null
        return AssistantSignalWaitStore.resolve(signal.type, packageName)
    }

    /** 计算下一次需要被精确唤醒的时刻（最近的未来定时任务），无则返回 null。 */
    fun computeNextWakeAtMs(nowMs: Long = System.currentTimeMillis()): Long? =
        AssistantProactiveTaskStore.readAll(limit = 64)
            .filter { it.enabled && it.fireAtMs > nowMs }
            .minOfOrNull { it.fireAtMs }

    private fun isSuspendedWaitingExternal(snapshot: SessionResumeSnapshot): Boolean =
        snapshot.resumable &&
            !snapshot.safety.awaitingConfirmation &&
            snapshot.externalWaitState != null &&
            snapshot.sessionId.isNotBlank()
}
