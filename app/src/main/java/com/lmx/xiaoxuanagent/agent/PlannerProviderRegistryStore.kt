package com.lmx.xiaoxuanagent.agent

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object PlannerProviderRegistryStore {
    private const val REGISTRY_DIR = "runtime"
    private const val REGISTRY_FILE = "planner_provider_registry.json"

    private val lock = Any()
    private var hydrated = false
    private var providers: List<PlannerProviderDescriptor> = defaultProviders()

    fun read(): List<PlannerProviderDescriptor> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providers
        }

    fun enabledProviders(): List<PlannerProviderDescriptor> =
        read().filter { it.enabled }

    fun update(
        next: List<PlannerProviderDescriptor>,
    ): List<PlannerProviderDescriptor> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providers =
                next
                    .distinctBy { it.providerId }
                    .sortedWith(
                        compareByDescending<PlannerProviderDescriptor> { it.priority }
                            .thenBy { it.providerId },
                    )
            persistUnlocked()
            providers
        }

    fun upsert(
        providerId: String,
        reducer: (PlannerProviderDescriptor) -> PlannerProviderDescriptor,
    ): List<PlannerProviderDescriptor> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val resolvedProviderId = PlannerProviderKey.resolveProviderId(providerId).orEmpty().ifBlank { providerId.trim().lowercase() }
            val base =
                providers.firstOrNull { it.providerId == resolvedProviderId }
                    ?: defaultProviders().firstOrNull { it.providerId == resolvedProviderId }
                    ?: PlannerProviderDescriptor(
                        providerId = resolvedProviderId,
                        modality = PlannerProviderModality.TEXT,
                        routeLabel = resolvedProviderId,
                        backendId = PlannerProviderBackend.LOCAL_HEURISTIC.backendId,
                        priority = 10,
                    )
            providers =
                (providers.filterNot { it.providerId == resolvedProviderId } + reducer(base))
                    .distinctBy { it.providerId }
                    .sortedWith(
                        compareByDescending<PlannerProviderDescriptor> { it.priority }
                            .thenBy { it.providerId },
                    )
            persistUnlocked()
            providers
        }

    fun summaryLines(
        limit: Int = 8,
    ): List<String> =
        read().take(limit.coerceAtLeast(1)).map { provider ->
            buildString {
                append(provider.providerId)
                append(" | ").append(provider.backendId)
                append(" | ").append(provider.modality.name.lowercase())
                append(" | priority=").append(provider.priority)
                append(" | latency=").append(provider.latencyTier.name.lowercase())
                append(" | cost=").append(provider.costTier.name.lowercase())
                append(" | enabled=").append(provider.enabled)
                append(" | route=").append(provider.routeLabel)
                if (provider.supportsScreenshot) append(" | screenshot")
                if (provider.supportsStructuredOutput) append(" | structured")
            }
        }

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            JSONObject().apply {
                put("providers", JSONArray().apply { providers.forEach { put(it.toJson()) } })
            }
        }

    fun importJson(
        json: JSONObject?,
    ): List<PlannerProviderDescriptor> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            providers = json?.optJSONArray("providers").toProviders().ifEmpty { defaultProviders() }
            persistUnlocked()
            providers
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = registryFile() ?: return
        if (!file.exists()) return
        runCatching {
            providers = JSONObject(file.readText()).optJSONArray("providers").toProviders().ifEmpty { defaultProviders() }
        }
    }

    private fun persistUnlocked() {
        val file = registryFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(exportJson().toString(2))
    }

    private fun registryFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, REGISTRY_DIR), REGISTRY_FILE)
    }

    private fun defaultProviders(): List<PlannerProviderDescriptor> =
        listOf(
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.OPENAI_VISION.providerId,
                modality = PlannerProviderModality.VISION,
                routeLabel = PlannerProviderKey.OPENAI_VISION.routeLabel,
                backendId = PlannerProviderBackend.OPENAI.backendId,
                priority = 100,
                latencyTier = PlannerProviderLatencyTier.HEAVY,
                costTier = PlannerProviderCostTier.HIGH,
                supportsScreenshot = true,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("openai", "vision", "screenshot"),
                capabilities = setOf("screenshot", "structured", "reasoning"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.OPENAI_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.OPENAI_TEXT.routeLabel,
                backendId = PlannerProviderBackend.OPENAI.backendId,
                priority = 90,
                latencyTier = PlannerProviderLatencyTier.BALANCED,
                costTier = PlannerProviderCostTier.HIGH,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("openai", "text", "structured"),
                capabilities = setOf("structured", "reasoning"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.LOCAL_HEURISTIC_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.LOCAL_HEURISTIC_TEXT.routeLabel,
                backendId = PlannerProviderBackend.LOCAL_HEURISTIC.backendId,
                priority = 60,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                supportsStructuredOutput = false,
                enabled = true,
                tags = setOf("local", "heuristic", "fallback"),
                capabilities = setOf("deterministic", "fast_fallback"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.SEMANTIC_LOCAL_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.SEMANTIC_LOCAL_TEXT.routeLabel,
                backendId = PlannerProviderBackend.SEMANTIC_LOCAL.backendId,
                priority = 72,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("local", "semantic", "resume"),
                capabilities = setOf("structured", "semantic_navigation", "resume", "fast_fallback"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.RECOVERY_HEURISTIC_TEXT.routeLabel,
                backendId = PlannerProviderBackend.RECOVERY_HEURISTIC.backendId,
                priority = 74,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                enabled = true,
                tags = setOf("local", "recovery", "resume"),
                capabilities = setOf("recovery", "resume", "deterministic", "fast_fallback"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.MEMORY_LOCAL_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.MEMORY_LOCAL_TEXT.routeLabel,
                backendId = PlannerProviderBackend.MEMORY_LOCAL.backendId,
                priority = 76,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("local", "memory", "personal"),
                capabilities = setOf("memory_alignment", "semantic_navigation", "resume"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.ROUTINE_LOCAL_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.ROUTINE_LOCAL_TEXT.routeLabel,
                backendId = PlannerProviderBackend.ROUTINE_LOCAL.backendId,
                priority = 68,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("local", "routine", "assistant_os"),
                capabilities = setOf("routine", "structured", "fast_fallback"),
            ),
            PlannerProviderDescriptor(
                providerId = PlannerProviderKey.ARTIFACT_LOCAL_TEXT.providerId,
                modality = PlannerProviderModality.TEXT,
                routeLabel = PlannerProviderKey.ARTIFACT_LOCAL_TEXT.routeLabel,
                backendId = PlannerProviderBackend.ARTIFACT_LOCAL.backendId,
                priority = 70,
                latencyTier = PlannerProviderLatencyTier.FAST,
                costTier = PlannerProviderCostTier.LOW,
                supportsStructuredOutput = true,
                enabled = true,
                tags = setOf("local", "artifact", "viewer"),
                capabilities = setOf("artifact_focus", "structured", "semantic_navigation"),
            ),
        )

    private fun PlannerProviderDescriptor.toJson(): JSONObject =
        JSONObject().apply {
            put("provider_id", providerId)
            put("modality", modality.name)
            put("route_label", routeLabel)
            put("backend_id", backendId)
            backend?.let { put("backend", it.name) }
            put("priority", priority)
            put("latency_tier", latencyTier.name)
            put("cost_tier", costTier.name)
            put("supports_screenshot", supportsScreenshot)
            put("supports_structured_output", supportsStructuredOutput)
            put("enabled", enabled)
            put("tags", JSONArray(tags.sorted()))
            put("capabilities", JSONArray(capabilities.sorted()))
        }

    private fun JSONArray?.toProviders(): List<PlannerProviderDescriptor> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val resolvedProviderId =
                    PlannerProviderKey.resolveProviderId(item.optString("provider_id"))
                        ?: item.optString("provider_id").trim().lowercase()
                if (resolvedProviderId.isBlank()) continue
                add(
                    PlannerProviderDescriptor(
                        providerId = resolvedProviderId,
                        modality =
                            runCatching { PlannerProviderModality.valueOf(item.optString("modality")) }
                                .getOrDefault(PlannerProviderModality.TEXT),
                        routeLabel = item.optString("route_label").ifBlank { resolvedProviderId },
                        backendId =
                            PlannerProviderBackend.resolveBackendId(
                                item.optString("backend_id").ifBlank { item.optString("backend") },
                            ) ?: PlannerProviderBackend.LOCAL_HEURISTIC.backendId,
                        priority = item.optInt("priority", 0),
                        latencyTier =
                            runCatching { PlannerProviderLatencyTier.valueOf(item.optString("latency_tier")) }
                                .getOrDefault(PlannerProviderLatencyTier.BALANCED),
                        costTier =
                            runCatching { PlannerProviderCostTier.valueOf(item.optString("cost_tier")) }
                                .getOrDefault(PlannerProviderCostTier.STANDARD),
                        supportsScreenshot = item.optBoolean("supports_screenshot", false),
                        supportsStructuredOutput = item.optBoolean("supports_structured_output", false),
                        enabled = item.optBoolean("enabled", true),
                        tags = item.optJSONArray("tags").toStringSet(),
                        capabilities = item.optJSONArray("capabilities").toStringSet(),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}
