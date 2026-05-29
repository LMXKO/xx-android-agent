package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.CrossAppMission
import com.lmx.xiaoxuanagent.agent.MissionLeg
import org.junit.Assert.assertEquals
import org.junit.Test

/** Item1 新增字段（kind / leg.handoff）必须跨序列化存活，否则重启后 mission 收口形态会回退。 */
class CrossAppMissionWireFormatKindTest {
    @Test
    fun `mission round-trips kind and leg handoff`() {
        val mission =
            CrossAppMission(
                missionId = "m",
                goal = "查快递再微信通知",
                kind = "general",
                legs =
                    listOf(
                        MissionLeg(
                            legId = "l0",
                            profileId = "cainiao_assistant",
                            targetPackageName = "com.cainiao.wireless",
                            subTask = "查快递",
                            handoff = mapOf("entity" to "顺丰单号123", "query" to "顺丰"),
                        ),
                    ),
            )
        val restored = mission.toJson().toCrossAppMission()!!
        assertEquals("general", restored.kind)
        assertEquals("顺丰单号123", restored.legs[0].handoff["entity"])
        assertEquals("顺丰", restored.legs[0].handoff["query"])
    }
}
