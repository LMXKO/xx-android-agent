package com.lmx.xiaoxuanagent.agent

import java.net.URI

internal data class PlatformWebPolicyDecision(
    val behavior: String = "allow",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val normalizedQuery: String = "",
    val normalizedUrl: String = "",
    val domain: String = "",
)

internal object PlatformWebPolicySupport {
    private val blockedHosts = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
    private val sensitiveKeywords = listOf("密码", "验证码", "私钥", "secret", "token")

    fun reviewQuery(
        query: String,
    ): PlatformWebPolicyDecision {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) {
            return PlatformWebPolicyDecision(
                behavior = "deny",
                summary = "web_query_blank",
                detailLines = listOf("web_policy=query_blank"),
            )
        }
        if (sensitiveKeywords.any { normalized.contains(it, ignoreCase = true) }) {
            return PlatformWebPolicyDecision(
                behavior = "ask",
                summary = "web_query_sensitive",
                detailLines = listOf("web_policy=query_sensitive"),
                normalizedQuery = normalized,
            )
        }
        return PlatformWebPolicyDecision(
            normalizedQuery = normalized,
            detailLines = listOf("web_policy=query_ok"),
        )
    }

    fun reviewUrl(
        url: String,
    ): PlatformWebPolicyDecision {
        val normalized = url.trim()
        val uri =
            runCatching { URI(normalized) }.getOrNull()
                ?: return PlatformWebPolicyDecision(
                    behavior = "deny",
                    summary = "web_url_invalid",
                    detailLines = listOf("web_policy=url_invalid"),
                )
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme !in setOf("http", "https")) {
            return PlatformWebPolicyDecision(
                behavior = "deny",
                summary = "web_url_scheme_blocked",
                detailLines = listOf("web_policy=scheme_blocked", "scheme=$scheme"),
            )
        }
        val host = uri.host.orEmpty().lowercase()
        if (host.isBlank() || host in blockedHosts || host.startsWith("192.168.") || host.startsWith("10.")) {
            return PlatformWebPolicyDecision(
                behavior = "deny",
                summary = "web_url_private_host",
                detailLines = listOf("web_policy=private_host", "host=$host"),
            )
        }
        val behavior =
            if (host.endsWith(".local") || host.endsWith(".internal")) {
                "ask"
            } else {
                "allow"
            }
        return PlatformWebPolicyDecision(
            behavior = behavior,
            summary = if (behavior == "ask") "web_url_untrusted_host" else "",
            detailLines = listOf("web_policy=url_ok", "domain=$host"),
            normalizedUrl = normalized,
            domain = host,
        )
    }
}
