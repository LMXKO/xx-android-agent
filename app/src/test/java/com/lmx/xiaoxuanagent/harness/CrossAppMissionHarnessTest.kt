package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.agent.CrossAppMissionEngine
import com.lmx.xiaoxuanagent.agent.InstalledPackageChecker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppMissionHarnessTest {
    @Test
    fun `cross app scenario is registered with multi-app tags`() {
        val scenario = HarnessScenarioRegistry.all().firstOrNull { it.id == "cross_app_mission" }
        assertNotNull(scenario)
        assertTrue(scenario!!.tags.contains("cross_app"))
        assertTrue(scenario.requiredCapabilities.contains("cross_app_orchestration"))
    }

    @Test
    fun `every cross app canonical task decomposes into a multi-leg mission`() {
        val scenario = HarnessScenarioRegistry.all().first { it.id == "cross_app_mission" }
        scenario.canonicalTasks.forEach { task ->
            // 显式假定 App 都已安装,排除 JVM 单测里 AppRuntimeContext 状态干扰。
            val mission = CrossAppMissionEngine.decompose(task, InstalledPackageChecker.ASSUME_ALL_INSTALLED)
            assertNotNull("应能分解为跨 App mission: $task", mission)
            assertTrue("至少两腿: $task", mission!!.legs.size >= 2)
        }
    }
}

