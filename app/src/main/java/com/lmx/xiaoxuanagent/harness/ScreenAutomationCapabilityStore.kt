package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.taskprofile.AutomationSupportPolicyStore
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ScreenAutomationCapabilitySnapshot(
    val profileId: String,
    val packageName: String,
    val runCount: Int = 0,
    val automationSuccessRate: Double = 0.0,
    val recentSuccessRate: Double = 0.0,
    val handoffReadyRate: Double = 0.0,
    val handoffCompletionRate: Double = 0.0,
    val avgTurns: Double = 0.0,
    val confidenceBand: String = "unknown",
    val preferFinalHandoff: Boolean = false,
    val restrictFill: Boolean = false,
    val restrictSubmit: Boolean = false,
    val note: String = "",
)

object ScreenAutomationCapabilityStore {
    private const val HARNESS_DIR = "harness"
    private const val CAPABILITY_FILE = "screen_automation_capabilities.json"
    private const val MAX_RUNS = 240
    private val lock = Any()

    fun recordRun(
        profileId: String,
        packageName: String,
        taskIntent: String,
        status: String,
        turnCount: Int,
        handoffReady: Boolean,
        handoffCompleted: Boolean,
        waitingExternal: Boolean,
    ) {
        synchronized(lock) {
            val file = storageFile() ?: return@synchronized
            val root = readRoot(file)
            val runs = root.optJSONArray("runs") ?: JSONArray().also { root.put("runs", it) }
            runs.put(
                JSONObject().apply {
                    put("profile_id", profileId)
                    put("package_name", packageName)
                    put("task_intent", taskIntent)
                    put("status", status)
                    put("turn_count", turnCount)
                    put("handoff_ready", handoffReady)
                    put("handoff_completed", handoffCompleted)
                    put("waiting_external", waitingExternal)
                    put("timestamp", System.currentTimeMillis())
                },
            )
            while (runs.length() > MAX_RUNS) {
                runs.remove(0)
            }
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2))
        }
    }

    fun snapshot(
        profileId: String,
        packageName: String = "",
        taskIntent: String = "",
    ): ScreenAutomationCapabilitySnapshot? =
        synchronized(lock) {
            val file = storageFile() ?: return@synchronized null
            if (!file.exists()) return@synchronized null
            val runs = readRoot(file).optJSONArray("runs") ?: return@synchronized null
            buildSnapshot(
                runs = filterRuns(runs, profileId = profileId, packageName = packageName, taskIntent = taskIntent),
                profileId = profileId,
                packageName = resolvePackageName(profileId = profileId, packageName = packageName),
            )
        }

    fun dashboardLines(limit: Int = 3): List<String> =
        synchronized(lock) {
            val file = storageFile() ?: return@synchronized listOf("screen_auto | 暂无专项样本")
            if (!file.exists()) return@synchronized listOf("screen_auto | 暂无专项样本")
            val runs = readRoot(file).optJSONArray("runs") ?: return@synchronized listOf("screen_auto | 暂无专项样本")
            val grouped =
                mutableMapOf<Pair<String, String>, MutableList<JSONObject>>()
            for (index in 0 until runs.length()) {
                val item = runs.optJSONObject(index) ?: continue
                val profileId = item.optString("profile_id")
                val packageName = item.optString("package_name")
                val key = profileId to packageName
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }
            grouped.entries
                .mapNotNull { (key, values) ->
                    buildSnapshot(
                        runs = values,
                        profileId = key.first,
                        packageName = resolvePackageName(key.first, key.second),
                    )
                }.sortedWith(
                    compareBy<ScreenAutomationCapabilitySnapshot> { confidenceRank(it.confidenceBand) }
                        .thenBy { it.recentSuccessRate }
                        .thenByDescending { it.runCount },
                )
                .take(limit)
                .map { snapshot ->
                    buildString {
                        append("screen_auto | ")
                        append(snapshot.profileId.ifBlank { snapshot.packageName.ifBlank { "default" } })
                        append(" | success=").append(percent(snapshot.automationSuccessRate))
                        append(" | recent=").append(percent(snapshot.recentSuccessRate))
                        append(" | handoff=").append(percent(snapshot.handoffReadyRate))
                        append(" | band=").append(snapshot.confidenceBand)
                    }
                }.ifEmpty { listOf("screen_auto | 暂无专项样本") }
        }

    private fun buildSnapshot(
        runs: List<JSONObject>,
        profileId: String,
        packageName: String,
    ): ScreenAutomationCapabilitySnapshot? {
        if (runs.isEmpty()) return null
        val policy = AutomationSupportPolicyStore.staticPolicy(profileId = profileId, packageName = packageName)
        val successCount =
            runs.count { run ->
                val status = run.optString("status")
                val reachedHandoff = run.optBoolean("handoff_ready")
                when {
                    policy.requiresFinalHandoff -> reachedHandoff || isCompletedStatus(status)
                    else -> isCompletedStatus(status)
                }
            }
        val recentRuns = runs.sortedByDescending { it.optLong("timestamp") }.take(6)
        val recentSuccessCount =
            recentRuns.count { run ->
                val status = run.optString("status")
                val reachedHandoff = run.optBoolean("handoff_ready")
                when {
                    policy.requiresFinalHandoff -> reachedHandoff || isCompletedStatus(status)
                    else -> isCompletedStatus(status)
                }
            }
        val handoffReadyCount = runs.count { it.optBoolean("handoff_ready") || it.optBoolean("waiting_external") }
        val handoffCompletionCount = runs.count { it.optBoolean("handoff_completed") || isCompletedStatus(it.optString("status")) }
        val avgTurns = runs.map { it.optInt("turn_count") }.average().takeIf { !it.isNaN() } ?: 0.0
        val automationSuccessRate = successCount.toDouble() / runs.size.toDouble()
        val recentSuccessRate = recentSuccessCount.toDouble() / recentRuns.size.coerceAtLeast(1).toDouble()
        val handoffReadyRate = handoffReadyCount.toDouble() / runs.size.toDouble()
        val handoffCompletionRate = handoffCompletionCount.toDouble() / runs.size.toDouble()
        val confidenceBand =
            when {
                runs.size < 3 -> "new"
                recentSuccessRate >= 0.85 && automationSuccessRate >= 0.75 -> "strong"
                recentSuccessRate >= 0.6 -> "monitor"
                else -> "weak"
            }
        val preferFinalHandoff = policy.requiresFinalHandoff || (runs.size >= 3 && handoffReadyRate >= 0.45)
        val restrictFill = runs.size >= 4 && recentSuccessRate < 0.35
        val restrictSubmit = preferFinalHandoff || (runs.size >= 4 && automationSuccessRate < 0.4)
        val note =
            buildString {
                append("最近 ").append(runs.size).append(" 次屏幕自动化")
                append("，推进成功率 ").append(percent(automationSuccessRate))
                append("，最近 handoff-ready ").append(percent(handoffReadyRate))
                if (preferFinalHandoff) {
                    append("，建议优先自动推进到最后确认前一步。")
                } else {
                    append("，可继续保持前台连续执行。")
                }
            }
        return ScreenAutomationCapabilitySnapshot(
            profileId = profileId,
            packageName = packageName,
            runCount = runs.size,
            automationSuccessRate = automationSuccessRate,
            recentSuccessRate = recentSuccessRate,
            handoffReadyRate = handoffReadyRate,
            handoffCompletionRate = handoffCompletionRate,
            avgTurns = avgTurns,
            confidenceBand = confidenceBand,
            preferFinalHandoff = preferFinalHandoff,
            restrictFill = restrictFill,
            restrictSubmit = restrictSubmit,
            note = note,
        )
    }

    private fun filterRuns(
        runs: JSONArray,
        profileId: String,
        packageName: String,
        taskIntent: String,
    ): List<JSONObject> {
        val resolvedPackage = resolvePackageName(profileId = profileId, packageName = packageName)
        return buildList {
            for (index in 0 until runs.length()) {
                val item = runs.optJSONObject(index) ?: continue
                val itemProfile = item.optString("profile_id")
                val itemPackage = item.optString("package_name")
                if (itemProfile == profileId || (resolvedPackage.isNotBlank() && itemPackage == resolvedPackage)) {
                    if (taskIntent.isNotBlank() && item.optString("task_intent") != taskIntent) continue
                    add(item)
                }
            }
        }
    }

    private fun resolvePackageName(
        profileId: String,
        packageName: String,
    ): String =
        packageName.ifBlank {
            TaskRegistry.get(profileId)?.packageName.orEmpty()
        }

    private fun storageFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, HARNESS_DIR), CAPABILITY_FILE)
    }

    private fun readRoot(file: File): JSONObject =
        runCatching {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        }.getOrElse { JSONObject() }

    private fun percent(value: Double): String = "${(value * 100.0).toInt()}%"

    private fun isCompletedStatus(status: String): Boolean = status == "COMPLETED"

    private fun confidenceRank(band: String): Int =
        when (band) {
            "weak" -> 0
            "monitor" -> 1
            "new" -> 2
            "strong" -> 3
            else -> 4
        }
}
