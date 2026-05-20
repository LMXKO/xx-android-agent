package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionCapabilityPolicyDecision {
    ALLOW,
    REVIEW,
    DENY,
}

data class SessionCapabilityPolicyRule(
    val ruleId: String,
    val entrySourcePrefix: String = "",
    val capability: SessionCapabilityKey? = null,
    val permissionFamily: String = "",
    val taskContains: String = "",
    val queryContains: String = "",
    val payloadKey: String = "",
    val payloadContains: String = "",
    val decision: SessionCapabilityPolicyDecision,
    val reason: String,
    val explanation: String = "",
    val riskLevel: String = "medium",
    val surfaceHint: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class SessionCapabilityPolicyMatch(
    val decision: SessionCapabilityPolicyDecision,
    val reason: String,
    val permissionFamily: String = "",
    val explanation: String = "",
    val riskLevel: String = "medium",
    val requestSignals: List<String> = emptyList(),
    val matchedRule: SessionCapabilityPolicyRule? = null,
    val evaluatedRules: List<SessionCapabilityPolicyRule> = emptyList(),
)

data class SessionCapabilityPolicyDiagnostics(
    val totalRules: Int,
    val shadowedRuleIds: List<String> = emptyList(),
    val conflictLines: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

object SessionCapabilityPolicyStore {
    private const val POLICY_DIR = "runtime"
    private const val POLICY_FILE = "capability_policy_rules.json"
    private val lock = Any()
    private var hydrated = false
    private val rules = mutableListOf<SessionCapabilityPolicyRule>()

    fun resolve(
        request: SessionCapabilityRequest,
    ): SessionCapabilityPolicyMatch =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val requestSignals = analyzeRequestSignals(request)
            if (request.entrySource.contains("approved_capability_request")) {
                return@synchronized SessionCapabilityPolicyMatch(
                    decision = SessionCapabilityPolicyDecision.ALLOW,
                    reason = "approved_capability_request",
                    permissionFamily = capabilityPermissionFamily(request.key),
                    explanation = capabilityExplanation(request.key, request.entrySource),
                    riskLevel = capabilityRiskLevel(request.key),
                    requestSignals = requestSignals,
                )
            }
            PlatformCapabilityApprovalStore.resolveGrant(request)?.let { grant ->
                return@synchronized SessionCapabilityPolicyMatch(
                    decision = SessionCapabilityPolicyDecision.ALLOW,
                    reason = "authorization_grant:${grant.scope}",
                    permissionFamily = grant.permissionFamily.ifBlank { capabilityPermissionFamily(request.key) },
                    explanation =
                        buildString {
                            append("grant=").append(grant.scope)
                            append(" | family=").append(grant.permissionFamily.ifBlank { capabilityPermissionFamily(request.key) })
                            append(" | source=").append(request.entrySource.ifBlank { "local" })
                            grant.note.takeIf { it.isNotBlank() }?.let { append(" | note=").append(it.take(72)) }
                        },
                    riskLevel = grant.maxRiskLevel.ifBlank { capabilityRiskLevel(request.key) },
                    requestSignals = requestSignals,
                )
            }
            val orderedRules = orderedRulesUnlocked()
            val match =
                orderedRules.firstOrNull { rule ->
                    rule.matches(request)
                }
            if (match != null) {
                SessionCapabilityPolicyMatch(
                    decision = match.decision,
                    reason = match.reason,
                    permissionFamily = match.permissionFamily.ifBlank { capabilityPermissionFamily(request.key) },
                    explanation = match.explanation.ifBlank { capabilityExplanation(request.key, request.entrySource) },
                    riskLevel = match.riskLevel.ifBlank { capabilityRiskLevel(request.key) },
                    requestSignals = requestSignals,
                    matchedRule = match,
                    evaluatedRules = orderedRules,
                )
            } else {
                heuristicInputReview(
                    request = request,
                    requestSignals = requestSignals,
                    evaluatedRules = orderedRules,
                ) ?: SessionCapabilityPolicyMatch(
                    decision = SessionCapabilityPolicyDecision.ALLOW,
                    reason = "default_allow",
                    permissionFamily = capabilityPermissionFamily(request.key),
                    explanation = capabilityExplanation(request.key, request.entrySource),
                    riskLevel = capabilityRiskLevel(request.key),
                    requestSignals = requestSignals,
                    evaluatedRules = orderedRules,
                )
            }
        }

    fun explain(
        request: SessionCapabilityRequest,
    ): SessionCapabilityPolicyMatch = resolve(request)

    fun readRules(): List<SessionCapabilityPolicyRule> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            orderedRulesUnlocked()
        }

    fun readDiagnostics(): SessionCapabilityPolicyDiagnostics =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            buildDiagnosticsUnlocked()
        }

    fun upsertRule(
        rule: SessionCapabilityPolicyRule,
    ): SessionCapabilityPolicyRule =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val normalized =
                rule.copy(
                    ruleId = rule.ruleId.ifBlank { "cap_rule_${now}" },
                    createdAtMs =
                        rules.firstOrNull { it.ruleId == rule.ruleId }?.createdAtMs
                            ?: rule.createdAtMs.takeIf { it > 0L }
                            ?: now,
                    updatedAtMs = now,
                )
            val index = rules.indexOfFirst { it.ruleId == normalized.ruleId }
            if (index >= 0) {
                rules[index] = normalized
            } else {
                rules += normalized
            }
            persistUnlocked()
            normalized
        }

    fun removeRule(
        ruleId: String,
    ): Boolean =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val removed = rules.removeAll { it.ruleId == ruleId }
            if (removed) {
                persistUnlocked()
            }
            removed
        }

    internal fun resetForTest() {
        synchronized(lock) {
            hydrated = false
            rules.clear()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = policyFile()
        if (file?.exists() == true) {
            runCatching {
                val array = JSONObject(file.readText()).optJSONArray("rules") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    rules += json.toRule()
                }
            }
        }
        if (rules.isEmpty()) {
            rules += defaultRules()
            persistUnlocked()
        }
    }

    private fun orderedRulesUnlocked(): List<SessionCapabilityPolicyRule> =
        rules
            .filter { it.enabled }
            .sortedWith(
                compareByDescending<SessionCapabilityPolicyRule> { it.priority }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.ruleId },
            )

    private fun buildDiagnosticsUnlocked(): SessionCapabilityPolicyDiagnostics {
        val orderedRules = orderedRulesUnlocked()
        val shadowed = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        orderedRules.forEachIndexed { index, current ->
            val previousRules = orderedRules.take(index)
            if (previousRules.any { previous -> previous.shadows(current) }) {
                shadowed += current.ruleId
            }
            previousRules
                .filter { previous ->
                    previous.entrySourcePrefix == current.entrySourcePrefix &&
                        previous.capability == current.capability &&
                        previous.decision != current.decision
                }.forEach { previous ->
                    conflicts += "${previous.ruleId} conflicts ${current.ruleId}"
                }
        }
        return SessionCapabilityPolicyDiagnostics(
            totalRules = orderedRules.size,
            shadowedRuleIds = shadowed.distinct(),
            conflictLines = conflicts.distinct(),
            lines =
                buildList {
                    orderedRules.forEach { rule ->
                        add(
                            "${rule.ruleId} | p=${rule.priority} | ${rule.entrySourcePrefix.ifBlank { "*" }} | ${rule.capability?.name?.lowercase() ?: "*"} | ${rule.permissionFamily.ifBlank { "general" }} | task~${rule.taskContains.ifBlank { "*" }} | query~${rule.queryContains.ifBlank { "*" }} | payload=${rule.payloadKey.ifBlank { "*" }}:${rule.payloadContains.ifBlank { "*" }} | ${rule.riskLevel} | ${rule.surfaceHint.ifBlank { "-" }} | ${rule.decision.name.lowercase()} | ${rule.reason}",
                        )
                    }
                    orderedRules
                        .groupBy { it.permissionFamily.ifBlank { "general" } }
                        .entries
                        .sortedByDescending { it.value.size }
                        .forEach { (family, familyRules) ->
                            add("family=$family count=${familyRules.size}")
                        }
                    if (shadowed.isNotEmpty()) {
                        add("shadowed=${shadowed.distinct().joinToString(",")}")
                    }
                    conflicts.distinct().forEach { add("conflict=$it") }
                },
        )
    }

    private fun defaultRules(): List<SessionCapabilityPolicyRule> =
        listOf(
            SessionCapabilityPolicyRule(
                ruleId = "remote_start_review",
                entrySourcePrefix = "remote",
                capability = SessionCapabilityKey.START_SESSION,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.START_SESSION),
                decision = SessionCapabilityPolicyDecision.REVIEW,
                reason = "远端发起新 session 需要人工批准。",
                explanation = capabilityExplanation(SessionCapabilityKey.START_SESSION, "remote"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.START_SESSION),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.START_SESSION),
                priority = 100,
            ),
            SessionCapabilityPolicyRule(
                ruleId = "remote_stop_review",
                entrySourcePrefix = "remote",
                capability = SessionCapabilityKey.STOP_SESSION,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.STOP_SESSION),
                decision = SessionCapabilityPolicyDecision.REVIEW,
                reason = "远端停止 session 需要人工批准。",
                explanation = capabilityExplanation(SessionCapabilityKey.STOP_SESSION, "remote"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.STOP_SESSION),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.STOP_SESSION),
                priority = 100,
            ),
            SessionCapabilityPolicyRule(
                ruleId = "remote_worker_review",
                entrySourcePrefix = "remote",
                capability = SessionCapabilityKey.FORK_WORKER,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.FORK_WORKER),
                decision = SessionCapabilityPolicyDecision.REVIEW,
                reason = "远端创建 worker 需要人工批准。",
                explanation = capabilityExplanation(SessionCapabilityKey.FORK_WORKER, "remote"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.FORK_WORKER),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.FORK_WORKER),
                priority = 100,
            ),
            SessionCapabilityPolicyRule(
                ruleId = "external_start_review",
                entrySourcePrefix = "external_signal",
                capability = SessionCapabilityKey.START_SESSION,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.START_SESSION),
                decision = SessionCapabilityPolicyDecision.REVIEW,
                reason = "外部信号直接启动 session 需要人工批准。",
                explanation = capabilityExplanation(SessionCapabilityKey.START_SESSION, "external_signal"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.START_SESSION),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.START_SESSION),
                priority = 90,
            ),
            SessionCapabilityPolicyRule(
                ruleId = "external_resume_review",
                entrySourcePrefix = "external_signal",
                capability = SessionCapabilityKey.RESUME_SESSION,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.RESUME_SESSION),
                decision = SessionCapabilityPolicyDecision.REVIEW,
                reason = "外部信号恢复 session 需要人工批准。",
                explanation = capabilityExplanation(SessionCapabilityKey.RESUME_SESSION, "external_signal"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.RESUME_SESSION),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.RESUME_SESSION),
                priority = 90,
            ),
            SessionCapabilityPolicyRule(
                ruleId = "external_refresh_allow",
                entrySourcePrefix = "external_signal",
                capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                permissionFamily = capabilityPermissionFamily(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                decision = SessionCapabilityPolicyDecision.ALLOW,
                reason = "外部信号刷新 assistant OS 默认允许。",
                explanation = capabilityExplanation(SessionCapabilityKey.REFRESH_ASSISTANT_OS, "external_signal"),
                riskLevel = capabilityRiskLevel(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                surfaceHint = capabilitySurfaceHint(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
                priority = 80,
            ),
        )

    private fun persistUnlocked() {
        val file = policyFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "rules",
                    JSONArray().apply {
                        rules.forEach { rule ->
                            put(rule.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun policyFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, POLICY_DIR), POLICY_FILE)
    }

    private fun SessionCapabilityPolicyRule.matches(
        request: SessionCapabilityRequest,
    ): Boolean =
        (entrySourcePrefix.isBlank() || request.entrySource.startsWith(entrySourcePrefix)) &&
            (capability == null || capability == request.key) &&
            (taskContains.isBlank() || request.task.contains(taskContains, ignoreCase = true)) &&
            (queryContains.isBlank() || request.query.contains(queryContains, ignoreCase = true)) &&
            (
                payloadKey.isBlank() ||
                    request.payload.entries.any { (key, value) ->
                        key.equals(payloadKey, ignoreCase = true) &&
                            (payloadContains.isBlank() || value.contains(payloadContains, ignoreCase = true))
                    }
            ) &&
            (
                payloadKey.isBlank() || payloadContains.isNotBlank() || request.payload.keys.any {
                    it.equals(payloadKey, ignoreCase = true)
                }
            )

    private fun SessionCapabilityPolicyRule.shadows(
        other: SessionCapabilityPolicyRule,
    ): Boolean {
        val capabilityCovers = capability == null || capability == other.capability
        val entrySourceCovers =
            entrySourcePrefix.isBlank() ||
                entrySourcePrefix == other.entrySourcePrefix ||
                other.entrySourcePrefix.startsWith(entrySourcePrefix)
        val taskCovers = taskContains.isBlank() || taskContains == other.taskContains
        val queryCovers = queryContains.isBlank() || queryContains == other.queryContains
        val payloadKeyCovers = payloadKey.isBlank() || payloadKey == other.payloadKey
        val payloadValueCovers = payloadContains.isBlank() || payloadContains == other.payloadContains
        return capabilityCovers && entrySourceCovers && taskCovers && queryCovers && payloadKeyCovers && payloadValueCovers
    }

    private fun SessionCapabilityPolicyRule.toJson(): JSONObject =
        JSONObject().apply {
            put("rule_id", ruleId)
            put("entry_source_prefix", entrySourcePrefix)
            put("capability", capability?.name.orEmpty())
            put("permission_family", permissionFamily)
            put("task_contains", taskContains)
            put("query_contains", queryContains)
            put("payload_key", payloadKey)
            put("payload_contains", payloadContains)
            put("decision", decision.name)
            put("reason", reason)
            put("explanation", explanation)
            put("risk_level", riskLevel)
            put("surface_hint", surfaceHint)
            put("priority", priority)
            put("enabled", enabled)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toRule(): SessionCapabilityPolicyRule =
        SessionCapabilityPolicyRule(
            ruleId = optString("rule_id"),
            entrySourcePrefix = optString("entry_source_prefix"),
            capability =
                optString("capability").takeIf { it.isNotBlank() }
                    ?.let { runCatching { SessionCapabilityKey.valueOf(it) }.getOrNull() },
            permissionFamily = optString("permission_family"),
            taskContains = optString("task_contains"),
            queryContains = optString("query_contains"),
            payloadKey = optString("payload_key"),
            payloadContains = optString("payload_contains"),
            decision =
                runCatching { SessionCapabilityPolicyDecision.valueOf(optString("decision")) }
                    .getOrDefault(SessionCapabilityPolicyDecision.ALLOW),
            reason = optString("reason"),
            explanation = optString("explanation"),
            riskLevel = optString("risk_level", "medium"),
            surfaceHint = optString("surface_hint"),
            priority = optInt("priority", 0),
            enabled = optBoolean("enabled", true),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun analyzeRequestSignals(
        request: SessionCapabilityRequest,
    ): List<String> {
        val requestText =
            buildString {
                append(request.task).append(' ')
                append(request.query).append(' ')
                request.payload.forEach { (key, value) ->
                    append(key).append('=').append(value).append(' ')
                }
            }
        val keywords =
            listOf(
                "删除" to "destructive_delete",
                "清空" to "destructive_clear",
                "全部" to "bulk_scope",
                "批量" to "bulk_scope",
                "token" to "credential_token",
                "密码" to "credential_password",
                "验证码" to "credential_code",
                "grant" to "grant_mutation",
                "policy" to "policy_mutation",
                "worker" to "worker_target",
                "session" to "session_target",
            )
        return buildList {
            keywords.forEach { (keyword, signal) ->
                if (requestText.contains(keyword, ignoreCase = true)) {
                    add(signal)
                }
            }
            if (request.payload.isNotEmpty()) {
                add("payload_present")
            }
            request.payload.keys
                .filter { it.contains("id", ignoreCase = true) || it.contains("scope", ignoreCase = true) }
                .forEach { add("selector:$it") }
        }.distinct()
    }

    private fun heuristicInputReview(
        request: SessionCapabilityRequest,
        requestSignals: List<String>,
        evaluatedRules: List<SessionCapabilityPolicyRule>,
    ): SessionCapabilityPolicyMatch? {
        if (requestSignals.isEmpty()) return null
        val family = capabilityPermissionFamily(request.key)
        val destructive =
            requestSignals.any { it in setOf("destructive_delete", "destructive_clear", "bulk_scope") }
        val credentialLike = requestSignals.any { it.startsWith("credential_") }
        val governanceSensitive = family in setOf("governance_policy", "approval_control", "memory_governance", "session_control", "worker_coordination")
        return when {
            request.key == SessionCapabilityKey.DELETE_MEMORY_ENTRY && destructive ->
                SessionCapabilityPolicyMatch(
                    decision = SessionCapabilityPolicyDecision.DENY,
                    reason = "heuristic_deny_destructive_memory_delete",
                    permissionFamily = family,
                    explanation = capabilityExplanation(request.key, request.entrySource) + " | signals=" + requestSignals.joinToString(","),
                    riskLevel = "high",
                    requestSignals = requestSignals,
                    evaluatedRules = evaluatedRules,
                )

            governanceSensitive && (destructive || credentialLike || "policy_mutation" in requestSignals || "grant_mutation" in requestSignals) ->
                SessionCapabilityPolicyMatch(
                    decision = SessionCapabilityPolicyDecision.REVIEW,
                    reason = "heuristic_review_sensitive_input",
                    permissionFamily = family,
                    explanation = capabilityExplanation(request.key, request.entrySource) + " | signals=" + requestSignals.joinToString(","),
                    riskLevel = capabilityRiskLevel(request.key),
                    requestSignals = requestSignals,
                    evaluatedRules = evaluatedRules,
                )

            else -> null
        }
    }
}

internal fun capabilityPermissionFamily(
    key: SessionCapabilityKey,
): String =
    when (key) {
        SessionCapabilityKey.START_SESSION,
        SessionCapabilityKey.RESUME_SESSION,
        SessionCapabilityKey.STOP_SESSION,
        -> "session_control"

        SessionCapabilityKey.FORK_WORKER,
        SessionCapabilityKey.READ_WORKER_MAILBOX,
        SessionCapabilityKey.POST_WORKER_MESSAGE,
        SessionCapabilityKey.ACK_WORKER_MESSAGE,
        -> "worker_coordination"

        SessionCapabilityKey.APPROVE_SAFETY,
        SessionCapabilityKey.REJECT_SAFETY,
        SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST,
        SessionCapabilityKey.REJECT_CAPABILITY_REQUEST,
        SessionCapabilityKey.REVOKE_CAPABILITY_GRANT,
        -> "approval_control"

        SessionCapabilityKey.READ_MEMORY_QUEUE,
        SessionCapabilityKey.RETRY_MEMORY_TASK,
        SessionCapabilityKey.READ_MEMORY_WORKSPACE,
        SessionCapabilityKey.READ_MEMORY_GOVERNANCE,
        SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
        SessionCapabilityKey.DELETE_MEMORY_ENTRY,
        SessionCapabilityKey.RECALL_MEMORY,
        -> "memory_governance"

        SessionCapabilityKey.READ_CAPABILITY_APPROVALS,
        SessionCapabilityKey.READ_CAPABILITY_GRANTS,
        SessionCapabilityKey.READ_CAPABILITY_POLICIES,
        SessionCapabilityKey.UPSERT_CAPABILITY_POLICY,
        SessionCapabilityKey.DELETE_CAPABILITY_POLICY,
        SessionCapabilityKey.READ_PROVIDER_POLICY,
        SessionCapabilityKey.UPSERT_PROVIDER_POLICY,
        SessionCapabilityKey.READ_PROVIDER_REGISTRY,
        SessionCapabilityKey.UPSERT_PROVIDER_REGISTRY,
        -> "governance_policy"

        SessionCapabilityKey.BATCH_REPLAY,
        SessionCapabilityKey.INSPECT_REPLAY,
        SessionCapabilityKey.INSPECT_REPLAY_STATE,
        SessionCapabilityKey.INSPECT_REPLAY_ARTIFACTS,
        SessionCapabilityKey.COMPARE_REPLAY_STEPS,
        SessionCapabilityKey.INSPECT_REPLAY_BREAKPOINT,
        SessionCapabilityKey.COMPARE_SESSIONS,
        SessionCapabilityKey.READ_SESSION_HISTORY,
        SessionCapabilityKey.SEARCH_SESSION_HISTORY,
        -> "history_replay"

        else -> "assistant_platform"
    }

internal fun capabilityRiskLevel(
    key: SessionCapabilityKey,
): String =
    when (key) {
        SessionCapabilityKey.STOP_SESSION,
        SessionCapabilityKey.APPROVE_SAFETY,
        SessionCapabilityKey.REJECT_SAFETY,
        SessionCapabilityKey.DELETE_MEMORY_ENTRY,
        SessionCapabilityKey.DELETE_CAPABILITY_POLICY,
        SessionCapabilityKey.REVOKE_CAPABILITY_GRANT,
        -> "high"

        SessionCapabilityKey.START_SESSION,
        SessionCapabilityKey.RESUME_SESSION,
        SessionCapabilityKey.FORK_WORKER,
        SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
        SessionCapabilityKey.UPSERT_CAPABILITY_POLICY,
        SessionCapabilityKey.RUN_ARTIFACT_RETENTION,
        SessionCapabilityKey.RUN_SESSION_COMPENSATION,
        -> "medium"

        else -> "low"
    }

internal fun capabilitySurfaceHint(
    key: SessionCapabilityKey,
): String =
    when (capabilityPermissionFamily(key)) {
        "session_control" -> "today"
        "worker_coordination" -> "viewer"
        "approval_control" -> "approvals"
        "memory_governance" -> "memory"
        "governance_policy" -> "governance"
        "history_replay" -> "sessions"
        else -> "command"
    }

internal fun capabilityExplanation(
    key: SessionCapabilityKey,
    entrySource: String,
): String =
    buildString {
        append("family=").append(capabilityPermissionFamily(key))
        append(" | risk=").append(capabilityRiskLevel(key))
        append(" | source=").append(entrySource.ifBlank { "local" })
        append(
            when (capabilityPermissionFamily(key)) {
                "session_control" -> " | 控制 session 生命周期，可能影响当前执行链路。"
                "worker_coordination" -> " | 影响 parent-child session graph 与 worker 协同。"
                "approval_control" -> " | 直接改变高风险动作或能力请求的审批结果。"
                "memory_governance" -> " | 会读取或修改长期记忆与整理结果。"
                "governance_policy" -> " | 会改变平台治理规则、provider 路由、权限策略或授权范围。"
                "history_replay" -> " | 读取历史/回放信息，用于诊断与复盘。"
                else -> " | 影响 assistant platform 的本地控制面。"
            },
        )
    }
