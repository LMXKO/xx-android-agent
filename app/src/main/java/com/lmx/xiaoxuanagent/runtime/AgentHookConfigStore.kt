package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class AgentHookConfigSnapshot(
    val disabledHookIds: Set<String> = emptySet(),
    val stageNotes: Map<String, List<String>> = emptyMap(),
)

object AgentHookConfigStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "agent_hook_config.json"
    private val lock = Any()
    private var cached: AgentHookConfigSnapshot? = null

    fun read(): AgentHookConfigSnapshot =
        synchronized(lock) {
            cached ?: loadFromDisk().also { cached = it }
        }

    fun isEnabled(hookId: String): Boolean = hookId !in read().disabledHookIds

    fun stageNotes(stage: AgentHookStage): List<String> = read().stageNotes[stage.name.lowercase()].orEmpty()

    private fun loadFromDisk(): AgentHookConfigSnapshot {
        val context = AppRuntimeContext.get() ?: return AgentHookConfigSnapshot()
        val file = File(File(context.filesDir, STORE_DIR), STORE_FILE)
        if (!file.exists()) return AgentHookConfigSnapshot()
        return runCatching {
            val json = JSONObject(file.readText())
            AgentHookConfigSnapshot(
                disabledHookIds =
                    json.optJSONArray("disabled_hook_ids")?.toStringSet().orEmpty(),
                stageNotes =
                    json.optJSONObject("stage_notes")?.let { objectJson ->
                        buildMap {
                            objectJson.keys().forEach { key ->
                                put(key, objectJson.optJSONArray(key)?.toStringList().orEmpty())
                            }
                        }
                    }.orEmpty(),
            )
        }.getOrDefault(AgentHookConfigSnapshot())
    }

    private fun JSONArray.toStringSet(): Set<String> =
        buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

    private fun JSONArray.toStringList(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
}
