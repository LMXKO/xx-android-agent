package com.lmx.xiaoxuanagent.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.Locale

data class PlatformSmsEntry(
    val address: String,
    val body: String,
    val box: String,
    val dateMs: Long,
    val read: Boolean,
)

object PlatformSmsToolService {
    const val DEFAULT_LIMIT = 6
    private const val MAX_LIMIT = 20
    private const val MAX_QUERY_LENGTH = 80
    private const val BODY_PREVIEW_LENGTH = 96

    fun readSms(
        service: AccessibilityService,
        query: String,
        box: String,
        limit: Int,
    ): AgentExecutionResult {
        val normalizedQuery = normalizeQuery(query)
        val normalizedBox = normalizeBox(box)
        val normalizedLimit = normalizeLimit(limit)
        if (!hasReadSmsPermission(service)) {
            return smsPermissionResult(normalizedQuery, normalizedBox)
        }

        val entries =
            querySms(
                service = service,
                query = normalizedQuery,
                box = normalizedBox,
                limit = normalizedLimit,
            )
        val detailLines =
            formatSmsDetailLines(entries = entries, nowMs = System.currentTimeMillis())
                .ifEmpty { listOf("sms | none") } +
                listOf(
                    "receipt=sms_read",
                    "query=${normalizedQuery.ifBlank { "*" }.take(24)}",
                    "box=${normalizedBox.ifBlank { "*" }}",
                    "count=${entries.size}",
                )
        val message =
            if (entries.isEmpty()) {
                "未找到匹配短信。"
            } else {
                "已读取 ${entries.size} 条最近短信：${displaySender(entries.first()).take(24)}。"
            }
        return AgentExecutionResult(
            message = message,
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_sms",
            resolvedTargetText = entries.firstOrNull()?.let { displaySender(it) } ?: normalizedQuery,
            toolRuntimeDetailLines = detailLines,
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.read_sms",
                summary = message,
                detailLines = detailLines.take(5),
                handoffRequired = false,
            )
        }
    }

    fun formatSmsDetailLines(
        entries: List<PlatformSmsEntry>,
        nowMs: Long,
    ): List<String> =
        entries.mapIndexed { index, entry ->
            "sms_${index + 1} | from=${displaySender(entry).take(24)} | box=${entry.box.ifBlank { "-" }} | read=${entry.read} | age_min=${ageMinutes(entry.dateMs, nowMs)} | body=${bodyPreview(entry.body)}"
        }

    fun normalizeLimit(limit: Int): Int =
        when {
            limit <= 0 -> DEFAULT_LIMIT
            else -> limit.coerceAtMost(MAX_LIMIT)
        }

    fun normalizeBox(raw: String): String =
        when (raw.trim().lowercase(Locale.getDefault())) {
            "inbox", "in", "received", "receive", "收件箱", "收到", "收到的", "接收" -> "inbox"
            "sent", "out", "outgoing", "已发", "已发送", "发出", "发送" -> "sent"
            "draft", "草稿", "草稿箱" -> "draft"
            "outbox", "queued", "待发", "发件箱" -> "outbox"
            "failed", "失败", "发送失败" -> "failed"
            "all", "全部", "*" -> ""
            else -> ""
        }

    fun ageMinutes(
        dateMs: Long,
        nowMs: Long,
    ): Long = ((nowMs - dateMs).coerceAtLeast(0L) / 60_000L)

    fun hasReadSmsPermission(service: AccessibilityService): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun querySms(
        service: AccessibilityService,
        query: String,
        box: String,
        limit: Int,
    ): List<PlatformSmsEntry> {
        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val digits = query.filter(Char::isDigit)
            selectionParts += "(${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?)"
            args += "%${digits.ifBlank { query }}%"
            args += "%$query%"
        }
        rawSmsType(box)?.let { smsType ->
            selectionParts += "${Telephony.Sms.TYPE} = ?"
            args += smsType.toString()
        }

        val rows = mutableListOf<PlatformSmsEntry>()
        service.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
            ),
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val address = cursor.getString(0)?.trim().orEmpty()
                val body = cursor.getString(1)?.trim().orEmpty()
                val dateMs = cursor.getLong(2)
                val rawType = cursor.getInt(3)
                val read = cursor.getInt(4) == 1
                if (address.isBlank() && body.isBlank()) continue
                rows +=
                    PlatformSmsEntry(
                        address = address,
                        body = body,
                        box = smsTypeLabel(rawType),
                        dateMs = dateMs,
                        read = read,
                    )
            }
        }
        return rows.distinctBy { "${it.address.filter(Char::isDigit)}|${it.dateMs}|${it.box}|${it.body.hashCode()}" }
    }

    private fun normalizeQuery(raw: String): String =
        raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_QUERY_LENGTH)

    private fun rawSmsType(box: String): Int? =
        when (box) {
            "inbox" -> Telephony.Sms.MESSAGE_TYPE_INBOX
            "sent" -> Telephony.Sms.MESSAGE_TYPE_SENT
            "draft" -> Telephony.Sms.MESSAGE_TYPE_DRAFT
            "outbox" -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
            "failed" -> Telephony.Sms.MESSAGE_TYPE_FAILED
            else -> null
        }

    private fun smsTypeLabel(type: Int): String =
        when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
            else -> "other"
        }

    private fun displaySender(entry: PlatformSmsEntry): String =
        maskAddress(entry.address).ifBlank { "未知短信" }

    private fun maskAddress(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        if (digits.length <= 4) return raw.take(24)
        return "${digits.take(3)}****${digits.takeLast(4)}"
    }

    private fun bodyPreview(raw: String): String {
        val compact = raw.trim().replace(Regex("\\s+"), " ")
        if (compact.isBlank()) return "-"
        val codeLike =
            Regex("验证码|校验码|动态码|verification|otp|\\bcode\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(compact)
        val redacted =
            compact
                .replace(Regex("(?<!\\d)\\d{11,}(?!\\d)"), "[number]")
                .let { body ->
                    if (codeLike) {
                        body.replace(Regex("(?<!\\d)\\d{4,8}(?!\\d)"), "[code]")
                    } else {
                        body
                    }
                }
        return redacted.take(BODY_PREVIEW_LENGTH)
    }

    private fun smsPermissionResult(
        query: String,
        box: String,
    ): AgentExecutionResult =
        AgentExecutionResult(
            message = "需要短信读取权限后才能读取最近短信。",
            keepRunning = false,
            requiresObservationCheck = false,
            groundingSource = "system_sms",
            toolRuntimeDetailLines =
                listOf(
                    "receipt=sms_permission_required",
                    "permission=READ_SMS",
                    "query=${query.ifBlank { "*" }.take(24)}",
                    "box=${box.ifBlank { "*" }}",
                    "fallback=system.open_settings",
                ),
        )
}
