package com.lmx.xiaoxuanagent.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal object RemoteTransportSyncEngine {
    fun sync(
        reason: String,
        outboundLimit: Int = 12,
        inboundLimit: Int = 12,
    ) {
        val providers = resolveProviders()
        if (providers.isEmpty()) return

        val pendingOutbound = RemoteTransportStore.readPendingOutbound(limit = outboundLimit)
        pendingOutbound.forEach { envelope ->
            val delivered =
                providers.any { provider ->
                    runCatching { provider.deliver(envelope) }
                        .onFailure { error ->
                            PlatformTraceStore.record(
                                category = "remote_transport_deliver_failed",
                                sessionId = envelope.sessionId,
                                summary = "${provider.providerId}:${error.message ?: error.javaClass.simpleName}",
                                metadata = mapOf("envelope_id" to envelope.envelopeId, "reason" to reason),
                            )
                        }.getOrDefault(false)
                }
            if (delivered) {
                RemoteTransportStore.markDelivered(
                    envelopeId = envelope.envelopeId,
                    summary = envelope.summary.ifBlank { "remote transport delivered" },
                )
                PlatformTraceStore.record(
                    category = "remote_transport_delivered",
                    sessionId = envelope.sessionId,
                    summary = envelope.type.name.lowercase(),
                    metadata = mapOf("envelope_id" to envelope.envelopeId, "reason" to reason),
                )
            }
        }

        val imported =
            providers.flatMap { provider ->
                runCatching { provider.pullInbound(limit = inboundLimit) }
                    .onFailure { error ->
                        PlatformTraceStore.record(
                            category = "remote_transport_pull_failed",
                            summary = "${provider.providerId}:${error.message ?: error.javaClass.simpleName}",
                            metadata = mapOf("reason" to reason),
                        )
                    }.getOrDefault(emptyList())
            }
        if (imported.isNotEmpty()) {
            RemoteTransportStore.importInboundEnvelopes(imported)
            PlatformTraceStore.record(
                category = "remote_transport_pull",
                summary = "imported=${imported.size}",
                metadata = mapOf("reason" to reason),
            )
        }
    }

    private fun resolveProviders(): List<RemoteTransportProvider> {
        val config = RemoteTransportConfigStore.read()
        return buildList {
            if (config.fileDropEnabled) {
                add(FileDropRemoteTransportProvider(config))
            }
            if (config.httpEnabled && config.httpBaseUrl.isNotBlank()) {
                add(HttpRemoteTransportProvider(config))
            }
        }
    }

    private interface RemoteTransportProvider {
        val providerId: String

        fun deliver(
            envelope: RemoteTransportEnvelope,
        ): Boolean

        fun pullInbound(
            limit: Int,
        ): List<RemoteTransportEnvelope>
    }

    private class FileDropRemoteTransportProvider(
        private val config: RemoteTransportConfig,
    ) : RemoteTransportProvider {
        override val providerId: String = "file_drop"

        override fun deliver(
            envelope: RemoteTransportEnvelope,
        ): Boolean {
            val outboxDir = resolveDir(config.fileDropOutboxDir) ?: return false
            outboxDir.mkdirs()
            File(outboxDir, "${envelope.envelopeId}.json").writeText(envelope.toJson().toString(2))
            return true
        }

        override fun pullInbound(
            limit: Int,
        ): List<RemoteTransportEnvelope> {
            val inboxDir = resolveDir(config.fileDropInboxDir) ?: return emptyList()
            if (!inboxDir.exists()) return emptyList()
            return inboxDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                .sortedBy { it.lastModified() }
                .take(limit)
                .mapNotNull { file ->
                    runCatching {
                        val parsed = JSONObject(file.readText()).toRemoteTransportEnvelope()
                        file.delete()
                        parsed
                    }.getOrNull()
                }
        }

        private fun resolveDir(
            path: String,
        ): File? {
            val context = AppRuntimeContext.get() ?: return null
            return File(context.filesDir, path)
        }
    }

    private class HttpRemoteTransportProvider(
        private val config: RemoteTransportConfig,
    ) : RemoteTransportProvider {
        override val providerId: String = "http_endpoint"

        override fun deliver(
            envelope: RemoteTransportEnvelope,
        ): Boolean {
            val targetUrl = buildUrl(config.httpBaseUrl, config.httpOutboundPath)
            val connection =
                (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    config.httpAuthToken.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
            connection.outputStream.use { output ->
                output.write(envelope.toJson().toString().toByteArray(Charsets.UTF_8))
            }
            return connection.responseCode in 200..299
        }

        override fun pullInbound(
            limit: Int,
        ): List<RemoteTransportEnvelope> {
            val targetUrl = buildUrl(config.httpBaseUrl, "${config.httpInboundPath}?limit=$limit")
            val connection =
                (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doInput = true
                    config.httpAuthToken.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
            if (connection.responseCode !in 200..299) {
                return emptyList()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val payload = body.takeIf { it.isNotBlank() } ?: return emptyList()
            return when {
                payload.trimStart().startsWith("[") ->
                    JSONArray(payload).toRemoteTransportEnvelopes()

                else -> {
                    val json = JSONObject(payload)
                    when {
                        json.has("envelopes") -> json.optJSONArray("envelopes").toRemoteTransportEnvelopes()
                        json.length() > 0 -> listOf(json.toRemoteTransportEnvelope())
                        else -> emptyList()
                    }
                }
            }
        }

        private fun buildUrl(
            baseUrl: String,
            path: String,
        ): String {
            val normalizedBase = baseUrl.trimEnd('/')
            val normalizedPath = path.takeIf { it.startsWith("/") } ?: "/$path"
            return normalizedBase + normalizedPath
        }
    }

    private fun RemoteTransportEnvelope.toJson(): JSONObject =
        JSONObject().apply {
            put("envelope_id", envelopeId)
            put("direction", direction.name)
            put("type", type.name)
            put("session_id", sessionId)
            put("summary", summary)
            put("capability", capability?.name.orEmpty())
            put("task", task)
            put("query", query)
            put("entry_source", entrySource)
            put("user_correction", userCorrection)
            put("channel", channel)
            put("body", body)
            put("status", status.name)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "payload",
                JSONObject().apply {
                    payload.forEach { (key, value) -> put(key, value) }
                },
            )
        }

    private fun JSONObject.toRemoteTransportEnvelope(): RemoteTransportEnvelope =
        RemoteTransportEnvelope(
            envelopeId = optString("envelope_id").ifBlank { "transport_${System.currentTimeMillis()}_remote" },
            direction =
                runCatching { RemoteTransportDirection.valueOf(optString("direction")) }
                    .getOrDefault(RemoteTransportDirection.INBOUND),
            type =
                runCatching { RemoteTransportEnvelopeType.valueOf(optString("type")) }
                    .getOrDefault(RemoteTransportEnvelopeType.CAPABILITY_REQUEST),
            sessionId = optString("session_id"),
            summary = optString("summary"),
            capability =
                optString("capability")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { SessionCapabilityKey.valueOf(it) }.getOrNull() },
            task = optString("task"),
            query = optString("query"),
            entrySource = optString("entry_source"),
            userCorrection = optString("user_correction"),
            channel = optString("channel"),
            body = optString("body"),
            payload = optJSONObject("payload").toStringMap(),
            status =
                runCatching { RemoteTransportEnvelopeStatus.valueOf(optString("status")) }
                    .getOrDefault(RemoteTransportEnvelopeStatus.PENDING),
            createdAtMs = optLong("created_at_ms", System.currentTimeMillis()),
            updatedAtMs = optLong("updated_at_ms", System.currentTimeMillis()),
        )

    private fun JSONArray?.toRemoteTransportEnvelopes(): List<RemoteTransportEnvelope> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(it.toRemoteTransportEnvelope()) }
            }
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
}
