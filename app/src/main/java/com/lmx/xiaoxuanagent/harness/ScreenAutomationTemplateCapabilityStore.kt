package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ScreenAutomationTemplateCapabilitySnapshot(
    val profileId: String,
    val packageName: String,
    val taskIntent: String,
    val planStage: String,
    val pageState: String,
    val actionTemplate: String,
    val sampleCount: Int = 0,
    val cleanRate: Double = 0.0,
    val recentCleanRate: Double = 0.0,
    val recoveryRate: Double = 0.0,
    val recentRecoveryRate: Double = 0.0,
    val handoffReadyRate: Double = 0.0,
    val confidenceBand: String = "unknown",
    val preferFinalHandoff: Boolean = false,
    val restrictFill: Boolean = false,
    val restrictSubmit: Boolean = false,
    val note: String = "",
)

object ScreenAutomationTemplateCapabilityStore {
    private const val HARNESS_DIR = "harness"
    private const val TEMPLATE_FILE = "screen_automation_template_capabilities.json"
    private const val MAX_SAMPLES = 960
    private val lock = Any()

    fun recordReplay(
        profileId: String,
        packageName: String,
        taskIntent: String,
        turns: JSONArray?,
        handoffReady: Boolean,
        handoffCompleted: Boolean,
    ) {
        if (turns == null || turns.length() == 0) return
        synchronized(lock) {
            val file = storageFile() ?: return@synchronized
            val root = readRoot(file)
            val samples = root.optJSONArray("samples") ?: JSONArray().also { root.put("samples", it) }
            for (index in 0 until turns.length()) {
                val turn = turns.optJSONObject(index) ?: continue
                val planStage = turn.optString("plan_stage")
                if (planStage.isBlank()) continue
                samples.put(
                    JSONObject().apply {
                        put("profile_id", profileId)
                        put("package_name", packageName)
                        put("task_intent", taskIntent)
                        put("plan_stage", planStage)
                        put("page_state", turn.optString("page_state"))
                        put("action_template", normalizeActionTemplate(turn.optString("action")))
                        put("clean", isCleanTurn(turn))
                        put("recovery_category", turn.optString("recovery_category"))
                        put("handoff_ready", handoffReady)
                        put("handoff_completed", handoffCompleted)
                        put("timestamp", turn.optLong("timestamp").takeIf { it > 0L } ?: System.currentTimeMillis())
                    },
                )
            }
            while (samples.length() > MAX_SAMPLES) {
                samples.remove(0)
            }
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2))
        }
    }

    fun snapshot(
        profileId: String,
        packageName: String = "",
        taskIntent: String = "",
        planStage: String = "",
        pageState: String = "",
        actionTemplate: String = "",
    ): ScreenAutomationTemplateCapabilitySnapshot? =
        synchronized(lock) {
            val file = storageFile() ?: return@synchronized null
            if (!file.exists()) return@synchronized null
            val samples = readRoot(file).optJSONArray("samples") ?: return@synchronized null
            buildSnapshot(
                profileId = profileId,
                packageName = packageName,
                taskIntent = taskIntent,
                planStage = planStage,
                pageState = pageState,
                actionTemplate = actionTemplate,
                samples =
                    buildList {
                        for (index in 0 until samples.length()) {
                            val item = samples.optJSONObject(index) ?: continue
                            if (!matches(item, profileId, packageName, taskIntent, planStage, pageState, actionTemplate)) continue
                            add(item)
                        }
                    },
            )
        }

    private fun buildSnapshot(
        profileId: String,
        packageName: String,
        taskIntent: String,
        planStage: String,
        pageState: String,
        actionTemplate: String,
        samples: List<JSONObject>,
    ): ScreenAutomationTemplateCapabilitySnapshot? {
        if (samples.isEmpty()) return null
        val recent = samples.sortedByDescending { it.optLong("timestamp") }.take(6)
        val cleanRate = samples.count { it.optBoolean("clean") }.toDouble() / samples.size.toDouble()
        val recentCleanRate = recent.count { it.optBoolean("clean") }.toDouble() / recent.size.coerceAtLeast(1).toDouble()
        val recoveryRate = samples.count { it.optString("recovery_category").isNotBlank() }.toDouble() / samples.size.toDouble()
        val recentRecoveryRate = recent.count { it.optString("recovery_category").isNotBlank() }.toDouble() / recent.size.coerceAtLeast(1).toDouble()
        val handoffReadyRate = samples.count { it.optBoolean("handoff_ready") }.toDouble() / samples.size.toDouble()
        val stage = planStage.ifBlank { samples.lastOrNull()?.optString("plan_stage").orEmpty() }
        val template = actionTemplate.ifBlank { samples.lastOrNull()?.optString("action_template").orEmpty() }
        val isFillStage = ScreenAutomationFocusStageSupport.isFillStage(profileId = profileId, stage = stage)
        val isSubmitStage = ScreenAutomationFocusStageSupport.isSubmitStage(profileId = profileId, stage = stage)
        val latestRecoveryCategory = recent.firstOrNull()?.optString("recovery_category").orEmpty()
        val confidenceBand =
            when {
                samples.size < 3 -> "new"
                recentRecoveryRate >= 0.45 -> "weak"
                recentCleanRate >= 0.85 && cleanRate >= 0.75 && recentRecoveryRate < 0.15 -> "strong"
                recentCleanRate >= 0.6 && recentRecoveryRate < 0.35 -> "monitor"
                else -> "weak"
            }
        val preferFinalHandoff = isSubmitStage && (handoffReadyRate >= 0.35 || recentCleanRate < 0.7 || recentRecoveryRate >= 0.25)
        val restrictFill = isFillStage && samples.size >= 3 && (recentCleanRate < 0.45 || recentRecoveryRate >= 0.5)
        val restrictSubmit = isSubmitStage && samples.size >= 3 && (recentCleanRate < 0.75 || recentRecoveryRate >= 0.25)
        val note =
            buildString {
                append("阶段 ").append(ScreenAutomationFocusStageSupport.stageLabel(profileId = profileId, stage = stage))
                template.takeIf { it.isNotBlank() }?.let {
                    append(" / ").append(it)
                }
                append(" 最近 ").append(samples.size).append(" 次")
                append(" clean=").append(percent(cleanRate))
                append(" recovery=").append(percent(recoveryRate))
                if (preferFinalHandoff) {
                    append("，建议优先推进到最终确认前一步。")
                } else if (restrictFill || restrictSubmit) {
                    append("，当前稳定度不足，建议收紧自动动作。")
                } else {
                    append("，可保持前台连续执行。")
                }
                ScreenAutomationFocusStageSupport
                    .recoveryHint(
                        profileId = profileId,
                        stage = stage,
                        recentRecoveryRate = recentRecoveryRate,
                        latestRecoveryCategory = latestRecoveryCategory,
                    )?.let {
                        append(' ').append(it)
                    }
            }
        return ScreenAutomationTemplateCapabilitySnapshot(
            profileId = profileId,
            packageName = packageName,
            taskIntent = taskIntent,
            planStage = stage,
            pageState = pageState,
            actionTemplate = template,
            sampleCount = samples.size,
            cleanRate = cleanRate,
            recentCleanRate = recentCleanRate,
            recoveryRate = recoveryRate,
            recentRecoveryRate = recentRecoveryRate,
            handoffReadyRate = handoffReadyRate,
            confidenceBand = confidenceBand,
            preferFinalHandoff = preferFinalHandoff,
            restrictFill = restrictFill,
            restrictSubmit = restrictSubmit,
            note = note,
        )
    }

    private fun matches(
        item: JSONObject,
        profileId: String,
        packageName: String,
        taskIntent: String,
        planStage: String,
        pageState: String,
        actionTemplate: String,
    ): Boolean {
        if (profileId.isNotBlank() && item.optString("profile_id") != profileId && item.optString("package_name") != packageName) return false
        if (packageName.isNotBlank() && item.optString("package_name") != packageName) return false
        if (taskIntent.isNotBlank() && item.optString("task_intent") != taskIntent) return false
        if (planStage.isNotBlank() && item.optString("plan_stage") != planStage) return false
        if (pageState.isNotBlank() && item.optString("page_state") != pageState) return false
        if (actionTemplate.isNotBlank() && item.optString("action_template") != normalizeActionTemplate(actionTemplate)) return false
        return true
    }

    private fun isCleanTurn(
        turn: JSONObject,
    ): Boolean {
        if (turn.optString("recovery_category").isNotBlank()) return false
        val result = turn.optString("result")
        return !result.contains("失败") && !result.contains("未执行")
    }

    private fun normalizeActionTemplate(
        value: String,
    ): String =
        value.trim()
            .lowercase()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9_\\u4e00-\\u9fa5]"), "")

    private fun percent(value: Double): String = "${(value * 100.0).toInt()}%"

    private fun storageFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, HARNESS_DIR), TEMPLATE_FILE)
    }

    private fun readRoot(file: File): JSONObject =
        runCatching {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        }.getOrElse { JSONObject() }
}
