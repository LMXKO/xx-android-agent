package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantSignalProviderTrustLevel {
    LOW,
    MEDIUM,
    HIGH,
    SYSTEM,
}

enum class AssistantSignalProviderGateAction {
    ALLOW,
    DEFER,
    DENY,
}

data class AssistantSignalProviderState(
    val providerId: String,
    val source: String,
    val trustLevel: AssistantSignalProviderTrustLevel,
    val providerType: String = "",
    val supportedCapabilities: List<SessionCapabilityKey> = emptyList(),
    val supportedSignalTypes: List<AssistantExternalSignalType> = emptyList(),
    val routingTags: List<String> = emptyList(),
    val preferredEntrySource: String = "",
    val deliveryMode: String = "",
    val routingPriority: Int = 0,
    val enabled: Boolean = true,
    val lastSeenAtMs: Long = 0L,
    val lastAcceptedAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
    val lastFailureAtMs: Long = 0L,
    val failureCount: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val totalSignals: Int = 0,
    val acceptedSignals: Int = 0,
    val rejectedSignals: Int = 0,
    val healthScore: Int = 100,
    val lastGateAction: AssistantSignalProviderGateAction = AssistantSignalProviderGateAction.ALLOW,
    val lastFailureMode: String = "",
    val diagnosticsSummary: String = "",
    val lastReason: String = "",
    val updatedAtMs: Long = 0L,
) {
    fun supports(
        capability: SessionCapabilityKey,
    ): Boolean = supportedCapabilities.isEmpty() || capability in supportedCapabilities

    fun supports(
        signalType: AssistantExternalSignalType,
    ): Boolean = supportedSignalTypes.isEmpty() || signalType in supportedSignalTypes
}

data class AssistantSignalProviderGateDecision(
    val action: AssistantSignalProviderGateAction,
    val reason: String = "",
    val deferByMs: Long = 0L,
) {
    val allows: Boolean
        get() = action == AssistantSignalProviderGateAction.ALLOW
}

object AssistantSignalProviderStore {
    private const val PROVIDER_DIR = "assistant_os"
    private const val PROVIDER_FILE = "signal_provider_states.json"
    private const val MAX_STATES = 64
    private val lock = Any()
    private val providers = ArrayDeque<AssistantSignalProviderState>()
    private var hydrated = false

    fun markRegistered(
        providerId: String,
        source: String,
        trustLevel: AssistantSignalProviderTrustLevel,
        providerType: String = source,
        supportedCapabilities: List<SessionCapabilityKey> = emptyList(),
        supportedSignalTypes: List<AssistantExternalSignalType> = emptyList(),
        routingTags: List<String> = emptyList(),
        preferredEntrySource: String = "",
        deliveryMode: String = "",
        routingPriority: Int = 0,
    ): AssistantSignalProviderState =
        upsert(
            providerId = providerId,
            source = source,
            trustLevel = trustLevel,
            providerType = providerType,
            supportedCapabilities = supportedCapabilities,
            supportedSignalTypes = supportedSignalTypes,
            routingTags = routingTags,
            preferredEntrySource = preferredEntrySource,
            deliveryMode = deliveryMode,
            routingPriority = routingPriority,
        ) { current, now ->
            current.copy(
                source = source,
                trustLevel = trustLevel,
                providerType = providerType.ifBlank { current.providerType.ifBlank { source } },
                supportedCapabilities =
                    if (supportedCapabilities.isEmpty()) {
                        current.supportedCapabilities
                    } else {
                        supportedCapabilities.distinct()
                    },
                supportedSignalTypes =
                    if (supportedSignalTypes.isEmpty()) {
                        current.supportedSignalTypes
                    } else {
                        supportedSignalTypes.distinct()
                    },
                routingTags =
                    if (routingTags.isEmpty()) {
                        current.routingTags
                    } else {
                        routingTags.distinct()
                    },
                preferredEntrySource = preferredEntrySource.ifBlank { current.preferredEntrySource },
                deliveryMode = deliveryMode.ifBlank { current.deliveryMode },
                routingPriority = maxOf(current.routingPriority, routingPriority),
                lastSeenAtMs = maxOf(current.lastSeenAtMs, now),
                updatedAtMs = now,
            )
        }

