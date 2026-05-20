package com.lmx.xiaoxuanagent.runtime

data class ConnectedAppLifecycleSnapshot(
    val appId: String,
    val connected: Boolean = true,
    val confirmationMode: String = "final_handoff",
)

object ConnectedAppLifecycleStore {
    private const val PREFS = "connected_app_lifecycle"
    private const val KEY_CONNECTED_PREFIX = "connected:"
    private const val KEY_CONFIRM_PREFIX = "confirm:"

    fun snapshot(
        appId: String,
    ): ConnectedAppLifecycleSnapshot {
        val prefs = prefs() ?: return ConnectedAppLifecycleSnapshot(appId = appId)
        return ConnectedAppLifecycleSnapshot(
            appId = appId,
            connected = prefs.getBoolean("$KEY_CONNECTED_PREFIX$appId", true),
            confirmationMode = prefs.getString("$KEY_CONFIRM_PREFIX$appId", "final_handoff").orEmpty().ifBlank { "final_handoff" },
        )
    }

    fun update(
        appId: String,
        connected: Boolean? = null,
        confirmationMode: String? = null,
    ): ConnectedAppLifecycleSnapshot {
        val current = snapshot(appId)
        val next =
            current.copy(
                connected = connected ?: current.connected,
                confirmationMode = confirmationMode?.ifBlank { current.confirmationMode } ?: current.confirmationMode,
            )
        prefs()?.edit()?.apply {
            putBoolean("$KEY_CONNECTED_PREFIX$appId", next.connected)
            putString("$KEY_CONFIRM_PREFIX$appId", next.confirmationMode)
        }?.apply()
        return next
    }

    private fun prefs() =
        AppRuntimeContext.get()?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
}
