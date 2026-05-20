package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionResumeSnapshot
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantTriggerType {
    RESUME_SESSION,
    REVIEW_PENDING_SAFETY,
    FOLLOW_UP_WAITING_EXTERNAL,
    EXTERNAL_SIGNAL,
}

data class AssistantTrigger(
    val id: String,
    val type: AssistantTriggerType,
    val sessionId: String,
    val title: String,
    val summary: String,
    val nextFireAtMs: Long,
    val enabled: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
)

object TriggerRegistry {
    private const val TRIGGER_DIR = "assistant_os"
    private const val TRIGGER_FILE = "triggers.json"
    private val lock = Any()

    fun readAll(): List<AssistantTrigger> =
        synchronized(lock) {
            readUnlocked()
        }

    fun readDue(
        nowMs: Long = System.currentTimeMillis(),
    ): List<AssistantTrigger> =
        synchronized(lock) {
            readUnlocked().filter { it.enabled && it.nextFireAtMs in 1..nowMs }
        }

    fun syncDerivedTriggers(
        resumeSnapshots: List<SessionResumeSnapshot>,
        assistantSnapshot: AssistantOsSnapshot,
        externalSignals: List<AssistantExternalSignal> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<AssistantTrigger> =
        synchronized(lock) {
            val derived =
                buildList {
                    resumeSnapshots.forEachIndexed { index, snapshot ->
                        if (!snapshot.resumable || snapshot.sessionId.isBlank()) {
                            return@forEachIndexed
                        }
                        when {
                            snapshot.safety.awaitingConfirmation ->
                                add(
                                    AssistantTrigger(
                                        id = "trigger:${snapshot.sessionId}:pending_safety",
                                        type = AssistantTriggerType.REVIEW_PENDING_SAFETY,
                                        sessionId = snapshot.sessionId,
                                        title = "跟进高风险确认",
                                        summary = snapshot.safety.pendingConfirmation?.summary.orEmpty().ifBlank { snapshot.task },
                                        nextFireAtMs = nowMs + (index * 15_000L),
                                        metadata = mapOf("status" to snapshot.status),
                                    ),
                                )

                            snapshot.externalWaitState != null || snapshot.paused ->
                                add(
                                    AssistantTrigger(
                                        id = "trigger:${snapshot.sessionId}:resume_follow_up",
                                        type = AssistantTriggerType.RESUME_SESSION,
                                        sessionId = snapshot.sessionId,
                                        title = "恢复挂起任务",
                                        summary =
                                            snapshot.externalWaitState?.reason
                                                ?: snapshot.takeoverSnapshot?.latestTakeoverReason
                                                ?: snapshot.task,
                                        nextFireAtMs = nowMs + (index * 15_000L),
                                        metadata = mapOf("status" to snapshot.status),
                                    ),
                                )

                            snapshot.running || snapshot.planning ->
                                add(
                                    AssistantTrigger(
                                        id = "trigger:${snapshot.sessionId}:waiting_follow_up",
                                        type = AssistantTriggerType.FOLLOW_UP_WAITING_EXTERNAL,
                                        sessionId = snapshot.sessionId,
                                        title = "检查长任务进度",
                                        summary = snapshot.task,
                                        nextFireAtMs = nowMs + 10 * 60_000L + (index * 15_000L),
                                        metadata = mapOf("status" to snapshot.status),
                                    ),
                                )
                        }
                    }
                    externalSignals.forEachIndexed { index, signal ->
                        if (!signal.enabled) {
                            return@forEachIndexed
                        }
                        add(
                            AssistantTrigger(
                                id = "trigger:external:${signal.id}",
                                type = AssistantTriggerType.EXTERNAL_SIGNAL,
                                sessionId = signal.sessionId,
                                title = signal.title.ifBlank { "处理外部信号" },
                                summary = signal.summary.ifBlank { signal.task.ifBlank { signal.query } },
                                nextFireAtMs = signal.fireAtMs.coerceAtLeast(nowMs) + (index * 5_000L),
                                metadata =
                                    signal.payload + mapOf(
                                        "status" to signal.capability.name.lowercase(),
                                        "signal_type" to signal.type.name.lowercase(),
                                        "source" to signal.source,
                                    ),
                            ),
                        )
                    }
                }
            val preserved =
                readUnlocked().filter { existing ->
                    existing.id !in derived.map { it.id }.toSet() &&
                        existing.enabled &&
                        assistantSnapshot.activeSession.sessionId.isNotBlank() &&
                        existing.sessionId == assistantSnapshot.activeSession.sessionId
                }
            val merged = (derived + preserved).sortedBy { it.nextFireAtMs }
            writeUnlocked(merged)
            merged
        }

    fun markTriggered(
        triggerId: String,
        deferByMs: Long = 30 * 60_000L,
    ) {
        if (triggerId.isBlank()) return
        synchronized(lock) {
            val updated =
                readUnlocked().map { trigger ->
                    if (trigger.id == triggerId) {
                        trigger.copy(nextFireAtMs = System.currentTimeMillis() + deferByMs)
                    } else {
                        trigger
                    }
                }
            writeUnlocked(updated)
        }
    }

    private fun readUnlocked(): List<AssistantTrigger> {
        val file = triggerFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            jsonToTriggers(JSONObject(file.readText()))
        }.getOrElse {
            emptyList()
        }
    }

    private fun writeUnlocked(
        triggers: List<AssistantTrigger>,
    ) {
        val file = triggerFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "triggers",
                    JSONArray().apply {
                        triggers.forEach { trigger ->
                            put(
                                JSONObject().apply {
                                    put("id", trigger.id)
                                    put("type", trigger.type.name)
                                    put("session_id", trigger.sessionId)
                                    put("title", trigger.title)
                                    put("summary", trigger.summary)
                                    put("next_fire_at_ms", trigger.nextFireAtMs)
                                    put("enabled", trigger.enabled)
                                    put(
                                        "metadata",
                                        JSONObject().apply {
                                            trigger.metadata.forEach { (key, value) ->
                                                put(key, value)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun jsonToTriggers(
        json: JSONObject,
    ): List<AssistantTrigger> =
        buildList {
            val array = json.optJSONArray("triggers") ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type =
                    runCatching {
                        AssistantTriggerType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue
                add(
                    AssistantTrigger(
                        id = item.optString("id"),
                        type = type,
                        sessionId = item.optString("session_id"),
                        title = item.optString("title"),
                        summary = item.optString("summary"),
                        nextFireAtMs = item.optLong("next_fire_at_ms"),
                        enabled = item.optBoolean("enabled", true),
                        metadata = item.optJSONObject("metadata").toStringMap(),
                    ),
                )
            }
        }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }

    private fun triggerFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, TRIGGER_DIR), TRIGGER_FILE)
    }
}