    fun evaluateIngress(
        providerId: String,
        source: String,
        trustLevel: AssistantSignalProviderTrustLevel,
        nowMs: Long = System.currentTimeMillis(),
    ): AssistantSignalProviderGateDecision {
        val state = markRegistered(providerId, source, trustLevel)
        val decision =
            if (!state.enabled) {
                AssistantSignalProviderGateDecision(
                action = AssistantSignalProviderGateAction.DENY,
                reason = "provider_disabled",
            )
            } else if (state.cooldownUntilMs > nowMs) {
                AssistantSignalProviderGateDecision(
                action = AssistantSignalProviderGateAction.DEFER,
                reason = "provider_cooldown",
                deferByMs = state.cooldownUntilMs - nowMs,
            )
            } else if (state.healthScore <= 20) {
                AssistantSignalProviderGateDecision(
                    action = AssistantSignalProviderGateAction.DEFER,
                    reason = "provider_health_degraded",
                    deferByMs = 5 * 60 * 1000L,
                )
            } else {
                AssistantSignalProviderGateDecision(AssistantSignalProviderGateAction.ALLOW)
            }
        rememberGateDecision(providerId = providerId, decision = decision)
        return decision
    }

    fun evaluateSignal(
        signal: AssistantExternalSignal,
        nowMs: Long = System.currentTimeMillis(),
    ): AssistantSignalProviderGateDecision {
        val providerId = signal.providerId()
        val state = read(providerId)
        if (state == null) {
            return AssistantSignalProviderGateDecision(AssistantSignalProviderGateAction.ALLOW)
        }
        val decision =
            if (!state.enabled) {
                AssistantSignalProviderGateDecision(
                action = AssistantSignalProviderGateAction.DENY,
                reason = "provider_disabled",
            )
            } else if (state.cooldownUntilMs > nowMs) {
                AssistantSignalProviderGateDecision(
                action = AssistantSignalProviderGateAction.DEFER,
                reason = "provider_cooldown",
                deferByMs = state.cooldownUntilMs - nowMs,
            )
            } else if (!state.supports(signal.capability)) {
                AssistantSignalProviderGateDecision(
                    action = AssistantSignalProviderGateAction.DENY,
                    reason = "provider_capability_not_supported",
                )
            } else if (!state.supports(signal.type)) {
                AssistantSignalProviderGateDecision(
                    action = AssistantSignalProviderGateAction.DENY,
                    reason = "provider_signal_type_not_supported",
                )
            } else if (state.healthScore <= 20) {
                AssistantSignalProviderGateDecision(
                    action = AssistantSignalProviderGateAction.DEFER,
                    reason = "provider_health_degraded",
                    deferByMs = 5 * 60 * 1000L,
                )
            } else if (
                state.trustLevel == AssistantSignalProviderTrustLevel.LOW &&
                signal.capability == SessionCapabilityKey.START_SESSION
            ) {
                AssistantSignalProviderGateDecision(
                action = AssistantSignalProviderGateAction.DENY,
                reason = "low_trust_provider_cannot_start_session",
            )
            } else {
                AssistantSignalProviderGateDecision(AssistantSignalProviderGateAction.ALLOW)
            }
        rememberGateDecision(providerId = providerId, decision = decision)
        return decision
    }

    fun markSignalObserved(
        providerId: String,
        source: String,
        trustLevel: AssistantSignalProviderTrustLevel,
        reason: String = "",
    ): AssistantSignalProviderState =
        upsert(providerId, source, trustLevel) { current, now ->
            current.copy(
                source = source,
                trustLevel = trustLevel,
                lastSeenAtMs = now,
                lastAcceptedAtMs = now,
                totalSignals = current.totalSignals + 1,
                acceptedSignals = current.acceptedSignals + 1,
                healthScore = (current.healthScore + 2).coerceAtMost(100),
                lastGateAction = AssistantSignalProviderGateAction.ALLOW,
                lastFailureMode = "",
                diagnosticsSummary = "accepted:${reason.ifBlank { "signal_observed" }}",
                lastReason = reason.ifBlank { current.lastReason },
                updatedAtMs = now,
            )
        }

