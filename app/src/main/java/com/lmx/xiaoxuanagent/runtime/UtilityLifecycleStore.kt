package com.lmx.xiaoxuanagent.runtime

data class UtilityLifecycleSnapshot(
    val toolName: String,
    val enabled: Boolean = true,
)

object UtilityLifecycleStore {
    private const val PREFS = "utility_lifecycle"
    private const val KEY_ENABLED_PREFIX = "enabled:"
    private val inMemoryEnabled = LinkedHashMap<String, Boolean>()

    fun snapshot(
        toolName: String,
    ): UtilityLifecycleSnapshot {
        val prefs = prefs()
            ?: return UtilityLifecycleSnapshot(
                toolName = toolName,
                enabled = inMemoryEnabled[toolName] ?: true,
            )
        return UtilityLifecycleSnapshot(
            toolName = toolName,
            enabled = prefs.getBoolean("$KEY_ENABLED_PREFIX$toolName", true),
        )
    }

    fun update(
        toolName: String,
        enabled: Boolean,
    ): UtilityLifecycleSnapshot {
        val next = UtilityLifecycleSnapshot(toolName = toolName, enabled = enabled)
        val prefs = prefs()
        if (prefs == null) {
            inMemoryEnabled[toolName] = enabled
        } else {
            prefs.edit().putBoolean("$KEY_ENABLED_PREFIX$toolName", enabled).apply()
        }
        return next
    }

    private fun prefs() =
        try {
            AppRuntimeContext.get()?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        } catch (_: RuntimeException) {
            null
        }
}
