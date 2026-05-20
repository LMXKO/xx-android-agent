package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONObject

private val DEFAULT_ARTIFACT_PINNED_TYPES =
    listOf(
        "task_result_summary",
        "execution_verification",
        "turn_failure",
    )

data class ArtifactRetentionPolicy(
    val enabled: Boolean = true,
    val maxAgeMs: Long = 14L * 24L * 60L * 60L * 1000L,
    val orphanSessionMaxAgeMs: Long = 30L * 24L * 60L * 60L * 1000L,
    val maxArtifactsPerSession: Int = 160,
    val maxArtifactsPerType: Int = 48,
    val maxArtifactsPerFamily: Int = 72,
    val keepLatestPerFamily: Int = 6,
    val hotArtifactWindowMs: Long = 24L * 60L * 60L * 1000L,
    val protectActiveSessions: Boolean = true,
    val pinnedTypes: List<String> = DEFAULT_ARTIFACT_PINNED_TYPES,
    val previewLineLimit: Int = 24,
)

object ArtifactRetentionPolicyStore {
    private const val POLICY_DIR = "runtime"
    private const val POLICY_FILE = "artifact_retention_policy.json"
    private val lock = Any()
    private var hydrated = false
    private var policy = ArtifactRetentionPolicy()

    fun read(): ArtifactRetentionPolicy =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy
        }

    fun update(
        next: ArtifactRetentionPolicy,
    ): ArtifactRetentionPolicy =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy = next
            persistUnlocked()
            policy
        }

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            policy.toJson()
        }

    fun importJson(
        json: JSONObject?,
    ) {
        if (json == null || json.length() <= 0) return
        synchronized(lock) {
            hydrated = true
            policy = json.toPolicy()
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = policyFile() ?: return
        if (!file.exists()) return
        runCatching {
            policy = JSONObject(file.readText()).toPolicy()
        }
    }

    private fun persistUnlocked() {
        val file = policyFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(policy.toJson().toString(2))
    }

    private fun policyFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, POLICY_DIR), POLICY_FILE)
    }

    private fun ArtifactRetentionPolicy.toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("max_age_ms", maxAgeMs)
            put("orphan_session_max_age_ms", orphanSessionMaxAgeMs)
            put("max_artifacts_per_session", maxArtifactsPerSession)
            put("max_artifacts_per_type", maxArtifactsPerType)
            put("max_artifacts_per_family", maxArtifactsPerFamily)
            put("keep_latest_per_family", keepLatestPerFamily)
            put("hot_artifact_window_ms", hotArtifactWindowMs)
            put("protect_active_sessions", protectActiveSessions)
            put("preview_line_limit", previewLineLimit)
            put("pinned_types", org.json.JSONArray(pinnedTypes))
        }

    private fun JSONObject.toPolicy(): ArtifactRetentionPolicy =
        ArtifactRetentionPolicy(
            enabled = optBoolean("enabled", true),
            maxAgeMs = optLong("max_age_ms", 14L * 24L * 60L * 60L * 1000L),
            orphanSessionMaxAgeMs = optLong("orphan_session_max_age_ms", 30L * 24L * 60L * 60L * 1000L),
            maxArtifactsPerSession = optInt("max_artifacts_per_session", 160).coerceAtLeast(16),
            maxArtifactsPerType = optInt("max_artifacts_per_type", 48).coerceAtLeast(4),
            maxArtifactsPerFamily = optInt("max_artifacts_per_family", 72).coerceAtLeast(8),
            keepLatestPerFamily = optInt("keep_latest_per_family", 6).coerceAtLeast(1),
            hotArtifactWindowMs = optLong("hot_artifact_window_ms", 24L * 60L * 60L * 1000L).coerceAtLeast(60L * 60L * 1000L),
            protectActiveSessions = optBoolean("protect_active_sessions", true),
            pinnedTypes =
                optJSONArray("pinned_types")
                    ?.let { array ->
                        buildList(array.length()) {
                            for (index in 0 until array.length()) {
                                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }?.ifEmpty { DEFAULT_ARTIFACT_PINNED_TYPES }
                    ?: DEFAULT_ARTIFACT_PINNED_TYPES,
            previewLineLimit = optInt("preview_line_limit", 24).coerceAtLeast(4),
        )
}
