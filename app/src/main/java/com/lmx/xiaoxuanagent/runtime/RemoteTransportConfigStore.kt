package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONObject

data class RemoteTransportConfig(
    val fileDropEnabled: Boolean = true,
    val fileDropInboxDir: String = "remote_transport/inbox",
    val fileDropOutboxDir: String = "remote_transport/outbox",
    val httpEnabled: Boolean = false,
    val httpBaseUrl: String = "",
    val httpAuthToken: String = "",
    val httpInboundPath: String = "/inbound",
    val httpOutboundPath: String = "/outbound",
)

object RemoteTransportConfigStore {
    private const val CONFIG_DIR = "runtime"
    private const val CONFIG_FILE = "remote_transport_config.json"
    private val lock = Any()
    @Volatile
    private var hydrated = false
    private var config = RemoteTransportConfig()

    fun read(): RemoteTransportConfig =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            config
        }

    fun update(
        reducer: (RemoteTransportConfig) -> RemoteTransportConfig,
    ): RemoteTransportConfig =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            config = reducer(config)
            persistUnlocked()
            config
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = configFile() ?: return
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            config =
                RemoteTransportConfig(
                    fileDropEnabled = json.optBoolean("file_drop_enabled", true),
                    fileDropInboxDir = json.optString("file_drop_inbox_dir", "remote_transport/inbox"),
                    fileDropOutboxDir = json.optString("file_drop_outbox_dir", "remote_transport/outbox"),
                    httpEnabled = json.optBoolean("http_enabled", false),
                    httpBaseUrl = json.optString("http_base_url"),
                    httpAuthToken = json.optString("http_auth_token"),
                    httpInboundPath = json.optString("http_inbound_path", "/inbound"),
                    httpOutboundPath = json.optString("http_outbound_path", "/outbound"),
                )
        }
    }

    private fun persistUnlocked() {
        val file = configFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("file_drop_enabled", config.fileDropEnabled)
                put("file_drop_inbox_dir", config.fileDropInboxDir)
                put("file_drop_outbox_dir", config.fileDropOutboxDir)
                put("http_enabled", config.httpEnabled)
                put("http_base_url", config.httpBaseUrl)
                put("http_auth_token", config.httpAuthToken)
                put("http_inbound_path", config.httpInboundPath)
                put("http_outbound_path", config.httpOutboundPath)
            }.toString(2),
        )
    }

    private fun configFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, CONFIG_DIR), CONFIG_FILE)
    }
}
