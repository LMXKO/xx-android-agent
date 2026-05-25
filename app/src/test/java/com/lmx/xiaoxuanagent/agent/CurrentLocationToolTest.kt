package com.lmx.xiaoxuanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationToolTest {
    @Test
    fun `current location is a read-only system utility`() {
        val descriptor = AgentToolCatalog.find("system.read_current_location")

        assertEquals("location_read", descriptor?.permissionFamily)
        assertTrue(descriptor?.concurrencySafe == true)
        assertTrue(ActionExecutor.registeredTools().contains("system.read_current_location"))

        val review =
            AgentToolRuntimeProtocol.evaluate(
                action = AgentAction.ReadCurrentLocation(maxAgeMinutes = 15),
                observation = emptyObservation(),
            )

        assertTrue(review.valid)
        assertTrue(review.readOnly)
        assertTrue(review.detailLines.any { it.contains("permission_family=location_read") })
    }

    @Test
    fun `nearby and current location tasks prefer current location tool`() {
        val nearbyPolicy =
            AgentToolSelectionPolicy.analyze(
                task = "帮我找附近的咖啡店",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.autonavi.minimap",
            )
        val whereAmIPolicy =
            AgentToolSelectionPolicy.analyze(
                task = "我现在在哪儿",
                observation = emptyObservation(),
                memoryContext = PlanningMemoryContext(),
                taskPlanState = TaskPlanState(planType = "utility", currentStage = "start"),
                targetPackageName = "com.autonavi.minimap",
            )

        assertTrue(nearbyPolicy.preferredTools.contains("system.read_current_location"))
        assertTrue(whereAmIPolicy.preferredTools.contains("system.read_current_location"))
    }

    @Test
    fun `planner parses current location action`() {
        val decision =
            OpenAiPlanner().debugParseDecision(
                rawContent = """{"action":"read_current_location","max_age_minutes":12,"reason":"先读取当前位置"}""",
                observation = emptyObservation(),
                targetPackageName = "com.autonavi.minimap",
            )

        assertTrue(decision.action is AgentAction.ReadCurrentLocation)
        val action = decision.action as AgentAction.ReadCurrentLocation
        assertEquals(12, action.maxAgeMinutes)
    }

    @Test
    fun `location detail lines expose freshness and rounded coordinates`() {
        val nowMs = 1_800_000L
        val snapshot =
            PlatformLocationSnapshot(
                provider = "gps",
                latitude = 31.230416,
                longitude = 121.473701,
                accuracyMeters = 12.4f,
                timeMs = nowMs - 120_000L,
            )

        val lines = PlatformCurrentLocationToolService.formatLocationDetailLines(snapshot, nowMs, maxAgeMinutes = 10)

        assertTrue(lines.contains("latitude=31.23042"))
        assertTrue(lines.contains("longitude=121.47370"))
        assertTrue(lines.contains("accuracy_m=12"))
        assertTrue(lines.contains("age_min=2"))
        assertTrue(lines.contains("fresh=true"))
        assertEquals("31.23042,121.47370", PlatformCurrentLocationToolService.coordinateLabel(snapshot))
    }

    @Test
    fun `stale location is not treated as current`() {
        val nowMs = 7_200_000L
        val stale =
            PlatformLocationSnapshot(
                provider = "network",
                latitude = 31.2,
                longitude = 121.4,
                accuracyMeters = 80f,
                timeMs = nowMs - 3_600_000L,
            )

        assertFalse(PlatformCurrentLocationToolService.isFresh(stale, nowMs, maxAgeMinutes = 30))
        assertTrue(
            PlatformCurrentLocationToolService
                .formatLocationDetailLines(stale, nowMs, maxAgeMinutes = 30)
                .contains("fresh=false"),
        )
    }

    private fun emptyObservation(): ScreenObservation =
        ScreenObservation(
            packageName = "com.lmx.xiaoxuanagent",
            pageState = "UNKNOWN",
            signature = "empty",
            screenSummary = "empty",
            topTexts = emptyList(),
            primaryEditableId = null,
            focusedElementId = null,
            defaultScrollableId = null,
            primaryInterruptActionId = null,
            interruptiveHints = emptyList(),
            elements = emptyList(),
        )
}