    fun markSignalRejected(
        providerId: String,
        reason: String,
        cooldownMs: Long = 0L,
    ): AssistantSignalProviderState? =
        mutate(providerId) { current, now ->
            current.copy(
                rejectedSignals = current.rejectedSignals + 1,
                failureCount = current.failureCount + 1,
                lastFailureAtMs = now,
                cooldownUntilMs = maxOf(current.cooldownUntilMs, now + cooldownMs),
                healthScore = (current.healthScore - 12).coerceAtLeast(0),
                lastGateAction =
                    if (cooldownMs > 0L) {
                        AssistantSignalProviderGateAction.DEFER
                    } else {
                        AssistantSignalProviderGateAction.DENY
                    },
                lastFailureMode = inferFailureMode(reason),
                diagnosticsSummary = buildDiagnosticsSummary(reason = reason, cooldownMs = cooldownMs, healthScore = current.healthScore - 12),
                lastReason = reason,
                updatedAtMs = now,
            )
        }

    fun markSignalProcessed(
        providerId: String,
        success: Boolean,
        reason: String = "",
        cooldownMs: Long = 0L,
    ): AssistantSignalProviderState? =
        mutate(providerId) { current, now ->
            if (success) {
                current.copy(
                    lastSuccessAtMs = now,
                    failureCount = 0,
                    cooldownUntilMs = 0L,
                    healthScore = (current.healthScore + 4).coerceAtMost(100),
                    lastGateAction = AssistantSignalProviderGateAction.ALLOW,
                    lastFailureMode = "",
                    diagnosticsSummary = "healthy:${reason.ifBlank { "signal_processed" }}",
                    lastReason = reason.ifBlank { current.lastReason },
                    updatedAtMs = now,
                )
            } else {
                current.copy(
                    failureCount = current.failureCount + 1,
                    lastFailureAtMs = now,
                    cooldownUntilMs = maxOf(current.cooldownUntilMs, now + cooldownMs),
                    healthScore = (current.healthScore - 15).coerceAtLeast(0),
                    lastGateAction =
                        if (cooldownMs > 0L) {
                            AssistantSignalProviderGateAction.DEFER
                        } else {
                            AssistantSignalProviderGateAction.DENY
                        },
                    lastFailureMode = inferFailureMode(reason),
                    diagnosticsSummary = buildDiagnosticsSummary(reason = reason, cooldownMs = cooldownMs, healthScore = current.healthScore - 15),
                    lastReason = reason,
                    updatedAtMs = now,
                )
            }
        }

    fun markProbeResult(
        providerId: String,
        success: Boolean,
        reason: String = "",
        cooldownMs: Long = 0L,
    ): AssistantSignalProviderState? =
        mutate(providerId) { current, now ->
            if (success) {
                current.copy(
                    lastSeenAtMs = now,
                    lastSuccessAtMs = maxOf(current.lastSuccessAtMs, now),
                    cooldownUntilMs = if (cooldownMs > 0L) now + cooldownMs else current.cooldownUntilMs,
                    healthScore = (current.healthScore + 1).coerceAtMost(100),
                    lastGateAction = AssistantSignalProviderGateAction.ALLOW,
                    lastFailureMode = "",
                    diagnosticsSummary = "probe_ok:${reason.ifBlank { "provider_probe" }}",
                    lastReason = reason.ifBlank { current.lastReason },
                    updatedAtMs = now,
                )
            } else {
                current.copy(
                    lastSeenAtMs = now,
                    lastFailureAtMs = now,
                    cooldownUntilMs = maxOf(current.cooldownUntilMs, now + cooldownMs),
                    healthScore = (current.healthScore - 4).coerceAtLeast(0),
                    lastGateAction =
                        if (cooldownMs > 0L) {
                            AssistantSignalProviderGateAction.DEFER
                        } else {
                            AssistantSignalProviderGateAction.DENY
                        },
                    lastFailureMode = inferFailureMode(reason),
                    diagnosticsSummary = "probe_fail:${buildDiagnosticsSummary(reason = reason, cooldownMs = cooldownMs, healthScore = current.healthScore - 4)}",
                    lastReason = reason,
                    updatedAtMs = now,
                )
            }
        }

