package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.CrossAppMissionEngine
import com.lmx.xiaoxuanagent.agent.InstalledPackageChecker
import com.lmx.xiaoxuanagent.agent.TaskResultField
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrossAppMissionWireFormatTest {
    @Test
    fun `mission round-trips through json preserving legs blackboard and index`() {
        val mission =
            CrossAppMissionEngine.decompose("比较京东和淘宝上 iPhone 的价格", InstalledPackageChecker.ASSUME_ALL_INSTALLED)!!
                .recordAndAdvance(
                    TaskResultPayload(
                        intentType = "shopping",
                        title = "京东",
                        summary = "京东价格",
                        highlights = listOf("¥5999"),
                        fields = listOf(TaskResultField(key = "price", label = "价格", value = "¥5999")),
                    ),
                )

        val restored = mission.toJson().toCrossAppMission()

        assertNotNull(restored)
        restored!!
        assertEquals(mission.missionId, restored.missionId)
        assertEquals(mission.goal, restored.goal)
        assertEquals(mission.activeLegIndex, restored.activeLegIndex)
        assertEquals(mission.legs.map { it.profileId }, restored.legs.map { it.profileId })
        assertEquals(mission.legs.map { it.targetPackageName }, restored.legs.map { it.targetPackageName })
        assertEquals("done", restored.legs[0].status)
        assertEquals(1, restored.blackboard.size)
        assertEquals("¥5999", restored.blackboard[0].fields.first { it.key == "price" }.value)
        assertEquals(mission.declaredHandoffFields, restored.declaredHandoffFields)
    }

    @Test
    fun `empty or missing json yields null mission`() {
        assertNull(JSONObject().toCrossAppMission())
        assertNull(null.toCrossAppMission())
    }
}
