package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.assistantos.AssistantPermissionMode
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSafetyGrantStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
}

data class RuntimeSafetyGrant(
    val grantId: String,
    val scope: String,
    val targetPackageName: String = "",
    val actionFamily: String = "",
    val riskLevel: String = "",
    val sessionId: String = "",
    val note: String = "",
    val actionLabel: String = "",
    val createdFromApprovalKey: String = "",
    val status: RuntimeSafetyGrantStatus = RuntimeSafetyGrantStatus.ACTIVE,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
)

object RuntimeSafetyGrantStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "safety_grants.json"
    private const val MAX_GRANTS = 160
    private val lock = Any()
    private val grants = mutableListOf<RuntimeSafetyGrant>()
    private var hydrated = false

    fun resolveGrant(
        packageName: String,
        actionFamily: String,
        sessionId: String = "",
    ): RuntimeSafetyGrant? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            expireUnlocked()
            val normalizedPackage = packageName.trim()
            val normalizedFamily = actionFamily.trim()
            if (normalizedFamily.isBlank()) return@synchronized null
            grants.firstOrNull { grant ->
                grant.status == RuntimeSafetyGrantStatus.ACTIVE &&
                    grant.actionFamily == normalizedFamily &&
                    (grant.targetPackageName.isBlank() || grant.targetPackageName == normalizedPackage) &&
                    when (grant.scope) {
                        "session" -> sessionId.isNotBlank() && grant.sessionId == sessionId
                        "package" -> true
                        else -> false
                    }
            }
        }

    fun issueGrant(
        sessionId: String,
        review: SafetyReview,
        permissionMode: AssistantPermissionMode,
        note: String = "",
    ): RuntimeSafetyGrant? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            expireUnlocked()
            val scope = scopeFor(permissionMode, review)
            if (scope.isBlank() || review.actionFamily.isBlank()) {
                return@synchronized null
            }
            val now = System.currentTimeMillis()
            val existingIndex =
                grants.indexOfFirst { grant ->
                    grant.status == RuntimeSafetyGrantStatus.ACTIVE &&
                        grant.scope == scope &&
                        grant.actionFamily == review.actionFamily &&
                        grant.targetPackageName == review.targetPackageName &&
                        (scope != "session" || grant.sessionId == sessionId)
                }
            val next =
                RuntimeSafetyGrant(
                    grantId =
                        if (existingIndex >= 0) {
                            grants[existingIndex].grantId
                        } else {
                            "safety_grant_${now}_${review.actionFamily.ifBlank { "general" }}"
                        },
                    scope = scope,
                    targetPackageName = review.targetPackageName,
                    actionFamily = review.actionFamily,
                    riskLevel = review.riskLevelLabel,
                    sessionId = if (scope == "session") sessionId else "",
                    note = note.ifBlank { review.summary },
                    actionLabel = review.actionLabel,
                    createdFromApprovalKey = review.approvalKey,
                    createdAtMs = if (existingIndex >= 0) grants[existingIndex].createdAtMs else now,
                    updatedAtMs = now,
                    expiresAtMs = expirationFor(scope, now),
                )
            if (existingIndex >= 0) {
                grants[existingIndex] = next
            } else {
                grants.add(0, next)
            }
            trimUnlocked()
            persistUnlocked()
            next
        }

    fun expireSessionGrantsForSession(
        sessionId: String,
        note: String = "",
    ) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            var changed = false
            grants.indices.forEach { index ->
                val grant = grants[index]
                if (
                    grant.status == RuntimeSafetyGrantStatus.ACTIVE &&
                    grant.scope == "session" &&
                    grant.sessionId == sessionId
                ) {
                    grants[index] =
                        grant.copy(
                            status = RuntimeSafetyGrantStatus.EXPIRED,
                            updatedAtMs = now,
                            expiresAtMs = if (grant.expiresAtMs > 0L) minOf(grant.expiresAtMs, now) else now,
                            note = if (note.isBlank()) grant.note else "${grant.note} | $note".take(160),
                        )
                    changed = true
                }
            }
            if (changed) {
                persistUnlocked()
            }
        }
    }

    fun readGrants(
        limit: Int = 12,
        includeInactive: Boolean = false,
        sessionId: String = "",
        targetPackageName: String = "",
        actionFamily: String = "",
    ): List<RuntimeSafetyGrant> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            expireUnlocked()
            grants
                .asSequence()
                .filter { includeInactive || it.status == RuntimeSafetyGrantStatus.ACTIVE }
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .filter { targetPackageName.isBlank() || it.targetPackageName == targetPackageName }
                .filter { actionFamily.isBlank() || it.actionFamily == actionFamily }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun revokeGrant(
        grantId: String,
        note: String = "",
    ): RuntimeSafetyGrant? {
        if (grantId.isBlank()) return null
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = grants.indexOfFirst { it.grantId == grantId }
            if (index < 0) return@synchronized null
            val current = grants[index]
            val next =
                current.copy(
                    status = RuntimeSafetyGrantStatus.REVOKED,
                    updatedAtMs = System.currentTimeMillis(),
                    note = if (note.isBlank()) current.note else "${current.note} | $note".take(160),
                )
            grants[index] = next
            persistUnlocked()
            next
        }
    }

    private fun scopeFor(
        permissionMode: AssistantPermissionMode,
        review: SafetyReview,
    ): String =
        when (permissionMode) {
            AssistantPermissionMode.PROMPT_EACH_TIME -> ""
            AssistantPermissionMode.ASSISTED -> "session"
            AssistantPermissionMode.HANDS_FREE ->
                if (review.actionFamily in setOf("message_send", "content_publish", "destructive", "transaction")) {
                    "session"
                } else {
                    "package"
                }
        }

    private fun expirationFor(
        scope: String,
        now: Long,
    ): Long =
        when (scope) {
            "package" -> now + 7L * 24 * 60 * 60 * 1000
            else -> 0L
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("grants") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toGrant()?.let(grants::add)
            }
            expireUnlocked()
            trimUnlocked()
        }
    }

    private fun expireUnlocked() {
        val now = System.currentTimeMillis()
        grants.indices.forEach { index ->
            val grant = grants[index]
            if (
                grant.status == RuntimeSafetyGrantStatus.ACTIVE &&
                grant.expiresAtMs > 0L &&
                grant.expiresAtMs <= now
            ) {
                grants[index] =
                    grant.copy(
                        status = RuntimeSafetyGrantStatus.EXPIRED,
                        updatedAtMs = now,
                    )
            }
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "grants",
                    JSONArray().apply {
                        grants.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (grants.size > MAX_GRANTS) {
            grants.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun RuntimeSafetyGrant.toJson(): JSONObject =
        JSONObject().apply {
            put("grant_id", grantId)
            put("scope", scope)
            put("target_package_name", targetPackageName)
            put("action_family", actionFamily)
            put("risk_level", riskLevel)
            put("session_id", sessionId)
            put("note", note)
            put("action_label", actionLabel)
            put("created_from_approval_key", createdFromApprovalKey)
            put("status", status.name)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put("expires_at_ms", expiresAtMs)
        }

    private fun JSONObject.toGrant(): RuntimeSafetyGrant =
        RuntimeSafetyGrant(
            grantId = optString("grant_id"),
            scope = optString("scope"),
            targetPackageName = optString("target_package_name"),
            actionFamily = optString("action_family"),
            riskLevel = optString("risk_level"),
            sessionId = optString("session_id"),
            note = optString("note"),
            actionLabel = optString("action_label"),
            createdFromApprovalKey = optString("created_from_approval_key"),
            status =
                runCatching {
                    RuntimeSafetyGrantStatus.valueOf(optString("status", RuntimeSafetyGrantStatus.ACTIVE.name))
                }.getOrDefault(RuntimeSafetyGrantStatus.ACTIVE),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            expiresAtMs = optLong("expires_at_ms"),
        )
}
