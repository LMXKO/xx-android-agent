package com.lmx.xiaoxuanagent.agent

data class UtilityExecutionReceipt(
    val toolName: String,
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val handoffRequired: Boolean = false,
    val updatedAtMs: Long = 0L,
)

object UtilityExecutionReceiptStore {
    private val lock = Any()
    private val receipts = LinkedHashMap<String, UtilityExecutionReceipt>()

    fun record(
        toolName: String,
        summary: String,
        detailLines: List<String>,
        handoffRequired: Boolean,
    ) {
        synchronized(lock) {
            receipts[toolName] =
                UtilityExecutionReceipt(
                    toolName = toolName,
                    summary = summary,
                    detailLines = detailLines,
                    handoffRequired = handoffRequired,
                    updatedAtMs = System.currentTimeMillis(),
                )
        }
    }

    fun latest(
        toolName: String,
    ): UtilityExecutionReceipt? =
        synchronized(lock) {
            receipts[toolName]
        }

    fun lines(
        limit: Int = 6,
    ): List<String> =
        synchronized(lock) {
            receipts.values
                .sortedByDescending { it.updatedAtMs }
                .take(limit.coerceAtLeast(1))
                .map { receipt ->
                    buildString {
                        append("utility_receipt | ").append(receipt.toolName)
                        append(" | handoff=").append(receipt.handoffRequired)
                        append(" | ").append(receipt.summary.ifBlank { "-" })
                    }
                }
        }
}
