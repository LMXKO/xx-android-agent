package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.SessionToolContractStore

internal object PlatformToolSearchService {
    fun search(
        query: String,
        limit: Int = 6,
    ): List<String> {
        val normalized = query.trim()
        val catalog = AgentToolCatalog.descriptors()
        if (normalized.isBlank()) {
            return catalog.take(limit).map(::formatDescriptor)
        }
        val contractLines = SessionToolContractStore.readSnapshot(limit = 24).lines.associateBy { it.substringBefore(" | ") }
        return catalog
            .map { descriptor -> descriptor to score(descriptor, normalized) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(1))
            .map { (descriptor, value) ->
                buildString {
                    append(formatDescriptor(descriptor))
                    append(" | score=").append(value)
                    contractLines[descriptor.name]?.let { append(" | ").append(it.take(96)) }
                }
            }
    }

    private fun score(
        descriptor: AgentToolDescriptor,
        query: String,
    ): Int {
        val haystack =
            listOf(
                descriptor.name,
                descriptor.title,
                descriptor.summary,
                descriptor.permissionFamily,
                descriptor.inputContract,
                descriptor.resultContract,
            ) + descriptor.keywords + descriptor.fallbackTools
        return haystack.fold(0) { total, candidate ->
            total +
                when {
                    candidate.equals(query, ignoreCase = true) -> 80
                    candidate.contains(query, ignoreCase = true) -> 36
                    query.split(' ', '-', '_').any { token -> token.isNotBlank() && candidate.contains(token, ignoreCase = true) } -> 12
                    else -> 0
                }
        }
    }

    private fun formatDescriptor(
        descriptor: AgentToolDescriptor,
    ): String =
        buildString {
            append(descriptor.name)
            append(" | ").append(descriptor.title)
            descriptor.summary.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(88)) }
        }
}
