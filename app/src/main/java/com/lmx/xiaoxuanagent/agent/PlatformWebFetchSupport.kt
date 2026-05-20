package com.lmx.xiaoxuanagent.agent

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class PlatformWebFetchResult(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val domain: String,
    val redirectChain: List<String> = emptyList(),
)

internal object PlatformWebFetchSupport {
    fun fetch(
        url: String,
        maxAttempts: Int = 2,
    ): Result<PlatformWebFetchResult> {
        var lastError: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            runCatching { singleFetch(url) }
                .onSuccess { return Result.success(it) }
                .onFailure { error -> lastError = error.takeUnless { attempt < maxAttempts - 1 && error is java.io.IOException } ?: error }
        }
        return Result.failure(lastError ?: IllegalStateException("web_fetch_failed"))
    }

    fun extractReadableSections(html: String): List<String> =
        buildList {
            extractMeta(html, "title")?.let { add("title=$it") }
            extractMetaDescription(html)?.let { add("description=$it") }
            extractMetaProperty(html, "og:title")?.let { add("og_title=$it") }
            extractMetaProperty(html, "og:description")?.let { add("og_description=$it") }
            extractCanonical(html)?.let { add("canonical=$it") }
            extractHeadings(html).take(2).forEachIndexed { index, heading -> add("heading_${index + 1}=$heading") }
            cleanHtml(html).chunked(220).map { it.trim() }.filter { it.length > 30 }.take(4).forEachIndexed { index, chunk ->
                add("body_${index + 1}=$chunk")
            }
        }

    private fun singleFetch(url: String): PlatformWebFetchResult {
        val visited = mutableListOf<String>()
        var currentUrl = url
        repeat(3) {
            visited += currentUrl
            val connection =
                (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("User-Agent", "XiaoxuanAgent/1.0")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                }
            val status = connection.responseCode
            val redirect = connection.getHeaderField("Location").orEmpty()
            if (status in 300..399 && redirect.isNotBlank()) {
                currentUrl = URI(currentUrl).resolve(redirect).toString()
                return@repeat
            }
            val body =
                (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.bufferedReader()
                    ?.use { reader -> reader.readText() }
                    .orEmpty()
            return PlatformWebFetchResult(
                finalUrl = currentUrl,
                statusCode = status,
                contentType = connection.contentType.orEmpty(),
                body = body,
                domain = runCatching { URL(currentUrl).host }.getOrDefault(""),
                redirectChain = visited,
            )
        }
        throw IllegalStateException("redirect_limit_exceeded")
    }

    private fun extractMeta(html: String, tag: String): String? =
        Regex("""<$tag[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() }

    private fun extractMetaDescription(html: String): String? =
        Regex("""<meta[^>]+name=["']description["'][^>]+content=["'](.*?)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() }

    private fun extractMetaProperty(html: String, property: String): String? =
        Regex("""<meta[^>]+property=["']$property["'][^>]+content=["'](.*?)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            ?.takeIf { it.isNotBlank() }

    private fun extractCanonical(html: String): String? =
        Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["'](.*?)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun extractHeadings(html: String): List<String> =
        Regex("""<h[1-2][^>]*>(.*?)</h[1-2]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .take(4)
            .toList()

    fun cleanHtml(raw: String): String =
        raw.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
