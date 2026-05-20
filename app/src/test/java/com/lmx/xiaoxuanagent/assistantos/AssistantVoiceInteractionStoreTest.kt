package com.lmx.xiaoxuanagent.assistantos

import android.content.Context
import android.content.ContextWrapper
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVoiceInteractionStoreTest {
    @Test
    fun `voice interaction store keeps availability partial and final transcript`() {
        val root = Files.createTempDirectory("voice-interaction-store-test").toFile()
        AppRuntimeContext.init(testContext(root))
        AssistantVoiceInteractionStore.resetForTest()

        AssistantVoiceInteractionStore.syncEnvironment(available = true, permissionGranted = false)
        AssistantVoiceInteractionStore.markBlocked(
            reason = "record_audio_permission_required",
            summary = "缺少录音权限",
        )
        AssistantVoiceInteractionStore.syncEnvironment(available = true, permissionGranted = true)
        AssistantVoiceInteractionStore.markListening(activeSessionId = "session_voice")
        AssistantVoiceInteractionStore.updatePartial("帮我整理今天的消息")
        val snapshot = AssistantVoiceInteractionStore.markFinal("帮我整理今天的消息")

        assertEquals("ready", snapshot.state)
        assertEquals("voice_ready", snapshot.availabilitySummary)
        assertTrue(snapshot.transcriptHistory.isNotEmpty())
        assertTrue(snapshot.finalTranscript.contains("整理今天的消息"))

        val exported = AssistantVoiceInteractionStore.exportJson()
        AssistantVoiceInteractionStore.resetForTest()
        val restored = AssistantVoiceInteractionStore.importJson(exported)
        assertEquals(snapshot.state, restored.state)
        assertEquals(snapshot.finalTranscript, restored.finalTranscript)
        assertEquals(snapshot.transcriptHistory, restored.transcriptHistory)

        root.deleteRecursively()
    }

    @Test
    fun `voice interaction store keeps pending transcript for runtime delivery`() {
        val root = Files.createTempDirectory("voice-interaction-store-pending-test").toFile()
        AppRuntimeContext.init(testContext(root))
        AssistantVoiceInteractionStore.resetForTest()

        val snapshot =
            AssistantVoiceInteractionStore.markFinal(
                transcript = "帮我回顾今天的重要消息",
                autoAction = "voice_execute",
                source = "voice_runtime_service",
                pendingDispatch = true,
                interactionMode = "hands_free_execution",
                pendingConfirmation = "准备开始执行这条语音任务。",
                spokenSummary = "收到，开始处理今天的重要消息。",
                lastHeardCommandType = "execute",
                autoExecuteEligible = true,
            )
        assertEquals("帮我回顾今天的重要消息", snapshot.pendingTranscript)
        assertEquals("voice_runtime_service", snapshot.pendingSource)
        assertEquals("voice_execute", snapshot.pendingAutoAction)
        assertEquals("hands_free_execution", snapshot.interactionMode)
        assertEquals("execute", snapshot.lastHeardCommandType)
        assertTrue(snapshot.autoExecuteEligible)

        val spoken = AssistantVoiceInteractionStore.markSpokenFeedback("收到，开始处理今天的重要消息。")
        assertEquals("收到，开始处理今天的重要消息。", spoken.spokenSummary)

        val handled =
            AssistantVoiceInteractionStore.markActionHandled(
                autoAction = "voice_execute",
                summary = "语音任务已转入自动执行。",
                activeSessionId = "session_voice",
            )
        assertEquals("executing", handled.state)
        assertEquals("session_voice", handled.activeSessionId)

        val cleared = AssistantVoiceInteractionStore.consumePendingTranscript()
        assertEquals("", cleared.pendingTranscript)
        assertEquals("", cleared.pendingSource)

        root.deleteRecursively()
    }

    private fun testContext(
        root: File,
    ): Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root
        }
}
