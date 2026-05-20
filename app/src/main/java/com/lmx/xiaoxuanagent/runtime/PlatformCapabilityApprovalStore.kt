package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class PlatformCapabilityApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

enum class PlatformCapabilityGrantStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
    CONSUMED,
}

data class PlatformCapabilityApprovalRequest(
    val approvalId: String,
    val capability: SessionCapabilityKey,
    val permissionFamily: String = "",
    val riskLevel: String = "medium",
    val sessionId: String = "",
    val task: String = "",
    val query: String = "",
    val entrySource: String = "",
    val userCorrection: String = "",
    val payload: Map<String, String> = emptyMap(),
    val policyReason: String = "",
    val explanation: String = "",
    val surfaceHint: String = "",
    val summary: String = "",
    val status: PlatformCapabilityApprovalStatus = PlatformCapabilityApprovalStatus.PENDING,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

data class PlatformCapabilityAuthorizationGrant(
    val grantId: String,
    val scope: String = "request_only",
    val capability: SessionCapabilityKey? = null,
    val permissionFamily: String = "",
    val sessionId: String = "",
    val rootSessionId: String = "",
    val entrySourcePrefix: String = "",
    val maxRiskLevel: String = "medium",
    val createdFromApprovalId: String = "",
    val note: String = "",
    val status: PlatformCapabilityGrantStatus = PlatformCapabilityGrantStatus.ACTIVE,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val consumedAtMs: Long = 0L,
)

object PlatformCapabilityApprovalStore {
    private const val APPROVAL_DIR = "runtime"
    private const val APPROVAL_FILE = "capability_approvals.json"
    private const val MAX_APPROVALS = 120
    private const val MAX_GRANTS = 120
    private val lock = Any()
    private val approvals = mutableListOf<PlatformCapabilityApprovalRequest>()
    private val grants = mutableListOf<PlatformCapabilityAuthorizationGrant>()
    private var hydrated = false

    fun enqueue(
        request: SessionCapabilityRequest,
        policy: SessionCapabilityPolicyMatch,
    ): PlatformCapabilityApprovalRequest {
        val now = System.currentTimeMillis()
        val family = policy.permissionFamily.ifBlank { capabilityPermissionFamily(request.key) }
        val approval =
            PlatformCapabilityApprovalRequest(
                approvalId = "cap_approval_${now}_${request.key.name.lowercase()}",
                capability = request.key,
                permissionFamily = family,
                riskLevel = policy.riskLevel.ifBlank { capabilityRiskLevel(request.key) },
                sessionId = request.sessionId,
                task = request.task,
                query = request.query,
                entrySource = request.entrySource,
                userCorrection = request.userCorrection,
                payload = request.payload,
                policyReason = policy.reason,
                explanation =
                    buildString {
                        append(policy.explanation.ifBlank { capabilityExplanation(request.key, request.entrySource) })
                        if (policy.requestSignals.isNotEmpty()) {
                            append(" | signals=").append(policy.requestSignals.joinToString(","))
                        }
                    },
                surfaceHint = capabilitySurfaceHint(request.key),
                summary =
                    buildString {
                        append(request.task.ifBlank { request.query.ifBlank { request.key.name.lowercase() } })
                        request.payload.entries.firstOrNull()?.let { (key, value) ->
                            append(" | ").append(key).append('=').append(value.take(48))
                        }
                    }.take(160),
                createdAtMs = now,
                updatedAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            approvals.add(0, approval)
            trimUnlocked()
            persistUnlocked()
        }
        return approval
    }

    fun readPending(
        limit: Int = 16,
        family: String = "",
        sessionId: String = "",
    ): List<PlatformCapabilityApprovalRequest> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            approvals
                .asSequence()
                .filter { it.status == PlatformCapabilityApprovalStatus.PENDING }
                .filter { family.isBlank() || it.permissionFamily == family }
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit)
                .toList()
        }

    fun readAll(
        limit: Int = 24,
    ): List<PlatformCapabilityApprovalRequest> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            approvals.take(limit)
        }

    fun readGrants(
        limit: Int = 24,
        includeInactive: Boolean = false,
        family: String = "",
        sessionId: String = "",
    ): List<PlatformCapabilityAuthorizationGrant> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            expireGrantsUnlocked()
            grants
                .asSequence()
                .filter { includeInactive || it.status == PlatformCapabilityGrantStatus.ACTIVE }
                .filter { family.isBlank() || it.permissionFamily == family }
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit)
                .toList()
        }

    fun markApproved(
        approvalId: String,
    ): PlatformCapabilityApprovalRequest? =
        mutateApproval(approvalId) { current ->
            current.copy(
                status = PlatformCapabilityApprovalStatus.APPROVED,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markRejected(
        approvalId: String,
    ): PlatformCapabilityApprovalRequest? =
        mutateApproval(approvalId) { current ->
            current.copy(
                status = PlatformCapabilityApprovalStatus.REJECTED,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun issueGrant(
        approval: PlatformCapabilityApprovalRequest,
        scope: String,
        ttlMinutes: Int = 0,
        note: String = "",
    ): PlatformCapabilityAuthorizationGrant? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val normalizedScope = normalizeGrantScope(scope)
            if (normalizedScope == "request_only" || normalizedScope == "none") return@synchronized null
            val now = System.currentTimeMillis()
            val grant =
                PlatformCapabilityAuthorizationGrant(
                    grantId = "cap_grant_${now}_${approval.capability.name.lowercase()}",
                    scope = normalizedScope,
                    capability =
                        when (normalizedScope) {
                            "family" -> null
                            else -> approval.capability
                        },
                    permissionFamily = approval.permissionFamily.ifBlank { capabilityPermissionFamily(approval.capability) },
                    sessionId = if (normalizedScope == "session") approval.sessionId else "",
                    rootSessionId = if (normalizedScope == "session") resolveGrantRootSessionId(approval.sessionId) else "",
                    entrySourcePrefix = approval.entrySource.substringBefore(":").takeIf { it.isNotBlank() }.orEmpty(),
                    maxRiskLevel = approval.riskLevel.ifBlank { capabilityRiskLevel(approval.capability) },
                    createdFromApprovalId = approval.approvalId,
                    note = note.ifBlank { approval.summary },
                    createdAtMs = now,
                    updatedAtMs = now,
                    expiresAtMs = computeGrantExpiry(now, normalizedScope, ttlMinutes),
                )
            grants.add(0, grant)
            trimUnlocked()
            persistUnlocked()
            grant
        }

    fun revokeGrant(
        grantId: String,
        note: String = "",
    ): PlatformCapabilityAuthorizationGrant? =
        mutateGrant(grantId) { current ->
            current.copy(
                status = PlatformCapabilityGrantStatus.REVOKED,
                note = note.ifBlank { current.note },
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun resolveGrant(
        request: SessionCapabilityRequest,
    ): PlatformCapabilityAuthorizationGrant? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            expireGrantsUnlocked()
            val index =
                grants.indexOfFirst { grant ->
                    grant.status == PlatformCapabilityGrantStatus.ACTIVE && grant.matches(request)
                }
            if (index < 0) return@synchronized null
            val grant = grants[index]
            if (grant.scope == "once") {
                val consumed =
                    grant.copy(
                        status = PlatformCapabilityGrantStatus.CONSUMED,
                        consumedAtMs = System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                grants[index] = consumed
                persistUnlocked()
                return@synchronized consumed
            }
            grant
        }

    fun expireSessionGrantsForSession(
        sessionId: String,
        note: String = "",
    ): List<PlatformCapabilityAuthorizationGrant> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            if (sessionId.isBlank()) return@synchronized emptyList()
            val rootSessionId = resolveGrantRootSessionId(sessionId).ifBlank { sessionId }
            val now = System.currentTimeMillis()
            val updated = mutableListOf<PlatformCapabilityAuthorizationGrant>()
            grants.indices.forEach { index ->
                val grant = grants[index]
                if (
                    grant.status == PlatformCapabilityGrantStatus.ACTIVE &&
                    grant.scope == "session" &&
                    (grant.sessionId == sessionId || grant.rootSessionId == rootSessionId)
                ) {
                    val expired =
                        grant.copy(
                            status = PlatformCapabilityGrantStatus.EXPIRED,
                            note = note.ifBlank { grant.note },
                            updatedAtMs = now,
                            expiresAtMs = if (grant.expiresAtMs > 0L) minOf(grant.expiresAtMs, now) else now,
                        )
                    grants[index] = expired
                    updated += expired
                }
            }
            if (updated.isNotEmpty()) {
                persistUnlocked()
            }
            updated
        }

    internal fun resetForTest() {
        synchronized(lock) {
            hydrated = false
            approvals.clear()
            grants.clear()
        }
    }

    private fun mutateApproval(
        approvalId: String,
        reducer: (PlatformCapabilityApprovalRequest) -> PlatformCapabilityApprovalRequest,
    ): PlatformCapabilityApprovalRequest? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = approvals.indexOfFirst { it.approvalId == approvalId }
            if (index < 0) return null
            val next = reducer(approvals[index])
            approvals[index] = next
            persistUnlocked()
            next
        }

    private fun mutateGrant(
        grantId: String,
        reducer: (PlatformCapabilityAuthorizationGrant) -> PlatformCapabilityAuthorizationGrant,
    ): PlatformCapabilityAuthorizationGrant? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = grants.indexOfFirst { it.grantId == grantId }
            if (index < 0) return null
            val next = reducer(grants[index])
            grants[index] = next
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = approvalFile() ?: return
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val approvalArray = root.optJSONArray("approvals") ?: JSONArray()
            val grantArray = root.optJSONArray("grants") ?: JSONArray()
            for (index in 0 until approvalArray.length()) {
                approvalArray.optJSONObject(index)?.toApproval()?.let(approvals::add)
            }
            for (index in 0 until grantArray.length()) {
                grantArray.optJSONObject(index)?.toGrant()?.let(grants::add)
            }
        }
    }

    private fun persistUnlocked() {
        val file = approvalFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "approvals",
                    JSONArray().apply {
                        approvals.forEach { put(it.toJson()) }
                    },
                )
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
        while (approvals.size > MAX_APPROVALS) {
            approvals.removeLast()
        }
        while (grants.size > MAX_GRANTS) {
            grants.removeLast()
        }
    }

    private fun expireGrantsUnlocked() {
        val now = System.currentTimeMillis()
        var changed = false
        grants.indices.forEach { index ->
            val grant = grants[index]
            val terminalSessionExpired =
                grant.status == PlatformCapabilityGrantStatus.ACTIVE &&
                    grant.scope == "session" &&
                    grant.sessionId.isNotBlank() &&
                    isSessionGrantTerminal(grant)
            if (
                grant.status == PlatformCapabilityGrantStatus.ACTIVE &&
                ((grant.expiresAtMs > 0L && grant.expiresAtMs <= now) || terminalSessionExpired)
            ) {
                grants[index] =
                    grant.copy(
                        status = PlatformCapabilityGrantStatus.EXPIRED,
                        updatedAtMs = now,
                        expiresAtMs = if (grant.expiresAtMs > 0L) minOf(grant.expiresAtMs, now) else now,
                    )
                changed = true
            }
        }
        if (changed) {
            persistUnlocked()
        }
    }

    private fun approvalFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, APPROVAL_DIR), APPROVAL_FILE)
    }

    private fun PlatformCapabilityAuthorizationGrant.matches(
        request: SessionCapabilityRequest,
    ): Boolean {
        if (status != PlatformCapabilityGrantStatus.ACTIVE) return false
        if (entrySourcePrefix.isNotBlank() && !request.entrySource.startsWith(entrySourcePrefix)) return false
        if (riskRank(capabilityRiskLevel(request.key)) > riskRank(maxRiskLevel)) return false
        return when (scope) {
            "session" ->
                matchesSessionScope(request) &&
                    (capability == null || capability == request.key)

            "family" ->
                permissionFamily.isNotBlank() &&
                    permissionFamily == capabilityPermissionFamily(request.key)

            "capability" ->
                capability == request.key

            "once" ->
                capability == request.key &&
                    (sessionId.isBlank() || sessionId == request.sessionId)

            else -> false
        }
    }

    private fun PlatformCapabilityApprovalRequest.toJson(): JSONObject =
        JSONObject().apply {
            put("approval_id", approvalId)
            put("capability", capability.name)
            put("permission_family", permissionFamily)
            put("risk_level", riskLevel)
            put("session_id", sessionId)
            put("task", task)
            put("query", query)
            put("entry_source", entrySource)
            put("user_correction", userCorrection)
            put("policy_reason", policyReason)
            put("explanation", explanation)
            put("surface_hint", surfaceHint)
            put("summary", summary)
            put("status", status.name)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) -> put(key, value) }
                },
            )
        }

    private fun PlatformCapabilityAuthorizationGrant.toJson(): JSONObject =
        JSONObject().apply {
            put("grant_id", grantId)
            put("scope", scope)
            put("capability", capability?.name.orEmpty())
            put("permission_family", permissionFamily)
            put("session_id", sessionId)
            put("root_session_id", rootSessionId)
            put("entry_source_prefix", entrySourcePrefix)
            put("max_risk_level", maxRiskLevel)
            put("created_from_approval_id", createdFromApprovalId)
            put("note", note)
            put("status", status.name)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put("expires_at_ms", expiresAtMs)
            put("consumed_at_ms", consumedAtMs)
        }

    private fun JSONObject.toApproval(): PlatformCapabilityApprovalRequest =
        PlatformCapabilityApprovalRequest(
            approvalId = optString("approval_id"),
            capability =
                runCatching { SessionCapabilityKey.valueOf(optString("capability")) }
                    .getOrDefault(SessionCapabilityKey.START_SESSION),
            permissionFamily = optString("permission_family"),
            riskLevel = optString("risk_level", "medium"),
            sessionId = optString("session_id"),
            task = optString("task"),
            query = optString("query"),
            entrySource = optString("entry_source"),
            userCorrection = optString("user_correction"),
            payload = optJSONObject("payload").toStringMap(),
            policyReason = optString("policy_reason"),
            explanation = optString("explanation"),
            surfaceHint = optString("surface_hint"),
            summary = optString("summary"),
            status =
                runCatching { PlatformCapabilityApprovalStatus.valueOf(optString("status")) }
                    .getOrDefault(PlatformCapabilityApprovalStatus.PENDING),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONObject.toGrant(): PlatformCapabilityAuthorizationGrant =
        PlatformCapabilityAuthorizationGrant(
            grantId = optString("grant_id"),
            scope = normalizeGrantScope(optString("scope")),
            capability =
                optString("capability")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { SessionCapabilityKey.valueOf(it) }.getOrNull() },
            permissionFamily = optString("permission_family"),
            sessionId = optString("session_id"),
            rootSessionId = optString("root_session_id"),
            entrySourcePrefix = optString("entry_source_prefix"),
            maxRiskLevel = optString("max_risk_level", "medium"),
            createdFromApprovalId = optString("created_from_approval_id"),
            note = optString("note"),
            status =
                runCatching { PlatformCapabilityGrantStatus.valueOf(optString("status")) }
                    .getOrDefault(PlatformCapabilityGrantStatus.ACTIVE),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            expiresAtMs = optLong("expires_at_ms"),
            consumedAtMs = optLong("consumed_at_ms"),
        )

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }

    private fun PlatformCapabilityAuthorizationGrant.matchesSessionScope(
        request: SessionCapabilityRequest,
    ): Boolean {
        if (sessionId.isBlank() && rootSessionId.isBlank()) return false
        if (sessionId.isNotBlank() && sessionId == request.sessionId) return true
        val requestRootSessionId = resolveGrantRootSessionId(request.sessionId)
        return rootSessionId.isNotBlank() && requestRootSessionId.isNotBlank() && rootSessionId == requestRootSessionId
    }

    private fun isSessionGrantTerminal(
        grant: PlatformCapabilityAuthorizationGrant,
    ): Boolean {
        val anchorSessionId = grant.rootSessionId.ifBlank { grant.sessionId }
        if (anchorSessionId.isBlank()) return false
        return SessionSessionGraphStore.read(anchorSessionId)?.statusModel?.category == AgentUiStatusCategory.TERMINAL
    }

    private fun resolveGrantRootSessionId(
        sessionId: String,
    ): String =
        sessionId.takeIf { it.isNotBlank() }
            ?.let { SessionSessionGraphStore.resolveRootSessionId(it).ifBlank { it } }
            .orEmpty()
}