    fun read(
        providerId: String,
    ): AssistantSignalProviderState? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providers.firstOrNull { it.providerId == providerId }
        }

    fun readAll(
        limit: Int = 16,
    ): List<AssistantSignalProviderState> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providers
                .sortedWith(
                    compareByDescending<AssistantSignalProviderState> { it.routingPriority }
                        .thenByDescending { it.updatedAtMs },
                )
                .take(limit)
        }

    fun exportJson(
        limit: Int = 24,
    ): JSONArray =
        JSONArray().apply {
            readAll(limit).forEach { put(it.toJson()) }
        }

    fun importJson(
        array: JSONArray?,
    ) {
        if (array == null || array.length() <= 0) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                providers.addLast(json.toProvider())
            }
            dedupeAndTrimUnlocked()
            persistUnlocked()
        }
    }

    private fun upsert(
        providerId: String,
        source: String,
        trustLevel: AssistantSignalProviderTrustLevel,
        providerType: String = source,
        supportedCapabilities: List<SessionCapabilityKey> = emptyList(),
        supportedSignalTypes: List<AssistantExternalSignalType> = emptyList(),
        routingTags: List<String> = emptyList(),
        preferredEntrySource: String = "",
        deliveryMode: String = "",
        routingPriority: Int = 0,
        reducer: (AssistantSignalProviderState, Long) -> AssistantSignalProviderState,
    ): AssistantSignalProviderState =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val index = providers.indexOfFirst { it.providerId == providerId }
            val current =
                if (index >= 0) {
                    providers.elementAt(index)
                } else {
                    AssistantSignalProviderState(
                        providerId = providerId,
                        source = source,
                        trustLevel = trustLevel,
                        providerType = providerType,
                        supportedCapabilities = supportedCapabilities.distinct(),
                        supportedSignalTypes = supportedSignalTypes.distinct(),
                        routingTags = routingTags.distinct(),
                        preferredEntrySource = preferredEntrySource,
                        deliveryMode = deliveryMode,
                        routingPriority = routingPriority,
                    )
                }
            val next = reducer(current, now)
            if (index >= 0) {
                providers.removeAt(index)
                providers.add(index, next)
            } else {
                providers.addFirst(next)
            }
            dedupeAndTrimUnlocked()
            persistUnlocked()
            next
        }

    private fun rememberGateDecision(
        providerId: String,
        decision: AssistantSignalProviderGateDecision,
    ) {
        mutate(providerId) { current, now ->
            current.copy(
                lastGateAction = decision.action,
                lastFailureMode =
                    if (decision.action == AssistantSignalProviderGateAction.ALLOW) {
                        ""
                    } else {
                        inferFailureMode(decision.reason)
                    },
                diagnosticsSummary =
                    if (decision.action == AssistantSignalProviderGateAction.ALLOW) {
                        "healthy:${decision.reason.ifBlank { "allow" }}"
                    } else {
                        buildDiagnosticsSummary(
                            reason = decision.reason,
                            cooldownMs = decision.deferByMs,
                            healthScore = current.healthScore,
                        )
                    },
                lastReason = decision.reason.ifBlank { current.lastReason },
                updatedAtMs = now,
            )
        }
    }

    private fun mutate(
        providerId: String,
        reducer: (AssistantSignalProviderState, Long) -> AssistantSignalProviderState,
    ): AssistantSignalProviderState? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = providers.indexOfFirst { it.providerId == providerId }
            if (index < 0) return null
            val now = System.currentTimeMillis()
            val next = reducer(providers.elementAt(index), now)
            providers.removeAt(index)
            providers.add(index, next)
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = providerFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("providers") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                providers.addLast(json.toProvider())
            }
            dedupeAndTrimUnlocked()
        }
    }

    private fun dedupeAndTrimUnlocked() {
        val merged =
            providers
                .distinctBy { it.providerId }
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_STATES)
        providers.clear()
        merged.forEach(providers::addLast)
    }

    private fun persistUnlocked() {
        val file = providerFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "providers",
                    JSONArray().apply {
                        providers.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun providerFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, PROVIDER_DIR), PROVIDER_FILE)
    }

    private fun AssistantSignalProviderState.toJson(): JSONObject =
        JSONObject().apply {
            put("provider_id", providerId)
            put("source", source)
            put("trust_level", trustLevel.name)
            put("provider_type", providerType)
            put(
                "supported_capabilities",
                JSONArray().apply {
                    supportedCapabilities.forEach { put(it.name) }
                },
            )
            put(
                "supported_signal_types",
                JSONArray().apply {
                    supportedSignalTypes.forEach { put(it.name) }
                },
            )
            put("routing_tags", JSONArray(routingTags))
            put("preferred_entry_source", preferredEntrySource)
            put("delivery_mode", deliveryMode)
            put("routing_priority", routingPriority)
            put("enabled", enabled)
            put("last_seen_at_ms", lastSeenAtMs)
            put("last_accepted_at_ms", lastAcceptedAtMs)
            put("last_success_at_ms", lastSuccessAtMs)
            put("last_failure_at_ms", lastFailureAtMs)
            put("failure_count", failureCount)
            put("cooldown_until_ms", cooldownUntilMs)
            put("total_signals", totalSignals)
            put("accepted_signals", acceptedSignals)
            put("rejected_signals", rejectedSignals)
            put("health_score", healthScore)
            put("last_gate_action", lastGateAction.name)
            put("last_failure_mode", lastFailureMode)
            put("diagnostics_summary", diagnosticsSummary)
            put("last_reason", lastReason)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toProvider(): AssistantSignalProviderState =
        AssistantSignalProviderState(
            providerId = optString("provider_id"),
            source = optString("source"),
            trustLevel =
                runCatching { AssistantSignalProviderTrustLevel.valueOf(optString("trust_level")) }
                    .getOrDefault(AssistantSignalProviderTrustLevel.MEDIUM),
            providerType = optString("provider_type"),
            supportedCapabilities = optJSONArray("supported_capabilities").toCapabilities(),
            supportedSignalTypes = optJSONArray("supported_signal_types").toSignalTypes(),
            routingTags = optJSONArray("routing_tags").toStringList(),
            preferredEntrySource = optString("preferred_entry_source"),
            deliveryMode = optString("delivery_mode"),
            routingPriority = optInt("routing_priority"),
            enabled = optBoolean("enabled", true),
            lastSeenAtMs = optLong("last_seen_at_ms"),
            lastAcceptedAtMs = optLong("last_accepted_at_ms"),
            lastSuccessAtMs = optLong("last_success_at_ms"),
            lastFailureAtMs = optLong("last_failure_at_ms"),
            failureCount = optInt("failure_count"),
            cooldownUntilMs = optLong("cooldown_until_ms"),
            totalSignals = optInt("total_signals"),
            acceptedSignals = optInt("accepted_signals"),
            rejectedSignals = optInt("rejected_signals"),
            healthScore = optInt("health_score", 100),
            lastGateAction =
                runCatching { AssistantSignalProviderGateAction.valueOf(optString("last_gate_action")) }
                    .getOrDefault(AssistantSignalProviderGateAction.ALLOW),
            lastFailureMode = optString("last_failure_mode"),
            diagnosticsSummary = optString("diagnostics_summary"),
            lastReason = optString("last_reason"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONArray?.toCapabilities(): List<SessionCapabilityKey> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                runCatching { SessionCapabilityKey.valueOf(optString(index)) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun JSONArray?.toSignalTypes(): List<AssistantExternalSignalType> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                runCatching { AssistantExternalSignalType.valueOf(optString(index)) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun inferFailureMode(
        reason: String,
    ): String {
        val normalized = reason.trim().lowercase()
        return when {
            normalized.contains("permission") -> "permission"
            normalized.contains("cooldown") -> "cooldown"
            normalized.contains("health") -> "health"
            normalized.contains("capability") -> "capability"
            normalized.contains("signal_type") || normalized.contains("signal type") -> "signal_type"
            normalized.contains("low_trust") || normalized.contains("trust") -> "trust"
            normalized.contains("provider_disabled") || normalized.contains("disabled") -> "disabled"
            normalized.isBlank() -> ""
            else -> "source"
        }
    }

    private fun buildDiagnosticsSummary(
        reason: String,
        cooldownMs: Long,
        healthScore: Int,
    ): String =
        buildString {
            append(inferFailureMode(reason).ifBlank { "unknown" })
            append(":").append(reason.ifBlank { "-" })
            if (cooldownMs > 0L) {
                append(" cooldown=").append(cooldownMs / 1000L).append("s")
            }
            append(" health=").append(healthScore.coerceIn(0, 100))
        }
}

fun AssistantExternalSignal.providerId(): String =
    payload["provider_source"].orEmpty().ifBlank { source.ifBlank { "unknown_provider" } }
