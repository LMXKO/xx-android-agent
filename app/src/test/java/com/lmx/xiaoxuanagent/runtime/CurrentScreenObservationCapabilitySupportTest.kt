package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.accessibility.CurrentScreenObservationSource
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.UiElementObservation
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentScreenObservationCapabilitySupportTest {
    @Test
    fun `inspect current screen returns structured element lines`() =
        runBlocking {
            CurrentScreenObservationCapabilitySupport.setSourceProviderForTest {
                object : CurrentScreenObservationSource {
                    override suspend fun inspectCurrentScreen(preferredPackageName: String): IndexedScreenObservation =
                        sampleObservation(preferredPackageName)
                }
            }

            val result =
                CurrentScreenObservationCapabilitySupport.inspectCurrentScreen(
                    SessionCapabilityRequest(
                        key = SessionCapabilityKey.READ_CURRENT_SCREEN,
                        payload = mapOf("preferred_package" to "com.tencent.mm", "limit" to "1"),
                    ),
                )

            assertTrue(result.success)
            assertTrue(result.summary.contains("com.tencent.mm"))
            assertTrue(result.payloadLines.any { it == "elements=2" })
            assertTrue(result.payloadLines.any { it.contains("element e01") && it.contains("发送") })
            assertTrue(result.payloadLines.none { it.contains("element e02") })
            assertTrue(result.payloadLines.any { it.contains("command | /screen") })

            CurrentScreenObservationCapabilitySupport.resetForTest()
        }

    @Test
    fun `inspect current screen fails cleanly without source`() =
        runBlocking {
            CurrentScreenObservationCapabilitySupport.setSourceProviderForTest { null }

            val result =
                CurrentScreenObservationCapabilitySupport.inspectCurrentScreen(
                    SessionCapabilityRequest(key = SessionCapabilityKey.READ_CURRENT_SCREEN),
                )

            assertEquals(false, result.success)
            assertTrue(result.summary.contains("无障碍服务未连接"))

            CurrentScreenObservationCapabilitySupport.resetForTest()
        }

    private fun sampleObservation(packageName: String): IndexedScreenObservation =
        IndexedScreenObservation(
            observation =
                ScreenObservation(
                    packageName = packageName,
                    pageState = "CHAT",
                    signature = "sig_screen",
                    screenSummary = "聊天页，有输入框和发送按钮。",
                    topTexts = listOf("张三", "发送"),
                    primaryEditableId = "e02",
                    focusedElementId = "e02",
                    defaultScrollableId = "e03",
                    primaryInterruptActionId = null,
                    interruptiveHints = emptyList(),
                    structureHints = listOf("row_group 1 | e01,e02"),
                    elements =
                        listOf(
                            UiElementObservation(
                                id = "e01",
                                text = "发送",
                                viewId = "send",
                                className = "Button",
                                bounds = "[10,20][80,70]",
                                clickable = true,
                                editable = false,
                                scrollable = false,
                                enabled = true,
                                focused = false,
                                selected = false,
                                roleHint = "submit",
                            ),
                            UiElementObservation(
                                id = "e02",
                                text = "",
                                viewId = "input",
                                className = "EditText",
                                bounds = "[90,20][500,70]",
                                clickable = true,
                                editable = true,
                                scrollable = false,
                                enabled = true,
                                focused = true,
                                selected = false,
                            ),
                        ),
                ),
            nodesById = emptyMap(),
            visualContext =
                VisualPerceptionContext(
                    captureAvailable = true,
                    summary = "OCR 文本 2 条，对齐节点 1 条。",
                    visualHints = listOf("发送"),
                ),
        )
}
