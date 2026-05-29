package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.CrossAppMission
import com.lmx.xiaoxuanagent.agent.MissionLeg
import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import org.json.JSONArray
import org.json.JSONObject

/** CrossAppMission 的 JSON 序列化，供 SessionResumeStore / SessionCommandWireFormat 复用，使 mission 跨重启/恢复存活。 */
internal fun CrossAppMission.toJson(): JSONObject =
    JSONObject().apply {
        put("mission_id", missionId)
        put("goal", goal)
        put("active_leg_index", activeLegIndex)
        put("legs", JSONArray().apply { legs.forEach { put(it.toMissionJson()) } })
        put("blackboard", JSONArray().apply { blackboard.forEach { put(it.toPayloadJson()) } })
        put("declared_handoff_fields", JSONArray(declaredHandoffFields.toList()))
        put("kind", kind)
    }

internal fun JSONObject?.toCrossAppMission(): CrossAppMission? {
    if (this == null || length() == 0) return null
    val missionId = optString("mission_id")
    val legsJson = optJSONArray("legs") ?: return null
    val legs =
        buildList(legsJson.length()) {
            for (index in 0 until legsJson.length()) {
                legsJson.optJSONObject(index)?.toMissionLeg()?.let(::add)
            }
        }
    if (missionId.isBlank() || legs.isEmpty()) return null
    val blackboardJson = optJSONArray("blackboard") ?: JSONArray()
    val blackboard =
        buildList(blackboardJson.length()) {
            for (index in 0 until blackboardJson.length()) {
                blackboardJson.optJSONObject(index)?.toTaskResultPayload()?.let(::add)
            }
        }
    return CrossAppMission(
        missionId = missionId,
        goal = optString("goal"),
        legs = legs,
        activeLegIndex = optInt("active_leg_index").coerceIn(0, legs.size),
        blackboard = blackboard,
        declaredHandoffFields = optJSONArray("declared_handoff_fields").toStringList().toSet(),
        kind = optString("kind", "general"),
    )
}

private fun MissionLeg.toMissionJson(): JSONObject =
    JSONObject().apply {
        put("leg_id", legId)
        put("profile_id", profileId)
        put("target_package_name", targetPackageName)
        put("sub_task", subTask)
        put("intent_type", intentType)
        put("status", status)
        put("handoff", JSONObject(handoff.mapValues { it.value }))
    }

private fun JSONObject.toMissionLeg(): MissionLeg =
    MissionLeg(
        legId = optString("leg_id"),
        profileId = optString("profile_id"),
        targetPackageName = optString("target_package_name"),
        subTask = optString("sub_task"),
        intentType = optString("intent_type", "shopping"),
        status = optString("status", "pending"),
        handoff =
            optJSONObject("handoff")?.let { obj ->
                buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
            } ?: emptyMap(),
    )

private fun TaskResultPayload.toPayloadJson(): JSONObject =
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

private fun JSONObject.toTaskResultPayload(): TaskResultPayload {
    val fieldsJson = optJSONArray("fields") ?: JSONArray()
    val fields =
        buildList(fieldsJson.length()) {
            for (index in 0 until fieldsJson.length()) {
                fieldsJson.optJSONObject(index)?.let { obj ->
                    add(TaskResultField(key = obj.optString("key"), label = obj.optString("label"), value = obj.optString("value")))
                }
            }
        }
    return TaskResultPayload(
        intentType = optString("intent_type"),
        title = optString("title"),
        summary = optString("summary"),
        highlights = optJSONArray("highlights").toStringList(),
        fields = fields,
    )
}
