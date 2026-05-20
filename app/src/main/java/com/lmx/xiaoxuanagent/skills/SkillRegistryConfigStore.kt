package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONObject

data class SkillRegistryConfigSnapshot(
    val disabledSkillIds: Set<String> = emptySet(),
    val pinnedSkillIds: Set<String> = emptySet(),
    val priorityBoosts: Map<String, Int> = emptyMap(),
)

object SkillRegistryConfigStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "skill_registry_config.json"

    fun read(): SkillRegistryConfigSnapshot {
        val context = AppRuntimeContext.get() ?: return SkillRegistryConfigSnapshot()
        val file = File(File(context.filesDir, STORE_DIR), STORE_FILE)
        if (!file.exists()) return SkillRegistryConfigSnapshot()
        return runCatching {
            val json = JSONObject(file.readText())
            SkillRegistryConfigSnapshot(
                disabledSkillIds = json.optJSONArray("disabled_skill_ids").toStringSet(),
                pinnedSkillIds = json.optJSONArray("pinned_skill_ids").toStringSet(),
                priorityBoosts =
                    json.optJSONObject("priority_boosts")?.let { boosts ->
                        buildMap {
                            boosts.keys().forEach { key -> put(key, boosts.optInt(key, 0)) }
                        }
                    }.orEmpty(),
            )
        }.getOrDefault(SkillRegistryConfigSnapshot())
    }

    private fun org.json.JSONArray?.toStringSet(): Set<String> =
        buildSet {
            if (this@toStringSet == null) return@buildSet
            for (index in 0 until this@toStringSet.length()) {
                this@toStringSet.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
}
