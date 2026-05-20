package com.lmx.xiaoxuanagent.runtime

import java.util.UUID

@JvmInline
value class SessionIdRef(
    val raw: String,
) {
    val isBlank: Boolean
        get() = raw.isBlank()

    override fun toString(): String = raw
}

@JvmInline
value class AgentIdRef(
    val raw: String,
) {
    val isBlank: Boolean
        get() = raw.isBlank()

    override fun toString(): String = raw
}

internal fun asSessionIdRef(
    raw: String,
): SessionIdRef = SessionIdRef(raw.trim())

internal fun asAgentIdRef(
    raw: String,
): AgentIdRef = AgentIdRef(raw.trim())

internal fun createAgentIdRef(
    label: String = "",
): AgentIdRef {
    val normalized =
        label.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(16)
    val suffix = UUID.randomUUID().toString().replace("-", "").takeLast(16)
    val prefix = if (normalized.isBlank()) "a" else "a${normalized}-"
    return AgentIdRef(prefix + suffix)
}
