package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyRule
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore
import com.lmx.xiaoxuanagent.safety.behaviorSummary
import com.lmx.xiaoxuanagent.safety.scopeSummary
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionPermissionProductTab(
    val id: String,
    val title: String,
    val count: Int = 0,
    val summary: String = "",
    val active: Boolean = false,
)

data class SessionPermissionProductCard(
    val ruleId: String,
    val cardType: String = "rule",
    val behavior: String,
    val title: String,
    val subtitle: String = "",
    val scope: String,
    val sourceTag: String = "",
    val surfaceHint: String = "",
    val explanation: String = "",
    val matchSummary: String = "",
    val primaryCommand: String = "",
    val secondaryCommands: List<String> = emptyList(),
)

data class SessionPermissionProductSnapshot(
    val sessionId: String = "",
    val activeTab: String = "",
    val query: String = "",
    val recentCount: Int = 0,
    val pendingCount: Int = 0,
    val grantCount: Int = 0,
    val allowCount: Int = 0,
    val askCount: Int = 0,
    val denyCount: Int = 0,
    val sourceCount: Int = 0,
    val tabCount: Int = 0,
    val cardCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val tabs: List<SessionPermissionProductTab> = emptyList(),
    val cards: List<SessionPermissionProductCard> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionPermissionProductStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_permission_product.json"
    private const val MAX_SNAPSHOTS = 120
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionPermissionProductSnapshot>()
    private var hydrated = false

    fun refresh(
        sessionId: String = "",
        limit: Int = 12,
        behavior: String = "",
        query: String = "",
    ): SessionPermissionProductSnapshot {
        val allRules = RuntimeSafetyPolicyStore.readRules(limit = maxOf(limit * 8, 64))
        val decisions = SessionSafetyDecisionStore.readRecent(sessionId = sessionId, limit = maxOf(limit * 4, 24))
        val grants = RuntimeSafetyGrantStore.readGrants(limit = maxOf(limit * 4, 24), includeInactive = false, sessionId = sessionId)
        val pending =
            when {
                sessionId.isBlank() || SessionRuntime.State.runtimeState().session.sessionId == sessionId ->
                    SessionRuntime.State.runtimeState().safety.pendingConfirmation
                else -> SessionPlatformFacade.readSessionSnapshot(sessionId).resumeSnapshot?.safety?.pendingConfirmation
            }
        val groupedSources = allRules.groupBy { it.sourceTag.ifBlank { "manual" } }
        val allowCount = allRules.count { it.behavior == RuntimeSafetyPolicyBehavior.ALLOW }
        val askCount = allRules.count { it.behavior == RuntimeSafetyPolicyBehavior.ASK }
        val denyCount = allRules.count { it.behavior == RuntimeSafetyPolicyBehavior.DENY }
        val activeTab = normalizeTab(behavior).ifBlank { preferredTab(allowCount, askCount, denyCount, pending != null, decisions.isNotEmpty(), grants.isNotEmpty()) }
        val normalizedQuery = query.trim()
        val filteredRules =
            allRules
                .asSequence()
                .filter { tabMatches(it, activeTab) }
                .filter { matchesQuery(it, normalizedQuery) }
                .take(limit.coerceAtLeast(1))
                .toList()
        val recentCards =
            buildList {
                pending?.let { pendingReview ->
                    add(
                        SessionPermissionProductCard(
                            ruleId = pendingReview.approvalKey.ifBlank { "pending_${sessionId.ifBlank { "current" }}" },
                            cardType = "pending",
                            behavior = "ask",
                            title = pendingReview.actionLabel.ifBlank { "待审批动作" },
                            subtitle = "pending",
                            scope = pendingReview.targetPackageName.ifBlank { "*" },
                            sourceTag = "runtime",
                            surfaceHint = "approval_required",
                            explanation = pendingReview.summary.take(160),
                            matchSummary = listOfNotNull(pendingReview.actionFamily.takeIf { it.isNotBlank() }, pendingReview.grantScopeHint.takeIf { it.isNotBlank() }).joinToString(" | "),
                            primaryCommand = sessionId.takeIf { it.isNotBlank() }?.let { "/approve --session-id $it" }.orEmpty(),
                            secondaryCommands =
                                listOfNotNull(
                                    sessionId.takeIf { it.isNotBlank() }?.let { "/reject --session-id $it" },
                                    sessionId.takeIf { it.isNotBlank() }?.let { "/permission-center --session-id $it" },
                                ),
                        ),
                    )
                }
                decisions
                    .asSequence()
                    .filter { matchesDecisionQuery(it, normalizedQuery) }
                    .take(limit.coerceAtLeast(1))
                    .forEach { decision ->
                        add(
                            SessionPermissionProductCard(
                                ruleId = decision.decisionId,
                                cardType = "decision",
                                behavior = decision.outcome,
                                title = decision.actionLabel.ifBlank { decision.actionFamily.ifBlank { "decision" } },
                                subtitle = decision.outcome,
                                scope = decision.targetPackageName.ifBlank { "*" },
                                sourceTag = "history",
                                surfaceHint = decision.actionFamily,
                                explanation = decision.summary.take(160),
                                matchSummary = decision.detailLines.joinToString(" | ").take(120),
                                primaryCommand = sessionId.takeIf { it.isNotBlank() }?.let { "/safety-decisions --session-id $it" }.orEmpty(),
                            ),
                        )
                    }
                grants
                    .asSequence()
                    .filter { matchesGrantQuery(it.actionFamily, it.targetPackageName, normalizedQuery) }
                    .take(limit.coerceAtLeast(1))
                    .forEach { grant ->
                        add(
                            SessionPermissionProductCard(
                                ruleId = grant.grantId,
                                cardType = "grant",
                                behavior = "allow",
                                title = grant.actionFamily.ifBlank { "runtime_grant" },
                                subtitle = grant.scope,
                                scope = grant.targetPackageName.ifBlank { "*" },
                                sourceTag = "runtime_grant",
                                surfaceHint = grant.scope,
                                explanation = "当前存在 ${grant.scope} 级运行时授权。",
                                matchSummary = "session=${grant.sessionId.ifBlank { "-" }}",
                                primaryCommand = "/revoke-runtime-safety-grant --grant-id ${grant.grantId}",
                            ),
                        )
                    }
            }.take(limit.coerceAtLeast(1))
        val presentation =
            AppPermissionPresentationStore.build(
                rules = allRules,
                grants = grants,
                sessionId = sessionId,
            )
        val tabs =
            listOf(
                tab("recent", pendingCount = if (pending != null) 1 else 0, recentCount = decisions.size, grantCount = grants.size, active = activeTab == "recent"),
                tab("allow", count = allowCount, active = activeTab == "allow"),
                tab("ask", count = askCount, active = activeTab == "ask"),
                tab("deny", count = denyCount, active = activeTab == "deny"),
            )
        val cards =
            if (activeTab == "recent") {
                (recentCards + presentation.cards).take(limit.coerceAtLeast(1))
            } else {
                filteredRules.map { rule ->
                    SessionPermissionProductCard(
                        ruleId = rule.ruleId,
                        cardType = "rule",
                        behavior = rule.behavior.name.lowercase(),
                        title = buildCardTitle(rule),
                        subtitle = rule.note.take(56),
                        scope = rule.scopeSummary(),
                        sourceTag = rule.sourceTag,
                        surfaceHint = rule.surfaceHint,
                        explanation = rule.behaviorSummary().take(160),
                        matchSummary = buildMatchSummary(rule),
                        primaryCommand = "/set-safety-policy --behavior ${rule.behavior.name.lowercase()} --action-family ${rule.actionFamily}",
                        secondaryCommands = listOf("/delete-safety-policy --rule-id ${rule.ruleId}"),
                    )
                } + if (activeTab == "ask") presentation.cards.take(2) else emptyList()
            }
        val snapshot =
            SessionPermissionProductSnapshot(
                sessionId = sessionId,
                activeTab = activeTab,
                query = normalizedQuery,
                recentCount = recentCards.size,
                pendingCount = if (pending != null) 1 else 0,
                grantCount = grants.size,
                allowCount = allowCount,
                askCount = askCount,
                denyCount = denyCount,
                sourceCount = groupedSources.size,
                tabCount = tabs.size,
                cardCount = cards.size,
                summary =
                    buildString {
                        append("tab=").append(activeTab)
                        append(" cards=").append(cards.size)
                        append(" recent=").append(recentCards.size)
                        append(" pending=").append(if (pending != null) 1 else 0)
                        append(" grants=").append(grants.size)
                        append(" allow=").append(allowCount)
                        append(" ask=").append(askCount)
                        append(" deny=").append(denyCount)
                        append(" sources=").append(groupedSources.size)
                        normalizedQuery.takeIf { it.isNotBlank() }?.let { append(" query=").append(it.take(24)) }
                    },
                lines =
                    buildList {
                        tabs.forEach { tab ->
                            add("tab | ${tab.id} | active=${tab.active} | count=${tab.count} | ${tab.summary}")
                        }
                        pending?.let { add("pending | ${it.actionLabel.ifBlank { "-" }} | ${it.summary.take(120)}") }
                        grants.take(3).forEach { grant ->
                            add("grant | ${grant.scope} | ${grant.actionFamily.ifBlank { "-" }} | ${grant.targetPackageName.ifBlank { "*" }}")
                        }
                        groupedSources.entries.sortedByDescending { it.value.size }.take(4).forEach { (source, sourceRules) ->
                            add("source | $source | count=${sourceRules.size}")
                        }
                        addAll(presentation.lines.take(4))
                        cards.take(6).forEach { card ->
                            add("card | ${card.cardType} | ${card.behavior} | ${card.title.take(56)} | ${card.scope.take(88)}")
                            card.sourceTag.takeIf { it.isNotBlank() }?.let { add("card_source | ${card.ruleId} | $it") }
                            card.surfaceHint.takeIf { it.isNotBlank() }?.let { add("card_surface | ${card.ruleId} | $it") }
                            card.subtitle.takeIf { it.isNotBlank() }?.let { add("card_subtitle | ${card.ruleId} | ${it.take(88)}") }
                            add("card_explain | ${card.ruleId} | ${card.explanation.take(120)}")
                            card.primaryCommand.takeIf { it.isNotBlank() }?.let { add("card_action | ${card.ruleId} | $it") }
                        }
                    },
                tabs = tabs,
                cards = cards,
                recommendedCommands =
                    buildList {
                        val sessionSuffix = sessionId.takeIf { it.isNotBlank() }?.let { " --session-id $it" }.orEmpty()
                        add("/permission-product$sessionSuffix --tab $activeTab")
                        tabs.forEach { tab -> add("/permission-product$sessionSuffix --tab ${tab.id}") }
                        normalizedQuery.takeIf { it.isNotBlank() }?.let { add("/permission-product$sessionSuffix --tab $activeTab --query \"$it\"") }
                        add("/permission-center$sessionSuffix")
                        if (pending != null && sessionId.isNotBlank()) add("/approve --session-id $sessionId")
                        if (allowCount > 0) add("/safety-policies --behavior allow")
                        if (askCount > 0) add("/safety-policies --behavior ask")
                        if (denyCount > 0) add("/safety-policies --behavior deny")
                        if (decisions.isNotEmpty()) add("/safety-decisions$sessionSuffix")
                    }.plus(presentation.recommendedCommands).distinct(),
                updatedAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[cacheKey(sessionId, activeTab, normalizedQuery)] = snapshot
            trimUnlocked()
            persistUnlocked()
        }
        return snapshot
    }

    fun readSnapshot(
        sessionId: String = "",
        behavior: String = "",
        query: String = "",
    ): SessionPermissionProductSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[cacheKey(sessionId, normalizeTab(behavior), query.trim())]
                ?: snapshots[cacheKey(sessionId, "", "")]
        }

    fun planningLines(
        sessionId: String = "",
        limit: Int = 3,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("permission_product: ${snapshot.summary.ifBlank { "-" }}")
                snapshot.tabs.forEach { add("permission_product_tab: ${it.id}:${it.count}:${it.active}") }
                addAll(snapshot.cards.take(limit.coerceAtLeast(1)).map { "permission_product_card: ${it.cardType}:${it.behavior}:${it.title.take(48)}" })
            }.take(limit.coerceAtLeast(1) + 1)
        }.orEmpty()

    private fun tab(
        id: String,
        count: Int = 0,
        pendingCount: Int = 0,
        recentCount: Int = 0,
        grantCount: Int = 0,
        active: Boolean,
    ): SessionPermissionProductTab =
        SessionPermissionProductTab(
            id = id,
            title =
                when (id) {
                    "recent" -> "Recent"
                    "allow" -> "Always Allow"
                    "deny" -> "Always Deny"
                    else -> "Always Ask"
                },
            count = if (id == "recent") pendingCount + recentCount + grantCount else count,
            summary =
                when (id) {
                    "recent" -> "查看最近审批、运行时授权与安全历史。"
                    "allow" -> "命中后默认放行，适合低风险高频动作。"
                    "deny" -> "命中后直接拒绝，适合永不允许的敏感范围。"
                    else -> "命中后始终确认，适合高风险但可人工接管的动作。"
                },
            active = active,
        )

    private fun preferredTab(
        allowCount: Int,
        askCount: Int,
        denyCount: Int,
        hasPending: Boolean,
        hasDecisions: Boolean,
        hasGrants: Boolean,
    ): String =
        when {
            hasPending || hasDecisions || hasGrants -> "recent"
            askCount > 0 -> "ask"
            denyCount > 0 -> "deny"
            allowCount > 0 -> "allow"
            else -> "ask"
        }

    private fun normalizeTab(
        behavior: String,
    ): String =
        when (behavior.trim().lowercase()) {
            "recent" -> "recent"
            "allow" -> "allow"
            "deny" -> "deny"
            else -> "ask".takeIf { behavior.isNotBlank() && behavior.trim().lowercase() == "ask" }.orEmpty()
        }

    private fun tabMatches(
        rule: RuntimeSafetyPolicyRule,
        activeTab: String,
    ): Boolean =
        when (activeTab) {
            "recent" -> false
            "allow" -> rule.behavior == RuntimeSafetyPolicyBehavior.ALLOW
            "deny" -> rule.behavior == RuntimeSafetyPolicyBehavior.DENY
            else -> rule.behavior == RuntimeSafetyPolicyBehavior.ASK
        }

    private fun matchesDecisionQuery(
        decision: SessionSafetyDecisionEntry,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        val haystack =
            listOf(
                decision.actionLabel,
                decision.actionFamily,
                decision.targetPackageName,
                decision.outcome,
                decision.summary,
            ).joinToString(" ").lowercase()
        return haystack.contains(query.lowercase())
    }

    private fun matchesGrantQuery(
        actionFamily: String,
        targetPackageName: String,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        val haystack = "$actionFamily $targetPackageName".lowercase()
        return haystack.contains(query.lowercase())
    }

    private fun matchesQuery(
        rule: RuntimeSafetyPolicyRule,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        val haystack =
            listOf(
                rule.actionFamily,
                rule.targetPackageName,
                rule.toolName,
                rule.pageState,
                rule.targetTextContains,
                rule.sourceTag,
                rule.surfaceHint,
                rule.explanation,
                rule.note,
            ).joinToString(" ").lowercase()
        return haystack.contains(query.lowercase())
    }

    private fun buildCardTitle(
        rule: RuntimeSafetyPolicyRule,
    ): String =
        buildString {
            append(rule.actionFamily.ifBlank { "general" })
            rule.targetPackageName.takeIf { it.isNotBlank() }?.let { append(" @ ").append(it) }
            rule.toolName.takeIf { it.isNotBlank() }?.let { append(" / ").append(it) }
        }

    private fun buildMatchSummary(
        rule: RuntimeSafetyPolicyRule,
    ): String =
        buildString {
            append(rule.behaviorSummary().take(88))
            rule.targetTextContains.takeIf { it.isNotBlank() }?.let { append(" | text~").append(it.take(32)) }
        }

    private fun cacheKey(
        sessionId: String,
        behavior: String,
        query: String,
    ): String =
        listOf(
            sessionId.ifBlank { "__global__" },
            behavior.ifBlank { "__default__" },
            query.ifBlank { "__all__" },
        ).joinToString("::")

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toSnapshot()?.let { snapshot ->
                    snapshots[cacheKey(snapshot.sessionId, snapshot.activeTab, snapshot.query)] = snapshot
                }
            }
            trimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "snapshots",
                    JSONArray().apply {
                        snapshots.values.sortedByDescending { it.updatedAtMs }.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (snapshots.size > MAX_SNAPSHOTS) {
            val oldest = snapshots.minByOrNull { it.value.updatedAtMs }?.key ?: break
            snapshots.remove(oldest)
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionPermissionProductSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("active_tab", activeTab)
            put("query", query)
            put("recent_count", recentCount)
            put("pending_count", pendingCount)
            put("grant_count", grantCount)
            put("allow_count", allowCount)
            put("ask_count", askCount)
            put("deny_count", denyCount)
            put("source_count", sourceCount)
            put("tab_count", tabCount)
            put("card_count", cardCount)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put("tabs", JSONArray().apply { tabs.forEach { put(it.toJson()) } })
            put("cards", JSONArray().apply { cards.forEach { put(it.toJson()) } })
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun SessionPermissionProductTab.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("count", count)
            put("summary", summary)
            put("active", active)
        }

    private fun SessionPermissionProductCard.toJson(): JSONObject =
        JSONObject().apply {
            put("rule_id", ruleId)
            put("card_type", cardType)
            put("behavior", behavior)
            put("title", title)
            put("subtitle", subtitle)
            put("scope", scope)
            put("source_tag", sourceTag)
            put("surface_hint", surfaceHint)
            put("explanation", explanation)
            put("match_summary", matchSummary)
            put("primary_command", primaryCommand)
            put("secondary_commands", JSONArray(secondaryCommands))
        }

    private fun JSONObject.toSnapshot(): SessionPermissionProductSnapshot =
        SessionPermissionProductSnapshot(
            sessionId = optString("session_id"),
            activeTab = optString("active_tab"),
            query = optString("query"),
            recentCount = optInt("recent_count"),
            pendingCount = optInt("pending_count"),
            grantCount = optInt("grant_count"),
            allowCount = optInt("allow_count"),
            askCount = optInt("ask_count"),
            denyCount = optInt("deny_count"),
            sourceCount = optInt("source_count"),
            tabCount = optInt("tab_count"),
            cardCount = optInt("card_count"),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            tabs =
                optJSONArray("tabs")
                    ?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optJSONObject(index)?.let { add(it.toTab()) }
                            }
                        }
                    }.orEmpty(),
            cards =
                optJSONArray("cards")
                    ?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optJSONObject(index)?.let { add(it.toCard()) }
                            }
                        }
                    }.orEmpty(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONObject.toTab(): SessionPermissionProductTab =
        SessionPermissionProductTab(
            id = optString("id"),
            title = optString("title"),
            count = optInt("count"),
            summary = optString("summary"),
            active = optBoolean("active"),
        )

    private fun JSONObject.toCard(): SessionPermissionProductCard =
        SessionPermissionProductCard(
            ruleId = optString("rule_id"),
            cardType = optString("card_type").ifBlank { "rule" },
            behavior = optString("behavior"),
            title = optString("title"),
            subtitle = optString("subtitle"),
            scope = optString("scope"),
            sourceTag = optString("source_tag"),
            surfaceHint = optString("surface_hint"),
            explanation = optString("explanation"),
            matchSummary = optString("match_summary"),
            primaryCommand = optString("primary_command"),
            secondaryCommands = optJSONArray("secondary_commands").toStringList(),
        )
}
