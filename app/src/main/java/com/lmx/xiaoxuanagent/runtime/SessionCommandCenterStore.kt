package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionCommandReceiptStatus {
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class SessionCommandReceipt(
    val receiptId: String,
    val rawInput: String,
    val entrySource: String,
    val resolvedCommand: String = "",
    val capability: String = "",
    val sessionId: String = "",
    val workerId: String = "",
    val status: SessionCommandReceiptStatus = SessionCommandReceiptStatus.SUBMITTED,
    val summary: String = "",
    val lines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

data class SessionCommandCenterSnapshot(
    val totalCount: Int = 0,
    val runningCount: Int = 0,
    val failedCount: Int = 0,
    val latestSummary: String = "",
    val attentionSummary: String = "",
    val recentLines: List<String> = emptyList(),
    val attentionLines: List<String> = emptyList(),
)

object SessionCommandCenterStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "command_center_receipts.json"
    private const val MAX_RECEIPTS = 120
    private val lock = Any()
    private val receipts = ArrayDeque<SessionCommandReceipt>()
    private var hydrated = false

    fun createReceipt(
        rawInput: String,
        entrySource: String,
    ): SessionCommandReceipt =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val receipt =
                SessionCommandReceipt(
                    receiptId = "cmd_${now}_${rawInput.hashCode().toUInt().toString(16)}",
                    rawInput = rawInput.trim(),
                    entrySource = entrySource,
                    status = SessionCommandReceiptStatus.SUBMITTED,
                    summary = "命令已提交，等待解析。",
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            receipts.addFirst(receipt)
            trimUnlocked()
            persistUnlocked()
            receipt
        }

    fun markParsed(
        receiptId: String,
        resolvedCommand: String,
        capability: String,
        summary: String,
    ): SessionCommandReceipt? =
        mutate(receiptId) { current ->
            current.copy(
                resolvedCommand = resolvedCommand,
                capability = capability,
                status = SessionCommandReceiptStatus.RUNNING,
                summary = summary,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markSucceeded(
        receiptId: String,
        summary: String,
        sessionId: String = "",
        workerId: String = "",
        lines: List<String> = emptyList(),
    ): SessionCommandReceipt? =
        mutate(receiptId) { current ->
            current.copy(
                sessionId = sessionId.ifBlank { current.sessionId },
                workerId = workerId.ifBlank { current.workerId },
                status = SessionCommandReceiptStatus.SUCCEEDED,
                summary = summary,
                lines = lines.take(10),
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun markFailed(
        receiptId: String,
        summary: String,
        lines: List<String> = emptyList(),
    ): SessionCommandReceipt? =
        mutate(receiptId) { current ->
            current.copy(
                status = SessionCommandReceiptStatus.FAILED,
                summary = summary,
                lines = lines.take(10),
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    fun readRecent(
        limit: Int = 12,
    ): List<SessionCommandReceipt> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            receipts.take(limit.coerceAtLeast(1)).toList()
        }

    fun readSnapshot(
        limit: Int = 8,
    ): SessionCommandCenterSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val recent = receipts.take(limit.coerceAtLeast(1)).toList()
            val running = receipts.count { it.status == SessionCommandReceiptStatus.RUNNING || it.status == SessionCommandReceiptStatus.SUBMITTED }
            val failed = receipts.count { it.status == SessionCommandReceiptStatus.FAILED }
            val attention =
                receipts.filter { it.status == SessionCommandReceiptStatus.FAILED || it.status == SessionCommandReceiptStatus.RUNNING }
                    .take(4)
            SessionCommandCenterSnapshot(
                totalCount = receipts.size,
                runningCount = running,
                failedCount = failed,
                latestSummary = recent.firstOrNull()?.summary.orEmpty(),
                attentionSummary =
                    buildString {
                        append("running=").append(running)
                        append(" failed=").append(failed)
                        recent.firstOrNull()?.let { append(" latest=").append(it.status.name.lowercase()) }
                    },
                recentLines = recent.map(::formatLine),
                attentionLines = attention.map(::formatLine),
            )
        }

    fun readRecentForSession(
        sessionId: String,
        limit: Int = 4,
    ): List<SessionCommandReceipt> {
        if (sessionId.isBlank()) return emptyList()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            receipts.filter { it.sessionId == sessionId }.take(limit.coerceAtLeast(1))
        }
    }

    private fun mutate(
        receiptId: String,
        reducer: (SessionCommandReceipt) -> SessionCommandReceipt,
    ): SessionCommandReceipt? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val index = receipts.indexOfFirst { it.receiptId == receiptId }
            if (index < 0) return@synchronized null
            val next = reducer(receipts.elementAt(index))
            receipts.removeAt(index)
            receipts.add(index, next)
            persistUnlocked()
            next
        }

    private fun formatLine(
        receipt: SessionCommandReceipt,
    ): String =
        buildString {
            append(receipt.receiptId)
            append(" | ").append(receipt.status.name.lowercase())
            receipt.capability.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.lowercase()) }
            receipt.sessionId.takeIf { it.isNotBlank() }?.let { append(" | session=").append(it) }
            append(" | ").append(receipt.summary.take(96))
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("receipts") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toReceipt()?.let(receipts::addLast)
            }
            trimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "receipts",
                    JSONArray().apply {
                        receipts.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (receipts.size > MAX_RECEIPTS) {
            receipts.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionCommandReceipt.toJson(): JSONObject =
        JSONObject().apply {
            put("receipt_id", receiptId)
            put("raw_input", rawInput)
            put("entry_source", entrySource)
            put("resolved_command", resolvedCommand)
            put("capability", capability)
            put("session_id", sessionId)
            put("worker_id", workerId)
            put("status", status.name)
            put("summary", summary)
            put("lines", JSONArray(lines))
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toReceipt(): SessionCommandReceipt =
        SessionCommandReceipt(
            receiptId = optString("receipt_id"),
            rawInput = optString("raw_input"),
            entrySource = optString("entry_source"),
            resolvedCommand = optString("resolved_command"),
            capability = optString("capability"),
            sessionId = optString("session_id"),
            workerId = optString("worker_id"),
            status =
                runCatching { SessionCommandReceiptStatus.valueOf(optString("status")) }
                    .getOrDefault(SessionCommandReceiptStatus.SUBMITTED),
            summary = optString("summary"),
            lines = optJSONArray("lines").toStringList(),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
