package com.lmx.xiaoxuanagent.memory

data class ResultArtifactMemory(
    val intentType: String,
    val title: String,
    val summary: String,
    val sourceProfileId: String = "",
    val updatedAt: Long = 0L,
)

data class ContactMemory(
    val name: String,
    val aliases: List<String> = emptyList(),
    val note: String = "",
    val sourceProfileId: String = "",
    val updatedAt: Long = 0L,
)

data class LocationMemory(
    val name: String,
    val category: String = "",
    val note: String = "",
    val sourceProfileId: String = "",
    val updatedAt: Long = 0L,
)

data class AppPreferenceMemory(
    val profileId: String,
    val preference: String,
    val note: String = "",
    val updatedAt: Long = 0L,
)

data class SafetyRuleMemory(
    val rule: String,
    val level: String = "confirm",
    val note: String = "",
    val updatedAt: Long = 0L,
)

data class CorrectionTemplateMemory(
    val templateType: String,
    val argument: String = "",
    val instruction: String = "",
    val sourceProfileId: String = "",
    val note: String = "",
    val updatedAt: Long = 0L,
)

data class MemoryExtractResult(
    val resultArtifacts: List<ResultArtifactMemory> = emptyList(),
    val contacts: List<ContactMemory> = emptyList(),
    val locations: List<LocationMemory> = emptyList(),
    val appPreferences: List<AppPreferenceMemory> = emptyList(),
    val safetyRules: List<SafetyRuleMemory> = emptyList(),
    val correctionTemplates: List<CorrectionTemplateMemory> = emptyList(),
    val facts: List<String> = emptyList(),
)

data class StructuredMemorySnapshot(
    val resultArtifacts: List<ResultArtifactMemory> = emptyList(),
    val contacts: List<ContactMemory> = emptyList(),
    val locations: List<LocationMemory> = emptyList(),
    val appPreferences: List<AppPreferenceMemory> = emptyList(),
    val safetyRules: List<SafetyRuleMemory> = emptyList(),
    val correctionTemplates: List<CorrectionTemplateMemory> = emptyList(),
)

data class RouteMemoryHint(
    val profileId: String,
    val summary: String,
    val score: Int,
)
