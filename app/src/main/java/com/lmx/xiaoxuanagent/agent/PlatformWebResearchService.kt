package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal object PlatformWebResearchService {
    fun search(
        query: String,
        limit: Int = 5,
    ): List<String> {
        val policy = PlatformWebPolicySupport.reviewQuery(query)
        if (policy.behavior == "deny") {
            return policy.detailLines + listOf(policy.summary.ifBlank { "web_query_denied" })
        }
        val searchBaseUrl = BuildConfig.AGENT_WEB_SEARCH_BASE_URL.trim()
        if (searchBaseUrl.isBlank()) {
            return listOf("web_search_base_url_missing")
        }
        val endpoint =
            searchBaseUrl.trimEnd('/') + "?q=" + URLEncoder.encode(policy.normalizedQuery.ifBlank { query }, Charsets.UTF_8.name())
        val response = PlatformWebFetchSupport.fetch(endpoint).getOrElse { return listOf("search_error=${it.message.orEmpty()}") }
        val html = response.body
        val blockRegex =
            Regex(
                """<div[^>]*class="result[^"]*"[^>]*>(.*?)</div>\s*</div>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val hits =
            blockRegex.findAll(html)
                .mapNotNull { extractSearchHit(it.groupValues[1]) }
                .take(limit.coerceAtLeast(1))
                .toList()
        if (hits.isEmpty()) {
            return listOf(
                "search_source=duckduckgo_html",
                "query=${policy.normalizedQuery.ifBlank { query }}",
                "final_url=${response.finalUrl}",
                PlatformWebFetchSupport.cleanHtml(html).take(220),
            )
        }
        return buildList {
            add("search_source=duckduckgo_html")
            add("query=${policy.normalizedQuery.ifBlank { query }}")
            add("final_url=${response.finalUrl}")
            hits.forEachIndexed { index, hit ->
                add("hit_${index + 1}=${hit.domain} | ${hit.title.take(88)} | ${hit.url.take(120)}")
                hit.snippet.takeIf { it.isNotBlank() }?.let { add("snippet_${index + 1}=${it.take(160)}") }
            }
        }
    }

    fun fetch(
        url: String,
    ): List<String> {
        val policy = PlatformWebPolicySupport.reviewUrl(url)
        if (policy.behavior == "deny") {
            return policy.detailLines + listOf(policy.summary.ifBlank { "web_url_denied" })
        }
        val response = PlatformWebFetchSupport.fetch(policy.normalizedUrl.ifBlank { url }).getOrElse { return listOf("fetch_error=${it.message.orEmpty()}") }
        val sections = PlatformWebFetchSupport.extractReadableSections(response.body)
        return buildList {
            add("url=${policy.normalizedUrl.ifBlank { url }}")
            add("final_url=${response.finalUrl}")
            add("domain=${policy.domain.ifBlank { response.domain }}")
            add("status=${response.statusCode}")
            add("content_type=${response.contentType.ifBlank { "-" }}")
            addAll(sections)
            if (response.redirectChain.size > 1) {
                add("redirect_chain=${response.redirectChain.joinToString(" -> ").take(220)}")
            }
        }
    }

    private fun extractSearchHit(
        block: String,
    ): SearchHit? {
        val anchor =
            Regex(
                """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(block) ?: return null
        val snippet =
            Regex(
                """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>|<div[^>]*class="result__snippet"[^>]*>(.*?)</div>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(block)
        val url = decodeSearchResultUrl(PlatformWebFetchSupport.cleanHtml(anchor.groupValues[1]))
        val domain = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return SearchHit(
            title = PlatformWebFetchSupport.cleanHtml(anchor.groupValues[2]),
            url = url,
            domain = domain.ifBlank { "unknown" },
            snippet = PlatformWebFetchSupport.cleanHtml(snippet?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty()),
        )
    }

    private fun decodeSearchResultUrl(raw: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(raw)?.groupValues?.getOrNull(1)
        return uddg?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }.orEmpty().ifBlank { raw }
    }

    private data class SearchHit(
        val title: String,
        val url: String,
        val domain: String,
        val snippet: String,
    )

}
