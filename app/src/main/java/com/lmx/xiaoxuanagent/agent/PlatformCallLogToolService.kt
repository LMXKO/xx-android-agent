package com.lmx.xiaoxuanagent.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.util.Locale

data class PlatformCallLogEntry(
    val displayName: String,
    val phoneNumber: String,
    val callType: String,
    val dateMs: Long,
    val durationSeconds: Long,
)

object PlatformCallLogToolService {
    const val DEFAULT_LIMIT = 6
    private const val MAX_LIMIT = 20
    private const val MAX_QUERY_LENGTH = 64

    fun readCallLog(
        service: AccessibilityService,
        query: String,
        type: String,
        limit: Int,
    ): AgentExecutionResult {
        val normalizedQuery = normalizeQuery(query)
        val normalizedType = normalizeType(type)
        val normalizedLimit = normalizeLimit(limit)
        if (!hasReadCallLogPermission(service)) {
            return callLogPermissionResult(normalizedQuery, normalizedType)
        }

        val entries = queryCallLog(
            service = service,
            query = normalizedQuery,
            type = normalizedType,
            limit = normalizedLimit,
        )
        val detailLines =
            formatCallLogDetailLines(entries = entries, nowMs = System.currentTimeMillis())
                .ifEmpty { listOf("call_log | none") } +
                listOf(
                    "receipt=call_log_read",
                    "query=${normalizedQuery.ifBlank { "*" }.take(24)}",
                    "type=${normalizedType.ifBlank { "*" }}",
                    "count=${entries.size}",
                )
        val message =
            if (entries.isEmpty()) {
                "未找到匹配通话记录。"
            } else {
                "已读取 ${entries.size} 条最近通话记录：${displayLabel(entries.first()).take(24)}。"
            }
        return AgentExecutionResult(
            message = message,
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_call_log",
            resolvedTargetText = entries.firstOrNull()?.let { displayLabel(it) } ?: normalizedQuery,
            toolRuntimeDetailLines = detailLines,
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.read_call_log",
                summary = message,
                detailLines = detailLines.take(5),
                handoffRequired = false,
            )
        }
    }

    fun formatCallLogDetailLines(
        entries: List<PlatformCallLogEntry>,
        nowMs: Long,
    ): List<String> =
        entries.mapIndexed { index, entry ->
            "call_${index + 1} | ${displayLabel(entry).take(24)} | ${maskPhone(entry.phoneNumber)} | type=${entry.callType} | duration_s=${entry.durationSeconds.coerceAtLeast(0)} | age_min=${ageMinutes(entry.dateMs, nowMs)}"
        }

    fun normalizeLimit(limit: Int): Int =
        when {
            limit <= 0 -> DEFAULT_LIMIT
            else -> limit.coerceAtMost(MAX_LIMIT)
        }

    fun normalizeType(raw: String): String =
        when (raw.trim().lowercase(Locale.getDefault())) {
            "incoming", "in", "来电", "接听", "已接" -> "incoming"
            "outgoing", "out", "去电", "拨出", "拨打" -> "outgoing"
            "missed", "miss", "未接", "未接来电" -> "missed"
            "rejected", "reject", "拒接", "已拒接" -> "rejected"
            "blocked", "block", "拦截", "已拦截" -> "blocked"
            else -> ""
        }

    fun ageMinutes(
        dateMs: Long,
        nowMs: Long,
    ): Long = ((nowMs - dateMs).coerceAtLeast(0L) / 60_000L)

    fun hasReadCallLogPermission(service: AccessibilityService): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun queryCallLog(
        service: AccessibilityService,
        query: String,
        type: String,
        limit: Int,
    ): List<PlatformCallLogEntry> {
        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val digits = query.filter(Char::isDigit)
            selectionParts += "(${CallLog.Calls.CACHED_NAME} LIKE ? OR ${CallLog.Calls.NUMBER} LIKE ?)"
            args += "%$query%"
            args += "%${digits.ifBlank { query }}%"
        }
        rawCallType(type)?.let { callType ->
            selectionParts += "${CallLog.Calls.TYPE} = ?"
            args += callType.toString()
        }

        val rows = mutableListOf<PlatformCallLogEntry>()
        service.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
            ),
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val displayName = cursor.getString(0)?.trim().orEmpty()
                val number = cursor.getString(1)?.trim().orEmpty()
                val dateMs = cursor.getLong(2)
                val rawType = cursor.getInt(3)
                val durationSeconds = cursor.getLong(4).coerceAtLeast(0L)
                if (displayName.isBlank() && number.isBlank()) continue
                rows +=
                    PlatformCallLogEntry(
                        displayName = displayName,
                        phoneNumber = number,
                        callType = callTypeLabel(rawType),
                        dateMs = dateMs,
                        durationSeconds = durationSeconds,
                    )
            }
        }
        return rows.distinctBy { "${it.phoneNumber.filter(Char::isDigit)}|${it.dateMs}|${it.callType}" }
    }

    private fun normalizeQuery(raw: String): String =
        raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_QUERY_LENGTH)

    private fun rawCallType(type: String): Int? =
        when (type) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            "rejected" -> CallLog.Calls.REJECTED_TYPE
            "blocked" -> CallLog.Calls.BLOCKED_TYPE
            else -> null
        }

    private fun callTypeLabel(type: Int): String =
        when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            else -> "other"
        }

    private fun displayLabel(entry: PlatformCallLogEntry): String =
        entry.displayName.ifBlank { maskPhone(entry.phoneNumber).ifBlank { "未知号码" } }

    private fun maskPhone(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        if (digits.length <= 4) return raw.take(16)
        return "${digits.take(3)}****${digits.takeLast(4)}"
    }

    private fun callLogPermissionResult(
        query: String,
        type: String,
    ): AgentExecutionResult =
        AgentExecutionResult(
            message = "需要通话记录读取权限后才能读取最近通话。",
            keepRunning = false,
            requiresObservationCheck = false,
            groundingSource = "system_call_log",
            toolRuntimeDetailLines =
                listOf(
                    "receipt=call_log_permission_required",
                    "permission=READ_CALL_LOG",
                    "query=${query.ifBlank { "*" }.take(24)}",
                    "type=${type.ifBlank { "*" }}",
                    "fallback=system.open_settings",
                ),
        )
}
