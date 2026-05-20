package com.lmx.xiaoxuanagent.agent

data class ConnectedAppExecutionReceipt(
    val appId: String,
    val operation: String,
    val state: String = "",
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val handoffRequired: Boolean = false,
    val updatedAtMs: Long = 0L,
)

object ConnectedAppExecutionReceiptStore {
    private val lock = Any()
    private val receipts = LinkedHashMap<String, ConnectedAppExecutionReceipt>()

    fun record(
        receipt: ConnectedAppExecutionReceipt,
    ) {
        synchronized(lock) {
            receipts[receipt.appId] = receipt.copy(updatedAtMs = System.currentTimeMillis())
        }
    }

    fun latest(
        appId: String,
    ): ConnectedAppExecutionReceipt? =
        synchronized(lock) {
            receipts[appId]
        }

    fun lines(
        limit: Int = 4,
    ): List<String> =
        synchronized(lock) {
            receipts.values
                .sortedByDescending { it.updatedAtMs }
                .take(limit.coerceAtLeast(1))
                .map { receipt ->
                    buildString {
                        append("connected_receipt | ").append(receipt.appId)
                        append(" | ").append(receipt.operation)
                        receipt.state.takeIf { it.isNotBlank() }?.let { append(" | state=").append(it) }
                        append(" | handoff=").append(receipt.handoffRequired)
                        append(" | ").append(receipt.summary.ifBlank { "-" })
                    }
                }
        }
}
