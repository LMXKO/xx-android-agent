package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class RemoteTransportDirection {
    INBOUND,
    OUTBOUND,
}

enum class RemoteTransportEnvelopeType {
    CAPABILITY_REQUEST,
    PLATFORM_EVENT,
    VIEWER_BUNDLE,
}

enum class RemoteTransportEnvelopeStatus {
    PENDING,
    CONSUMED,
    DELIVERED,
    FAILED,
}

data class RemoteTransportEnvelope(
    val envelopeId: String,
    val direction: RemoteTransportDirection,
    val type: RemoteTransportEnvelopeType,
    val sessionId: String = "",
    val summary: String = "",
    val capability: SessionCapabilityKey? = null,
    val task: String = "",
    val query: String = "",
    val entrySource: String = "",
    val userCorrection: String = "",
    val channel: String = "",
    val body: String = "",
    val payload: Map<String, String> = emptyMap(),
    val status: RemoteTransportEnvelopeStatus = RemoteTransportEnvelopeStatus.PENDING,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

data class RemoteTransportSnapshot(
    val pendingInbound: List<RemoteTransportEnvelope> = emptyList(),
    val pendingOutbound: List<RemoteTransportEnvelope> = emptyList(),
    val recentInbound: List<RemoteTransportEnvelope> = emptyList(),
    val recentOutbound: List<RemoteTransportEnvelope> = emptyList(),
)

object RemoteTransportStore {
    private const val TRANSPORT_DIR = "runtime"
    private const val TRANSPORT_FILE = "remote_transport_envelopes.json"
    private const val MAX_ENVELOPES = 200
    private const val MAX_BODY_CHARS = 96_000
    private const val MAX_PAYLOAD_VALUE_CHARS = 4_000
    private val lock = Any()
    private val envelopes = ArrayDeque<RemoteTransportEnvelope>()
    private var hydrated = false

    fun enqueueInboundCapabilityRequest(
        capability: SessionCapabilityKey,
        sessionId: String = "",
        task: String = "",
        query: String = "",
        entrySource: String = "remote_transport",
        userCorrection: String = "",
        channel: String = "transport_inbox",
        payload: Map<String, String> = emptyMap(),
        summary: String = "",
    ): RemoteTransportEnvelope {
        val now = System.currentTimeMillis()
        val envelope =
            RemoteTransportEnvelope(
                envelopeId = "transport_${now}_${capability.name.lowercase()}",
                direction = RemoteTransportDirection.INBOUND,
                type = RemoteTransportEnvelopeType.CAPABILITY_REQUEST,
                sessionId = sessionId,
                summary = summary.ifBlank { task.ifBlank { query.ifBlank { capability.name.lowercase() } } },
                capability = capability,
                task = task.trim(),
                query = query.trim(),
                entrySource = entrySource,
                userCorrection = userCorrection,
                channel = channel,
                payload = payload,
                createdAtMs = now,
                updatedAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            envelopes.addFirst(envelope)
            trimUnlocked()
            persistUnlocked()
        }
        return envelope
    }

    fun publishPlatformEvent(
        sessionId: String = "",
        summary: String,
        channel: String = "transport_outbox",
        payload: Map<String, String> = emptyMap(),
    ): RemoteTransportEnvelope =
        appendOutbound(
            type = RemoteTransportEnvelopeType.PLATFORM_EVENT,
            sessionId = sessionId,
            summary = summary,
            channel = channel,
            payload = payload,
        )

    fun publishViewerBundle(
        sessionId: String,
        body: String,
        summary: String,
        channel: String = "remote_viewer",
        payload: Map<String, String> = emptyMap(),
    ): RemoteTransportEnvelope =
        appendOutbound(
            type = RemoteTransportEnvelopeType.VIEWER_BUNDLE,
            sessionId = sessionId,
            summary = summary,
            channel = channel,
            body = body,
            payload = payload,
        )

    fun readPendingInbound(
        limit: Int = 8,
    ): List<RemoteTransportEnvelope> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            envelopes
                .filter {
                    it.direction == RemoteTransportDirection.INBOUND &&
                        it.status == RemoteTransportEnvelopeStatus.PENDING
                }
                .sortedBy { it.createdAtMs }
                .take(limit)
        }

    fun readPendingOutbound(
        limit: Int = 8,
    ): List<RemoteTransportEnvelope> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            envelopes
                .filter {
                    it.direction == RemoteTransportDirection.OUTBOUND &&
                        it.status == RemoteTransportEnvelopeStatus.PENDING
                }
                .sortedBy { it.createdAtMs }
                .take(limit)
        }

    fun readSnapshot(
        inboundLimit: Int = 8,
        outboundLimit: Int = 8,
    ): RemoteTransportSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            RemoteTransportSnapshot(
                pendingInbound =
                    envelopes
                        .filter {
                            it.direction == RemoteTransportDirection.INBOUND &&
                                it.status == RemoteTransportEnvelopeStatus.PENDING
                        }
                        .sortedBy { it.createdAtMs }
                        .take(inboundLimit),
                pendingOutbound =
                    envelopes
                        .filter {
                            it.direction == RemoteTransportDirection.OUTBOUND &&
                                it.status == RemoteTransportEnvelopeStatus.PENDING
                        }
                        .sortedByDescending { it.updatedAtMs }
                        .take(outboundLimit),
                recentInbound =
                    envelopes
                        .filter { it.direction == RemoteTransportDirection.INBOUND }
                        .take(inboundLimit)
                        .toList(),
                recentOutbound =
                    envelopes
                        .filter { it.direction == RemoteTransportDirection.OUTBOUND }
                        .take(outboundLimit)
                        .toList(),
            )
        }

    fun markConsumed(
        envelopeId: String,
        summary: String = "",
    ) {
        mutate(envelopeId) { current ->
            current.copy(
                status = RemoteTransportEnvelopeStatus.CONSUMED,
                summary = summary.ifBlank { current.summary },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun markDelivered(
        envelopeId: String,
        summary: String = "",
    ) {
        mutate(envelopeId) { current ->
            current.copy(
                status = RemoteTransportEnvelopeStatus.DELIVERED,
                summary = summary.ifBlank { current.summary },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun markFailed(
        envelopeId: String,
        summary: String,
    ) {
        mutate(envelopeId) { current ->
            current.copy(
                status = RemoteTransportEnvelopeStatus.FAILED,
                summary = summary.ifBlank { current.summary },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun importSnapshot(
        snapshot: RemoteTransportSnapshot,
    ) {
        val imported = snapshot.pendingInbound + snapshot.pendingOutbound + snapshot.recentInbound + snapshot.recentOutbound
        if (imported.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (envelopes + imported)
                    .distinctBy { it.envelopeId }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_ENVELOPES)
            envelopes.clear()
            merged.forEach(envelopes::addLast)
            persistUnlocked()
        }
    }

    fun importInboundEnvelopes(
        importedEnvelopes: List<RemoteTransportEnvelope>,
    ) {
        if (importedEnvelopes.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (envelopes +
                    importedEnvelopes.map { envelope ->
                        envelope.copy(
                            direction = RemoteTransportDirection.INBOUND,
                            status = RemoteTransportEnvelopeStatus.PENDING,
                        )
                    })
                    .distinctBy { it.envelopeId }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_ENVELOPES)
            envelopes.clear()
            merged.forEach(envelopes::addLast)
            persistUnlocked()
        }
    }

    private fun appendOutbound(
        type: RemoteTransportEnvelopeType,
        sessionId: String,
        summary: String,
        channel: String,
        body: String = "",
        payload: Map<String, String> = emptyMap(),
    ): RemoteTransportEnvelope {
        val now = System.currentTimeMillis()
        val safeBody = body.safeBody()
        val safePayload = payload.safePayload()
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val pendingIndex =
                envelopes.indexOfFirst { current ->
                    current.direction == RemoteTransportDirection.OUTBOUND &&
                        current.status == RemoteTransportEnvelopeStatus.PENDING &&
                        current.type == type &&
                        current.sessionId == sessionId &&
                        current.channel == channel
                }
            val envelope =
                if (pendingIndex >= 0) {
                    val current = envelopes.elementAt(pendingIndex)
                    current.copy(
                        summary = summary.safeSummary(body, safeBody),
                        body = safeBody,
                        payload = safePayload,
                        updatedAtMs = now,
                    ).also {
                        envelopes.removeAt(pendingIndex)
                        envelopes.add(pendingIndex, it)
                    }
                } else {
                    RemoteTransportEnvelope(
                        envelopeId = "transport_${now}_${type.name.lowercase()}",
                        direction = RemoteTransportDirection.OUTBOUND,
                        type = type,
                        sessionId = sessionId,
                        summary = summary.safeSummary(body, safeBody),
                        channel = channel,
                        body = safeBody,
                        payload = safePayload,
                        status = RemoteTransportEnvelopeStatus.PENDING,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ).also(envelopes::addFirst)
                }
            trimUnlocked()
            persistUnlocked()
            return envelope
        }
    }

    private fun mutate(
        envelopeId: String,
        reducer: (RemoteTransportEnvelope) -> RemoteTransportEnvelope,
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = envelopes.indexOfFirst { it.envelopeId == envelopeId }
            if (index < 0) return
            val next = reducer(envelopes.elementAt(index))
            envelopes.removeAt(index)
            envelopes.add(index, next)
            persistUnlocked()
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = transportFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("envelopes") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                envelopes.addLast(json.toEnvelope())
            }
        }
    }

    private fun persistUnlocked() {
        val file = transportFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "envelopes",
                    JSONArray().apply {
                        envelopes.forEach { envelope ->
                            put(envelope.toJson())
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (envelopes.size > MAX_ENVELOPES) {
            envelopes.removeLast()
        }
    }

    private fun transportFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, TRANSPORT_DIR), TRANSPORT_FILE)
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
            put("body", body.safeBody())
            put("status", status.name)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put(
                "payload",
                JSONObject().apply {
                    payload.safePayload().forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun JSONObject.toEnvelope(): RemoteTransportEnvelope =
        RemoteTransportEnvelope(
            envelopeId = optString("envelope_id"),
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
            body = optString("body").safeBody(),
            payload = optJSONObject("payload").toStringMap().safePayload(),
            status =
                runCatching { RemoteTransportEnvelopeStatus.valueOf(optString("status")) }
                    .getOrDefault(RemoteTransportEnvelopeStatus.PENDING),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }

    private fun String.safeBody(): String =
        if (length <= MAX_BODY_CHARS) {
            this
        } else {
            take(MAX_BODY_CHARS) +
                "\n...[remote_transport_body_truncated original_chars=$length limit=$MAX_BODY_CHARS]"
        }

    private fun Map<String, String>.safePayload(): Map<String, String> =
        mapValues { (_, value) ->
            if (value.length <= MAX_PAYLOAD_VALUE_CHARS) {
                value
            } else {
                value.take(MAX_PAYLOAD_VALUE_CHARS) +
                    "...[truncated original_chars=${value.length} limit=$MAX_PAYLOAD_VALUE_CHARS]"
            }
        }

    private fun String.safeSummary(
        originalBody: String,
        safeBody: String,
    ): String =
        if (originalBody.length == safeBody.length) {
            this
        } else {
            "$this | body_truncated=${originalBody.length}->${safeBody.length}"
        }.take(512)
}
