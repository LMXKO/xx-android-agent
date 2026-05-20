package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

data class ScreenAutomationReleaseGateSnapshot(
    val profileId: String,
    val packageName: String,
    val gateStatus: String = "ungated",
    val focusApp: Boolean = false,
    val summary: String = "",
)

object ScreenAutomationReleaseGateStore {
    private val focusProfiles = setOf("wechat_assistant", "meituan_assistant", "amap_assistant")

    fun snapshot(
        profileId: String,
        packageName: String = "",
        taskIntent: String = "",
    ): ScreenAutomationReleaseGateSnapshot? {
        val focus = profileId in focusProfiles
        if (!focus) return null
        val resolvedPackage = packageName.ifBlank { TaskRegistry.get(profileId)?.packageName.orEmpty() }
        val capability = ScreenAutomationCapabilityStore.snapshot(profileId = profileId, packageName = resolvedPackage, taskIntent = taskIntent)
        val criticalStages = criticalStagesForProfile(profileId)
        val weakestStage =
            criticalStages
                .mapNotNull { stage ->
                    ScreenAutomationTemplateCapabilityStore.snapshot(
                        profileId = profileId,
                        packageName = resolvedPackage,
                        taskIntent = taskIntent,
                        planStage = stage,
                    )
                }.minByOrNull {
                    when (it.confidenceBand) {
                        "weak" -> 0
                        "monitor" -> 1
                        "new" -> 2
                        else -> 3
                    }
                }
        val gateStatus =
            when {
                capability == null -> "yellow"
                capability.confidenceBand == "weak" || weakestStage?.confidenceBand == "weak" -> "red"
                capability.confidenceBand == "monitor" || weakestStage?.confidenceBand == "monitor" -> "yellow"
                else -> "green"
            }
        return ScreenAutomationReleaseGateSnapshot(
            profileId = profileId,
            packageName = resolvedPackage,
            gateStatus = gateStatus,
            focusApp = focus,
            summary =
                buildString {
                    append("focus=").append(profileId)
                    append(" gate=").append(gateStatus)
                    capability?.let {
                        append(" recent=").append((it.recentSuccessRate * 100.0).toInt()).append('%')
                        append(" handoff=").append((it.handoffReadyRate * 100.0).toInt()).append('%')
                    }
                    weakestStage?.let {
                        append(" critical=").append(it.planStage)
                        append(':').append(it.confidenceBand)
                    }
                },
        )
    }

    fun dashboardLines(): List<String> =
        focusProfiles.mapNotNull { profileId ->
            snapshot(profileId)?.let { snapshot ->
                "release_gate | ${snapshot.profileId} | ${snapshot.gateStatus} | ${snapshot.summary}"
            }
        }

    internal fun criticalStagesForProfile(
        profileId: String,
    ): List<String> = ScreenAutomationFocusStageSupport.criticalStagesForProfile(profileId)
}
