package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import org.json.JSONArray
import org.json.JSONObject

internal object ArtifactStorePreviewSupport {
    fun readPreviewLines(
        type: String,
        json: JSONObject,
    ): List<String> =
        when (type) {
            "planning_observation" -> buildPlanningObservationPreviewLines(json)
            "ui_xml_snapshot" -> buildUiXmlSnapshotPreviewLines(json)
            "planning_screenshot" -> buildPlanningScreenshotPreviewLines(json)
            "task_result_summary" -> buildTaskResultPreviewLines(json)
            "execution_verification" -> buildExecutionVerificationPreviewLines(json)
            "planner_decision" -> buildPlannerDecisionPreviewLines(json)
            "execution_trace" -> buildExecutionTracePreviewLines(json)
            "turn_failure" -> buildTurnFailurePreviewLines(json)
            else -> emptyList()
        }.take(2)

    fun buildPlanningSummary(
        observation: ScreenObservation,
        taskPlanState: TaskPlanState?,
        activeSkills: List<SkillContext>,
    ): String =
        buildString {
            append("sig=").append(observation.signature)
            append(" page=").append(observation.pageState)
            taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { append(" stage=").append(it) }
            if (activeSkills.isNotEmpty()) {
                append(" skills=").append(activeSkills.joinToString(",") { it.id })
            }
        }

    fun buildTaskResultSummary(
        taskResult: TaskResultPayload,
    ): String =
        buildString {
            append("intent=").append(taskResult.intentType.ifBlank { "-" })
            append(" title=").append(taskResult.title.take(48).ifBlank { "-" })
            append(" summary=").append(taskResult.summary.take(96).ifBlank { "-" })
            if (taskResult.highlights.isNotEmpty()) {
                append(" highlights=").append(taskResult.highlights.joinToString(" | ").take(96))
            }
        }

    fun buildScreenshotSummary(
        observation: ScreenObservation,
        screenshot: ScreenshotPayload,
        visualContext: VisualPerceptionContext,
    ): String =
        buildString {
            append("sig=").append(observation.signature)
            append(" page=").append(observation.pageState)
            append(" size=").append(screenshot.width).append("x").append(screenshot.height)
            if (visualContext.summary.isNotBlank()) {
                append(" visual=").append(visualContext.summary.take(80))
            }
        }

    fun buildObservationXmlSnapshot(
        observation: ScreenObservation,
    ): String =
        buildString {
            append("<screen package=\"").append(escapeXml(observation.packageName)).append("\" ")
            append("page_state=\"").append(escapeXml(observation.pageState)).append("\" ")
            append("signature=\"").append(escapeXml(observation.signature)).append("\">\n")
            observation.elements.forEach { element ->
                append("  <element")
                append(" id=\"").append(escapeXml(element.id)).append("\"")
                append(" text=\"").append(escapeXml(element.text)).append("\"")
                append(" view_id=\"").append(escapeXml(element.viewId)).append("\"")
                append(" class=\"").append(escapeXml(element.className)).append("\"")
                append(" bounds=\"").append(escapeXml(element.bounds)).append("\"")
                append(" clickable=\"").append(element.clickable).append("\"")
                append(" editable=\"").append(element.editable).append("\"")
                append(" scrollable=\"").append(element.scrollable).append("\"")
                append(" enabled=\"").append(element.enabled).append("\"")
                append(" focused=\"").append(element.focused).append("\"")
                append(" selected=\"").append(element.selected).append("\"")
                append(" />\n")
            }
            append("</screen>")
        }

    fun escapeXml(
        value: String,
    ): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun ScreenObservation.toArtifactJson(): JSONObject =
        JSONObject().apply {
            put("package_name", packageName)
            put("page_state", pageState)
            put("signature", signature)
            put("screen_summary", screenSummary)
            put("top_texts", JSONArray(topTexts))
            put("primary_editable_id", primaryEditableId.orEmpty())
            put("focused_element_id", focusedElementId.orEmpty())
            put("default_scrollable_id", defaultScrollableId.orEmpty())
            put("primary_interrupt_action_id", primaryInterruptActionId.orEmpty())
            put(
                "interruptive_hints",
                JSONArray().apply {
                    interruptiveHints.forEach { hint ->
                        put(
                            JSONObject().apply {
                                put("element_id", hint.elementId)
                                put("text", hint.text)
                                put("reason", hint.reason)
                            },
                        )
                    }
                },
            )
            put(
                "elements",
                JSONArray().apply {
                    elements.forEach { element ->
                        put(
                            JSONObject().apply {
                                put("id", element.id)
                                put("text", element.text)
                                put("view_id", element.viewId)
                                put("class_name", element.className)
                                put("bounds", element.bounds)
                                put("clickable", element.clickable)
                                put("editable", element.editable)
                                put("scrollable", element.scrollable)
                                put("enabled", element.enabled)
                                put("focused", element.focused)
                                put("selected", element.selected)
                            },
                        )
                    }
                },
            )
        }

