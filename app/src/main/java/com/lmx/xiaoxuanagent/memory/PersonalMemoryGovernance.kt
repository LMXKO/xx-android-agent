package com.lmx.xiaoxuanagent.memory

enum class PersonalMemoryEntryType(
    val wireName: String,
) {
    FACT("fact"),
    RESULT_ARTIFACT("result_artifact"),
    CONTACT("contact"),
    LOCATION("location"),
    APP_PREFERENCE("app_preference"),
    SAFETY_RULE("safety_rule"),
    CORRECTION_TEMPLATE("correction_template"),
    ;

    companion object {
        fun fromWireName(
            raw: String,
        ): PersonalMemoryEntryType? {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { entry ->
                entry.wireName == normalized || entry.name.lowercase() == normalized
            }
        }
    }
}

data class PersonalMemoryGovernanceEntry(
    val entryId: String,
    val type: PersonalMemoryEntryType,
    val title: String,
    val summary: String = "",
    val profileId: String = "",
    val updatedAtMs: Long = 0L,
)

data class PersonalMemoryAuditEntry(
    val action: String,
    val type: PersonalMemoryEntryType,
    val entryId: String,
    val summary: String,
    val timestampMs: Long,
)

data class PersonalMemoryGovernanceSnapshot(
    val summary: String = "",
    val workspaceSummary: String = "",
    val totalEntries: Int = 0,
    val factCount: Int = 0,
    val structuredCount: Int = 0,
    val entries: List<PersonalMemoryGovernanceEntry> = emptyList(),
    val auditTrail: List<PersonalMemoryAuditEntry> = emptyList(),
)

data class PersonalAssistantUserModelSnapshot(
    val summary: String = "",
    val identitySummary: String = "",
    val preferenceSummary: String = "",
    val relationshipSummary: String = "",
    val locationSummary: String = "",
    val appSummary: String = "",
    val routineSummary: String = "",
    val safetySummary: String = "",
    val workspaceSummary: String = "",
    val topProfileIds: List<String> = emptyList(),
    val topContactNames: List<String> = emptyList(),
    val topLocationNames: List<String> = emptyList(),
    val preferredThemes: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

data class PersonalMemoryInsightSnapshot(
    val summary: String = "",
    val awaySummary: String = "",
    val thinkbackSummary: String = "",
    val consolidationSummary: String = "",
    val dreamSummary: String = "",
    val suggestionSummary: String = "",
    val awayLines: List<String> = emptyList(),
    val thinkbackLines: List<String> = emptyList(),
    val consolidationLines: List<String> = emptyList(),
    val dreamLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)
