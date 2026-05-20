package com.lmx.xiaoxuanagent.harness

import org.junit.Assert.assertTrue
import org.junit.Test

class RegressionRunnerTest {
    @Test
    fun `scenario catalog carries recovery metadata`() {
        val scenarios = RegressionRunner.scenarioCatalog()

        assertTrue(scenarios.any { it.intentType == "approval_recovery" && "resume" in it.requiredCapabilities })
        assertTrue(scenarios.any { it.goldenKeywords.isNotEmpty() })
        assertTrue(scenarios.any { it.expectedStageHints.isNotEmpty() })
        assertTrue(scenarios.any { it.intentType == "mobile_product_shell" })
        assertTrue(scenarios.any { it.intentType == "safety_governance" })
        assertTrue(scenarios.any { it.intentType == "commerce_golden_path" })
        assertTrue(scenarios.any { it.intentType == "payment_handoff" && it.riskLevel == "critical" })
    }
}
