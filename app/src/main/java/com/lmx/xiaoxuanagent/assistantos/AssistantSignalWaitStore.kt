package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 信号等待绑定：把一个挂起会话绑定到"目标 App 的某类外部信号"。
 *
 * 修复审计发现的"入站信号无法恢复挂起会话"（通知 sessionId 恒空、等待条件从不被外部事件唤醒）。
 * 典型语义：会话挂起在登录/验证/权限墙（external_wait），绑定其目标 App 的 APP_FOREGROUND 信号；
 * 当用户在该 App 内完成手动步骤、App 回到前台时，[PersistentAssistantEngine] 解析绑定 → 经
 * 协调器 bootstrap 自动续跑（并由 [com.lmx.xiaoxuanagent.runtime.SessionTargetAppLauncher] 拉起目标 App）。
 */
data class AssistantSignalWaitBinding(
    val id: String,
    val sessionId: String,
    val targetPackageName: String,
    val signalType: AssistantExternalSignalType,
    val resumeEvent: String = "",
    val reason: String = "",
    val enabled: Boolean = true,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

object AssistantSignalWaitStore {
    private const val WAIT_DIR = "assistant_os"
    private const val WAIT_FILE = "signal_wait_bindings.json"
    private const val MAX_BINDINGS = 80
    private val lock = Any()
    private val bindings = ArrayDeque<AssistantSignalWaitBinding>()
    private var hydrated = false

    /** 绑定（幂等：相同 sessionId+signalType 更新而非新增）。 */
    fun bind(
        sessionId: String,
        targetPackageName: String,
        signalType: AssistantExternalSignalType,
        resumeEvent: String = "",
        reason: String = "",
    ): AssistantSignalWaitBinding? {
        if (sessionId.isBlank() || targetPackageName.isBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index =
                bindings.indexOfFirst { it.sessionId == sessionId && it.signalType == signalType }
            if (index >= 0) {
                val current = bindings.elementAt(index)
                val updated =
                    current.copy(
                        targetPackageName = targetPackageName,
                        resumeEvent = resumeEvent.ifBlank { current.resumeEvent },
                        reason = reason.ifBlank { current.reason },
                        enabled = true,
                        updatedAtMs = now,
                    )
                bindings.removeAt(index)
                bindings.addFirst(updated)
                persistUnlocked()
                return updated
            }
            val binding =
                AssistantSignalWaitBinding(
                    id = "signalwait_${now}_${signalType.name.lowercase()}",
                    sessionId = sessionId,
                    targetPackageName = targetPackageName,
                    signalType = signalType,
                    resumeEvent = resumeEvent,
                    reason = reason,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            bindings.addFirst(binding)
            trimUnlocked()
            persistUnlocked()
            return binding
        }
    }

    fun readActive(limit: Int = 40): List<AssistantSignalWaitBinding> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            bindings.filter { it.enabled }.sortedByDescending { it.updatedAtMs }.take(limit)
        }

    /** 用入站信号解析出应被唤醒的挂起会话绑定（按信号类型 + 目标 App 包名匹配）。 */
    fun resolve(
        signalType: AssistantExternalSignalType,
        packageName: String,
    ): AssistantSignalWaitBinding? {
        if (packageName.isBlank()) return null
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            bindings.firstOrNull {
                it.enabled && it.signalType == signalType && it.targetPackageName == packageName
            }
        }
    }

    /** 会话恢复后清空其全部绑定，避免陈旧重复唤醒。 */
    fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            var changed = false
            val updated =
                bindings.map { binding ->
                    if (binding.sessionId == sessionId && binding.enabled) {
                        changed = true
                        binding.copy(enabled = false, updatedAtMs = System.currentTimeMillis())
                    } else {
                        binding
                    }
                }
            if (changed) {
                bindings.clear()
                updated.forEach(bindings::addLast)
                persistUnlocked()
            }
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = waitFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("bindings") ?: JSONArray()
            bindings.clear()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.toBinding()?.let(bindings::addLast)
            }
        }
    }

    private fun persistUnlocked() {
        val file = waitFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "bindings",
                    JSONArray().apply {
                        bindings.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (bindings.size > MAX_BINDINGS) {
            bindings.removeLast()
        }
    }

    private fun waitFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, WAIT_DIR), WAIT_FILE)
    }

    private fun AssistantSignalWaitBinding.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("session_id", sessionId)
            put("target_package_name", targetPackageName)
            put("signal_type", signalType.name)
            put("resume_event", resumeEvent)
            put("reason", reason)
            put("enabled", enabled)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toBinding(): AssistantSignalWaitBinding =
        AssistantSignalWaitBinding(
            id = optString("id"),
            sessionId = optString("session_id"),
            targetPackageName = optString("target_package_name"),
            signalType =
                runCatching { AssistantExternalSignalType.valueOf(optString("signal_type")) }
                    .getOrDefault(AssistantExternalSignalType.APP_FOREGROUND),
            resumeEvent = optString("resume_event"),
            reason = optString("reason"),
            enabled = optBoolean("enabled", true),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    internal fun resetForTest() {
        synchronized(lock) {
            bindings.clear()
            waitFile()?.delete()
            // 置 hydrated=true 避免下次访问从磁盘重载旧绑定（测试环境可能存在真实 filesDir）。
            hydrated = true
        }
    }
}
