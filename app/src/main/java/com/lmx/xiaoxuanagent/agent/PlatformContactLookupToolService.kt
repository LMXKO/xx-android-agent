package com.lmx.xiaoxuanagent.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

data class PlatformContactCandidate(
    val displayName: String,
    val phoneNumber: String,
    val phoneType: String = "",
    val matchedBy: String = "",
)

object PlatformContactLookupToolService {
    private const val MAX_QUERY_LENGTH = 64

    fun lookup(
        service: AccessibilityService,
        contactName: String,
        limit: Int,
    ): AgentExecutionResult {
        val query = normalizeQuery(contactName)
        if (query.isBlank()) {
            return AgentExecutionResult("联系人查询为空。", keepRunning = false)
        }
        if (!hasReadContactsPermission(service)) {
            return contactPermissionResult()
        }

        val candidates = queryContacts(service, query, limit.coerceIn(1, 10))
        val lines = candidates.mapIndexed { index, candidate ->
            "contact_${index + 1} | ${candidate.displayName.take(24)} | ${maskPhone(candidate.phoneNumber)} | type=${candidate.phoneType.ifBlank { "-" }} | match=${candidate.matchedBy}"
        }
        val message =
            if (candidates.isEmpty()) {
                "未找到联系人：${query.take(24)}。"
            } else {
                "找到 ${candidates.size} 个联系人候选：${candidates.first().displayName.take(24)}。"
            }
        return AgentExecutionResult(
            message = message,
            keepRunning = true,
            requiresObservationCheck = false,
            shouldImmediateReplan = true,
            groundingSource = "system_contacts",
            resolvedTargetText = candidates.firstOrNull()?.displayName ?: query,
            toolRuntimeDetailLines =
                lines.ifEmpty { listOf("contact | none | query=${query.take(24)}") } +
                    listOf(
                        "receipt=contacts_lookup",
                        "query=${query.take(24)}",
                        "count=${candidates.size}",
                    ),
        ).also {
            UtilityExecutionReceiptStore.record(
                toolName = "system.lookup_contact",
                summary = message,
                detailLines = lines.take(3),
                handoffRequired = false,
            )
        }
    }

    fun resolveBestPhoneNumber(
        service: AccessibilityService,
        contactName: String,
    ): PlatformContactCandidate? {
        val query = normalizeQuery(contactName)
        if (query.isBlank() || !hasReadContactsPermission(service)) return null
        return queryContacts(service, query, limit = 1).firstOrNull()
    }

    fun hasReadContactsPermission(
        service: AccessibilityService,
    ): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun queryContacts(
        service: AccessibilityService,
        query: String,
        limit: Int,
    ): List<PlatformContactCandidate> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection =
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
        val normalizedDigits = query.filter(Char::isDigit)
        val selection: String
        val args: Array<String>
        if (normalizedDigits.length >= 3) {
            selection =
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?"
            args = arrayOf("%$normalizedDigits%", "%$query%")
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?"
            args = arrayOf("%$query%")
        }
        val rows = mutableListOf<PlatformContactCandidate>()
        service.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (cursor.moveToNext() && rows.size < limit) {
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (displayName.isBlank() || number.isBlank()) continue
                rows +=
                    PlatformContactCandidate(
                        displayName = displayName,
                        phoneNumber = number,
                        phoneType = phoneTypeLabel(cursor.getInt(typeIndex)),
                        matchedBy = matchKind(displayName, number, query, normalizedDigits),
                    )
            }
        }
        return rows.distinctBy { "${it.displayName}|${it.phoneNumber.filter(Char::isDigit)}" }
    }

    private fun normalizeQuery(
        raw: String,
    ): String =
        raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_QUERY_LENGTH)

    private fun matchKind(
        displayName: String,
        phoneNumber: String,
        query: String,
        queryDigits: String,
    ): String {
        val normalizedName = displayName.lowercase(Locale.getDefault())
        val normalizedQuery = query.lowercase(Locale.getDefault())
        val phoneDigits = phoneNumber.filter(Char::isDigit)
        return when {
            queryDigits.length >= 3 && phoneDigits.contains(queryDigits) -> "phone_fragment"
            normalizedName == normalizedQuery -> "exact_name"
            normalizedName.contains(normalizedQuery) -> "name_contains"
            else -> "provider_match"
        }
    }

    private fun phoneTypeLabel(
        type: Int,
    ): String =
        when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
            else -> "other"
        }

    private fun maskPhone(
        raw: String,
    ): String {
        val digits = raw.filter(Char::isDigit)
        if (digits.length <= 4) return raw.take(16)
        return "${digits.take(3)}****${digits.takeLast(4)}"
    }

    private fun contactPermissionResult(): AgentExecutionResult =
        AgentExecutionResult(
            message = "需要联系人读取权限后才能查找联系人。",
            keepRunning = false,
            requiresObservationCheck = false,
            groundingSource = "system_contacts",
            toolRuntimeDetailLines =
                listOf(
                    "receipt=contacts_permission_required",
                    "permission=READ_CONTACTS",
                    "fallback=system.open_settings",
                ),
        )
}
