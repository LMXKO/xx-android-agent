package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class AssistantTriggerRoutePolicySnapshot(
    val disabledPolicyIds: Set<String> = emptySet(),
    val signalTypeCapabilityOverrides: Map<String, SessionCapabilityKey> = emptyMap(),
    val packageCapabilityOverrides: Map<String, SessionCapabilityKey> = emptyMap(),
)

object AssistantTriggerRoutePolicyStore {
    private const val POLICY_DIR = "assistant_os"
    private const val POLICY_FILE = "trigger_router_policy.json"
    private val lock = Any()
    @Volatile
    private var hydrated = false
    private var snapshot = AssistantTriggerRoutePolicySnapshot()

    fun read(): AssistantTriggerRoutePolicySnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = policyFile() ?: return
        if (!file.exists()) return
        runCatching {
            snapshot = JSONObject(file.readText()).toSnapshot()
        }
    }

    private fun policyFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, POLICY_DIR), POLICY_FILE)
    }

    private fun JSONObject.toSnapshot(): AssistantTriggerRoutePolicySnapshot =
        AssistantTriggerRoutePolicySnapshot(
            disabledPolicyIds = optJSONArray("disabled_policy_ids").toStringSet(),
            signalTypeCapabilityOverrides = optJSONArray("signal_type_capability_overrides").toCapabilityMap("signal_type"),
            packageCapabilityOverrides = optJSONArray("package_capability_overrides").toCapabilityMap("package_name"),
        )

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toCapabilityMap(
        keyField: String,
    ): Map<String, SessionCapabilityKey> {
        if (this == null) return emptyMap()
        return buildMap {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val key = json.optString(keyField)
                val capability =
                    runCatching { SessionCapabilityKey.valueOf(json.optString("capability")) }.getOrNull()
                        ?: continue
                if (key.isNotBlank()) put(key, capability)
            }
        }
    }
}
