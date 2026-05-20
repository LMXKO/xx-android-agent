package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.AlarmClock

object PlatformTimerToolService {
    internal const val DIRECT_STOPWATCH_ACTION = "android.intent.action.SHOW_STOPWATCH"
    internal const val APP_CLOCK_CATEGORY = "android.intent.category.APP_CLOCK"

    fun createTimer(
        service: AccessibilityService,
        durationLabel: String,
        message: String,
    ): AgentExecutionResult {
        val seconds = parseDurationSeconds(durationLabel)
            ?: return AgentExecutionResult("无法解析计时器时长：${durationLabel.ifBlank { "-" }}。", keepRunning = false)
        val intent =
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message.take(80))
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runIntent(
            service = service,
            intent = intent,
            successMessage = "已尝试创建计时器：${durationLabel.take(24)}。",
            detailLines =
                listOf(
                    "receipt=timer_create_requested",
                    "duration_seconds=$seconds",
                    "mode=handoff_required",
                    "completion=user_confirm_or_start_required",
                ),
        )
    }

    fun openStopwatch(
        service: AccessibilityService,
    ): AgentExecutionResult {
        val candidates = stopwatchCandidateIntents()
        val directSupported = candidates.firstOrNull()?.resolveActivity(service.packageManager) != null
        val intent =
            candidates.firstOrNull { candidate ->
                candidate.resolveActivity(service.packageManager) != null
            }
                ?: return AgentExecutionResult("当前系统未找到可用的时钟入口，建议通过 GUI 打开秒表。", keepRunning = false)
        return runIntent(
            service = service,
            intent = intent,
            successMessage = stopwatchSuccessMessage(directSupported),
            detailLines =
                listOf(
                    if (directSupported) "receipt=stopwatch_opened" else "receipt=clock_opened_for_stopwatch",
                    if (directSupported) "handoff=none" else "handoff=locate_stopwatch_tab",
                ),
        )
    }

    internal fun stopwatchCandidateIntents(): List<Intent> =
        listOf(
            Intent(DIRECT_STOPWATCH_ACTION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, APP_CLOCK_CATEGORY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

    internal fun stopwatchSuccessMessage(
        directStopwatchSupported: Boolean,
    ): String =
        if (directStopwatchSupported) {
            "已打开时钟秒表界面。"
        } else {
            "当前系统未提供通用秒表直达入口，已打开时钟应用，请继续定位秒表。"
        }

    private fun runIntent(
        service: AccessibilityService,
        intent: Intent,
        successMessage: String,
        detailLines: List<String> = emptyList(),
    ): AgentExecutionResult =
        runCatching {
            service.startActivity(intent)
            AgentExecutionResult(
                message = successMessage,
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 900L,
                groundingSource = "system_timer",
                toolRuntimeDetailLines = detailLines,
            ).also {
                UtilityExecutionReceiptStore.record(
                    toolName =
                        when {
                            successMessage.contains("秒表") || successMessage.contains("时钟应用") -> "system.open_stopwatch"
                            else -> "system.create_timer"
                        },
                    summary = successMessage,
                    detailLines = detailLines,
                    handoffRequired = detailLines.any { it.contains("handoff=", ignoreCase = true) && !it.endsWith("none") },
                )
            }
        }.getOrElse { error ->
            AgentExecutionResult("系统时钟操作失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }

    private fun parseDurationSeconds(
        raw: String,
    ): Int? {
        val normalized = raw.trim()
        Regex("""(\d{1,3})\s*(秒|s)""").find(normalized)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(\d{1,3})\s*(分钟|分|min|m)""").find(normalized)?.let { return (it.groupValues[1].toIntOrNull() ?: return null) * 60 }
        Regex("""(\d{1,2})[:：](\d{1,2})""").find(normalized)?.let {
            val minutes = it.groupValues[1].toIntOrNull() ?: return null
            val seconds = it.groupValues[2].toIntOrNull() ?: return null
            return minutes * 60 + seconds
        }
        return normalized.toIntOrNull()
    }
}
