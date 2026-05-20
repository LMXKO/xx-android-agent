package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryForkContext(
    val sessionId: String,
    val task: String,
    val profileId: String,
    val workingMemory: SessionWorkingMemorySnapshot,
    val compact: SessionConversationCompactSnapshot?,
    val explanations: List<SessionExplanationEntry>,
    val commandReceipts: List<SessionCommandReceipt>,
    val toolLedger: List<SessionToolUseEntry>,
    val turnLoop: SessionTurnLoopSnapshot?,
    val memoryPolicy: SessionMemoryPolicySnapshot?,
    val memoryCurator: SessionMemoryCuratorSnapshot?,
    val actionLifecycle: List<SessionActionLifecycleEntry>,
    val currentNotebookPath: String = "",
    val currentNotebook: String = "",
    val currentHeadline: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class SessionMemoryForkOutput(
    val sessionId: String,
    val task: String,
    val profileId: String,
    val forkSummary: String,
    val promptPreview: String,
    val headline: String,
    val focusSummary: String,
    val previewLines: List<String>,
    val markdown: String,
    val sourceLines: List<String> = emptyList(),
    val runtimeMode: String = "",
    val modelName: String = "",
)

internal object SessionMemoryNotebookForkAgent {
    private const val DEFAULT_RUNTIME_MODE = "deterministic_fallback"

    fun run(
        forkContext: SessionMemoryForkContext,
    ): SessionMemoryForkOutput =
        runCatching {
            runModelBacked(forkContext)
        }.getOrElse { error ->
            buildDeterministicFallback(forkContext, failure = error)
        }

    private fun runModelBacked(
        forkContext: SessionMemoryForkContext,
    ): SessionMemoryForkOutput {
        val modelName = resolveModelName()
        val apiBaseUrl = BuildConfig.AGENT_API_BASE_URL.trim()
        require(apiBaseUrl.isNotBlank()) { "未配置 AGENT_API_BASE_URL。" }
        require(modelName.isNotBlank()) { "未配置可用的记忆 fork 模型。" }
        val promptPreview = buildPromptPreview(forkContext = forkContext, runtimeMode = "model_backed_detached_worker")
        val requestPayload =
            JSONObject().apply {
                put("model", modelName)
                put("temperature", 0.1)
                put("max_tokens", 1200)
                put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt())
                            },
                        )
                        .put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", buildUserPrompt(forkContext, promptPreview))
                            },
                        ),
                )
            }
        val url = URL("${apiBaseUrl.trimEnd('/')}/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = min(BuildConfig.AGENT_ROUTE_CONNECT_TIMEOUT_MS, BuildConfig.AGENT_PLANNER_CONNECT_TIMEOUT_MS)
            readTimeout = min(BuildConfig.AGENT_ROUTE_READ_TIMEOUT_MS, BuildConfig.AGENT_PLANNER_READ_TIMEOUT_MS)
            setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.AGENT_API_KEY.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${BuildConfig.AGENT_API_KEY}")
            }
        }
        connection.outputStream.use { output ->
            output.write(requestPayload.toString().toByteArray())
        }
        val responseCode = connection.responseCode
        val responseText = readConnectionText(connection, responseCode in 200..299)
        require(responseCode in 200..299) { "memory fork 请求失败，code=$responseCode body=${responseText.take(240)}" }
        val responseJson = JSONObject(responseText)
        val messageObject =
            responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
        val content = messageObject.optString("content").ifBlank {
            messageObject.optJSONArray("content")
                ?.let { contentArray ->
                    buildString {
                        for (index in 0 until contentArray.length()) {
                            val item = contentArray.optJSONObject(index) ?: continue
                            append(item.optString("text"))
                        }
                    }
                }.orEmpty()
        }
        val outputJson = extractJsonObject(content)
        val previewLines =
            (
                listOf(
                    "fork_runtime=model_backed_detached_worker",
                    "fork_model=$modelName",
                    "fork_prompt=$promptPreview",
                ) +
                    outputJson.optJSONArray("preview_lines").toStringList() +
                    SessionMemoryNotebookStore.renderPreviewLines(forkContext)
            ).distinct().take(10)
        val markdown =
            outputJson.optString("markdown").ifBlank {
                buildDeterministicFallback(forkContext, failure = null).markdown
            }
        val sourceLines =
            buildList {
                add("runtime=model_backed_detached_worker")
                add("model=$modelName")
                outputJson.optJSONArray("source_lines").toStringList().forEach(::add)
            }.take(8)
        return SessionMemoryForkOutput(
            sessionId = forkContext.sessionId,
            task = forkContext.task,
            profileId = forkContext.profileId,
            forkSummary =
                outputJson.optString("fork_summary")
                    .ifBlank { "model-backed detached memory curator worker 已整理 session notebook。" },
            promptPreview = promptPreview,
            headline =
                outputJson.optString("headline")
                    .ifBlank { forkContext.currentHeadline.ifBlank { forkContext.task.ifBlank { forkContext.sessionId } } },
            focusSummary =
                outputJson.optString("focus_summary")
                    .ifBlank { forkContext.workingMemory.nextFocusHint.ifBlank { forkContext.turnLoop?.summary.orEmpty() } },
            previewLines = previewLines,
            markdown = markdown,
            sourceLines = sourceLines,
            runtimeMode = "model_backed_detached_worker",
            modelName = modelName,
        )
    }

    private fun buildDeterministicFallback(
        forkContext: SessionMemoryForkContext,
        failure: Throwable?,
    ): SessionMemoryForkOutput {
        val modelName = resolveModelName()
        val promptPreview = buildPromptPreview(forkContext = forkContext, runtimeMode = DEFAULT_RUNTIME_MODE)
        val headline =
            forkContext.workingMemory.progressSummary.ifBlank {
                forkContext.explanations.firstOrNull()?.summary.orEmpty()
            }.ifBlank {
                forkContext.currentHeadline
            }.ifBlank {
                forkContext.task.ifBlank { forkContext.sessionId }
            }
        val focusSummary =
            forkContext.workingMemory.nextFocusHint.ifBlank {
                forkContext.turnLoop?.summary.orEmpty()
            }
        val previewLines =
            (
                listOf(
                    "fork_runtime=$DEFAULT_RUNTIME_MODE",
                    "fork_model=${modelName.ifBlank { "-" }}",
                    "fork_prompt=$promptPreview",
                    "fork_policy=${forkContext.memoryPolicy?.summary?.ifBlank { "-" } ?: "-"}",
                    "fork_worker=${forkContext.memoryCurator?.workerId?.ifBlank { "-" } ?: "-"}",
                ) + SessionMemoryNotebookStore.renderPreviewLines(forkContext)
            ).distinct().take(10)
        val markdown =
            buildString {
                append(SessionMemoryNotebookStore.renderMarkdown(forkContext))
                append("\n\n## Fork Agent Notes\n\n")
                append("- prompt_preview: ").append(promptPreview).append('\n')
                append("- fork_runtime: ").append(DEFAULT_RUNTIME_MODE).append('\n')
                append("- fork_model: ").append(modelName.ifBlank { "-" }).append('\n')
                append("- fork_worker: ").append(forkContext.memoryCurator?.workerId?.ifBlank { "-" } ?: "-").append('\n')
                append("- previous_notebook_path: ").append(forkContext.currentNotebookPath.ifBlank { "-" }).append('\n')
                append("- source_events: ").append(forkContext.explanations.size + forkContext.commandReceipts.size + forkContext.toolLedger.size).append('\n')
                failure?.message?.takeIf { it.isNotBlank() }?.let { message ->
                    append("- fallback_reason: ").append(message.take(160)).append('\n')
                }
            }
        return SessionMemoryForkOutput(
            sessionId = forkContext.sessionId,
            task = forkContext.task,
            profileId = forkContext.profileId,
            forkSummary =
                if (failure == null) {
                    "detached memory curator worker 已在隔离上下文整理 session notebook。"
                } else {
                    "model-backed memory fork 不可用，已回退 deterministic detached worker。"
                },
            promptPreview = promptPreview,
            headline = headline,
            focusSummary = focusSummary,
            previewLines = previewLines,
            markdown = markdown,
            sourceLines =
                buildList {
                    add("runtime=$DEFAULT_RUNTIME_MODE")
                    modelName.takeIf { it.isNotBlank() }?.let { add("model=$it") }
                    add("headline=${headline.take(96)}")
                    focusSummary.takeIf { it.isNotBlank() }?.let { add("focus=${it.take(96)}") }
                    failure?.message?.takeIf { it.isNotBlank() }?.let { add("fallback=${it.take(96)}") }
                },
            runtimeMode = DEFAULT_RUNTIME_MODE,
            modelName = modelName,
        )
    }

    private fun buildPromptPreview(
        forkContext: SessionMemoryForkContext,
        runtimeMode: String,
    ): String =
        buildString {
            append("fork=session_memory")
            append(" | runtime=").append(runtimeMode)
            append(" | task=").append(forkContext.task.ifBlank { forkContext.sessionId }.take(48))
            append(" | open_loops=").append(forkContext.workingMemory.openLoops.size)
            append(" | explanations=").append(forkContext.explanations.size)
            append(" | current_lines=").append(forkContext.currentNotebook.lineSequence().count())
        }

    private fun buildUserPrompt(
        forkContext: SessionMemoryForkContext,
        promptPreview: String,
    ): String =
        JSONObject().apply {
            put("prompt_preview", promptPreview)
            put("session_id", forkContext.sessionId)
            put("task", forkContext.task)
            put("profile_id", forkContext.profileId)
            put(
                "working_memory",
                JSONObject().apply {
                    put("status", forkContext.workingMemory.status)
                    put("turn_count", forkContext.workingMemory.turnCount)
                    put("progress_summary", forkContext.workingMemory.progressSummary)
                    put("next_focus_hint", forkContext.workingMemory.nextFocusHint)
                    put("open_loops", JSONArray(forkContext.workingMemory.openLoops))
                    put("recent_facts", JSONArray(forkContext.workingMemory.recentFacts))
                    put("caution_notes", JSONArray(forkContext.workingMemory.cautionNotes))
                },
            )
            put(
                "compact",
                JSONObject().apply {
                    put("conversation_summary", forkContext.compact?.conversationSummary.orEmpty())
                    put("tool_use_summary", forkContext.compact?.toolUseSummary.orEmpty())
                    put("recovery_summary", forkContext.compact?.recoverySummary.orEmpty())
                    put("boundary_digests", JSONArray(forkContext.compact?.boundaryDigests.orEmpty().takeLast(3)))
                },
            )
            put(
                "turn_loop",
                JSONObject().apply {
                    put("summary", forkContext.turnLoop?.summary.orEmpty())
                    put("phase", forkContext.turnLoop?.phase.orEmpty())
                    put("blockers", JSONArray(forkContext.turnLoop?.blockerLines.orEmpty()))
                },
            )
            put(
                "memory_policy",
                JSONObject().apply {
                    put("summary", forkContext.memoryPolicy?.summary.orEmpty())
                    put("last_reason", forkContext.memoryPolicy?.lastReason.orEmpty())
                    put("update_queued", forkContext.memoryPolicy?.notebookUpdateQueued ?: false)
                },
            )
            put(
                "recent_explanations",
                JSONArray().apply {
                    forkContext.explanations.take(6).forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("turn", entry.turn)
                                put("phase", entry.phase)
                                put("summary", entry.summary)
                                put("detail_lines", JSONArray(entry.detailLines.take(3)))
                            },
                        )
                    }
                },
            )
            put(
                "recent_commands",
                JSONArray().apply {
                    forkContext.commandReceipts.take(4).forEach { receipt ->
                        put(
                            JSONObject().apply {
                                put("status", receipt.status.name.lowercase())
                                put("summary", receipt.summary)
                                put("capability", receipt.capability)
                                put("lines", JSONArray(receipt.lines.take(3)))
                            },
                        )
                    }
                },
            )
            put(
                "recent_tool_results",
                JSONArray().apply {
                    forkContext.toolLedger.take(4).forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("tool_name", entry.toolName)
                                put("status", entry.status.name.lowercase())
                                put("summary", entry.summary)
                                put("detail_lines", JSONArray(entry.detailLines.take(3)))
                            },
                        )
                    }
                },
            )
            put(
                "recent_action_lifecycle",
                JSONArray().apply {
                    forkContext.actionLifecycle.take(6).forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("phase", entry.phase)
                                put("status", entry.status)
                                put("summary", entry.summary)
                                put("detail_lines", JSONArray(entry.detailLines.take(3)))
                            },
                        )
                    }
                },
            )
            put("current_notebook_path", forkContext.currentNotebookPath)
            put("current_notebook_excerpt", forkContext.currentNotebook.take(3200))
            put("current_headline", forkContext.currentHeadline)
            put(
                "response_contract",
                JSONObject().apply {
                    put("headline", "单行 headline")
                    put("focus_summary", "一句话说明接下来该关注什么")
                    put("fork_summary", "一句话说明本次 fork 输出重点")
                    put("preview_lines", JSONArray(listOf("最多 6 条 preview line")))
                    put("source_lines", JSONArray(listOf("最多 6 条 source line")))
                    put("markdown", "完整 markdown notebook")
                },
            )
        }.toString(2)

    private fun systemPrompt(): String =
        """
        你是一个“超级手机助手”的 session memory curator。
        你的任务是根据当前 session 的工作记忆、why log、tool 结果、turn loop 和已有 notebook，
        产出一份更适合长期恢复与继续执行的 session notebook。
        你必须严格返回一个 JSON 对象，不能输出 Markdown 代码块，也不能输出解释性前后缀。
        要求：
        1. headline 要概括当前 session 的真正推进点，而不是泛泛总结。
        2. focus_summary 要指出下一步最值得继续关注的 blocker 或 objective。
        3. preview_lines / source_lines 使用短句，最多 6 条。
        4. markdown 必须是完整 notebook，优先保留 open loops、recent facts、cautions、why log、tool results。
        5. 如果现有 notebook 中已有稳定信息，要在 markdown 里继承，不要无故丢失。
        """.trimIndent()

    private fun resolveModelName(): String =
        BuildConfig.AGENT_MODEL.ifBlank { BuildConfig.AGENT_ROUTE_MODEL }

    private fun readConnectionText(
        connection: HttpURLConnection,
        useInputStream: Boolean,
    ): String {
        val stream =
            if (useInputStream) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
        return stream.bufferedReader().use(BufferedReader::readText)
    }

    private fun extractJsonObject(
        rawContent: String,
    ): JSONObject {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) error("memory fork 响应为空。")
        return runCatching { JSONObject(trimmed) }.getOrElse {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            require(start >= 0 && end > start) { "memory fork 响应不是合法 JSON：${trimmed.take(160)}" }
            JSONObject(trimmed.substring(start, end + 1))
        }
    }
}
