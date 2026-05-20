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
    ): RuntimeSafetyPolicyRule? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            rules
                .asSequence()
                .filter { it.actionFamily == actionFamily }
                .sortedWith(
                    compareByDescending<RuntimeSafetyPolicyRule> { it.targetPackageName == targetPackageName }
                        .thenByDescending { it.toolName.isNotBlank() && it.toolName == toolName }
                        .thenByDescending { it.pageState.isNotBlank() && it.pageState == pageState }
                        .thenByDescending { it.targetTextContains.isNotBlank() && targetText.contains(it.targetTextContains, ignoreCase = true) }
                        .thenByDescending { it.targetPackageName.isBlank() }
                        .thenByDescending { it.updatedAtMs },
                ).firstOrNull { rule ->
                    (rule.targetPackageName.isBlank() || rule.targetPackageName == targetPackageName) &&
                        (rule.toolName.isBlank() || rule.toolName == toolName) &&
                        (rule.pageState.isBlank() || rule.pageState == pageState) &&
                        (rule.targetTextContains.isBlank() || targetText.contains(rule.targetTextContains, ignoreCase = true))
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

    fun ensureSuperAssistantBaselineRules(): List<RuntimeSafetyPolicyRule> =
        SUPER_ASSISTANT_BASELINE_RULES.mapNotNull { baseline ->
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

    fun upsertRule(
        behavior: RuntimeSafetyPolicyBehavior,
        actionFamily: String,
        targetPackageName: String = "",
        toolName: String = "",
        pageState: String = "",
        targetTextContains: String = "",
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
                        it.targetTextContains == normalizedTargetText
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
                note = "超级手机助手默认禁止自动交易。",
                surfaceHint = "payment_or_checkout",
                explanation = "支付、转账、下单等交易动作必须交给用户亲自完成。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.DENY,
                actionFamily = "destructive",
                note = "超级手机助手默认禁止自动删除/注销。",
                surfaceHint = "irreversible_change",
                explanation = "删除、注销、拉黑等不可逆动作默认阻断。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.ASK,
                actionFamily = "message_send",
                note = "发消息前必须确认。",
                surfaceHint = "send_or_reply",
                explanation = "短信、IM、通知回复等真实发送动作需要人工确认。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.ASK,
                actionFamily = "content_publish",
                note = "发布内容前必须确认。",
                surfaceHint = "publish_content",
                explanation = "评论、发布、发表内容前需要用户确认最终内容。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.ASK,
                actionFamily = "submit_in_context",
                note = "上下文提交前必须确认。",
                surfaceHint = "context_submit",
                explanation = "输入框上下文里的提交动作需要确认，避免误发或误提交。",
            ),
            BaselineRule(
                behavior = RuntimeSafetyPolicyBehavior.ASK,
                actionFamily = "route_confirm",
                note = "开始导航前确认。",
                surfaceHint = "route_or_navigation",
                explanation = "路线确认和开始导航前需要用户确认目的地与路线。",
            ),
        )
}