internal fun normalizeGrantScope(
    raw: String,
): String =
    when (raw.trim().lowercase()) {
        "",
        "none",
        "request",
        "request_only",
        -> "request_only"

        "once",
        "single",
        -> "once"

        "session",
        "current_session",
        -> "session"

        "family",
        "permission_family",
        -> "family"

        "capability",
        "capability_family",
        -> "capability"

        else -> "request_only"
    }

internal fun computeGrantExpiry(
    now: Long,
    scope: String,
    ttlMinutes: Int,
): Long {
    val explicit = ttlMinutes.coerceAtLeast(0)
    if (explicit > 0) return now + explicit * 60_000L
    return when (scope) {
        "once" -> now + 30 * 60_000L
        "session" -> now + 12 * 60 * 60_000L
        "capability" -> now + 24 * 60 * 60_000L
        "family" -> now + 3 * 24 * 60 * 60_000L
        else -> 0L
    }
}

internal fun suggestedGrantScopeFor(
    approval: PlatformCapabilityApprovalRequest,
): String =
    when {
        approval.permissionFamily == "session_control" -> "session"
        approval.permissionFamily in setOf("approval_control", "governance_policy") -> "request_only"
        approval.riskLevel == "low" -> "family"
        else -> "capability"
    }

internal fun riskRank(
    riskLevel: String,
): Int =
    when (riskLevel.lowercase()) {
        "low" -> 1
        "medium" -> 2
        "high" -> 3
        "critical" -> 4
        else -> 2
    }
