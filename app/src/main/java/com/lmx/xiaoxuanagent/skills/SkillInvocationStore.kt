package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SkillInvocationStore {
    private const val DIR = "skills"
    private const val FILE = "skill_invocations.json"
    private const val MAX_ITEMS = 60
    private val lock = Any()

    fun record(
        skillId: String,
        task: String,
        profileId: String,
        parameters: List<String>,
        phase: String,
    ) {
        if (AppRuntimeContext.get() == null) return
        synchronized(lock) {
            val json = readJson() ?: JSONObject()
            val items = json.optJSONArray("items") ?: JSONArray().also { json.put("items", it) }
            items.put(
                JSONObject().apply {
                    put("skill_id", skillId)
                    put("task", task.take(80))
                    put("profile_id", profileId)
                    put("phase", phase)
                    put("parameters", JSONArray(parameters))
                    put("timestamp", System.currentTimeMillis())
                },
            )
            while (items.length() > MAX_ITEMS) {
                items.remove(0)
            }
            file()?.writeText(json.toString(2))
        }
    }

    fun recentSummary(limit: Int = 3): List<String> {
        if (AppRuntimeContext.get() == null) return emptyList()
        val json = readJson() ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<String>()
        for (index in maxOf(0, items.length() - limit) until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val skillId = item.optString("skill_id")
            val phase = item.optString("phase")
            val params = item.optJSONArray("parameters")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) add(array.optString(i))
                }.joinToString("/")
            }.orEmpty()
            result += "$skillId@$phase ${params.ifBlank { "-" }}"
        }
        return result.reversed()
    }

    private fun readJson(): JSONObject? {
        val file = file() ?: return null
        if (!file.exists()) {
            return JSONObject().apply { put("items", JSONArray()) }
        }
        return JSONObject(file.readText())
    }

    private fun file(): File? {
        val context = AppRuntimeContext.get() ?: return null
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE)
    }
}
