package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class AssistantAdaptivePolicySnapshot(
    val preferredEntrySurfaces: List<String> = emptyList(),
    val blockedEntrySurfaces: List<String> = emptyList(),
    val suppressedTipSources: List<String> = emptyList(),
    val preferredAudience: String = "all",
    val preferredDigestCadence: String = "",
    val preferredFocusTheme: String = "",
    val preferredRoutineThemes: List<String> = emptyList(),
    val preferredCommandFamilies: List<String> = emptyList(),
    val trustedSignalSources: List<String> = emptyList(),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

internal object AssistantAdaptivePolicyStore {
    private const val POLICY_DIR = "assistant_os"
    private const val POLICY_FILE = "adaptive_policy.json"
    private val lock = Any()

    fun read(): AssistantAdaptivePolicySnapshot =
        synchronized(lock) {
            readUnlocked()
        }

    fun refresh(
        productShell: AssistantProductShellSnapshot,
        analytics: AssistantAnalyticsSnapshot = AssistantAnalyticsStore.read(),
        userModel: PersonalAssistantUserModelSnapshot = productShell.userModel,
    ): AssistantAdaptivePolicySnapshot =
        synchronized(lock) {
            val next = derive(productShell, analytics, userModel)
            writeUnlocked(next)
            next
        }

    internal fun replace(
        snapshot: AssistantAdaptivePolicySnapshot,
    ): AssistantAdaptivePolicySnapshot =
        synchronized(lock) {
            writeUnlocked(snapshot)
            snapshot
        }

    internal fun derive(
        productShell: AssistantProductShellSnapshot,
        analytics: AssistantAnalyticsSnapshot,
        userModel: PersonalAssistantUserModelSnapshot,
    ): AssistantAdaptivePolicySnapshot {
        val surfaceCounts =
            analytics.events
                .filter { it.name == "assistant_entry" }
                .mapNotNull { it.metadata["surface"]?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
        val preferredEntrySurfaces =
            surfaceCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
        val dismissCounts =
            productShell.tipLedger
                .filter { it.status == "dismissed" }
                .groupingBy { it.source.ifBlank { "unknown" } }
                .eachCount()
        val completedCounts =
            productShell.tipLedger
                .filter { it.status == "completed" }
                .groupingBy { it.source.ifBlank { "unknown" } }
                .eachCount()
        val suppressedTipSources =
            dismissCounts.entries
                .filter { (source, count) -> count >= 2 && count > (completedCounts[source] ?: 0) }
                .sortedByDescending { it.value }
                .map { it.key }
        val audienceCounts =
            productShell.tipLedger
                .filter { it.status == "completed" || it.status == "active" }
                .groupingBy { it.audience.ifBlank { "all" } }
                .eachCount()
        val preferredAudience =
            audienceCounts.maxByOrNull { it.value }?.key ?: "all"
        val totalDismissed = dismissCounts.values.sum()
        val totalCompleted = completedCounts.values.sum()
        val preferredDigestCadence =
            when {
                totalDismissed >= 3 && totalDismissed > totalCompleted -> "quiet"
                preferredEntrySurfaces.isNotEmpty() && totalCompleted >= totalDismissed -> "focused"
                else -> "adaptive"
            }
        val preferredFocusTheme =
            when {
                preferredAudience == "operator" -> "审批收口"
                preferredAudience == "personal" -> "生活跟进"
                suppressedTipSources.any { it == "provider" || it == "diagnostics" } -> "稳定性修复"
                userModel.preferredThemes.isNotEmpty() -> userModel.preferredThemes.first()
                else -> productShell.routinePolicy.focusTheme
            }
        val blockedEntrySurfaces =
            buildList {
                if (preferredDigestCadence == "quiet") add("voice")
                if (suppressedTipSources.isNotEmpty()) add("share_sheet")
                if (productShell.autonomyPlan.mode == "approval_guard") add("share_sheet")
            }.distinct()
        val memoryPreferredSurfaces =
            userModel.lines
                .firstOrNull { it.startsWith("apps |") }
                ?.let { line ->
                    buildList {
                        if (line.contains("message", ignoreCase = true)) add("notification")
                        if (line.contains("calendar", ignoreCase = true)) add("widget")
                    }
                }.orEmpty()
        val preferredRoutineThemes =
            analytics.events
                .mapNotNull { event ->
                    event.metadata["routine_theme"]
                        ?.takeIf(String::isNotBlank)
                        ?: if (event.name.contains("routine", ignoreCase = true)) event.source.takeIf(String::isNotBlank) else null
                }.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
                .ifEmpty {
                    listOfNotNull(
                        preferredFocusTheme.takeIf(String::isNotBlank),
                        productShell.routinePolicy.focusTheme.takeIf(String::isNotBlank),
                        userModel.preferredThemes.firstOrNull()?.takeIf(String::isNotBlank),
                    ).distinct()
                }
        val preferredCommandFamilies =
            analytics.events
                .mapNotNull { event ->
                    event.metadata["command_family"]
                        ?.takeIf(String::isNotBlank)
                        ?: event.metadata["command"]?.substringBefore(" ")?.takeIf { it.startsWith("/") }
                }.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(4)
                .map { it.key }
        val trustedSignalSources =
            analytics.events
                .filter { it.name.contains("signal", ignoreCase = true) || it.source.contains("signal", ignoreCase = true) }
                .mapNotNull { it.metadata["signal_source"]?.takeIf(String::isNotBlank) ?: it.source.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
                .entries
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .take(4)
                .map { it.key }
        return AssistantAdaptivePolicySnapshot(
            preferredEntrySurfaces = (preferredEntrySurfaces + memoryPreferredSurfaces).distinct().take(4),
            blockedEntrySurfaces = blockedEntrySurfaces,
            suppressedTipSources = suppressedTipSources,
            preferredAudience = preferredAudience,
            preferredDigestCadence = preferredDigestCadence,
            preferredFocusTheme = preferredFocusTheme,
            preferredRoutineThemes = preferredRoutineThemes,
            preferredCommandFamilies = preferredCommandFamilies,
            trustedSignalSources = trustedSignalSources,
            summary =
                buildString {
                    append("cadence=").append(preferredDigestCadence.ifBlank { "adaptive" })
                    append(" | audience=").append(preferredAudience)
                    append(" | surfaces=").append(preferredEntrySurfaces.joinToString(",").ifBlank { "-" })
                    append(" | suppress=").append(suppressedTipSources.joinToString(",").ifBlank { "-" })
                    append(" | themes=").append(preferredRoutineThemes.joinToString(",").ifBlank { "-" })
                    append(" | commands=").append(preferredCommandFamilies.joinToString(",").ifBlank { "-" })
                    userModel.summary.takeIf { it.isNotBlank() }?.let {
                        append(" | user=").append(it.take(72))
                    }
                },
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun readUnlocked(): AssistantAdaptivePolicySnapshot {
        val file = policyFile() ?: return AssistantAdaptivePolicySnapshot()
        if (!file.exists()) return AssistantAdaptivePolicySnapshot()
        return runCatching { JSONObject(file.readText()).toAdaptivePolicy() }.getOrDefault(AssistantAdaptivePolicySnapshot())
    }

    private fun writeUnlocked(
        snapshot: AssistantAdaptivePolicySnapshot,
    ) {
        val file = policyFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun policyFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, POLICY_DIR), POLICY_FILE)
    }
}

private fun AssistantAdaptivePolicySnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("preferred_entry_surfaces", JSONArray(preferredEntrySurfaces))
        put("blocked_entry_surfaces", JSONArray(blockedEntrySurfaces))
        put("suppressed_tip_sources", JSONArray(suppressedTipSources))
        put("preferred_audience", preferredAudience)
        put("preferred_digest_cadence", preferredDigestCadence)
        put("preferred_focus_theme", preferredFocusTheme)
        put("preferred_routine_themes", JSONArray(preferredRoutineThemes))
        put("preferred_command_families", JSONArray(preferredCommandFamilies))
        put("trusted_signal_sources", JSONArray(trustedSignalSources))
        put("summary", summary)
        put("updated_at_ms", updatedAtMs)
    }

private fun JSONObject.toAdaptivePolicy(): AssistantAdaptivePolicySnapshot =
    AssistantAdaptivePolicySnapshot(
        preferredEntrySurfaces = optJSONArray("preferred_entry_surfaces").toAdaptiveStringList(),
        blockedEntrySurfaces = optJSONArray("blocked_entry_surfaces").toAdaptiveStringList(),
        suppressedTipSources = optJSONArray("suppressed_tip_sources").toAdaptiveStringList(),
        preferredAudience = optString("preferred_audience", "all"),
        preferredDigestCadence = optString("preferred_digest_cadence"),
        preferredFocusTheme = optString("preferred_focus_theme"),
        preferredRoutineThemes = optJSONArray("preferred_routine_themes").toAdaptiveStringList(),
        preferredCommandFamilies = optJSONArray("preferred_command_families").toAdaptiveStringList(),
        trustedSignalSources = optJSONArray("trusted_signal_sources").toAdaptiveStringList(),
        summary = optString("summary"),
        updatedAtMs = optLong("updated_at_ms"),
    )

private fun JSONArray?.toAdaptiveStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