    fun TaskPlanState.toArtifactJson(): JSONObject =
        JSONObject().apply {
            put("plan_type", planType)
            put("current_stage", currentStage)
            put("stage_summary", stageSummary)
            put("next_objective", nextObjective)
            put("completion_signal", completionSignal)
            put("current_subgoal_id", currentSubgoalId)
            put("waiting_for_external", waitingForExternal)
            put("waiting_for_event", waitingForEvent)
            put("suspendable", suspendable)
            put("suspend_reason", suspendReason)
            put(
                "steps",
                JSONArray().apply {
                    steps.forEach { step ->
                        put(
                            JSONObject().apply {
                                put("id", step.id)
                                put("title", step.title)
                                put("status", step.status)
                                put("evidence", step.evidence)
                            },
                        )
                    }
                },
            )
            put(
                "resume_context",
                JSONObject().apply {
                    put("active", resumeContext.active)
                    put("source", resumeContext.source)
                    put("resume_event", resumeContext.resumeEvent)
                    put("resume_hint", resumeContext.resumeHint)
                    put("resumed_subgoal_id", resumeContext.resumedSubgoalId)
                    put("resume_attempt", resumeContext.resumeAttempt)
                    put("user_correction", resumeContext.userCorrection)
                },
            )
        }

    fun SkillContext.toArtifactJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("description", description)
            put("instructions", instructions)
            put("layer", layer.name)
            put("risk_level", riskLevel.name)
            put("required_tools", JSONArray(requiredTools))
            put("parameters", JSONArray(parameters))
        }

    fun RecoveryDiagnosis.toArtifactJson(): JSONObject =
        JSONObject().apply {
            put("category", category.name)
            put("summary", summary)
            put("suggested_action", suggestedAction?.label.orEmpty())
        }

    fun TaskResultPayload.toArtifactJson(): JSONObject =
        JSONObject().apply {
            put("intent_type", intentType)
            put("title", title)
            put("summary", summary)
            put("highlights", JSONArray(highlights))
            put(
                "fields",
                JSONArray().apply {
                    fields.forEach { field ->
                        put(
                            JSONObject().apply {
                                put("key", field.key)
                                put("label", field.label)
                                put("value", field.value)
                            },
                        )
                    }
                },
            )
        }

    private fun buildTaskResultPreviewLines(json: JSONObject): List<String> {
        val taskResult = json.optJSONObject("task_result") ?: return emptyList()
        return listOfNotNull(
            taskResult.optString("title").takeIf { it.isNotBlank() }?.let { "title=$it" },
            taskResult.optString("summary").takeIf { it.isNotBlank() }?.let { "summary=${it.take(72)}" },
        )
    }

    private fun buildPlanningObservationPreviewLines(json: JSONObject): List<String> {
        val taskPlanState = json.optJSONObject("task_plan_state") ?: JSONObject()
        val activeSkills = json.optJSONArray("active_skills") ?: JSONArray()
        val observation = json.optJSONObject("observation") ?: JSONObject()
        val skillIds =
            buildList {
                for (index in 0 until activeSkills.length()) {
                    activeSkills.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        return listOfNotNull(
            taskPlanState.optString("current_stage").takeIf { it.isNotBlank() }?.let { "stage=$it" },
            skillIds.takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "skills=$it" },
            observation.optString("screen_summary").takeIf { it.isNotBlank() }?.let { "screen=${it.take(72)}" },
        )
    }

    private fun buildUiXmlSnapshotPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            json.optString("signature").takeIf { it.isNotBlank() }?.let { "sig=$it" },
            json.optString("xml_snapshot").lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "xml=${it.take(72)}" },
        )

    private fun buildPlanningScreenshotPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "size=${json.optInt("width", 0)}x${json.optInt("height", 0)}",
            json.optString("visual_summary").takeIf { it.isNotBlank() }?.let { "visual=${it.take(72)}" },
        )

    private fun buildExecutionVerificationPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "verified=${json.optBoolean("verified")} replan=${json.optBoolean("should_immediate_replan")}",
            json.optString("suggested_recovery_action").takeIf { it.isNotBlank() }?.let { "next=$it" },
            json.optString("final_message").takeIf { it.isNotBlank() }?.let { "message=${it.take(72)}" },
        )

    private fun buildPlannerDecisionPreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            json.optString("action").takeIf { it.isNotBlank() }?.let { "action=$it" },
            json.optString("reason").takeIf { it.isNotBlank() }?.let { "reason=${it.take(72)}" },
        )

    private fun buildExecutionTracePreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "keepRunning=${json.optBoolean("keep_running")}",
            json.optString("suggested_recovery_action").takeIf { it.isNotBlank() }?.let { "next=$it" },
            json.optString("result").takeIf { it.isNotBlank() }?.let { "result=${it.take(72)}" },
        )

    private fun buildTurnFailurePreviewLines(json: JSONObject): List<String> =
        listOfNotNull(
            "keepRunning=${json.optBoolean("keep_running")}",
            json.optString("error").takeIf { it.isNotBlank() }?.let { "error=${it.take(72)}" },
        )
}

