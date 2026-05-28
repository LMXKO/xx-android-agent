package com.lmx.xiaoxuanagent.safety

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSafetyPolicyBehavior {
    ALLOW,
    ASK,
    DENY,
}

data class RuntimeSafetyPolicyRule(
    val ruleId: String,
    val behavior: RuntimeSafetyPolicyBehavior,
    val actionFamily: String,
    val targetPackageName: String = "",
    val toolName: String = "",
    val pageState: String = "",
    val targetTextContains: String = "",
    val contactContains: String = "",
    val dataType: String = "",
    val note: String = "",
    val sourceTag: String = "",
    val surfaceHint: String = "",
    val explanation: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

fun RuntimeSafetyPolicyRule.scopeSummary(): String =
    buildString {
        append(actionFamily)
        append(" | app=").append(targetPackageName.ifBlank { "*" })
        append(" | tool=").append(toolName.ifBlank { "*" })
        append(" | page=").append(pageState.ifBlank { "*" })
        append(" | text=").append(targetTextContains.ifBlank { "*" })
        append(" | contact=").append(contactContains.ifBlank { "*" })
        append(" | data=").append(dataType.ifBlank { "*" })
    }

fun RuntimeSafetyPolicyRule.behaviorSummary(): String =
    explanation.ifBlank {
        when (behavior) {
            RuntimeSafetyPolicyBehavior.ALLOW -> "命中此范围时默认放行。"
            RuntimeSafetyPolicyBehavior.ASK -> "命中此范围时始终要求人工确认。"
            RuntimeSafetyPolicyBehavior.DENY -> "命中此范围时直接拒绝执行。"
        }
    }

object RuntimeSafetyPolicyStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "safety_policy_rules.json"
    private const val MAX_RULES = 160
    private val lock = Any()
    private val rules = mutableListOf<RuntimeSafetyPolicyRule>()
    private var hydrated = false

    fun resolveRule(
        actionFamily: String,
        targetPackageName: String,
        toolName: String = "",
        pageState: String = "",
        targetText: String = "",
        contact: String = "",
        dataType: String = "",
    ): RuntimeSafetyPolicyRule? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            rules
                .asSequence()
                .filter { it.actionFamily == actionFamily }
                .sortedWith(
                    compareByDescending<RuntimeSafetyPolicyRule> { it.targetPackageName == targetPackageName }
                        .thenByDescending { it.contactContains.isNotBlank() && contact.contains(it.contactContains, ignoreCase = true) }
                        .thenByDescending { it.dataType.isNotBlank() && it.dataType.equals(dataType, ignoreCase = true) }
                        .thenByDescending { it.toolName.isNotBlank() && it.toolName == toolName }
                        .thenByDescending { it.pageState.isNotBlank() && it.pageState == pageState }
                        .thenByDescending { it.targetTextContains.isNotBlank() && targetText.contains(it.targetTextContains, ignoreCase = true) }
                        .thenByDescending { it.targetPackageName.isBlank() }
                        .thenByDescending { it.updatedAtMs },
                ).firstOrNull { rule ->
                    (rule.targetPackageName.isBlank() || rule.targetPackageName == targetPackageName) &&
                        (rule.toolName.isBlank() || rule.toolName == toolName) &&
                        (rule.pageState.isBlank() || rule.pageState == pageState) &&
                        (rule.targetTextContains.isBlank() || targetText.contains(rule.targetTextContains, ignoreCase = true)) &&
                        (rule.contactContains.isBlank() || contact.contains(rule.contactContains, ignoreCase = true)) &&
                        (rule.dataType.isBlank() || rule.dataType.equals(dataType, ignoreCase = true))
                }
        }

    fun readRules(
        limit: Int = 24,
        actionFamily: String = "",
        targetPackageName: String = "",
        toolName: String = "",
        pageState: String = "",
        behavior: RuntimeSafetyPolicyBehavior? = null,
    ): List<RuntimeSafetyPolicyRule> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            rules
                .asSequence()
                .filter { actionFamily.isBlank() || it.actionFamily == actionFamily }
                .filter { targetPackageName.isBlank() || it.targetPackageName == targetPackageName }
                .filter { toolName.isBlank() || it.toolName == toolName }
                .filter { pageState.isBlank() || it.pageState == pageState }
                .filter { behavior == null || it.behavior == behavior }
                .sortedByDescending { it.updatedAtMs }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun ensureSuperAssistantBaselineRules(): List<RuntimeSafetyPolicyRule> {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            // 清掉旧版本遗留的 baseline 规则，避免 message_send/submit 等旧 ASK 规则把自治策略拉回频繁确认。
            val before = rules.size
            rules.removeAll { it.sourceTag == "super_assistant_baseline" }
            if (rules.size != before) persistUnlocked()
        }
        return SUPER_ASSISTANT_BASELINE_RULES.mapNotNull { baseline ->
            upsertRule(
                behavior = baseline.behavior,
                actionFamily = baseline.actionFamily,
                targetPackageName = baseline.targetPackageName,
                toolName = baseline.toolName,
                pageState = baseline.pageState,
                targetTextContains = baseline.targetTextContains,
                note = baseline.note,
                sourceTag = "super_assistant_baseline",
                surfaceHint = baseline.surfaceHint,
                explanation = baseline.explanation,
            )
        }
    }

    fun upsertRule(
        behavior: RuntimeSafetyPolicyBehavior,
        actionFamily: String,
        targetPackageName: String = "",
        toolName: String = "",
        pageState: String = "",
        targetTextContains: String = "",
        contactContains: String = "",
        dataType: String = "",
        note: String = "",
        sourceTag: String = "",
        surfaceHint: String = "",
        explanation: String = "",
    ): RuntimeSafetyPolicyRule? {
        val normalizedFamily = actionFamily.trim()
        if (normalizedFamily.isBlank()) return null
        val normalizedPackage = targetPackageName.trim()
        val normalizedToolName = toolName.trim()
        val normalizedPageState = pageState.trim()
        val normalizedTargetText = targetTextContains.trim()
        val normalizedContact = contactContains.trim()
        val normalizedDataType = dataType.trim()
        val normalizedSourceTag = sourceTag.trim()
        val normalizedSurfaceHint = surfaceHint.trim()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val index =
                rules.indexOfFirst {
                    it.actionFamily == normalizedFamily &&
                        it.targetPackageName == normalizedPackage &&
                        it.toolName == normalizedToolName &&
                        it.pageState == normalizedPageState &&
                        it.targetTextContains == normalizedTargetText &&
                        it.contactContains == normalizedContact &&
                        it.dataType == normalizedDataType
                }
            val next =
                RuntimeSafetyPolicyRule(
                    ruleId = if (index >= 0) rules[index].ruleId else "policy_${now}_${normalizedFamily}",
                    behavior = behavior,
                    actionFamily = normalizedFamily,
                    targetPackageName = normalizedPackage,
                    toolName = normalizedToolName,
                    pageState = normalizedPageState,
                    targetTextContains = normalizedTargetText,
                    contactContains = normalizedContact,
                    dataType = normalizedDataType,
                    note = note.take(160),
                    sourceTag = normalizedSourceTag.take(48),
                    surfaceHint = normalizedSurfaceHint.take(88),
                    explanation = explanation.trim().ifBlank { defaultExplanation(behavior, normalizedSurfaceHint, normalizedSourceTag) }.take(160),
                    createdAtMs = if (index >= 0) rules[index].createdAtMs else now,
                    updatedAtMs = now,
                )
            if (index >= 0) {
                rules[index] = next
            } else {
                rules.add(0, next)
            }
            trimUnlocked()
            persistUnlocked()
            next
        }
    }

    /** 可读审计：列出当前持久化的所有规则（行为 + 范围 + 来源 + 更新时间），用于权限治理追溯。 */
    fun auditLines(
        limit: Int = 64,
    ): List<String> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            rules
                .sortedByDescending { it.updatedAtMs }
                .take(limit.coerceAtLeast(1))
                .map { rule ->
                    "${rule.behavior.name.lowercase()} :: ${rule.scopeSummary()} :: src=${rule.sourceTag.ifBlank { "user" }}"
                }
        }

    fun deleteRule(
        ruleId: String,
    ): RuntimeSafetyPolicyRule? {
        if (ruleId.isBlank()) return null
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = rules.indexOfFirst { it.ruleId == ruleId }
            if (index < 0) return@synchronized null
            val removed = rules.removeAt(index)
            persistUnlocked()
            removed
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("rules") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toRule()?.let(rules::add)
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
                    "rules",
                    JSONArray().apply {
                        rules.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (rules.size > MAX_RULES) {
            rules.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun RuntimeSafetyPolicyRule.toJson(): JSONObject =
        JSONObject().apply {
            put("rule_id", ruleId)
            put("behavior", behavior.name)
            put("action_family", actionFamily)
            put("target_package_name", targetPackageName)
            put("tool_name", toolName)
            put("page_state", pageState)
            put("target_text_contains", targetTextContains)
            put("contact_contains", contactContains)
            put("data_type", dataType)
            put("note", note)
            put("source_tag", sourceTag)
            put("surface_hint", surfaceHint)
            put("explanation", explanation)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toRule(): RuntimeSafetyPolicyRule =
        RuntimeSafetyPolicyRule(
            ruleId = optString("rule_id"),
            behavior = runCatching { RuntimeSafetyPolicyBehavior.valueOf(optString("behavior")) }.getOrDefault(RuntimeSafetyPolicyBehavior.ASK),
            actionFamily = optString("action_family"),
            targetPackageName = optString("target_package_name"),
            toolName = optString("tool_name"),
            pageState = optString("page_state"),
            targetTextContains = optString("target_text_contains"),
            contactContains = optString("contact_contains"),
            dataType = optString("data_type"),
            note = optString("note"),
            sourceTag = optString("source_tag"),
            surfaceHint = optString("surface_hint"),
            explanation = optString("explanation"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun defaultExplanation(
        behavior: RuntimeSafetyPolicyBehavior,
        surfaceHint: String,
        sourceTag: String,
    ): String {
        val scopeHint =
            listOfNotNull(
                surfaceHint.takeIf { it.isNotBlank() },
                sourceTag.takeIf { it.isNotBlank() },
            ).joinToString(" / ")
        val suffix = scopeHint.takeIf { it.isNotBlank() }?.let { "（范围：$it）" }.orEmpty()
        return when (behavior) {
            RuntimeSafetyPolicyBehavior.ALLOW -> "命中此范围时默认放行$suffix"
            RuntimeSafetyPolicyBehavior.ASK -> "命中此范围时始终确认$suffix"
            RuntimeSafetyPolicyBehavior.DENY -> "命中此范围时直接拒绝$suffix"
        }
    }

    private data class BaselineRule(
        val behavior: RuntimeSafetyPolicyBehavior,
        val actionFamily: String,
        val targetPackageName: String = "",
        val toolName: String = "",
        val pageState: String = "",
        val targetTextContains: String = "",
        val note: String = "",
        val surfaceHint: String = "",
        val explanation: String = "",
    )

    private val SUPER_ASSISTANT_BASELINE_RULES =
        listOf(
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.DENY,
                actionFamily = "transaction",
                note = "支付/转账/下单类交易默认交给用户。",
                surfaceHint = "payment_or_checkout",
                explanation = "支付、转账、下单等交易动作必须交给用户亲自完成。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.ASK,
                actionFamily = "destructive",
                note = "不可逆删除/注销默认需要确认。",
                surfaceHint = "irreversible_change",
                explanation = "删除、注销、拉黑等不可逆动作需要用户确认后执行；其余动作默认自动执行。",
            ),
        )
}
